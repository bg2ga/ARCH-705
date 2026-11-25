// The IC-705 over WiFi (no lead): Icom's network protocol - the one the RS-BA1 software and the radio's remote
// settings use - through FT8CN's implementation (MIT; package uk.hamdigital.icom). The radio sends its receive audio
// (12 kHz, 16-bit) and CI-V replies; the app sends CI-V commands, PTT and transmit audio. On the IC-705: MENU > SET >
// WLAN Set (connect it to the same WiFi as the phone, or use its own access point), and WLAN Set > Remote Settings >
// Network User1 for the user name and password; the radio's IP address is shown under WLAN Set > Connection Status.
//
// Keeping the link up (found on the air, 0.8.2):
//  - The app's traffic is pinned to the phone's WiFi network (bindProcessToNetwork). Android may route an app's
//    packets over mobile data, where the radio cannot be reached ("sendto failed: EPERM").
//  - The phone's WiFi is watched: when it drops the link waits; when it is back, the link is made again.
//  - A watchdog: the radio serves one remote session at a time and ignores a new one while an old one it was not told
//    about is still open, and FT8CN's code asks "are you ready" only once. So if the login has not succeeded within
//    10 s, or the radio has sent no CI-V for 8 s (60 s after a fresh login, while it lets go of an old session's CI-V
//    stream), the app logs out and tries again (5 s, then 10, 20, 30 s apart).
//  - Nothing is sent on the CI-V or audio stream before the radio has given its port (0.8.4): an early send failed
//    with EINVAL and FT8CN's code then closed the whole connection.
//  - Logging out properly (disconnect) when the app is closed, so the radio is not left with a stale session.
package uk.hamdigital.rig

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.icom.IComWifiRig
import uk.hamdigital.icom.IcomScopeAssembler
import uk.hamdigital.icom.WifiRig
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.schedule

object IcomNet {
    private const val TAG = "IcomNet"                 // logcat tag
    @Volatile var rig: IComWifiRig? = null; private set // the connection, while open
    val status = MutableStateFlow("")                 // what is happening
    val scope = MutableStateFlow<IcomScopeAssembler.Frame?>(null)
    /** CI-V round-trip latency in milliseconds; -1 until the first reply. */
    val latencyMs = MutableStateFlow(-1L)
    @Volatile private var civQueryNs = 0L
    @Volatile private var lastScopeMs = 0L
    fun scopeFresh(): Boolean = lastScopeMs > 0L && System.currentTimeMillis() - lastScopeMs < 3000L
    fun resetScope() { scope.value = null; lastScopeMs = 0L }
    val loginState = MutableStateFlow(false)
    /** The generation whose credentials were accepted; it may still be waiting for the CI-V/audio transport. */
    val authAcceptedGeneration = MutableStateFlow(0L)
    /** The generation whose current target has completed the full transport login. */
    val authenticatedGeneration = MutableStateFlow(0L)
    private val scopeAssembler = IcomScopeAssembler()
    @Volatile var loggedIn = false; private set       // the radio accepted us

