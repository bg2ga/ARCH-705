package uk.hamdigital;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Secure OTA updater for IC-705 Remote. */
public final class Updater {
    public interface StatusListener { void onStatus(String status); }

    private static final String MANIFEST_URL = "https://hljrsa.cn:50113/releases/ota/ic705-remote-version.json";
    private static final String ALLOWED_APK_PREFIX = "https://hljrsa.cn:50113/releases/apps/ic705-remote/";
    private static final String PREFS = "ic705_updater";
    private static final String KEY_PENDING_APK = "pending_apk";
    private static final String USER_AGENT = "IC705Remote-Android/" + BuildConfig.VERSION_NAME;
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static boolean autoChecked;

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Updater(Activity activity) { this.activity = activity; }

    public void check(boolean silent, StatusListener listener) {
        if (silent && autoChecked) return;
        if (silent) autoChecked = true;
        report(listener, "正在检查更新…");
        new Thread(() -> {
            try {
                JSONObject manifest = fetchManifest();
                ui.post(() -> processManifest(manifest, silent, listener));
            } catch (Exception error) {
                android.util.Log.e("IC705-UPDATE", "update check failed", error);
                ui.post(() -> {
                    report(listener, "检查更新失败");
                    if (!silent) showInfo("检查更新失败", safeMessage(error));
                });
            }
        }, "ic705-update-check").start();
    }

