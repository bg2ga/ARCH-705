# ARCH-705

ARCH-705 是面向 Icom IC-705 的 Android 直连远程控制 App。手机直接使用 Icom LAN/UDP 协议连接电台的控制、CI-V 和音频端口，不经过 wfweb、wfview server、电脑网关或第三方控制服务器。

## 功能

- 地址支持 IP、域名和自定义控制/串口/音频端口。
- 原生频谱和瀑布、频率/模式/滤波、七模式 S 表、PTT、RF 增益、SQL、功率、AGC、NB、NR、ATT、PRE。
- RS-BA1 风格紧凑旋钮和平面模式按钮；频谱支持蓝色强度渐变、网格与两帧拖影。
- 接收音频支持 SQL 0 / WLAN AF 一键恢复和静音自动检测。
- 多电台档案，密码使用 Android Keystore + AES-GCM 保存。
- FT8/FT4/FT2、WSPR、JS8、RTTY、JTTY、PSK31、Olivia、CW、SSTV、DV/D-STAR、FreeDV、天气传真、APRS 和 FreeDATA。
- BI1BIF v1.5.0 改版固件的 DMR、C4FM、P25 Phase 1 语音接收提示，以及 FT8/FT4 自动选台、APRS 64 条记录等能力适配。
- 正式签名 APK、应用内在线更新、源码包、第三方许可和免责声明。

## 连接方式

1. 在 IC-705 上开启 WLAN，并配置 Remote Settings 的 Network User1/密码。
2. 手机与电台处于同一局域网，或通过受保护的 VPN/端口映射访问。
3. 在 App 登录页填写地址、用户名、密码；默认端口是 50001/50002/50003，也可自定义。
4. 首次发射前必须阅读并确认发射许可提示；建议使用假负载、低功率并保持有人值守。

直连 UDP 协议本身不提供 TLS/DTLS 加密。不要把 50001–50003 裸露在公网；外网使用应通过 WireGuard、Tailscale 等受保护网络。

## 构建

构建在 96 构建服务器上完成，不在开发电脑上编译：

```bash
cd android-direct-app
gradle testDebugUnitTest lintDebug assembleRelease
```

正式构建脚本：`tools/build-arch705-release.sh`。发布包必须同时包含 APK、源码包、LICENSE、第三方声明、release notes 和 SHA-256。

## 许可

GNU GPL v3.0。App 包含 ft8_lib、WSJT-X、JS8Call、fldigi、FT8CN、Robot36、SSTV Encoder 2、codec2、Dire Wolf、rade_c/Opus 等开源代码，具体来源和许可见源码目录及发布包的 `THIRD_PARTY_NOTICES.md`。

作者：BG2GA。项目主页：https://arch.hljrsa.cn。
