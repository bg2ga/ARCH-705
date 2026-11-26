package uk.hamdigital.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.hamdigital.MainViewModel

class TxGate(val ask: (() -> Unit) -> Unit)

@Composable
fun rememberTxGate(vm: MainViewModel): TxGate {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { uk.hamdigital.ui.Text("发射前确认") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    uk.hamdigital.ui.Text(
                        "发射需要有效的业余无线电操作资格。你必须对以自己呼号发送的频率、功率和内容负责。\n\n" +
                            "远程使用前必须在 IC-705 本机开启 TX Time-out Timer。手机断网、上行链路丢失或 App 被系统终止时，手机端无法保证 PTT OFF 送达电台，最终必须依赖电台自身的发射超时保护。\n\n" +
                            "请确认天线或假负载、SWR 和功率设置正常，并保持有人值守。"
                    )
                }
            },
            confirmButton = {
                TextButton({
                    uk.hamdigital.core.TxControl.licensed = true
                    vm.updateSettings { it.copy(txOk = true) }
                    pending = null
                    action()
                }) { uk.hamdigital.ui.Text("我具备操作资格并继续") }
            },
            dismissButton = { TextButton({ pending = null }) { uk.hamdigital.ui.Text("取消") } },
        )
    }
    return remember(settings.txOk) { TxGate { action -> if (settings.txOk) action() else pending = action } }
}
