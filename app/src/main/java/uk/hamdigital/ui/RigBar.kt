// The radio bar on every mode's page: the IC-705's frequency and mode (over CI-V), and the mode's bands as chips - a tap
// tunes the radio to that band's usual frequency for the mode and sets USB-D (or CW). Without the radio connected the
// chips just show the frequency to tune by hand. Opening a mode's page tunes the radio to that mode's frequency on the
// band it is already on (FT8 on 7.074 -> WSPR 7.0386), with USB-D or CW, once; never while something is being sent.
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.core.Mode
import uk.hamdigital.core.PttLock
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState
import kotlin.math.abs

/** Opening mode [m]'s page: the radio to that mode's frequency on the band it is on (and USB-D / CW), once the radio's
 *  frequency is known; once each time the page is opened; never while something is being sent. (Part of RigBar; pages
 *  that hide RigBar when sideways call it themselves.) */
@Composable
fun AutoTune(m: Mode) {
    val rig by Ic705.state.collectAsStateWithLifecycle() // the radio
    val on = rig.link == RigState.Link.CONNECTED
    var tuned by rememberSaveable(m) { mutableStateOf(false) } // done for this opening
    LaunchedEffect(m, on, rig.freqHz > 0) {
        if (tuned || !on || rig.freqHz <= 0) return@LaunchedEffect // (not until the radio's frequency is known)
        tuned = true
        if (busyTransmitting()) return@LaunchedEffect // never while sending, or with a contact, beacon or message under way
        val dial = defaultDial(m)                     // the most-used dial for this mode (never inherit 28 MHz)
        val rm = m.rigMode(dial)                      // USB-D, LSB-D or CW
        if (abs(Math.round(dial * 1000) - rig.freqHz) > 50 || !Ic705.isIn(rm, rig)) Ic705.tune(dial, rm)
        if (dial >= 30_000.0 && rig.sql == 0) Ic705.setSquelch(uk.hamdigital.core.BandPlan.VHF_UHF_SQUELCH)
    }
}

private fun defaultDial(m: Mode): Double = m.defaultDialKHz()

/** Mode [m]'s usual frequency (kHz) for the band the radio is on ([hz]); if the mode has none there, the nearest one it has. */
private fun dialFor(m: Mode, hz: Long): Double {
    val band = uk.hamdigital.core.Logbook.band(hz)    // "40m"
    m.dialsKHz.firstOrNull { "${it.first}m" == band }?.let { return it.second } // the same band
    return m.dialsKHz.minBy { kotlin.math.abs(kotlin.math.ln(it.second * 1000 / hz)) }.second // else the nearest (by ratio)
}

/** Something is being sent or is due to be: a transmission, an FT8 / FT4 contact with transmit on, the WSPR beacon, or
 *  a JS8 message still queued - then a page must not move the radio. */
internal fun busyTransmitting(): Boolean = Ic705.cwSending.value || uk.hamdigital.audio.Transmitter.on.value || uk.hamdigital.core.FtQso.FT8.state.value.enabled ||
    uk.hamdigital.core.FtQso.FT4.state.value.enabled || uk.hamdigital.core.FtQso.FT2.state.value.enabled || uk.hamdigital.core.WsprBeacon.state.value.enabled || uk.hamdigital.core.Js8Tx.queued.value > 0

/** The radio's frequency and mode, then the mode's band chips. Returns nothing; the radio is the state. Opening the page
 *  tunes the radio to this mode's frequency on the band it is on (unless something is being sent). */
