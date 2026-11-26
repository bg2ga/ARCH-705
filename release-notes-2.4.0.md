# ARCH-705 2.4.0

- 新增手机端 DSD-neo 数字语音引擎，不再依赖 IC-705 固件 DMRRX 页面。
- 主界面可直接选择 DMR、YSF、P25 Phase 1、NXDN、dPMR；选中后不跳页，解码信息占用原常用波段三行区域。
- 数字接收显示时隙、DMR 色码、组呼/个呼、目标 ID、源 ID 和呼号/别名；加密和数据呼叫会明确标记。
- 增加 TETRA 入口，并按要求使用 WFM；但 TETRA 的 72 kHz I/Q 需求超出当前 IC-705 WLAN 48 kHz PCM 能力，App 会明确提示不能可靠解码。
- 修正 IC-705 WLAN 接收 PCM 的采样率错误：实际链路为 48 kHz，不是 12 kHz。
- 修正 WiFi 音频频谱、语音音调和数字解码速率。
- 数字模式自动把 IC-705 切到 FM、DATA OFF、SQL 0，原厂 1.40 和 BI1BIF v1.5.0 均可使用。
- 后台默认不继续播放数字解码音频；开启“后台音频”后恢复。
- 增加 DSD-neo、mbelib-neo、libsndfile 许可证和第三方声明。
- 保留 IC-705 本机 TX Time-out Timer 安全要求；手机断网时仍需依赖电台自身发射超时。
