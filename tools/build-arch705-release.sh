#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
set -a
source /private/android-sign.env
set +a
IC705_KEYSTORE_PASSWORD=${IC705_KEYSTORE_PASSWORD//$'\r'/}
IC705_KEY_PASSWORD=${IC705_KEY_PASSWORD//$'\r'/}
export IC705_KEYSTORE_PASSWORD IC705_KEY_PASSWORD
export ANDROID_HOME=/work/android-sdk
export ANDROID_SDK_ROOT=/work/android-sdk
export GRADLE_USER_HOME=/work/project/.gradle-release-home
export ANDROID_USER_HOME=/work/project/.android-home
cd /work/project
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
VERSION_NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
test -n "$VERSION_NAME"
test -n "$VERSION_CODE"
SOURCE_HASH=$(find app/src gradle build.gradle.kts settings.gradle.kts gradle.properties app/build.gradle.kts tools -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
if [[ -z "${ARCH_EXPECTED_SOURCE_HASH:-}" ]]; then
  echo "ARCH_EXPECTED_SOURCE_HASH is required for a reviewed release build" >&2
  exit 20
fi
if [[ "$SOURCE_HASH" != "$ARCH_EXPECTED_SOURCE_HASH" ]]; then
  echo "reviewed source hash mismatch: expected $ARCH_EXPECTED_SOURCE_HASH, got $SOURCE_HASH" >&2
  exit 20
fi
/opt/gradle-9.8.0/bin/gradle --no-daemon --stacktrace clean testDebugUnitTest lintDebug assembleRelease
mkdir -p /artifacts
rm -f \
  "/artifacts/ARCH-705-$VERSION_NAME.apk" \
  "/artifacts/ARCH-705-$VERSION_NAME-apksigner.txt" \
  "/artifacts/ARCH-705-$VERSION_NAME-badging.txt" \
  "/artifacts/ARCH-705-$VERSION_NAME-SHA256SUMS.txt" \
  "/artifacts/ARCH-705-source-$VERSION_NAME.tar.gz" \
  "/artifacts/ARCH-705-$VERSION_NAME-build-manifest.json"
APK="/artifacts/ARCH-705-$VERSION_NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
BT=/work/android-sdk/build-tools/36.0.0
"$BT/apksigner" verify --verbose --print-certs "$APK" | tee "/artifacts/ARCH-705-$VERSION_NAME-apksigner.txt"
"$BT/aapt2" dump badging "$APK" | tee "/artifacts/ARCH-705-$VERSION_NAME-badging.txt"
grep -Fq "package: name='cn.arch.ic705remote' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" "/artifacts/ARCH-705-$VERSION_NAME-badging.txt"
! grep -Fq "application-debuggable" "/artifacts/ARCH-705-$VERSION_NAME-badging.txt"
grep -Fq "Signer #1 certificate SHA-256 digest: f442665a4a9c89c36ba8027aaa85063e839932a2d5dd47e90d0d69c911d52a97" "/artifacts/ARCH-705-$VERSION_NAME-apksigner.txt"
(cd /artifacts && sha256sum "ARCH-705-$VERSION_NAME.apk") | tee "/artifacts/ARCH-705-$VERSION_NAME-SHA256SUMS.txt"
TEST_HASH=$(find app/build/test-results/testDebugUnitTest -name 'TEST-*.xml' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
LINT_HASH=$(sha256sum app/build/reports/lint-results-debug.html | cut -d' ' -f1)
NOTES_HASH=$(sha256sum "release-notes-$VERSION_NAME.md" | cut -d' ' -f1)
SOURCE_TGZ="/artifacts/ARCH-705-source-$VERSION_NAME.tar.gz"
tar --exclude=.gradle --exclude=.gradle-user --exclude=.gradle-release-home --exclude=.android-home --exclude=build --exclude=app/build -czf "$SOURCE_TGZ" .
SOURCE_TAR_HASH=$(sha256sum "$SOURCE_TGZ" | cut -d' ' -f1)
APK_HASH=$(sha256sum "$APK" | cut -d' ' -f1)
cat > "/artifacts/ARCH-705-$VERSION_NAME-build-manifest.json" <<EOF
{"versionCode":$VERSION_CODE,"versionName":"$VERSION_NAME","package":"cn.arch.ic705remote","apkSha256":"$APK_HASH","certificateSha256":"f442665a4a9c89c36ba8027aaa85063e839932a2d5dd47e90d0d69c911d52a97","sourceTreeSha256":"$SOURCE_HASH","sourceTarSha256":"$SOURCE_TAR_HASH","unitTestReportSha256":"$TEST_HASH","lintReportSha256":"$LINT_HASH","releaseNotesSha256":"$NOTES_HASH","gradle":"9.8.0","builtAt":"$(date -Iseconds)"}
EOF