    public void resumePendingInstall() {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) return;
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String path = preferences.getString(KEY_PENDING_APK, "");
        if (path == null || path.isEmpty()) return;
        File apk = new File(path);
        preferences.edit().remove(KEY_PENDING_APK).apply();
        if (apk.isFile()) install(apk);
    }

    private JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                MANIFEST_URL + "?t=" + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(false);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("更新清单 HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) > 0) {
                    total += count;
                    if (total > MAX_MANIFEST_BYTES) throw new IllegalStateException("更新清单过大");
                    output.write(buffer, 0, count);
                }
                JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
                validateManifest(json);
                return json;
            }
        } finally { connection.disconnect(); }
    }

    private void validateManifest(JSONObject manifest) throws Exception {
        int versionCode = manifest.getInt("versionCode");
        String versionName = manifest.getString("versionName").trim();
        String apkUrl = manifest.getString("apkUrl").trim();
        String sha256 = manifest.getString("sha256").trim();
        long size = manifest.getLong("size");
        if (versionCode <= 0 || versionName.isEmpty() || size <= 0 || size > MAX_APK_BYTES
                || !sha256.matches("[0-9a-fA-F]{64}") || !isAllowedApkUrl(apkUrl)) {
            throw new SecurityException("更新清单字段无效");
        }
        manifest.put("versionName", versionName);
        manifest.put("apkUrl", apkUrl);
        manifest.put("sha256", sha256.toLowerCase(Locale.ROOT));
    }

    private void processManifest(JSONObject manifest, boolean silent, StatusListener listener) {
        try {
            int remoteCode = manifest.getInt("versionCode");
            String remoteName = manifest.getString("versionName");
            if (remoteCode <= BuildConfig.VERSION_CODE) {
                report(listener, "当前已是最新版本 " + BuildConfig.VERSION_NAME);
                if (!silent) showInfo("检查更新", "当前已是最新版本。\n\n版本：" +
                        BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）");
                return;
            }
            String apkUrl = manifest.getString("apkUrl");
            String sha256 = manifest.getString("sha256");
            long size = manifest.getLong("size");
            String notes = manifest.optString("notes", "功能与稳定性更新");
            report(listener, "发现新版本 " + remoteName);
            new AlertDialog.Builder(activity)
                    .setTitle("发现新版本 " + remoteName)
                    .setMessage(notes + "\n\n版本代码：" + remoteCode + "\n安装包：" + formatBytes(size) +
                            "\n\n安装前将校验 SHA-256、包名、版本和签名。")
                    .setPositiveButton("下载并更新", (dialog, which) ->
                            download(apkUrl, sha256, size, remoteCode, listener))
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Exception error) {
            report(listener, "更新处理失败");
            if (!silent) showInfo("更新失败", safeMessage(error));
        }
    }

    private void download(String apkUrl, String expectedSha, long expectedSize,
                          int expectedVersionCode, StatusListener listener) {
        report(listener, "正在下载更新…");
        new Thread(() -> {
            File target = new File(activity.getCacheDir(), ApkProvider.UPDATE_FILE);
            File temp = new File(activity.getCacheDir(), ApkProvider.UPDATE_FILE + ".part");
            try {
                if (temp.exists() && !temp.delete()) throw new IllegalStateException("无法清理临时文件");
                HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setInstanceFollowRedirects(false);
                try {
                    int code = connection.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("APK HTTP " + code);
                    try (InputStream input = connection.getInputStream();
                         FileOutputStream output = new FileOutputStream(temp)) {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] buffer = new byte[64 * 1024];
                        long total = 0;
                        int count;
                        while ((count = input.read(buffer)) > 0) {
                            total += count;
                            if (total > MAX_APK_BYTES || total > expectedSize) throw new SecurityException("APK 体积异常");
                            output.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                        }
                        output.getFD().sync();
                        if (total != expectedSize) throw new SecurityException("APK 大小不一致");
                        if (!toHex(digest.digest()).equalsIgnoreCase(expectedSha)) throw new SecurityException("SHA-256 校验失败");
                    }
                } finally { connection.disconnect(); }
                verifyPackage(temp, expectedVersionCode);
                if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换更新文件");
                if (!temp.renameTo(target)) throw new IllegalStateException("无法保存更新文件");
                ui.post(() -> { report(listener, "下载与校验完成"); install(target); });
            } catch (Exception error) {
                if (temp.exists()) temp.delete();
                android.util.Log.e("IC705-UPDATE", "download failed", error);
                ui.post(() -> { report(listener, "下载失败"); showInfo("更新失败", safeMessage(error)); });
            }
        }, "ic705-update-download").start();
    }

    private void verifyPackage(File apk, int expectedVersionCode) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName)
                || candidate.versionCode != expectedVersionCode || candidate.versionCode <= BuildConfig.VERSION_CODE) {
            throw new SecurityException("更新包包名或版本不匹配");
        }
        if (!signatureDigest(candidate, flags).equals(signatureDigest(
                manager.getPackageInfo(activity.getPackageName(), flags), flags))) {
            throw new SecurityException("更新包签名与当前版本不匹配");
        }
    }

    @SuppressWarnings("deprecation")
    private String signatureDigest(PackageInfo info, int flags) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) throw new SecurityException("无法读取文件签名");
            signatures = signingInfo.hasMultipleSigners() ? signingInfo.getApkContentsSigners() : signingInfo.getSigningCertificateHistory();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException("文件没有签名");
        return toHex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }

    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY_PENDING_APK, apk.getAbsolutePath()).apply();
            showInfo("需要安装权限", "请允许“ARCH-705”安装未知应用，返回后会继续安装。", "打开设置", () -> {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            });
            return;
        }
        Uri uri = ApkProvider.uriFor(activity);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(android.content.ClipData.newRawUri("", uri));
        try { activity.startActivity(intent); }
        catch (Exception error) { showInfo("无法启动安装器", safeMessage(error)); }
    }

    private boolean isAllowedApkUrl(String url) {
        return url != null && url.startsWith(ALLOWED_APK_PREFIX) && url.endsWith(".apk");
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private void report(StatusListener listener, String status) { if (listener != null) ui.post(() -> listener.onStatus(status)); }
    private void showInfo(String title, String message) { showInfo(title, message, null, null); }
    private void showInfo(String title, String message, String positiveText, Runnable action) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton(positiveText == null ? "确定" : positiveText,
                        (dialog, which) -> { if (action != null) action.run(); }).show();
    }
    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}


