# ARCH-705 2.5.0 标准版

## 主要变更

- 删除手机端全部 SDR/DSP 解码器及其 JNI、C/C++、Kotlin 实现。
- 删除 DMR、YSF、P25、NXDN、M17、dPMR 模式入口和解码面板。
- 删除 FT8、WSPR、JS8、RTTY 解码页、SSTV、FreeDV、APRS、FreeDATA 等本地解码功能页。
- 删除相关原生库、测试、世界地图资源和第三方解码器许可文件。
- 主模式选择保留 12 个标准 IC-705 模式。
- 保留 IC-705 直连认证、UDP 控制、CI-V、音频、频谱、S 表、PTT、频道、日志、设置、后台驻留、悬浮 PTT 和在线更新。
- Release 构建启用 R8 代码收缩和资源收缩。

## 兼容

- 原厂 IC-705 1.40。
- BI1BIF v1.5.0 的标准 CI-V / RS-BA1 网络接口。
- Android 8.0 及以上。

## 测试

- 96 Android Builder 单元测试和 lint 通过。
- Android 35 模拟器直连真实 IC-705 成功，认证、传输通道、频谱、S 表和模式弹窗正常。
- APK 包内无 DSD-neo、mbelib-neo、libsndfile、`libhamdigital`、`libhamrade` 等本地解码器。

## 安全

远程发射仍必须在 IC-705 本机开启 TX Time-out Timer。手机断网、上行链路完全丢失或 App 被系统终止时，手机端无法保证 PTT OFF 送达电台。
