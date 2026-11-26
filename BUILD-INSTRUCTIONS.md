# ARCH-705 2.5.0 构建说明

## 环境

- JDK 17
- Android SDK 37
- Android Build Tools 36.0.0
- Gradle 9.8.0

## 构建

```bash
./gradlew clean testDebugUnitTest lintDebug assembleRelease
```

正式发布使用外部签名环境。仓库不包含 keystore、密码、`local.properties` 或 APK/AAB 文件。

发布脚本：`tools/build-arch705-release.sh`。