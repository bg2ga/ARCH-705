package uk.hamdigital.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Chinese operating guide and legal notice for ARCH-705. */
@Composable
fun ChineseGuideScreen(onBack: () -> Unit) {
    ModeFrame("使用说明", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("连接")
            Body("ARCH-705 由手机直接连接 IC-705，不使用 wfweb、wfview 或任何中转服务器。\n\n" +
                "1. IC-705 开启 WLAN，并在 Remote Settings 中开启 Network Control。\n" +
                "2. 确认控制、串口、音频三个 UDP 端口。默认是 50001 / 50002 / 50003。\n" +
                "3. 在登录页填写电台地址、用户名和密码。地址可以是 IP 或域名。\n" +
                "4. 修改过端口时取消“使用默认端口”，填写实际端口后登录。")

            Section("多台电台")
            Body("设置页可以保存多台 IC-705 的名称、地址、账号和端口。选择“使用”后返回登录页，信息会自动填入。登录成功后地址、用户名和密码会保存在本机，升级或重新登录不会要求重复输入。")

            Section("频谱和音频")
            Body("连接成功后，音频和频谱都来自 IC-705 的 Wi-Fi 数据流。频谱中的蓝色为弱信号，黄色为中等信号，红色为强信号。\n\n" +
                "点击或拖动频谱可以选择工作频率；频谱带宽、速度和刷新率可在主界面或设置中调整。")

            Section("发射")
            Body("主界面提供按住式 PTT，可在设置中启用系统悬浮 PTT。发射前必须确认天线或假负载、功率和 SWR，并保持电台本机可控。\n\n" +
                "App 内置发射看门狗和断链保护，但不能替代电台侧的超时与硬件保护。")

            Section("标准控制范围")
            Body("频率、模式、滤波器、增益、静噪、功率、前置放大、衰减、AGC、NB、NR、VFO A/B、频谱、音频、PTT、频道、日志和在线更新均通过 IC-705 标准 USB / RS-BA1 与 CI-V 接口工作。\n\n" +
                "本版本不包含手机端 SDR/DSP 数字语音或数据模式解码，也不依赖云端或第三方控制服务器。")

            Section("原版与改版 1.40")
            Body("基础连接、音频、频率、模式、PTT、频谱、频道和日志使用标准 RS-BA1 和 CI-V，兼容原厂 IC-705 1.40 与 BI1BIF v1.5.0。\n\n" +
                "改版固件的专有扩展不会在纯标准基线中调用；后续固件适配完成后会以独立版本加入。")

            Section("在线更新")
            Body("设置或登录页可以检查更新。更新清单和 APK 均通过 HTTPS、版本号、大小、SHA-256、包名和签名校验。")

            Section("免责声明与责任限制")
            Body("本软件按“现状”和“现有可用”状态提供，不附带任何明示或默示担保。作者、贡献者和分发者不保证软件、设备、网络、音频、频谱、PTT、更新或控制命令在任何情况下正常工作。\n\n" +
                "使用者必须自行遵守全部适用法律、业余无线电执照、频率、功率、呼号、电磁兼容和射频暴露要求，并对实际发射及全部后果承担全部责任。远程控制可能发生断网、丢包、命令丢失、PTT 卡住、误调谐、设备过热、意外发射、干扰、数据损坏或人身财产损失。远程发射前必须开启 IC-705 自身 TX Time-out Timer，使用假负载或确认天线与 SWR，并保持有人值守。\n\n" +
                "在法律允许的最大范围内，作者、贡献者、分发者和维护者不对任何直接、间接、偶然、特殊、惩罚性或后果性损失承担责任，包括人身伤害、死亡、设备或财产损坏、数据丢失、通信中断、利润损失、行政或法律处罚及第三方索赔。安装、连接、发射或继续使用即表示接受本免责声明。")

        }
    }
}

@Composable private fun Section(t: String) = uk.hamdigital.ui.Text(t, color = Pal.Cyan, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
@Composable private fun Body(t: String) = uk.hamdigital.ui.Text(t, color = Pal.Text2, fontSize = 14.sp, lineHeight = 20.sp)
