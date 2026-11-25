# ARCH-705 2.3.9 构建说明

## 环境

- JDK 17
- Android SDK 37
- Android Build Tools 36.0.0
- Android NDK 30.0.16248370
- Gradle 9.8.0

## 构建

```bash
./gradlew clean testDebugUnitTest lintDebug assembleRelease
```

正式发布构建还需要外部签名环境。仓库不包含任何 keystore、密码或 `local.properties`。

发布脚本参考 `tools/build-arch705-release.sh`。该脚本从构建环境读取签名变量，并要求先确认已审核的源码哈希。