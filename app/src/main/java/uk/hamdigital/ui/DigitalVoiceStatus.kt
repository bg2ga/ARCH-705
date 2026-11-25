package uk.hamdigital.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.hamdigital.rig.DigitalVoiceCallKind
import uk.hamdigital.rig.DigitalVoiceInfo
import uk.hamdigital.rig.DigitalVoicePhase
import uk.hamdigital.rig.DigitalVoiceProtocol

@Composable
fun DigitalVoiceStatusPanel(info: DigitalVoiceInfo?, connected: Boolean, modifier: Modifier = Modifier) {
    val voice = info
    val fresh = voice != null && System.currentTimeMillis() - voice.receivedAtMs < 3000L
    val accent = when (voice?.protocol) {
        DigitalVoiceProtocol.DMR -> Color(0xFF65AFFF)
        DigitalVoiceProtocol.YSF -> Color(0xFF70D4B4)
        DigitalVoiceProtocol.P25 -> Color(0xFFFFC857)
        null -> Pal.Cyan
    }
    Box(
        modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, accent.copy(alpha = if (fresh) 0.60f else 0.20f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                uk.hamdigital.ui.Text("数字语音解码", color = Pal.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                uk.hamdigital.ui.Text(
                    when {
                        !connected -> "未连接"
                        voice == null -> "等待扩展状态"
                        !fresh -> "状态超时"
                        else -> info.protocol.label + " · " + info.phase.label
                    },
                    color = when {
                        !connected -> Pal.Amber
                        voice == null || !fresh -> Pal.Muted
                        else -> Pal.Green
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (voice == null || !fresh) {
                uk.hamdigital.ui.Text(
                    if (connected) "未收到 ARCH-705 数字状态扩展帧；原版固件仍可正常收发，但不会显示解码元数据。"
                    else "连接电台后显示 DMR、YSF/C4FM 与 P25 的实时解码信息。",
                    color = Pal.Muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                when (voice.protocol) {
                    DigitalVoiceProtocol.DMR -> {
                        VoiceValue("呼型", voice.callKind.label, Modifier.weight(1f))
                        VoiceValue("色码 CC", voice.colorCode?.toString() ?: "—", Modifier.weight(0.8f))
                        VoiceValue("时隙 TS", voice.slot?.toString() ?: "—", Modifier.weight(0.8f))
                    }
                    DigitalVoiceProtocol.YSF -> {
                        VoiceValue("呼型", voice.callKind.label, Modifier.weight(1f))
                        VoiceValue("DG-ID", voice.colorCode?.toString()?.padStart(2, '0') ?: "—", Modifier.weight(1f))
                        VoiceValue("呼号", voice.callsign ?: "—", Modifier.weight(1.5f))
                    }
                    DigitalVoiceProtocol.P25 -> {
                        VoiceValue("呼型", voice.callKind.label, Modifier.weight(1f))
                        VoiceValue("NAC", voice.colorCode?.toString(16)?.uppercase()?.padStart(3, '0') ?: "—", Modifier.weight(0.8f))
                        VoiceValue("源 ID", voice.sourceId?.toString() ?: "—", Modifier.weight(1.4f))
                    }
                }
            }
            if (voice.protocol == DigitalVoiceProtocol.DMR) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    VoiceValue("源 ID", voice.sourceId?.toString() ?: "—", Modifier.weight(1f))
                    VoiceValue(if (voice.callKind == DigitalVoiceCallKind.GROUP) "组呼 ID" else "目标 ID", voice.targetId?.toString() ?: "—", Modifier.weight(1f))
                }
            } else if (voice.protocol == DigitalVoiceProtocol.P25) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    VoiceValue(if (voice.callKind == DigitalVoiceCallKind.GROUP) "组呼 ID" else "目标 ID", voice.targetId?.toString() ?: "—", Modifier.fillMaxWidth())
                }
            }
            if (voice.row.isNotBlank()) {
                uk.hamdigital.ui.Text(
                    voice.row,
                    color = Pal.Text2,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 5.dp).background(Pal.Bg.copy(alpha = 0.72f), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 3.dp).fillMaxWidth(),
                )
            } else if (voice.phase != DigitalVoicePhase.RECEIVING) {
                uk.hamdigital.ui.Text("电台已启用 " + voice.protocol.label + "，正在等待可解码通话。", color = Pal.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun VoiceValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.065f)).padding(horizontal = 7.dp, vertical = 5.dp)) {
        uk.hamdigital.ui.Text(label, color = Pal.Muted, fontSize = 9.sp, maxLines = 1)
        uk.hamdigital.ui.Text(value, color = Pal.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
    }
}
