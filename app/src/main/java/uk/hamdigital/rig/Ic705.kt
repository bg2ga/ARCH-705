// The IC-705 over its USB lead (or, with IcomNet, over WiFi): CI-V control on the radio's first USB serial port (the second carries GPS data), using
// usb-serial-for-android (MIT) for the CDC-ACM serial link. Reads the frequency and mode (asked every second, and from
// the radio's own transceive broadcasts), tunes it, sets the mode (with DATA for the digital modes), and - from stage 7 -
// keys the transmitter. CI-V frames: FE FE <to> <from> <command> [sub] [data] FD; the IC-705's address is A4 by default
// (MENU > SET > Connectors > CI-V > CI-V Address), the app's E0.
package uk.hamdigital.rig

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import uk.hamdigital.core.BandPlan
import uk.hamdigital.core.TxControl
import uk.hamdigital.core.PttLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** How a mode wants the radio: upper or lower sideband with DATA on, or CW. */
enum class RigMode(val label: String) { USB_D("USB-D"), LSB_D("LSB-D"), CW("CW"), FM_D("FM-D"), DV("DV") } // (FM-D: APRS / packet on VHF / UHF)

/** The radio as last heard. */
data class RigState(
    val link: Link = Link.NONE,                       // connection
    val message: String = "",                         // what is happening / went wrong
    val freqHz: Long = 0,                             // selected VFO frequency
    val freqAHz: Long = 0,                            // stored VFO A frequency
    val freqBHz: Long = 0,                            // stored VFO B frequency
    val selectedVfo: Int = 0,                         // 0 = A, 1 = B
    val mode: String = "",                            // USB, LSB, CW, ...
    val data: Boolean = false,                        // DATA mode on (USB-D)
    val modeA: String = "",                           // stored VFO A mode
    val modeB: String = "",                           // stored VFO B mode
    val dataA: Boolean = false,                       // stored VFO A DATA flag
    val dataB: Boolean = false,                       // stored VFO B DATA flag
    val tx: Boolean = false,                          // radio reports transmitting
    val txRequested: Boolean = false,                 // TX may still be active until the radio confirms OFF
    val pttOffConfirmations: Int = 0,                 // consecutive OFF reports after a release request
    val vfoSwitching: Boolean = false,                // an A/B select or mode transaction is being applied
    val breakIn: Int = -1,                            // CW break-in: 0 off, 1 semi, 2 full (-1 not known yet)
    val nr: Int = -1,                                 // noise reduction: 0 off, 1 on (-1 not known yet)
    val nrLevel: Int = -1,                            // noise reduction level, 0 off, 1..255 on
    val autoNotch: Int = -1,                          // auto notch: 0 off, 1 on (-1 not known yet)
    val nb: Int = -1,                                 // noise blanker: 0 off, 1 on (-1 not known yet)
    val filter: Int = -1,                             // filter 1..3
    val filterIndex: Int = -1,                        // IF filter width index (0..40, AM 0..49)
    val filterWidth: Int = -1,                        // passband width in Hz, derived from filterIndex
    val sMeter: Int = -1,                             // S meter, 0..255 scale from CI-V
    val powerMeter: Int = -1,                         // RF power meter, 0..255
    val swrMeter: Int = -1,                           // SWR meter, 0..255
    val alcMeter: Int = -1,                           // ALC meter, 0..255
    val compMeter: Int = -1,                          // compressor meter, 0..255
    val vdMeter: Int = -1,                            // supply voltage meter, 0..255
    val idMeter: Int = -1,                            // supply current meter, 0..255
    val wlanAfSql: Int = -1,                          // WLAN AF SQL routing: 0 off, 1 on
    val agc: Int = -1,                                // AGC 1 fast, 2 medium, 3 slow
    val att: Int = -1,                                // attenuator 0 or 20 dB
    val preamp: Int = -1,                             // preamp 0 off, 1/2 on
    val rfGain: Int = -1,                             // RF gain 0..255
    val sql: Int = -1,                                // squelch 0..255
    val power: Int = -1,                              // TX power 0..255
    val scopeSpeed: Int = -1,                         // 0 fast, 1 mid, 2 slow
    val scopeSpan: Int = -1,                          // 0 ±2.5k, 1 ±5k, 2 ±10k, 3 ±25k, 4 ±50k, 5 ±100k, 6 ±250k, 7 ±500k
    val manualNotch: Int = -1,                        // manual notch: 0 off, 1 on
    val split: Int = 0,                               // 0 off, 0x01 split, 0x11 DUP-, 0x12 DUP+
    val toneMode: Int = -1,                           // 0 off, 1 TONE, 2 TSQL, 3 DTCS
    val toneTenths: Int = 0,                          // repeater/CTCSS tone, 10 Hz units
    val tsqlTenths: Int = 0,                          // TSQL receive tone, 10 Hz units
    val dtcsCode: Int = 0,                            // DTCS code
    val offsetHz: Long = 0,                           // repeater offset
    val tbw: Int = -1,                                // SSB TX bandwidth 0..2
    val compressor: Int = -1,                         // speech compressor 0/1
    val monitor: Int = -1,                            // TX monitor 0/1
    val monitorGain: Int = -1,                        // monitor gain 0..255
    val compressorLevel: Int = -1,                    // compressor level 0..255
) {
    enum class Link { NONE, ASKING, CONNECTED }       // not connected / waiting for USB permission / talking to it
    /** "14.074.000" */
    val freqText get() = if (freqHz <= 0) "--.---.---" else "%d.%03d.%03d".format(freqHz / 1_000_000, freqHz / 1000 % 1000, freqHz % 1000)
    val modeText get() = if (mode.isEmpty()) "" else mode + if (data) "-D" else "" // "USB-D"
}

object Ic705 {
    const val VENDOR_ICOM = 0x0C26                    // Icom's USB vendor id
    const val PRODUCT_IC705 = 0x0036                  // the IC-705
    private const val ACTION_PERMISSION = "uk.hamdigital.USB_PERMISSION" // our permission answer
    private const val CTRL = 0xE0                     // the app's CI-V address (a controller)
    @Volatile var civAddress = 0xA4
    private val SPANS_HZ = longArrayOf(2_500L, 5_000L, 10_000L, 25_000L, 50_000L, 100_000L, 250_000L, 500_000L)
    @Volatile var controlSeenMs = 0L                  // last normal CI-V reply (not the scope stream)
    @Volatile private var originalScopeSpeed = -1
    @Volatile private var vfoSwitchPendingUntil = 0L
    @Volatile private var pttReleaseAtMs = 0L
    private val vfoTransactions = AtomicInteger(0)

