// JTTY page (WSJT-X 3.2's RTTY-style mode, cpp/jtty): the radio and its bands, a waterfall - tap to set the frequency
// you receive and send on (the lowest of the four tones; the red lines are a signal's 125 Hz) - the messages heard and
// sent, newest first, each growing frame by frame (1.9 s a frame; "..." where frames were missed), and sending: typed
// text, or the contest keys (WSJT-X's F1-F8 defaults: CQ, his call + exchange, TU + CQ, my call, his call, TU NOW +
// exchange, AGN?, exchange). Tap a message to take its callsign as "his call".
package uk.hamdigital.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.Jtty
import uk.hamdigital.core.Mode
import uk.hamdigital.engine.JttyNative
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun JttyScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio, level
    val spec = remember { Spectrum(Jtty.RATE) }         // the waterfall
    remember { Jtty.restart(); 0 }                      // a fresh receive time line each time the page opens
    val rx = rememberRx(Jtty.RATE, 600, s.audio) { b, n -> spec.feed(b, n); Jtty.feed(b, n) } // 50 ms blocks: waterfall + decoder
    val msgs by Jtty.messages.collectAsStateWithLifecycle()
    val behind by Jtty.behind.collectAsStateWithLifecycle()
    var rxHz by remember { mutableFloatStateOf(Jtty.rxHz) } // receive / transmit frequency
    var his by rememberSaveable { mutableStateOf("") } // the other station
    var nr by rememberSaveable { mutableIntStateOf(1) } // the serial number to send
    var typed by remember { mutableStateOf("") }      // being typed
    var txMsg by remember { mutableStateOf("") }      // why a transmission did not start
    var logging by remember { mutableStateOf<uk.hamdigital.core.Qso?>(null) } // the log form, open
    val gate = rememberTxGate(vm)                       // the licence notice before transmitting
    val my = s.callsign.ifBlank { "MYCALL" }
    val exch = "599 %03d".format(nr)                    // the exchange (WSJT-X's %E: a decimal serial)
    fun send(t: String, profile: Int) = gate.ask {      // send [t] now
        if (t.isBlank()) return@ask
        val (frames, _) = Jtty.preview(t, profile)
        if (frames < 0) { txMsg = "Too long to send (16 frames at most)"; return@ask }
        txMsg = if (Jtty.send(ctx, s.callsign, t, profile, s.txLevel / 100f)) "" else Transmitter.lastError.value
    }
    ModeFrame("JTTY", { vm.back() }, actions = {
        TextButton({ logging = newQso("JTTY", s).copy(call = his) }) { uk.hamdigital.ui.Text("Log", color = Pal.Text2) } // the contact, to finish by hand
        TextButton({ Jtty.clear() }) { uk.hamdigital.ui.Text("Clear", color = Pal.Text2) }
    }) {
        RxStatus(rx, if (behind > 20) "decoder %.1f s behind".format(behind * 0.05f) else "") // audio, level (and if the phone cannot keep up)
        RigBar(Mode.JTTY)                                 // the radio, the bands
        Waterfall(spec, Modifier.fillMaxWidth().height(100.dp).padding(vertical = 4.dp),
            marks = listOf(rxHz to Pal.Red, rxHz + Jtty.WIDTH to Pal.Red)) { hz -> rxHz = hz; Jtty.rxHz = hz } // tap: receive / send there
        Row(verticalAlignment = Alignment.CenterVertically) {
            uk.hamdigital.ui.Text("RX / TX %.0f Hz".format(rxHz), color = Pal.Cyan, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            uk.hamdigital.ui.Text(if (his.isNotEmpty()) "   his call: $his" else "   tap a message for his call", color = if (his.isNotEmpty()) Pal.Text else Pal.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (his.isNotEmpty()) TextButton({ his = "" }, contentPadding = PaddingValues(horizontal = 6.dp)) { uk.hamdigital.ui.Text("Clear", color = Pal.Text2, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp)) { // newest first
            if (msgs.isEmpty()) item { uk.hamdigital.ui.Text("Messages heard appear here, growing as each 1.9 s frame arrives. Tune the IC-705 to a JTTY " +
                "frequency (14.090 MHz USB-D is the meeting place) and tap the waterfall where the signals are.", color = Pal.Dim, fontSize = 13.sp, lineHeight = 18.sp) }
            items(msgs, key = { it.id }) { m ->
                val utc = Instant.ofEpochMilli(m.startMs).atZone(ZoneOffset.UTC).let { "%02d%02d%02d".format(it.hour, it.minute, it.second) }
                Row(Modifier.fillMaxWidth().clickable { Jtty.callIn(m.text, s.callsign)?.let { his = it } }.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    uk.hamdigital.ui.Text(utc, color = Pal.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(54.dp))
                    uk.hamdigital.ui.Text(if (m.mine) "  TX" else "%4d".format(m.snr), color = if (m.mine) Pal.Amber else Pal.Text2, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(34.dp))
                    uk.hamdigital.ui.Text("%5.0f".format(m.freq), color = Pal.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    uk.hamdigital.ui.Text(m.text + if (m.complete) "" else " ...", color = if (m.mine) Pal.Amber else Pal.Text, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        TxBanner { Transmitter.halt() }
        if (txMsg.isNotEmpty()) uk.hamdigital.ui.Text(txMsg, color = Pal.Red, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            CompactField(typed, { typed = it.uppercase().take(80) }, "Type to send (JTTY)", Modifier.weight(1f))
            Button({ send(typed, JttyNative.UNKNOWN); typed = "" }, contentPadding = PaddingValues(horizontal = 14.dp)) { uk.hamdigital.ui.Text("Send") }
        }
        val h = his.ifEmpty { "?" }                       // (keys needing his call wait for it)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallChip("CQ", false) { send("CQ $my CQ", JttyNative.RTTY) }                          // F1
            SmallChip("$h $exch", false) { if (his.isNotEmpty()) send("$his $exch", JttyNative.RTTY) } // F2: his call + exchange
            SmallChip("TU CQ", false) { if (his.isNotEmpty()) send("$his TU CQ $my CQ", JttyNative.RTTY) } // F3
            SmallChip("My call", false) { send(my, JttyNative.RTTY) }                               // F4
            SmallChip("His call", false) { if (his.isNotEmpty()) send(his, JttyNative.RTTY) }       // F5
            SmallChip("AGN?", false) { if (his.isNotEmpty()) send("$his AGN?", JttyNative.RTTY) }  // F7
            SmallChip(exch, false) { send(exch, JttyNative.RTTY) }                                 // F8: the exchange alone
            uk.hamdigital.ui.Text("Nr", color = Pal.Muted, fontSize = 12.sp)
            SmallChip("-", false) { if (nr > 1) nr-- }; uk.hamdigital.ui.Text("%03d".format(nr), color = Pal.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp); SmallChip("+", false) { nr++ }
        }
    }
    logging?.let { QsoEditor(it, true) { logging = null } } // the log form (the serial is moved on by hand: + / -)
}

