# ARCH-705

ARCH-705 是面向 Icom IC-705 的 Android 直连远程控制 App。手机直接使用 Icom LAN/UDP 协议连接电台的控制、CI-V 和音频端口，不经过 wfweb、wfview server、电脑网关或第三方控制服务器。

## 2.5.0 标准版功能

- 地址支持 IP、域名和自定义控制/串口/音频端口。
- 原生频谱和瀑布、频率/模式/滤波、七模式 S 表、PTT、RF 增益、SQL、功率、AGC、NB、NR、ATT、PRE。
- RS-BA1 风格紧凑旋钮和平面模式按钮；频谱支持蓝色强度渐变、网格与信号拖影。
- 接收音频支持 WLAN AF、静音检测、后台驻留与系统悬浮 PTT。
- 多电台档案，登录信息保存在本机；密码使用 Android Keystore + AES-GCM 保存。
- 12 个标准模式：USB、LSB、AM、FM、CW、CW-R、RTTY、WFM、USB-D、LSB-D、FM-D、DV。
- 频道存储、通联日志、在线更新、免责声明和版本信息。

2.5.0 不包含手机端 SDR/DSP 数字语音或数据模式解码。DMR、YSF、P25、NXDN、M17、dPMR 等固件扩展能力将在固件音频链路改造完成后以独立版本适配。

## 连接方式

1. 在 IC-705 上开启 WLAN，并配置 Remote Settings 的 Network User1/密码。
2. 手机与电台处于同一局域网，或通过受保护的 VPN/端口映射访问。
3. 在 App 登录页填写地址、用户名、密码；默认端口是 50001/50002/50003，也可自定义。
4. 首次发射前必须阅读并确认发射许可提示；建议使用假负载、低功率并保持有人值守。

直连 UDP 协议本身不提供 TLS/DTLS 加密。不要把 50001–50003 裸露在公网；外网使用应通过 WireGuard、Tailscale 等受保护网络。

## 构建

构建只在 96 构建服务器的 Docker Android Builder 中完成：

```bash
tools/build-arch705-release.sh
```

发布包必须同时包含 APK、源码包、LICENSE、第三方声明、release notes 和 SHA-256。

## 许可

GNU GPL v3.0。标准控制基线使用 hamdigital-android、FT8CN Icom LAN 协议实现，以及 kappanhang / rsba1-core 的协议研究资料。第三方声明见 `THIRD_PARTY_NOTICES.md`。

作者：BG2GA。项目主页：https://arch.hljrsa.cn。
