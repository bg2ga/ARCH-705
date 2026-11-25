# ARCH-705 2.2.0

## 本次更新

- 重做 RS-BA1 风格界面：平面模式按钮、紧凑黑色金属旋钮、白色指针和更整齐的控制页。
- 频谱改为 IC-705 风格黑底、白色填充曲线、蓝/青渐变瀑布、网格和两帧拖影；增加频谱超时重订阅。
- S 表增加七模式：S、功率、SWR、ALC、COMP、电压、电流，并修正 CI-V 0x15 查询轮询。
- 修正 RF 增益、静噪、发射功率和滤波带宽的 CI-V 编码：0x14 使用两字节 BCD，滤波使用 1A 03 索引。
- 接收音频增加非阻塞播放、线程安全切换、静音检测提示和一键 SQL 0 / WLAN AF 恢复。
- 修正 WiFi PTT 音频流启动顺序、发射期间手机回放静音、CI-V 序列并发和 CI-V 地址同步。
- 增加电平和滤波编码单元测试、普通 CI-V 看门狗、应用返回逻辑与 S0 显示。
- 默认频率：D-STAR 145.670 MHz、APRS 144.800 MHz，其他模式优先 20 米。

## 兼容性

- 原厂 IC-705 1.40 使用标准 RS-BA1/CI-V。
- BI1BIF v1.5.0 的 DMR、C4FM、P25 Phase 1 接收由电台固件完成；App 提供 DV/DMR/C4FM/P25 入口、音频和控制兼容。
- DMR 使用前请确认 SD 卡 `IC-705/DMRRX.BIN` 已就位。

## 构建与安全

- versionCode 12，versionName 2.2.0，包名 cn.arch.ic705remote。
- APK v2 签名通过，证书 SHA-256：`f442665a4a9c89c36ba8027aaa85063e839932a2d5dd47e90d0d69c911d52a97`。
- 正式源码测试：`testDebugUnitTest`、`lintDebug`、`assembleRelease` 通过。
- 外网必须使用 WireGuard/Tailscale 或可信 VPN；不要把 50001–50003 裸露到公网。
- 发射必须使用假负载或确认天线/SWR，低功率并有人值守。