    private val _state = MutableStateFlow(RigState()) // the radio, for the screens
    val state: StateFlow<RigState> = _state
    private val _cwSending = MutableStateFlow(false)
    val cwSending: StateFlow<Boolean> = _cwSending

    private var port: UsbSerialPort? = null           // the open CI-V port
    private var reader: Thread? = null                // reads its replies; asks for the frequency and mode every second
    @Volatile private var running = false             // reader running
    private var receiverOn = false                    // permission receiver registered
    private val lock = Any()                          // writes from several threads
    private var baselineRf = -1                       // the radio's original level values
    private var baselineSql = -1
    private var baselinePower = -1
    private var baselineFilter = -1
    private var baselineWlanAfSql = -1
    private data class PendingLevel(val expected: Int, val deadline: Long, val attempts: Int)
    private data class PendingFilter(val expected: Int, val deadline: Long, val attempts: Int)
    private data class PendingFrequency(val expected: Long, val deadline: Long, val attempts: Int, val targetVfo: Int)
    private val pendingLevels = ConcurrentHashMap<Int, PendingLevel>()
    @Volatile private var pendingFilter: PendingFilter? = null
    @Volatile private var pendingFrequency: PendingFrequency? = null
    private val netCivBuffer = ByteArrayOutputStream()

    /** Mode names by CI-V mode number (command 04 / 01). */
    private val MODES = mapOf(0x00 to "LSB", 0x01 to "USB", 0x02 to "AM", 0x03 to "CW", 0x04 to "RTTY", 0x05 to "FM", 0x06 to "WFM", 0x07 to "CW-R", 0x08 to "RTTY-R", 0x17 to "DV")

    /** The IC-705 (or another Icom) on the USB bus, if plugged in. */
    fun findDevice(ctx: Context): UsbDevice? {
        val um = ctx.getSystemService(UsbManager::class.java) ?: return null // no USB host
        val icom = um.deviceList.values.filter { it.vendorId == VENDOR_ICOM } // Icom radios
        return icom.firstOrNull { it.productId == PRODUCT_IC705 } ?: icom.firstOrNull() // the IC-705 first
    }