@Composable
fun RigBar(m: Mode, autoTune: Boolean = true) {
    val ctx = LocalContext.current
    val rig by Ic705.state.collectAsStateWithLifecycle() // the radio
    val pttLocked by PttLock.locked.collectAsStateWithLifecycle()
    val on = rig.link == RigState.Link.CONNECTED      // talking to it
    var picked by rememberSaveable(m) { mutableStateOf(m.dialsKHz.firstOrNull { it.first == "20" }?.first ?: m.dialsKHz.first().first) } // band chosen by hand (no radio)
    var frequencyDialog by remember { mutableStateOf(false) }
    var frequencyVfo by remember { mutableIntStateOf(rig.selectedVfo) }
    var frequencyText by remember { mutableStateOf("") }
    var frequencyMode by remember { mutableStateOf<MainModeChoice?>(null) }
    var frequencyError by remember { mutableStateOf("") }
    val onBand = m.dialsKHz.firstOrNull { abs(Math.round(it.second * 1000) - rig.freqHz) <= 3000 }?.first // the band the radio is on, for this mode (within 3 kHz)
    val shown = if (on) onBand else picked             // chip lit
    val canTune = on && !busyTransmitting() && !rig.tx && !rig.txRequested
    if (autoTune) AutoTune(m)                         // opening the page: the radio to this mode
    LaunchedEffect(on) { while (on) { Ic705.askDsp(); kotlinx.coroutines.delay(15_000) } } // the radio's NR / auto notch, checked now and then (the user may switch them)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (on) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.IcomBlue.copy(alpha = 0.22f))
                    .border(1.dp, Pal.IcomBlueBright.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .clickable(enabled = canTune) {
                        frequencyVfo = rig.selectedVfo
                        frequencyText = ""
                        frequencyError = ""
                        frequencyMode = MAIN_MODES.firstOrNull { it.label == rig.modeText }
                        frequencyDialog = true
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) { uk.hamdigital.ui.Text(rig.freqText, color = Pal.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp) } // 14.074.000
            uk.hamdigital.ui.Text("  ${rig.modeText}", color = Pal.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)                 // USB-D
            TextButton(onClick = { PttLock.toggle() }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                uk.hamdigital.ui.Text(if (pttLocked) "PTT锁" else "已解锁", color = if (pttLocked) Pal.Amber else Pal.Green, fontSize = 10.sp)
            }
            val rm = m.rigMode(rig.freqHz / 1000.0)    // right mode for this page (and band)?
            if (rig.mode.isNotEmpty() && !Ic705.isIn(rm, rig)) uk.hamdigital.ui.Text("  （需要 ${rm.label}）", color = Pal.Amber, fontSize = 13.sp)
            if (rig.tx) uk.hamdigital.ui.Text("  TX", color = Pal.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp)                       // transmitting
            if (m != Mode.CW && (rig.nr == 1 || rig.autoNotch == 1 || rig.nb == 1)) TextButton({ Ic705.dspOff() }, contentPadding = PaddingValues(horizontal = 6.dp)) { // the radio's adaptive DSP smears data signals: tap to turn it off
                uk.hamdigital.ui.Text(listOfNotNull("NB".takeIf { rig.nb == 1 }, "NR".takeIf { rig.nr == 1 }, "陷波".takeIf { rig.autoNotch == 1 }).joinToString(" + ") + " 已开 · 点击关闭", color = Pal.Amber, fontSize = 12.sp) }
        } else {
            val khz = m.dialsKHz.first { it.first == picked }.second // tune by hand
            uk.hamdigital.ui.Text("调谐 %s MHz %s".format(if (khz % 1.0 != 0.0) "%.4f".format(khz / 1000.0) else "%.3f".format(khz / 1000.0), m.rigMode(khz).label), color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f, false))
            uk.hamdigital.ui.Text("  ${rig.message.ifEmpty { "IC-705 未连接" }}", color = Pal.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (rig.link == RigState.Link.NONE && Ic705.findDevice(ctx) != null) TextButton({ Ic705.connect(ctx) }) { uk.hamdigital.ui.Text("连接") } // plugged in but not open
        }
    }
    if (frequencyDialog) FrequencyEntryDialog(
        vfo = frequencyVfo,
        currentHz = rig.freqHz,
        currentMode = rig.modeText,
        value = frequencyText,
        selectedMode = frequencyMode,
        error = frequencyError,
        enabled = canTune,
        onKey = { key ->
            frequencyError = ""
            frequencyText = when (key) {
                "⌫" -> frequencyText.dropLast(1)
                "清空" -> ""
                "." -> if (frequencyText.contains('.') || frequencyText.length >= 10) frequencyText else if (frequencyText.isEmpty()) "0." else frequencyText + "."
                else -> if (key.length == 1 && key[0].isDigit() && frequencyText.length < 10) frequencyText + key else frequencyText
            }
        },
        onMode = { frequencyMode = it },
        onConfirm = {
            val hz = frequencyText.toDoubleOrNull()?.times(1_000_000.0)?.toLong()
            if (!on || busyTransmitting() || Ic705.state.value.tx || Ic705.state.value.txRequested) {
                frequencyError = "正在发射，不能调整频率"
            } else if (hz == null || hz !in 30_000L..1_300_000_000L) {
                frequencyError = "请输入有效频率，例如 14.270"
            } else if (Ic705.setFrequencyForVfo(frequencyVfo, hz, frequencyMode?.code, frequencyMode?.data ?: false)) {
                frequencyDialog = false
            } else {
                frequencyError = "正在切换 VFO，请稍候再试"
            }
        },
        onDismiss = { frequencyDialog = false },
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // bands
        m.dialsKHz.forEach { (b, khz) -> SmallChip(if (b.all { it.isDigit() }) "$b m" else b, b == shown) {
            picked = b
            if (canTune && !busyTransmitting()) Ic705.tune(khz, m.rigMode(khz))
        } } // tap: tune the radio (a band, or a station by name)
    }
}