    private data class Target(val ip: String, val port: Int, val user: String, val password: String,
                              val civPort: Int, val audioPort: Int, val generation: Long)
    private data class CachedLocalHost(val local: Boolean, val atMs: Long)
    private data class ResolvedTarget(val endpoint: String, val local: Boolean, val resolved: Boolean = true)
    @Volatile private var target: Target? = null      // what to stay connected to (null: not wanted)
    @Volatile private var wifi: Network? = null       // the phone's WiFi network, while up
    @Volatile private var startedMs = 0L              // when this connection attempt began
    @Volatile private var lastCivMs = 0L              // when the radio last sent CI-V (or, before any, when we logged in)
    @Volatile private var civSeen = false
    @Volatile private var authSucceeded = false
    @Volatile private var authSucceededMs = 0L
    @Volatile private var retries = 0                 // failed attempts in a row (for the back-off)
    @Volatile private var retryPaused = false         // credentials rejected: wait for the user to edit them
    @Volatile private var nextTryMs = 0L              // when the next attempt may start
    private var watchdog: Timer? = null               // checks the link every 2 s
    private var cm: ConnectivityManager? = null
    private var callbackOn = false                    // WiFi callback registered
    private val lifecycleLock = Any()
    private val pttLock = Any()
    @Volatile private var closing = true
    @Volatile private var activeLocal = false
    @Volatile private var activeRouteUsesWifi = false
    private val dnsExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "icom-dns").apply { isDaemon = true }
    }
    @Volatile private var pttRequested = false
    @Volatile private var powerOnAfterLogin = false
    @Volatile private var powerWakeDeadlineMs = 0L
    private val powerWakeRun = AtomicLong(0L)
    @Volatile private var appBackground = false
    @Volatile private var resumeProbeDeadlineMs = 0L
    private var pttSafety: Timer? = null
    private val localHostCache = ConcurrentHashMap<String, CachedLocalHost>()
    private val connectionGeneration = AtomicLong(0L)
    private val openingGeneration = AtomicLong(0L)

    /** Stay connected to the radio (connecting now, and again whenever the link is lost). */
    fun connect(ctx: Context, ip: String, port: Int, user: String, password: String,
                civPort: Int = 50002, audioPort: Int = 50003, powerOn: Boolean = false): Long {
        if (ip.isBlank()) { status.value = "请输入 IC-705 的 IP 地址"; return 0L }
        synchronized(pttLock) { closing = true }
        val generation = connectionGeneration.incrementAndGet()
        powerWakeRun.incrementAndGet()
        powerOnAfterLogin = powerOn
        loggedIn = false
        loginState.value = false
        authAcceptedGeneration.value = 0L
        authenticatedGeneration.value = 0L
        authSucceeded = false
        authSucceededMs = 0L
        latencyMs.value = -1L
        civQueryNs = 0L
        target = Target(ip.trim(), port, user, password, civPort, audioPort, generation); retries = 0; nextTryMs = 0; retryPaused = false
        openingGeneration.set(0L)
        watchWifi(ctx.applicationContext)
        Thread({ open() }, "icom-open").start()
        if (watchdog == null) watchdog = Timer("icom-watchdog", true).apply { schedule(2000, 2000) { check() } }
        if (pttSafety == null) pttSafety = Timer("ptt-safety", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    forceUnkeyIfIdle()
                }
            }, 300, 300)
        }
        return generation
    }

    fun syncCivAddress(address: Int) {
        if (!loggedIn) {
            Ic705.civAddress = address and 0xFF
            rig?.controlUdp?.civUdp?.setCivAddress(address and 0xFF)
        }
    }

    /** Stop: log out and do not reconnect. [then] runs once the logout has been sent - stop the background service only
     *  then, or Android cuts the app off the network before the radio hears it leave (0.8.5). */
    fun disconnect(then: (() -> Unit)? = null) {
        powerOnAfterLogin = false
        powerWakeRun.incrementAndGet()
        authAcceptedGeneration.value = 0L
        authenticatedGeneration.value = 0L
        val generation = target?.generation
        watchdog?.cancel()
        watchdog = null
        pttSafety?.cancel()
        pttSafety = null
        openingGeneration.set(0L)
        forceUnkey()
        close("WiFi 连接已关闭", then, generation, stop = true)
    }

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.hostAddress?.lowercase()?.let { it.startsWith("fc") || it.startsWith("fd") } == true

    private fun resolveTarget(host: String): ResolvedTarget {
        val h = host.trim().lowercase()
        if (h == "localhost" || h.endsWith(".local")) {
            val ip = runCatching { InetAddress.getByName(h) }.getOrNull()?.hostAddress ?: h
            return ResolvedTarget(ip, true)
        }
        val lookup = dnsExecutor.submit<List<InetAddress>> { InetAddress.getAllByName(h).toList() }
        val addresses = try {
            lookup.get(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            lookup.cancel(true)
            return ResolvedTarget(h, false, resolved = false)
        }
        if (addresses.isEmpty()) return ResolvedTarget(h, false, resolved = false)
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        val selected = ipv4.firstOrNull(::isPrivateAddress) ?: ipv4.firstOrNull() ?: addresses.first()
        return ResolvedTarget(selected.hostAddress ?: h, isPrivateAddress(selected))
    }

    private fun isLocalRadioHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val now = System.currentTimeMillis()
        localHostCache[h]?.takeIf { now - it.atMs < 60_000L }?.let { return it.local }
        val addresses = runCatching { InetAddress.getAllByName(h) }.getOrNull()
        if (addresses.isNullOrEmpty()) return false
        val resolved = addresses.any(::isPrivateAddress)
        localHostCache[h] = CachedLocalHost(resolved, now)
        return resolved
    }

    private fun watchWifi(app: Context) {             // follow the phone's WiFi network - and keep it
        if (callbackOn) return; callbackOn = true
        val c = app.getSystemService(ConnectivityManager::class.java) ?: return; cm = c
        // Requested, not just watched (0.17.1): a WiFi network with no internet (a radio's own access point, or a home
        // router whose internet is down) is "unwanted" to Android once nothing asks for it, and it drops it for mobile
        // data - found on the phone's log (CMD_UNWANTED_NETWORK), taking the radio link with it every few minutes. A
        // request for WiFi without the internet capability says this app needs that network, internet or not.
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        c.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "phone WiFi up: $network"); wifi = network; localHostCache.clear()
                if (activeRouteUsesWifi && target?.ip?.let(::isLocalRadioHost) == true) c.bindProcessToNetwork(network)
                if (target != null && rig == null) { retries = 0; nextTryMs = 0 }
            }
            override fun onLost(network: Network) {
                Log.d(TAG, "phone WiFi lost: $network"); localHostCache.clear()
                if (wifi == network) { wifi = null; c.bindProcessToNetwork(null) }
                if (target != null && activeLocal && activeRouteUsesWifi) close("手机 Wi-Fi 断开，恢复后自动重连", expectedGeneration = target?.generation)
            }
        })
    }

    private fun open() {                              // one connection attempt
        val t = target ?: return
        if (retryPaused) return
        if (connectionGeneration.get() != t.generation) return
        if (!openingGeneration.compareAndSet(0L, t.generation)) return
        try {
            val resolved = resolveTarget(t.ip)
            synchronized(lifecycleLock) {
                if (target !== t || connectionGeneration.get() != t.generation) return
                openLocked(t, resolved)
            }
        } finally {
            openingGeneration.compareAndSet(t.generation, 0L)
        }
    }

    private fun openLocked(t: Target, resolved: ResolvedTarget) {
        if (!resolved.resolved) {
            status.value = "地址解析超时，请检查网络或 DNS"
            return
        }
        val local = resolved.local
        activeLocal = local
        if (target !== t || connectionGeneration.get() != t.generation) return
        synchronized(pttLock) { closing = true }
        closeNow(null, expected = rig)                   // (any earlier attempt)
        if (target !== t || connectionGeneration.get() != t.generation) return
        val activeNetwork = cm?.activeNetwork
        val activeCaps = activeNetwork?.let { cm?.getNetworkCapabilities(it) }
        val onVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val wifiNetwork = wifi ?: activeNetwork?.takeIf {
            cm?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val w = when {
            !local -> activeNetwork
            onVpn -> activeNetwork
            else -> wifiNetwork ?: activeNetwork
        }
        if (w == null) {
            status.value = if (local) "等待手机 Wi-Fi、VPN 或可用网络" else "等待手机网络"
            return
        }
        activeRouteUsesWifi = local && !onVpn && wifiNetwork != null
        if (activeRouteUsesWifi) {
            wifi = wifiNetwork
            cm?.bindProcessToNetwork(wifiNetwork)
        } else {
            cm?.bindProcessToNetwork(null)
        }
        status.value = if (retries == 0) "正在直连 ${t.ip}…" else "正在直连 ${t.ip}（第 ${retries + 1} 次）…"
        Log.d(TAG, "open ${t.ip}:${t.port} try ${retries + 1}")
        val r = IComWifiRig(resolved.endpoint, t.port, t.user, t.password, t.civPort, t.audioPort)
        r.onStatus = WifiRig.OnStatus { msg, ok ->   // login and network messages
            if (r !== rig || target !== t || connectionGeneration.get() != t.generation) return@OnStatus
            Log.d(TAG, "status: $msg ($ok)")
            if (ok) {
                val audioUdp = r.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp
                if ((audioUdp?.lastAudioPacketMs ?: 0L) > 0L) {
                    markLoggedIn(t, r, msg)
                } else if (!loggedIn) {
                    status.value = "认证成功，正在等待音频通道…"
                    authAcceptedGeneration.value = t.generation
                    if (!authSucceeded) {
                        authSucceeded = true
                        authSucceededMs = System.currentTimeMillis()
                    }
                }
            } else if (msg.contains("认证成功")) {
                if (authSucceededMs == 0L) {
                    authSucceeded = true
                    authSucceededMs = System.currentTimeMillis()
                    authAcceptedGeneration.value = t.generation
                }
                if (!loggedIn) {
                    status.value = msg
                    loginState.value = false
                    scope.value = null
                    if (powerOnAfterLogin) startPowerWake(t, r)
                }
            } else {
                status.value = msg
                synchronized(pttLock) { closing = true }
                loggedIn = false
                loginState.value = false
                authenticatedGeneration.value = 0L
                if (msg.contains("认证未通过") || msg.contains("拒绝用户名或密码")) authAcceptedGeneration.value = 0L
                scope.value = null
                Ic705.netConnected(false, msg)
                rig = null
                if (msg.contains("认证未通过")) {
                    retryPaused = true
                    nextTryMs = Long.MAX_VALUE
                } else {
                    scheduleRetry()
                }
            }
        }
        r.setOnDataEvents(object : WifiRig.OnDataEvents {
            override fun onReceivedCivData(data: ByteArray) {
                if (r === rig && target === t && connectionGeneration.get() == t.generation) {
                    lastCivMs = System.currentTimeMillis(); civSeen = true; observeCivLatency(data); parseScope(data); Ic705.netFrame(data)
                }
            }
            override fun onReceivedWaveData(data: ByteArray) {                     // receive audio: 12 kHz 16-bit little-endian
                if (r !== rig || target !== t || connectionGeneration.get() != t.generation) return
                val n = data.size / 2; val s = ShortArray(n) { i -> ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort() }
                AudioIn.netAudio(s, n)
                if (!loggedIn) markLoggedIn(t, r, "IC-705 传输通道已就绪")
            }
        })
        synchronized(pttLock) {
            if (target !== t || connectionGeneration.get() != t.generation) return
            rig = r
            startedMs = System.currentTimeMillis()
            loggedIn = false
            loginState.value = false
            authSucceeded = false
            authSucceededMs = 0L
        }
        Thread({ try { r.start() } catch (e: Exception) {
            val why = "连接失败：" + (e.message ?: "未知错误")
            status.value = why
            if (r === rig) closeNow(why, expected = r)
        } }, "icom-net").start()
    }

    private fun markLoggedIn(t: Target, r: IComWifiRig, message: String) {
        if (loggedIn || r !== rig || target !== t || connectionGeneration.get() != t.generation) return
        status.value = message
        loggedIn = true
        loginState.value = true
        authAcceptedGeneration.value = t.generation
        authenticatedGeneration.value = t.generation
        r.controlUdp?.civUdp?.let {
            val address = it.civAddress.toInt() and 0xFF
            Ic705.civAddress = address
            it.setCivAddress(address)
        }
        retries = 0; retryPaused = false; civSeen = false; startedMs = System.currentTimeMillis(); lastCivMs = System.currentTimeMillis()
        synchronized(pttLock) { if (r === rig && target != null) closing = false }
        Ic705.netConnected(true)
        if (powerOnAfterLogin) startPowerWake(t, r)
        Thread({ try { Thread.sleep(1200); if (r === rig && !closing && target?.generation == t.generation) enableScope(r, t.generation) } catch (_: Exception) { } }, "icom-scope-on").start()
    }

    private fun close(why: String?, then: (() -> Unit)? = null, expectedGeneration: Long? = null, stop: Boolean = false) {
        var doomed: IComWifiRig? = null
        synchronized(pttLock) {
            if (expectedGeneration != null && target?.generation != expectedGeneration) {
                runCatching { then?.invoke() }
                return
            }
            if (stop) target = null
            closing = true
            pttRequested = false
            doomed = rig
            runCatching { doomed?.quickUnkey() }
        }
        Thread({ closeNow(why, then, doomed) }, "icom-net-close").start()
    }

    private fun closeNow(why: String?, then: (() -> Unit)? = null, expected: IComWifiRig?) {
        synchronized(pttLock) {
            if (expected == null) {
                if (rig != null) return
            } else if (rig !== expected) {
                runCatching { then?.invoke() }
                return
            }
            val r = expected
            closing = true
            pttRequested = false
            if (rig === r) {
                powerWakeRun.incrementAndGet()
                rig = null; loggedIn = false; loginState.value = false; authenticatedGeneration.value = 0L; authSucceeded = false; authSucceededMs = 0L
            }
            if (r != null) {
                try {
                    r.forceUnkey()
                    if (civReady(r)) {
                        val restoreSpeed = Ic705.scopeSpeedForRestore()
                        if (restoreSpeed != null) {
                            repeat(3) { r.sendCivData(civFrame(0x27, 0x1A, 0x00, restoreSpeed)) }
                            Thread.sleep(250)
                        }
                        r.sendCivData(civFrame(0x27, 0x11, 0x00))
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { r.close() }
                }
            }
            runCatching { then?.invoke() }
            resetScope()
            if (why != null) status.value = why
            Ic705.netConnected(false, why ?: "")
        }
    }

    /**
     * Keep sending the CI-V power-on command while the radio is still in network standby. The CI-V UDP port becomes
     * known from the 0x50 status packet before the full I_AM_READY handshake, so this must not wait for [loggedIn].
     */
    private fun startPowerWake(t: Target, r: IComWifiRig) {
        powerWakeDeadlineMs = System.currentTimeMillis() + 30_000L
        val runId = powerWakeRun.incrementAndGet()
        Thread({
            while (target?.generation == t.generation && rig === r && powerWakeRun.get() == runId &&
                System.currentTimeMillis() < powerWakeDeadlineMs) {
                val civPort = r.controlUdp?.civUdp?.rigPort ?: 0
                if (civPort > 0) {
                    repeat(2) {
                        runCatching { r.sendCivData(civFrame(0x18, 0x01)) }
                        try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@Thread }
                    }
                }
                if (loggedIn) {
                    runCatching { Ic705.askAll() }
                    runCatching { Ic705.requestScope() }
                }
                try { Thread.sleep(650) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@Thread }
                if (loggedIn && Ic705.state.value.freqHz > 0L) {
                    powerOnAfterLogin = false
                    powerWakeDeadlineMs = 0L
                    status.value = "电台已开机，连接正常"
                    return@Thread
                }
            }
            if (target?.generation == t.generation && rig === r && powerWakeRun.get() == runId) {
                powerWakeDeadlineMs = 0L
                status.value = "软开机超时，请确认电台网络待机电源和远程开机设置已开启"
            }
        }, "icom-power-wake").start()
    }

    fun enterBackground() { appBackground = true }

    fun enterForeground() {
        appBackground = false
        if (loggedIn && rig != null && target != null) {
            resumeProbeDeadlineMs = System.currentTimeMillis() + 2_500L
            Ic705.askAll()
            Ic705.requestScope()
        } else {
            reconnectNow()
        }
    }

    private fun scheduleRetry() {
        retries++
        nextTryMs = System.currentTimeMillis() + minOf(5_000L + retries * 1_000L, 10_000L)
    }

    private fun check() {                             // the watchdog (every 2 s)
        val t = target ?: return
        val generation = t.generation
        val now = System.currentTimeMillis()
        if (appBackground && rig != null) {
            if (loggedIn && civSeen && now - lastCivMs > 20_000L && now > powerWakeDeadlineMs) {
                close("后台电台链路超时，正在重连", expectedGeneration = generation)
                scheduleRetry()
            }
            return
        }
        if (rig != null && now < resumeProbeDeadlineMs) return
        when {
            rig == null -> if (!retryPaused && now >= nextTryMs) open()
            !loggedIn && authSucceeded && now - authSucceededMs > (if (powerOnAfterLogin) 30_000L else 15_000L) -> {
                val r = rig
                val why = if (r != null && civReady(r)) {
                    "认证成功，但音频通道未建立。请确认 UDP 50003（或自定义音频端口）已转发到 IC-705"
                } else {
                    "认证成功，但串口通道未建立。请确认 UDP 50002（或自定义串口端口）已转发到 IC-705，并关闭其他占用远程会话的软件"
                }
                Log.d(TAG, "watchdog: $why")
                close(why, expectedGeneration = generation)
                scheduleRetry()
            }
            !loggedIn && !authSucceeded && now - startedMs > 20_000 -> {
                val why = "电台登录超时，正在重试"
                Log.d(TAG, "watchdog: $why")
                close(why, expectedGeneration = generation)
                scheduleRetry()
            }
            loggedIn && now > powerWakeDeadlineMs && civSeen && now - lastCivMs > 8_000 -> {
                Log.d(TAG, "watchdog: radio quiet")
                close("电台停止响应，正在重连", expectedGeneration = generation)
                scheduleRetry()
            }
            loggedIn && now > powerWakeDeadlineMs && !civSeen && now - lastCivMs > 60_000 -> {
                Log.d(TAG, "watchdog: no CI-V")
                close("未收到控制数据，正在重连", expectedGeneration = generation)
                scheduleRetry()
            }
            loggedIn && now > powerWakeDeadlineMs && rig?.let { r ->
                val last = (r.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.lastAudioPacketMs ?: 0L
                last > 0L && now - last > 3_000L
            } == true -> {
                Log.d(TAG, "watchdog: audio stream stalled")
                forceUnkey()
                close("音频数据中断，正在重连", expectedGeneration = generation)
                scheduleRetry()
            }
            loggedIn && now > powerWakeDeadlineMs && now - startedMs > 8_000 && Ic705.controlSeenMs == 0L -> {
                Log.d(TAG, "watchdog: no normal CI-V")
                close("控制通道未返回数据，正在重连", expectedGeneration = generation)
                scheduleRetry()
            }
            loggedIn && now > powerWakeDeadlineMs && Ic705.controlSeenMs > 0L && now - Ic705.controlSeenMs > 12_000 -> {
                Log.d(TAG, "watchdog: normal CI-V stalled")
                close("控制数据中断，正在重连", expectedGeneration = generation)
                scheduleRetry()
            }
        }
    }

    /** The radio has told us its CI-V / audio ports: until then a send goes to port 0, fails (EINVAL), and FT8CN's code
     *  closes the whole connection - which is what broke the first logins (0.8.3). */
    private fun civReady(r: IComWifiRig) = (r.controlUdp?.civUdp?.rigPort ?: 0) != 0
    private fun audioReady(r: IComWifiRig) = (r.controlUdp?.audioUdp?.rigPort ?: 0) != 0
    private fun audioFresh(r: IComWifiRig): Boolean {
        val last = (r.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.lastAudioPacketMs ?: 0L
        return last > 0L && System.currentTimeMillis() - last < 1_500L
    }
    /** PTT may key only after the radio reported its ports and the audio stream has delivered a packet. */
    fun txAudioReady(): Boolean {
        val r = rig ?: return false
        return !closing && target != null && loggedIn && civReady(r) && audioReady(r) && audioFresh(r)
    }
    /** Frequency, mode and the core level parameters must be read back before PTT can key. */
    fun radioStateReady(): Boolean {
        val s = Ic705.state.value
        val filterRequired = s.mode.isNotBlank() && s.mode != "FM" && s.mode != "DV" && !s.mode.startsWith("WFM")
        return s.freqHz > 0L && s.mode.isNotBlank() && s.rfGain >= 0 && s.sql >= 0 && s.power >= 0 &&
            (!filterRequired || s.filterIndex >= 0)
    }

    /** CI-V command bytes to the radio (dropped until the CI-V stream is open; the next poll repeats them). */
    fun civ(frame: ByteArray) {
        val r = rig ?: return
        if (!closing && target != null && loggedIn && civReady(r)) {
            if (frame.size > 4 && (frame[4].toInt() and 0xFF) == 0x03) civQueryNs = System.nanoTime()
            r.sendCivData(frame)
        }
    }

    private fun civFrame(vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 5)
        out[0] = 0xFE.toByte(); out[1] = 0xFE.toByte(); out[2] = Ic705.civAddress.toByte(); out[3] = 0xE0.toByte()
        body.forEachIndexed { i, value -> out[4 + i] = value.toByte() }; out[out.lastIndex] = 0xFD.toByte()
        return out
    }

    private fun observeCivLatency(data: ByteArray) {
        val sent = civQueryNs
        if (sent == 0L) return
        var i = 0
        while (i + 5 < data.size) {
            if ((data[i].toInt() and 0xFF) == 0xFE && (data[i + 1].toInt() and 0xFF) == 0xFE) {
                var end = i + 2
                while (end < data.size && (data[end].toInt() and 0xFF) != 0xFD) end++
                if (end < data.size && (data[i + 4].toInt() and 0xFF) == 0x03) {
                    latencyMs.value = ((System.nanoTime() - sent) / 1_000_000L).coerceAtLeast(0L)
                    civQueryNs = 0L
                    return
                }
                i = end
            }
            i++
        }
    }

    private fun enableScope(r: IComWifiRig, generation: Long) {
        if (r !== rig || !loggedIn || closing || target?.generation != generation) return
        listOf(
            intArrayOf(0x27, 0x10, 0x01), intArrayOf(0x27, 0x11, 0x01),
            intArrayOf(0x27, 0x14, 0x00), intArrayOf(0x27, 0x15, 0x00),
            intArrayOf(0x27, 0x17, 0x00), intArrayOf(0x27, 0x1A, 0x00),
        ).forEach { r.sendCivData(civFrame(*it)) }
    }

    private fun parseScope(data: ByteArray) {
        var i = 0
        while (i + 7 < data.size) {
            if ((data[i].toInt() and 0xFF) == 0xFE && (data[i + 1].toInt() and 0xFF) == 0xFE &&
                (data[i + 4].toInt() and 0xFF) == 0x27 && (data[i + 5].toInt() and 0xFF) == 0x00) {
                var end = i + 7
                while (end < data.size && (data[end].toInt() and 0xFF) != 0xFD) end++
                if (end > i + 7) scopeAssembler.accept(data.copyOfRange(i + 7, end))?.let {
                    lastScopeMs = System.currentTimeMillis()
                    scope.value = it
                }
                return
            }
            i++
        }
    }

    /** PTT, with duplicated edge commands because Icom LAN UDP has no command acknowledgement. */
    fun ptt(on: Boolean) {
        synchronized(pttLock) {
            val r = rig
            if (on && (r == null || !loggedIn || closing || target == null)) {
                pttRequested = false
                runCatching { r?.quickUnkey() }
                return
            }
            pttRequested = on
            if (r != null && civReady(r)) {
                repeat(if (on) 3 else 4) {
                    runCatching { r.setPttOn(on) }
                    try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
                if (!on) {
                    (r.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.stopTxStream()
                }
            }
        }
    }

    /** Soft power off over CI-V, then close the remote session so it cannot reconnect. */
    fun powerOff() {
        uk.hamdigital.core.TxControl.stopAll()
        forceUnkey()
        try {
            repeat(2) {
                runCatching { Ic705.setPowerState(false) }
                try { Thread.sleep(120) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            Thread.sleep(250)
        } finally {
            disconnect()
        }
    }

    /** Soft power on. The remote LAN session must already be authenticated. */
    fun powerOn() {
        val t = target
        val r = rig
        if (r == null || t == null || !authSucceeded) {
            status.value = "需要先登录电台网络待机会话"
            return
        }
        startPowerWake(t, r)
    }

    /** Emergency/local release used by lifecycle changes, disconnects and the safety watchdog. */
    fun forceUnkey() {
        synchronized(pttLock) {
            pttRequested = false
            val r = rig ?: return
            repeat(4) { runCatching { r.quickUnkey() } }
            Ic705.pttReleaseSent()
        }
    }

    /** Safety watchdog path: re-check under the same lock so a new legal PTT cannot be cancelled. */
    private fun forceUnkeyIfIdle() {
        synchronized(pttLock) {
            if (pttRequested || closing || target == null || !Ic705.state.value.tx) return
            pttRequested = false
            repeat(4) { runCatching { rig?.quickUnkey() } }
            Ic705.pttReleaseSent()
        }
    }

    /** True when [generation] is the attempt that completed authentication. */
    fun isAuthenticated(generation: Long): Boolean = generation > 0L && authenticatedGeneration.value == generation && loggedIn

    /** Reconnect immediately after returning from a long background period. */
    fun reconnectNow() {
        val t = target ?: return
        if (loggedIn && rig != null) return
        if (retryPaused) return
        retries = 0
        nextTryMs = 0
        powerWakeRun.incrementAndGet()
        if (rig != null) close("从后台恢复，正在重新连接", expectedGeneration = t.generation)
        else Thread({ open() }, "icom-resume").start()
    }

    /** Transmit audio: 12 kHz floats, -1..1, sent in real time by the protocol code (20 ms packets). */
    fun sendAudio(samples: FloatArray): Boolean {
        val r = rig ?: return false
        if (!txAudioReady()) return false
        r.sendWaveData(samples)
        return true
    }

    /** Streamed transmit audio (FreeDV voice, made as it goes): start sending the queue - PTT must be on - ... */
    fun streamAvailable(): Boolean = txAudioReady()
    fun streamStart() { val r = rig ?: return; if (txAudioReady()) (r.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.startTxStream() }
    /** ... queue 12 kHz 16-bit samples ... */
    fun streamPush(s: ShortArray, n: Int) { val r = rig ?: return; if (!closing && target != null) (r.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.pushTxAudio(s, n) }
    /** ... (samples still waiting in the queue) ... */
    fun streamQueued(): Int = (rig?.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.txQueued() ?: 0
    /** Receive audio packets: [received, filled with silence (lost), recovered (asked for again), too late] - null when not on WiFi. */
    fun rxAudioStats(): LongArray? = (rig?.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.let { longArrayOf(it.rxPackets, it.rxLost, it.rxRecovered, it.rxLate) }
    /** ... and stop. */
    fun streamStop() { (rig?.controlUdp?.audioUdp as? uk.hamdigital.icom.IcomAudioUdp)?.stopTxStream() }
}