    /** Connect if the radio is plugged in and not yet connected (asks for USB permission the first time). Safe to call often. */
    fun connect(ctx: Context) {
        val app = ctx.applicationContext               // (outlives any screen)
        if (port != null || net) return                // already connected (USB or WiFi)
        val dev = findDevice(app) ?: run { set { it.copy(link = RigState.Link.NONE, message = "IC-705 not plugged in") }; return }
        val um = app.getSystemService(UsbManager::class.java)
        if (!um.hasPermission(dev)) {                  // Android asks the user once
            registerReceiver(app)                      // to hear the answer
            val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE) // (mutable: Android adds the answer)
            um.requestPermission(dev, pi)              // the "Allow HF Digital Modes to access IC-705?" box
            set { it.copy(link = RigState.Link.ASKING, message = "Allow access to the IC-705 (Android is asking)") }
            return
        }
        open(app, um, dev)                             // allowed: open it
    }

    private fun registerReceiver(app: Context) {
        if (receiverOn) return; receiverOn = true      // once
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_PERMISSION -> if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(c) // allowed: connect
                                         else set { it.copy(link = RigState.Link.NONE, message = "USB access to the IC-705 was not allowed") }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> if (findDevice(c) == null) disconnect("IC-705 unplugged") // the lead came out
                }
            }
        }
        val f = IntentFilter(ACTION_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) } // permission answers, unplugging
        ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun open(app: Context, um: UsbManager, dev: UsbDevice) {
        registerReceiver(app)                          // to hear it unplugged
        try {
            val drv = CdcAcmSerialDriver(dev)          // the radio's USB serial ports (composite device: audio + 2 serial)
            val p = drv.ports.firstOrNull() ?: throw Exception("no serial port") // port A: CI-V
            val conn = um.openDevice(dev) ?: throw Exception("would not open")
            p.open(conn); p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) // (the speed is nominal over USB)
            p.dtr = true; p.rts = false                // RTS low: the IC-705 can key on RTS (USB SEND), so never raise it
            port = p; running = true
            set { RigState(link = RigState.Link.CONNECTED, message = "IC-705 connected (CI-V)") }
            reader = Thread({ readLoop(p) }, "civ").apply { isDaemon = true; start() } // replies + polling
        } catch (e: Exception) { set { it.copy(link = RigState.Link.NONE, message = "IC-705 CI-V would not open: ${e.message}") } }
    }

    fun disconnect(why: String = "Disconnected") {
        if (port != null) {
            repeat(2) { sendUsb(0x1C, 0x00, 0x00) }
            try { Thread.sleep(60) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        running = false; pendingLevels.clear(); pendingFilter = null; pendingFrequency = null
        try { port?.close() } catch (e: Exception) { }
        port = null
        set { RigState(link = RigState.Link.NONE, message = why) }
    }

    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(256); val frame = ByteArrayOutputStream() // read buffer, the frame being collected
        var lastPoll = 0L
        var lastMeterPoll = 0L
        var meterTick = 0
        var lastPendingCheck = 0L
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastPoll > 1000) { lastPoll = now; send(0x07); send(0x03); send(0x04); send(0x1A, 0x06); send(0x1C, 0x00); send(0x25, 0x00); send(0x25, 0x01) }
            if (now - lastMeterPoll >= 100) {
                lastMeterPoll = now
                val tick = meterTick++
                send(0x15, 0x02)
                if (tick % 2 == 0) {
                    send(0x15, 0x11); send(0x15, 0x12); send(0x15, 0x13); send(0x15, 0x14)
                }
                if (tick % 5 == 0) {
                    send(0x15, 0x15); send(0x15, 0x16)
                }
            }
            if (now - lastPendingCheck > 500) { lastPendingCheck = now; retryPending() }
            val n = try { p.read(buf, 200) } catch (e: Exception) { if (running) disconnect("IC-705 link lost: ${e.message}"); return } // waits up to 0.2 s
            for (i in 0 until n) {                     // collect frames
                val b = buf[i].toInt() and 0xFF
                if (b == 0xFE && frame.size() >= 2 && frame.toByteArray().all { (it.toInt() and 0xFF) == 0xFE }) continue // (extra preamble bytes)
                frame.write(b)
                if (b == 0xFD) { handle(frame.toByteArray()); frame.reset() } // end of frame
                if (frame.size() > 64) frame.reset()   // rubbish: start again
            }
        }
    }

    /** One frame from the radio: FE FE to from cmd [data] FD. */
    private fun handle(f: ByteArray) {
        val u = f.map { it.toInt() and 0xFF }          // unsigned
        if (u.size < 6 || u[0] != 0xFE || u[1] != 0xFE) return // not a frame
        if (u[3] == CTRL) return                       // our own command echoed back (CI-V USB Echo Back on)
        if (u[2] != CTRL && u[2] != 0x00) return       // for another controller
        val cmd = u[4]; val d = u.subList(5, u.size - 1) // command, data
        if (cmd != 0x27) controlSeenMs = System.currentTimeMillis()
        when (cmd) {
            0x00, 0x03 -> if (d.size >= 5 && System.currentTimeMillis() >= vfoSwitchPendingUntil) {
                val hz = bcdToHz(d)
                pendingFrequency?.let { pending -> if (pending.targetVfo == state.value.selectedVfo && kotlin.math.abs(hz - pending.expected) <= 5L) pendingFrequency = null }
                set { if (it.selectedVfo == 0) it.copy(freqHz = hz, freqAHz = hz) else it.copy(freqHz = hz, freqBHz = hz) }
            }
            0x07 -> if (d.isNotEmpty() && System.currentTimeMillis() >= vfoSwitchPendingUntil) {
                val vfo = d[0] and 0x01
                set {
                    val storedHz = if (vfo == 0) it.freqAHz else it.freqBHz
                    val storedMode = if (vfo == 0) it.modeA else it.modeB
                    val storedData = if (vfo == 0) it.dataA else it.dataB
                    it.copy(
                        selectedVfo = vfo,
                        freqHz = storedHz.takeIf { hz -> hz > 0 } ?: it.freqHz,
                        mode = storedMode.ifBlank { it.mode },
                        data = storedData,
                    )
                }
            }
            0x01, 0x04 -> if (d.isNotEmpty() && System.currentTimeMillis() >= vfoSwitchPendingUntil) {
                val label = MODES[d[0]] ?: "?"
                val filterIndex = if (d.size >= 2) d[1] else state.value.filter
                set {
                    if (it.selectedVfo == 0) it.copy(mode = label, modeA = label, filter = filterIndex)
                    else it.copy(mode = label, modeB = label, filter = filterIndex)
                }
            }
            0x11 -> if (d.isNotEmpty()) set { it.copy(att = if (d[0] == 0x20) 20 else 0) }
            0x14 -> if (d.size >= 2) {
                val actual = bcd2Msb(d, 1)
                when (d[0]) {
                    0x02 -> set { it.copy(rfGain = actual) }
                    0x03 -> set { it.copy(sql = actual) }
                    0x06 -> set { it.copy(nrLevel = actual) }
                    0x0A -> set { it.copy(power = actual) }
                    0x0E -> set { it.copy(compressorLevel = actual) }
                    0x15 -> set { it.copy(monitorGain = actual) }
                }
                pendingLevels[d[0]]?.let { pending -> if (pending.expected == actual) pendingLevels.remove(d[0], pending) }
            }
            0x15 -> if (d.size >= 2) when (d[0]) {
                0x02 -> set { it.copy(sMeter = bcd2Msb(d, 1)) }
                0x11 -> set { it.copy(powerMeter = bcd2Msb(d, 1)) }
                0x12 -> set { it.copy(swrMeter = bcd2Msb(d, 1)) }
                0x13 -> set { it.copy(alcMeter = bcd2Msb(d, 1)) }
                0x14 -> set { it.copy(compMeter = bcd2Msb(d, 1)) }
                0x15 -> { val raw = bcd2Msb(d, 1); android.util.Log.d("Ic705Meter", "Vd bytes=${d.drop(1)} raw=$raw"); set { it.copy(vdMeter = raw) } }
                0x16 -> { val raw = bcd2Msb(d, 1); android.util.Log.d("Ic705Meter", "Id bytes=${d.drop(1)} raw=$raw"); set { it.copy(idMeter = raw) } }
            }
            0x0D -> if (d.size >= 3) set { it.copy(offsetHz = Ic705Codec.offsetValue(d)) }
            0x0F -> if (d.isNotEmpty()) set { it.copy(split = d[0]) }
            0x1B -> if (d.isNotEmpty()) when (d[0]) {
                0x00 -> if (d.size >= 3) set { it.copy(toneTenths = toneTenths(d)) }
                0x01 -> if (d.size >= 3) set { it.copy(tsqlTenths = toneTenths(d)) }
                0x02 -> if (d.size >= 3) set { it.copy(dtcsCode = toneTenths(d)) }
            }
            0x1A -> if (d.size >= 2) when (d[0]) {
                0x03 -> {
                    set { it.copy(filterIndex = d[1], filterWidth = filterWidthFor(it.mode, d[1])) }
                    pendingFilter?.let { pending -> if (pending.expected == d[1]) pendingFilter = null }
                }
                0x06 -> if (System.currentTimeMillis() >= vfoSwitchPendingUntil) {
                    val dataOn = d[1] != 0
                    set {
                        if (it.selectedVfo == 0) it.copy(data = dataOn, dataA = dataOn)
                        else it.copy(data = dataOn, dataB = dataOn)
                    }
                }
                0x05 -> if (d.size >= 4 && d[1] == 0x01 && d[2] == 0x15) set { it.copy(wlanAfSql = d[3]) }
            }
            0x25 -> if (d.size >= 6) {
                val hz = bcdToHz(d.subList(1, d.size))
                set {
                    when (d[0]) {
                        0x00 -> {
                            pendingFrequency?.let { pending -> if (pending.targetVfo == it.selectedVfo && kotlin.math.abs(hz - pending.expected) <= 5L) pendingFrequency = null }
                            if (it.selectedVfo == 0) it.copy(freqAHz = hz, freqHz = hz) else it.copy(freqBHz = hz, freqHz = hz)
                        }
                        0x01 -> if (it.selectedVfo == 0) it.copy(freqBHz = hz) else it.copy(freqAHz = hz)
                        else -> it
                    }
                }
            }
            0x1C -> if (d.size >= 2 && d[0] == 0x00) {
                val transmitting = d[1] != 0
                set {
                    if (transmitting) {
                        it.copy(tx = true, txRequested = true, pttOffConfirmations = 0)
                    } else {
                        val releaseReady = pttReleaseAtMs > 0L && System.currentTimeMillis() - pttReleaseAtMs >= 200L
                        val confirmations = if (releaseReady) it.pttOffConfirmations + 1 else it.pttOffConfirmations
                        it.copy(
                            tx = false,
                            txRequested = if (releaseReady && confirmations >= 1) false else it.txRequested,
                            pttOffConfirmations = confirmations,
                        )
                    }
                }
            }
            0x16 -> if (d.size >= 2) when (d[0]) {
                0x02 -> set { it.copy(preamp = d[1]) }; 0x12 -> set { it.copy(agc = d[1]) }; 0x22 -> set { it.copy(nb = d[1]) }
                0x40 -> set { it.copy(nr = d[1]) }; 0x41 -> set { it.copy(autoNotch = d[1]) }; 0x44 -> set { it.copy(compressor = d[1]) }
                0x45 -> set { it.copy(monitor = d[1]) }; 0x47 -> set { it.copy(breakIn = d[1]) }; 0x48 -> set { it.copy(manualNotch = d[1]) }
                0x58 -> set { it.copy(tbw = d[1]) }; 0x5D -> set { it.copy(toneMode = d[1]) }
            } // preamp, AGC, NB, NR, notch, break-in
            0x27 -> if (d.size >= 2) when (d[0]) {
                0x15 -> if (d.size >= 7) {
                    val hz = bcdToHz(d.subList(2, 7))
                    val index = SPANS_HZ.indexOfFirst { it == hz }
                    if (index >= 0) set { it.copy(scopeSpan = index) }
                }
                0x1A -> if (d.size >= 3) {
                    if (originalScopeSpeed < 0) originalScopeSpeed = d[2]
                    set { it.copy(scopeSpeed = d[2]) }
                }
            }
            0xFA -> set { it.copy(message = "The IC-705 refused a command") } // NG
        }
        captureBaseline()
    }

    private fun captureBaseline() {
        val s = state.value
        if (baselineRf < 0 && s.rfGain >= 0) baselineRf = s.rfGain
        if (baselineSql < 0 && s.sql >= 0) baselineSql = s.sql
        if (baselinePower < 0 && s.power >= 0) baselinePower = s.power
        if (baselineFilter < 0 && s.filterIndex >= 0) baselineFilter = s.filterIndex
        if (baselineWlanAfSql < 0 && s.wlanAfSql >= 0) baselineWlanAfSql = s.wlanAfSql
    }

    private fun bcdToHz(d: List<Int>): Long = Ic705Codec.frequencyValue(d)
    private fun bcdWords(d: List<Int>, at: Int): Int { var value = 0; var mul = 1; for (i in 0 until 2) { val b = d[at + i]; value += (b and 0x0F) * mul; mul *= 10; value += ((b shr 4) and 0x0F) * mul; mul *= 10 }; return value }
    private fun bcd3(d: List<Int>, at: Int): Int { var value = 0; var mul = 1; for (i in 0 until 3) { val b = d[at + i]; value += (b and 0x0F) * mul; mul *= 10; value += ((b shr 4) and 0x0F) * mul; mul *= 10 }; return value }

    private fun hzToBcd(hz: Long): IntArray = Ic705Codec.frequencyBcd(hz)

    private fun frequencyBcd(hz: Long): IntArray = Ic705Codec.frequencyBcd(hz)
    private fun toneTenths(d: List<Int>): Int {
        if (d.size < 3) return 0
        return (d[1] and 0x0F) * 100 + ((d[1] shr 4) and 0x0F) * 1000 + (d[2] and 0x0F) + ((d[2] shr 4) and 0x0F) * 10
    }
    private fun toneBcd(tenths: Int, txInvert: Boolean, rxInvert: Boolean): IntArray {
        val v = tenths.coerceIn(0, 9999)
        val inv = (if (rxInvert) 0x10 else 0) or (if (txInvert) 0x01 else 0)
        return intArrayOf(inv, ((v / 100) % 10) or (((v / 1000) % 10) shl 4), (v % 10) or (((v / 10) % 10) shl 4))
    }

    private fun frame(vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 5)             // FE FE to from .. FD
        out[0] = 0xFE.toByte(); out[1] = 0xFE.toByte(); out[2] = civAddress.toByte(); out[3] = CTRL.toByte()
        body.forEachIndexed { i, b -> out[4 + i] = b.toByte() }; out[out.lastIndex] = 0xFD.toByte()
        return out
    }

    /** Send a command (bytes after the addresses, before FD). */
    private fun send(vararg body: Int) {
        val f = frame(*body)
        if (net) { IcomNet.civ(f); return }            // over WiFi
        val p = port ?: return
        synchronized(lock) { try { p.write(f, 500) } catch (e: Exception) { } } // (a lost write is retried by the next poll)
    }

    /** Bypass WiFi routing when cleaning up an old USB link. */
    private fun sendUsb(vararg body: Int) {
        val p = port ?: return
        val f = frame(*body)
        synchronized(lock) { try { p.write(f, 500) } catch (_: Exception) { } }
    }

    // ---- WiFi (IcomNet): the same commands and replies over Icom's network protocol ----
    @Volatile var net = false; private set             // using the WiFi link
    private var netPoll: java.util.Timer? = null       // asks for the frequency and mode every second
    private var meterPoll: java.util.Timer? = null      // fast S/power/SWR/ALC/COMP/V/A meter refresh
    private val channelModeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "civ-vfo-mode").apply { isDaemon = true }
    }

    /** IcomNet: logged in (or not) to the radio over WiFi. */
    fun netConnected(on: Boolean, why: String = "") {
        android.util.Log.d("IcomNet", "netConnected($on, $why)")
        net = on; netPoll?.cancel(); netPoll = null; meterPoll?.cancel(); meterPoll = null
        if (on) {
            if (port != null) disconnect("Using WiFi")     // (one link at a time)
            baselineRf = -1; baselineSql = -1; baselinePower = -1; baselineFilter = -1; baselineWlanAfSql = -1
            pendingLevels.clear(); pendingFilter = null; pendingFrequency = null
            controlSeenMs = 0L
            net = true
            beginSessionRead()
            set { RigState(link = RigState.Link.CONNECTED, message = "IC-705 connected over WiFi") }
            askAll()
            var poll = 0
            var meterTick = 0
            netPoll = java.util.Timer("civ-net", true).apply { scheduleAtFixedRate(object : java.util.TimerTask() { override fun run() {
                send(0x07); send(0x03); send(0x04); send(0x1A, 0x06); send(0x1C, 0x00); send(0x25, 0x00); send(0x25, 0x01)
                retryPending()
                if (poll++ % 2 == 0) {
                    send(0x11); askLevels()
                    send(0x16, 0x02); send(0x16, 0x12); send(0x16, 0x22); send(0x16, 0x40); send(0x16, 0x41)
                    send(0x27, 0x1A)
                }
            } }, 500, 1000) }
            meterPoll = java.util.Timer("civ-meter", true).apply { scheduleAtFixedRate(object : java.util.TimerTask() { override fun run() {
                val tick = meterTick++
                send(0x15, 0x02)
                if (tick % 2 == 0) {
                    send(0x15, 0x11); send(0x15, 0x12); send(0x15, 0x13); send(0x15, 0x14)
                }
                if (tick % 5 == 0) {
                    send(0x15, 0x15); send(0x15, 0x16)
                }
            } }, 100, 100) }
        } else if (port == null) {
            pendingLevels.clear(); pendingFilter = null; pendingFrequency = null
            set { RigState(link = RigState.Link.NONE, message = why) }
        }
    }

    /** IcomNet: CI-V bytes from the radio (one or more frames). */
    fun netFrame(data: ByteArray) {
        synchronized(netCivBuffer) {
            netCivBuffer.write(data)
            val bytes = netCivBuffer.toByteArray()
            var start = -1
            var consumed = 0
            for (i in bytes.indices) {
                val value = bytes[i].toInt() and 0xFF
                if (start < 0 && value == 0xFE) start = i
                if (start >= 0 && value == 0xFD) {
                    handle(bytes.copyOfRange(start, i + 1))
                    consumed = i + 1
                    start = -1
                }
            }
            netCivBuffer.reset()
            if (start >= 0) netCivBuffer.write(bytes, start, bytes.size - start)
            else if (bytes.size - consumed > 256) netCivBuffer.reset()
        }
    }

    /** Tune to [hz]. */
    fun setFrequency(hz: Long): Boolean {
        if (state.value.vfoSwitching) return false
        pendingFrequency = PendingFrequency(hz, System.currentTimeMillis() + 1_800L, 0, state.value.selectedVfo)
        repeat(3) { send(0x05, *hzToBcd(hz)) }
        send(0x03)
        set {
            if (it.selectedVfo == 0) it.copy(freqHz = hz, freqAHz = hz) else it.copy(freqHz = hz, freqBHz = hz)
        }
        return true
    }

    /** Set frequency and, when requested, mode in one VFO transaction. */
    fun setFrequencyForVfo(vfo: Int, hz: Long, mode: Int? = null, data: Boolean = false, filter: Int = 1): Boolean {
        if (state.value.vfoSwitching) return false
        val target = vfo.coerceIn(0, 1)
        beginVfoTransaction()
        channelModeExecutor.execute {
            try {
                selectVfoNow(target)
                try { Thread.sleep(150) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
                pendingFrequency = PendingFrequency(hz, System.currentTimeMillis() + 1_800L, 0, target)
                repeat(3) { send(0x05, *hzToBcd(hz)) }
                send(0x03)
                if (mode != null) {
                    pendingFilter = null
                    sendRepeated(0x06, mode, filter)
                    try { Thread.sleep(70) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                    sendRepeated(0x1A, 0x06, if (data) 0x01 else 0x00, if (data) 0x01 else 0x00)
                    try { Thread.sleep(70) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
                send(0x03)
                send(0x04)
                send(0x1A, 0x06)
                send(0x1A, 0x03)
                send(0x25, 0x00)
                send(0x25, 0x01)
                set {
                    if (target == 0) it.copy(freqHz = hz, freqAHz = hz) else it.copy(freqHz = hz, freqBHz = hz)
                }
            } finally {
                endVfoTransaction()
            }
        }
        return true
    }

    /** Set the mode: USB or LSB with DATA on for the digital modes ("USB-D", "LSB-D"), or CW. */
    fun setBasicMode(mode: Int, filter: Int = 1) { setChannelMode(mode, false, filter) }
    fun setChannelMode(mode: Int, data: Boolean, filter: Int = 1) {
        pendingFilter = null
        send(0x06, mode, filter)
        send(0x1A, 0x06, if (data) 0x01 else 0x00, if (data) 0x01 else 0x00)
    }

    /** CI-V applies mode and DATA to the selected VFO, so select the target first and leave enough time for the radio to switch. */
    fun setChannelModeForVfo(vfo: Int, mode: Int, data: Boolean, filter: Int = 1): Boolean {
        if (state.value.vfoSwitching) return false
        val target = vfo.coerceIn(0, 1)
        beginVfoTransaction()
        channelModeExecutor.execute {
            try {
                selectVfoNow(target)
                Thread.sleep(150)
                pendingFilter = null
                sendRepeated(0x06, mode, filter)
                Thread.sleep(70)
                sendRepeated(0x1A, 0x06, if (data) 0x01 else 0x00, if (data) 0x01 else 0x00)
                Thread.sleep(70)
                send(0x03)
                send(0x04)
                send(0x1A, 0x06)
                send(0x1A, 0x03)
                send(0x25, 0x00)
                send(0x25, 0x01)
            } finally {
                endVfoTransaction()
            }
        }
        return true
    }
    /** CI-V 18: 0 powers the radio off, 1 turns it on when the LAN control link is available. */
    fun setPowerState(on: Boolean) {
        val value = if (on) 0x01 else 0x00
        repeat(if (on) 2 else 3) {
            send(0x18, value)
            try { Thread.sleep(70) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    fun setScopeFast() { setScopeSpeed(0) }
    fun setScopeSpeed(value: Int) {
        val v = value.coerceIn(0, 2)
        set { it.copy(scopeSpeed = v) }
        sendRepeated(0x27, 0x1A, 0x00, v); send(0x27, 0x1A, 0x00)
    }
    fun setScopeSpan(value: Int) {
        val v = value.coerceIn(0, 7)
        set { it.copy(scopeSpan = v) }
        val bcd = hzToBcd(SPANS_HZ[v])
        sendRepeated(0x27, 0x15, 0x00, *bcd); send(0x27, 0x15, 0x00)
    }
    private fun selectVfoNow(target: Int) {
        val vfo = target.coerceIn(0, 1)
        pendingFrequency?.let { pending -> if (pending.targetVfo != vfo) pendingFrequency = null }
        vfoSwitchPendingUntil = System.currentTimeMillis() + 900L
        set {
            val stored = if (vfo == 0) it.freqAHz else it.freqBHz
            it.copy(selectedVfo = vfo, freqHz = stored.takeIf { hz -> hz > 0 } ?: it.freqHz)
        }
        sendRepeated(0x07, vfo)
        send(0x03); send(0x04); send(0x1A, 0x06); send(0x1A, 0x03)
        send(0x25, 0x00); send(0x25, 0x01)
    }
    private fun beginVfoTransaction() {
        vfoTransactions.incrementAndGet()
        set { it.copy(vfoSwitching = true) }
    }
    private fun endVfoTransaction() {
        if (vfoTransactions.decrementAndGet() <= 0) {
            vfoTransactions.set(0)
            set { it.copy(vfoSwitching = false) }
        }
    }
    fun selectVfoB() = switchVfo(1)
    fun selectVfoA() = switchVfo(0)
    fun toggleVfo() = switchVfo(if (state.value.selectedVfo == 0) 1 else 0)
    private fun switchVfo(target: Int) {
        beginVfoTransaction()
        channelModeExecutor.execute {
            try { selectVfoNow(target) } finally { endVfoTransaction() }
        }
    }
    fun swapVfo() {
        beginVfoTransaction()
        channelModeExecutor.execute {
            try {
                vfoSwitchPendingUntil = System.currentTimeMillis() + 900L
                sendRepeated(0x07, 0xB0)
                send(0x03); send(0x04); send(0x1A, 0x06); send(0x1A, 0x03)
                send(0x25, 0x00); send(0x25, 0x01)
            } finally {
                endVfoTransaction()
            }
        }
    }
    fun civ(vararg body: Int) { send(*body) }
    fun requestScope() { send(0x27, 0x10, 0x01); send(0x27, 0x11, 0x01); send(0x27, 0x14, 0x00); send(0x27, 0x15, 0x00); send(0x27, 0x17, 0x00); send(0x27, 0x1A, 0x00) }
    /** Scope fallback: show the centre frequency even while a CI-V reply is pending. */
    fun scopeCenter(hz: Long) {
        if (hz <= 0) return
        val current = state.value.freqHz
        if (current <= 0 || kotlin.math.abs(current - hz) > 100) {
            set { if (it.selectedVfo == 0) it.copy(freqHz = hz, freqAHz = hz) else it.copy(freqHz = hz, freqBHz = hz) }
        }
    }
    fun setAttenuator(value: Int) {
        val att = if (value >= 20) 20 else 0
        set { it.copy(att = att) }
        send(0x11, if (att == 20) 0x20 else 0x00)
    }
    fun setPreamp(value: Int) {
        val preamp = value.coerceIn(0, 2)
        set { it.copy(preamp = preamp) }
        send(0x16, 0x02, preamp)
    }
    fun setAgc(value: Int) {
        val agc = value.coerceIn(1, 3)
        set { it.copy(agc = agc) }
        send(0x16, 0x12, agc)
    }
    fun setNoiseBlanker(on: Boolean) {
        set { it.copy(nb = if (on) 1 else 0) }
        send(0x16, 0x22, if (on) 1 else 0)
    }
    fun setNoiseReduction(on: Boolean) { setNrLevel(if (on) maxOf(1, state.value.nrLevel) else 0) }
    fun setNrLevel(value: Int) {
        val nr = value.coerceIn(0, 255)
        set { it.copy(nr = if (nr == 0) 0 else 1, nrLevel = nr) }
        sendRepeated(0x16, 0x40, if (nr == 0) 0 else 1)
        setLevel(0x06, nr)
    }
    /** Route the radio's squelched receive audio into the WLAN stream. */
    fun setWlanAfSql(on: Boolean) { sendRepeated(0x1A, 0x05, 0x01, 0x15, if (on) 1 else 0) }
    /** A one-tap test path for silent phone audio: open SQL and enable WLAN AF audio. */
    fun restoreReceiveAudio() { setSquelch(0); setWlanAfSql(true) }
    /** Level commands 0x14 use a hundreds byte followed by a packed BCD tens/ones byte.
     *  128 is 01 28, 100 is 01 00 and 255 is 02 55. */
    fun setRfGain(value: Int) { setLevel(0x02, value) }
    fun setSquelch(value: Int) { setLevel(0x03, value) }
    fun setPower(value: Int) { setLevel(0x0A, value) }
    fun resetLevels() {
        setRfGain(128); setSquelch(0); setPower(128)
        if (supportsFilter(state.value.mode)) setFilterIndex(10)
    }
    fun restoreBaselineLevels() {
        if (baselineRf >= 0) setRfGain(baselineRf)
        if (baselineSql >= 0) setSquelch(baselineSql)
        if (baselinePower >= 0) setPower(baselinePower)
        if (baselineFilter >= 0 && supportsFilter(state.value.mode)) setFilterIndex(baselineFilter)
        if (baselineWlanAfSql >= 0) setWlanAfSql(baselineWlanAfSql == 1)
    }
    fun askLevels() { send(0x14, 0x02); send(0x14, 0x03); send(0x14, 0x06); send(0x14, 0x0A); send(0x1A, 0x03); send(0x1A, 0x05, 0x01, 0x15) }

    /** Load current radio state on connect without changing it. */
    fun askAll() {
        send(0x07); send(0x03); send(0x04); send(0x1A, 0x06); send(0x1C, 0x00); send(0x25, 0x00); send(0x25, 0x01); send(0x11)
        send(0x15, 0x02); send(0x15, 0x11); send(0x15, 0x12); send(0x15, 0x13); send(0x15, 0x14); send(0x15, 0x15); send(0x15, 0x16)
        send(0x16, 0x02); send(0x16, 0x12); send(0x16, 0x22); send(0x16, 0x40); send(0x16, 0x41)
        send(0x16, 0x44); send(0x16, 0x45); send(0x16, 0x48); send(0x16, 0x58); send(0x16, 0x5D)
        send(0x0F); send(0x0D); send(0x1B, 0x00); send(0x1B, 0x01); send(0x1B, 0x02)
        send(0x27, 0x15); send(0x27, 0x1A)
        askLevels(); askDsp()
    }

    fun beginSessionRead() { originalScopeSpeed = -1 }
    fun scopeSpeedForRestore(): Int? = originalScopeSpeed.takeIf { it >= 0 } ?: state.value.scopeSpeed.takeIf { it >= 0 }
    fun setFilterIndex(index: Int) {
        if (!supportsFilter(state.value.mode)) return
        val i = index.coerceIn(0, filterLimit(state.value.mode))
        pendingFilter = PendingFilter(i, System.currentTimeMillis() + 2500L, 0)
        sendRepeated(0x1A, 0x03, i)
        send(0x1A, 0x03)
    }
    /** Compatibility helper for callers that still supply a width in Hz. */
    fun setFilterWidth(width: Int) {
        val w = width.coerceIn(50, 10_000)
        val i = when {
            state.value.mode.startsWith("AM") || state.value.mode.startsWith("WFM") -> ((w - 200) / 200).coerceIn(0, 49)
            w < 600 -> ((w - 50) / 50).coerceIn(0, 10)
            else -> (10 + (w - 600) / 100).coerceIn(0, filterLimit(state.value.mode))
        }
        setFilterIndex(i)
    }
    fun setFilter(filter: Int) { val m = MODE_NUMBERS.entries.firstOrNull { it.key == state.value.mode }?.value ?: 0x01; send(0x06, m, filter.coerceIn(1, 3)) }

    fun setMode(m: RigMode) {
        pendingFilter = null
        when (m) {
            RigMode.CW -> { send(0x06, 0x03, 0x01); send(0x1A, 0x06, 0x00, 0x00) }    // CW, filter 1; DATA off
            RigMode.USB_D -> { send(0x06, 0x01, 0x01); send(0x1A, 0x06, 0x01, 0x01) } // USB, filter 1; DATA on (D1), filter 1
            RigMode.LSB_D -> { send(0x06, 0x00, 0x01); send(0x1A, 0x06, 0x01, 0x01) } // LSB (SSTV below 10 MHz), DATA on
            RigMode.FM_D -> { send(0x06, 0x05, 0x01); send(0x1A, 0x06, 0x01, 0x01) }  // FM, filter 1; DATA on (APRS / packet)
            RigMode.DV -> { send(0x06, 0x17, 0x01); send(0x1A, 0x06, 0x00, 0x00) }    // DV / D-STAR, radio codec, DATA off
        }
    }

    /** Is the radio in mode [m] now? */
    fun isIn(m: RigMode, s: RigState = state.value) = when (m) {
        RigMode.CW -> s.mode.startsWith("CW"); RigMode.USB_D -> s.mode == "USB" && s.data; RigMode.LSB_D -> s.mode == "LSB" && s.data
        RigMode.FM_D -> s.mode == "FM" && s.data
        RigMode.DV -> s.mode == "DV"
    }

    /** Tune to a mode's dial frequency and set its mode. */
    fun tune(khz: Double, m: RigMode) {
        val hz = Math.round(khz * 1000)
        if (setFrequency(hz)) setMode(m)
    }

    /** Key / unkey the transmitter; false means the command was not sent. */
    fun ptt(on: Boolean): Boolean {
        if (on && (PttLock.locked.value || state.value.link != RigState.Link.CONNECTED)) return false
        if (on) {
            pttReleaseAtMs = 0L
            set { it.copy(txRequested = true, pttOffConfirmations = 0) }
        } else {
            pttReleaseAtMs = System.currentTimeMillis()
            set { it.copy(pttOffConfirmations = 0) }
        }
        if (net) {
            if (!IcomNet.ptt(on)) {
                if (on) set { it.copy(txRequested = false) }
                return false
            }
        } else send(0x1C, 0x00, if (on) 0x01 else 0x00)
        send(0x1C, 0x00)
        return true
    }

    fun pttReleaseSent() {
        pttReleaseAtMs = System.currentTimeMillis()
        set { it.copy(pttOffConfirmations = 0) }
        send(0x1C, 0x00)
    }

    /** Send Morse with the radio's own keyer (CI-V 17): up to 30 characters a command, so longer text goes in pieces
     *  (the radio queues them). Needs the radio in CW with break-in on. Only characters the keyer knows are sent. */
    fun sendCw(text: String) {
        val ok = text.uppercase().filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/?.,-=+@: " } // the IC-705 keyer's characters
        ok.chunked(30).forEach { part -> send(0x17, *part.map { it.code }.toIntArray()) }
    }

    /** Put the radio in CW, enable semi break-in, set the keyer speed and send the text. */
    fun sendCwMessage(text: String, wpm: Int): Boolean {
        if (PttLock.locked.value) return false
        val clean = text.uppercase().filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/?.,-=+@: " }
        if (clean.isBlank()) return false
        val token = TxControl.beginTx()
        _cwSending.value = true
        channelModeExecutor.execute {
            try {
                if (!TxControl.isCurrent(token)) return@execute
                sendRepeated(0x06, 0x03, 0x01)
                sendRepeated(0x16, 0x47, 0x01)
                setCwSpeed(wpm.coerceIn(6, 48))
                Thread.sleep(180)
                var sentAny = false
                clean.chunked(30).forEach { part ->
                    if (!TxControl.isCurrent(token)) {
                        if (sentAny) send(0x17, 0xFF)
                        return@execute
                    }
                    send(0x17, *part.map { it.code }.toIntArray())
                    sentAny = true
                    Thread.sleep(60)
                }
                if (TxControl.isCurrent(token)) {
                    send(0x03)
                    send(0x04)
                    send(0x16, 0x47)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                send(0x17, 0xFF)
            } finally {
                _cwSending.value = false
            }
        }
        return true
    }

    /** Ask the radio for its CW break-in setting (CI-V 16 47; the reply lands in state.breakIn): with it off the keyer
     *  only sounds the sidetone, so the CW page warns rather than seem to send. */
    fun askBreakIn() { send(0x16, 0x47) }

    /** Ask for the noise reduction and auto notch (CI-V 16 40, 16 41; the replies land in state.nr / autoNotch). Both
     *  adapt to steady tones and smear the sudden changes after them: an SSTV header's 10 ms break and start bit after
     *  its 300 ms leaders are lost, so the VIS code is never found (found in recordings, 2026-10-12). */
    fun askDsp() { send(0x16, 0x40); send(0x16, 0x41); send(0x16, 0x22) }

    /** Turn the noise reduction and auto notch off (CI-V 16 40 00, 16 41 00), then read them back. */
    fun dspOff() { send(0x16, 0x40, 0x00); send(0x16, 0x41, 0x00); send(0x16, 0x22, 0x00); askDsp() }

    fun setAutoNotch(on: Boolean) { set { it.copy(autoNotch = if (on) 1 else 0) }; sendRepeated(0x16, 0x41, if (on) 0x01 else 0x00); send(0x16, 0x41) }
    fun setManualNotch(on: Boolean) { set { it.copy(manualNotch = if (on) 1 else 0) }; sendRepeated(0x16, 0x48, if (on) 0x01 else 0x00); send(0x16, 0x48) }
    fun setCompressor(on: Boolean) { set { it.copy(compressor = if (on) 1 else 0) }; sendRepeated(0x16, 0x44, if (on) 0x01 else 0x00); send(0x16, 0x44) }
    fun setMonitor(on: Boolean) { set { it.copy(monitor = if (on) 1 else 0) }; sendRepeated(0x16, 0x45, if (on) 0x01 else 0x00); send(0x16, 0x45) }
    fun setTbw(value: Int) { val v = value.coerceIn(0, 2); set { it.copy(tbw = v) }; sendRepeated(0x16, 0x58, v); send(0x16, 0x58) }
    fun setSplit(mode: Int) { val v = mode.coerceIn(0, 0x36); set { it.copy(split = v) }; sendRepeated(0x0F, v); send(0x0F) }
    fun setOffsetHz(hz: Long) {
        val b = frequencyBcd((hz.coerceAtLeast(0L) / 10L)).copyOfRange(0, 3)
        set { it.copy(offsetHz = hz.coerceAtLeast(0L)) }; sendRepeated(0x0D, b[0], b[1], b[2]); send(0x0D)
    }
    fun setToneMode(mode: Int) { val v = mode.coerceIn(0, 9); set { it.copy(toneMode = v) }; sendRepeated(0x16, 0x5D, v); send(0x16, 0x5D) }
    fun setToneFrequency(tenths: Int, txInvert: Boolean = false, rxInvert: Boolean = false) {
        val t = toneBcd(tenths, txInvert, rxInvert)
        set { it.copy(toneTenths = tenths.coerceIn(0, 9999)) }; sendRepeated(0x1B, 0x00, t[0], t[1], t[2]); send(0x1B, 0x00)
    }
    fun setTsqlFrequency(tenths: Int, txInvert: Boolean = false, rxInvert: Boolean = false) {
        val t = toneBcd(tenths, txInvert, rxInvert)
        set { it.copy(tsqlTenths = tenths.coerceIn(0, 9999)) }; sendRepeated(0x1B, 0x01, t[0], t[1], t[2]); send(0x1B, 0x01)
    }
    fun setDtcsCode(code: Int, txInvert: Boolean = false, rxInvert: Boolean = false) {
        val t = toneBcd(code, txInvert, rxInvert)
        set { it.copy(dtcsCode = code.coerceIn(0, 9999)) }; sendRepeated(0x1B, 0x02, t[0], t[1], t[2]); send(0x1B, 0x02)
    }

    /** Restore the saveable repeater/tone state after a channel is selected. */
    fun applyChannelConfiguration(toneMode: Int, toneTenths: Int, tsqlTenths: Int, dtcsCode: Int, split: Int, offsetHz: Long) {
        if (offsetHz > 0) setOffsetHz(offsetHz)
        send(0x0F, split.coerceIn(0, 0x36))
        if (toneTenths > 0) setToneFrequency(toneTenths)
        if (tsqlTenths > 0) setTsqlFrequency(tsqlTenths)
        if (dtcsCode > 0) setDtcsCode(dtcsCode)
        setToneMode(toneMode.coerceIn(0, 9))
    }

    /** Stop the keyer's message now (CI-V 17 FF). */
    fun stopCw() { send(0x17, 0xFF) }

    /** Keyer speed, 6-48 WPM (CI-V 14 0C, 0-255 as 4-digit BCD). */
    fun setCwSpeed(wpm: Int) {
        val v = ((wpm.coerceIn(6, 48) - 6) * 255 + 21) / 42 // 6 WPM = 0, 48 WPM = 255
        send(0x14, 0x0C, (v / 100), ((v / 10 % 10) shl 4) or (v % 10)) // BCD: 0x0X 0xYZ
    }

    /** UDP commands can be lost; two identical writes are harmless and make a slider tap less likely to disappear. */
    private fun sendRepeated(vararg body: Int) { repeat(2) { send(*body) } }

    private fun setLevel(sub: Int, value: Int) {
        val v = value.coerceIn(0, 255)
        pendingLevels[sub] = PendingLevel(v, System.currentTimeMillis() + 2500L, 0)
        sendRepeated(0x14, sub, *bcdMsb(v))
        send(0x14, sub)
    }

    private fun retryPending() {
        val now = System.currentTimeMillis()
        pendingLevels.entries.toList().forEach { (sub, pending) ->
            if (now <= pending.deadline) return@forEach
            if (pending.attempts >= 2) {
                pendingLevels.remove(sub, pending)
                set { it.copy(message = "电平设置未被电台确认，请重试第 $sub 项") }
            } else {
                val next = pending.copy(deadline = now + 1800L, attempts = pending.attempts + 1)
                pendingLevels[sub] = next
                send(0x14, sub, *bcdMsb(next.expected))
                send(0x14, sub)
            }
        }
        pendingFrequency?.let { pending ->
            val active = state.value.freqHz
            if (state.value.selectedVfo != pending.targetVfo) {
                pendingFrequency = null
            } else if (active > 0L && kotlin.math.abs(active - pending.expected) <= 5L) {
                pendingFrequency = null
            } else if (now > pending.deadline) {
                if (pending.attempts >= 2) {
                    pendingFrequency = null
                    set { it.copy(message = "频率设置未被电台确认，请重试") }
                } else {
                    pendingFrequency = pending.copy(deadline = now + 1_800L, attempts = pending.attempts + 1)
                    repeat(3) { send(0x05, *hzToBcd(pending.expected)) }
                    send(0x03)
                }
            }
        }
        pendingFilter?.let { pending ->
            if (!supportsFilter(state.value.mode)) {
                pendingFilter = null
            } else if (now > pending.deadline) {
                if (pending.attempts >= 2) {
                    pendingFilter = null
                    set { it.copy(message = "滤波设置未被电台确认，请重试") }
                } else {
                    pendingFilter = pending.copy(deadline = now + 1800L, attempts = pending.attempts + 1)
                    send(0x1A, 0x03, pending.expected)
                    send(0x1A, 0x03)
                }
            }
        }
    }

    private fun bcdMsb(value: Int): IntArray = Ic705Codec.levelBcd(value)

    private fun bcd2Msb(d: List<Int>, at: Int): Int = Ic705Codec.levelValue(d, at)

    private fun supportsFilter(mode: String): Boolean = mode.isNotEmpty() && !mode.startsWith("FM") && mode != "DV"
    private fun filterLimit(mode: String): Int = Ic705Codec.filterLimit(mode)
    internal fun filterWidthFor(mode: String, index: Int): Int = Ic705Codec.filterWidth(mode, index)

    private val MODE_NUMBERS = MODES.entries.associate { it.value to it.key }

    private inline fun set(f: (RigState) -> RigState) { _state.update(f) }
}








