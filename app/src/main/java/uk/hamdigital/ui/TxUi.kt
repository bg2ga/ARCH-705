// Transmit controls shared by the modes: the licence notice (shown once, before the first transmission; Settings can
// show it again), the TX indicator, and the FT8 / FT4 transmit panel (enable, Call CQ, Halt, 1st / 2nd slot, the six
// messages with the next one lit, the TX offset, and what is happening).
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.FtQso

/** Runs [action] if transmitting is allowed (the licence notice accepted), else shows the notice first. */
class TxGate(val ask: (() -> Unit) -> Unit)

@Composable
fun rememberTxGate(vm: MainViewModel): TxGate {
    val s by vm.settings.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) } // the action waiting for the answer
    pending?.let { act ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { uk.hamdigital.ui.Text("发射前确认") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    uk.hamdigital.ui.Text("发射需要有效的业余无线电操作资格。你必须对以自己呼号发送的频率、功率和内容负责。\n\n" +
                        "远程使用前必须在 IC-705 本机开启 TX Time-out Timer（发射超时保护）。手机断网、上行链路完全丢失或 App 被终止时，手机端无法保证 PTT OFF 送达电台，最终必须依赖电台自身的超时保护。\n\n" +
                        "本 App 通过 WiFi/互联网直接控制 IC-705，不经第三方软件或中转服务器。请确认天线或假负载、SWR 和功率设置正常，并保持有人值守。\n\n" +
                        "建议先降低发射音频电平，观察 ALC 不要过高；数字模式应使用 USB-D / DATA 设置。")
                }
            },
            confirmButton = {
                TextButton({
                    uk.hamdigital.core.TxControl.licensed = true
                    vm.updateSettings { it.copy(txOk = true) }
                    pending = null
                    act()
                }) { uk.hamdigital.ui.Text("我具备操作资格并继续") }
            },
            dismissButton = { TextButton({ pending = null }) { uk.hamdigital.ui.Text("取消") } },
        )
    }
    return remember(s.txOk) { TxGate { action -> if (s.txOk) action() else pending = action } }
}

/** A red "TX" banner while transmitting, with Halt. */
@Composable
fun TxBanner(onHalt: () -> Unit) {
    val on by Transmitter.on.collectAsStateWithLifecycle()
    if (on) Row(Modifier.fillMaxWidth().background(Pal.Red, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        uk.hamdigital.ui.Text("正在发射", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Button(onHalt, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Pal.Red), contentPadding = PaddingValues(horizontal = 14.dp)) { uk.hamdigital.ui.Text("停止") }
    }
}

/** FT8 / FT4 transmit panel. */
@Composable
fun FtTxPanel(qso: FtQso, gate: TxGate) {
    val q by qso.state.collectAsStateWithLifecycle()  // the contact
    TxBanner { qso.halt() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        SmallChip(if (q.enabled) "TX: on" else "TX: off", q.enabled) { if (q.enabled) qso.enable(false) else gate.ask { qso.enable(true) } } // enable
        SmallChip("Call CQ", q.enabled && q.dx.isEmpty() && q.next == 6) { gate.ask { qso.callCq() } }
        SmallChip("Halt", false) { qso.halt() }
        SmallChip(if (q.secondSlot) "2nd slot" else "1st slot", false) { qso.setSlot(!q.secondSlot) } // which slots we use
        uk.hamdigital.ui.Text("TX ${q.txHz} Hz", color = Pal.Red, fontSize = 12.sp)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { // the six messages
        for (n in listOf(1, 2, 3, 4, 5, 6)) {
            val t = qso.text(n, q); val ok = n == 6 || q.dx.isNotEmpty() // (Tx1-5 need a DX call)
            SmallChip("Tx$n ${if (ok) t else "-"}", n == q.next) { if (ok) qso.setNext(n) }
        }
    }
    uk.hamdigital.ui.Text(listOfNotNull(q.dx.takeIf { it.isNotEmpty() }?.let { "DX $it ${q.dxGrid}" }, q.rcvd.takeIf { it.isNotEmpty() }?.let { "rcvd $it" }, q.status.ifEmpty { null },
        if (q.logged) "logged" else null).joinToString("  •  ").ifEmpty { if (qso.myCall.isBlank()) "Set your callsign and locator in Settings to transmit." else "Double-tap a CQ (or a station calling you) to answer it, or Call CQ. Tap the waterfall to set the TX offset." },
        color = if (q.enabled) Pal.Amber else Pal.Muted, fontSize = 12.sp, fontFamily = FontFamily.Default, maxLines = 2)
}

/** Typing and sending for the keyboard modes: a text box, Send, and quick messages (CQ, 73) with your callsign. */
@Composable
fun SendBox(myCall: String, mode: String, onSend: (String) -> Unit, onLog: (() -> Unit)? = null, extra: @Composable RowScope.() -> Unit = {}) {
    var text by remember { mutableStateOf("") }       // being typed
    val call = myCall.ifBlank { "MYCALL" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        CompactField(text, { text = it.uppercase() }, "Type to send ($mode)", Modifier.weight(1f))
        Button({ if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, contentPadding = PaddingValues(horizontal = 14.dp)) { uk.hamdigital.ui.Text("Send") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallChip("CQ", false) { onSend("CQ CQ CQ DE $call $call $call PSE K") }      // calling CQ
        SmallChip("73", false) { onSend("TNX FER QSO 73 DE $call SK") }               // ending a contact
        SmallChip("Callsign", false) { text = (text + " $call").trim() }              // your call into the box
        if (onLog != null) SmallChip("Log", false, onClick = onLog)                  // log the contact (the form)
        extra()
    }
}

