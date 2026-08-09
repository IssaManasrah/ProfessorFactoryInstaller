package com.shaikhalkar.professorinstaller;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class InstallCoordinator {
    interface Host {
        Activity activity();
        void showQueueScreen();
        void onQueueStatus(String title, String detail, int progress, boolean indeterminate);
        void onQueuePosition(int current, int total, String source);
        void onQueueComplete(int success, int skipped, int failed);
        void onQueueError(String title, String message);
        void launchUninstall(String packageName);
    }

    static final class Task {
        final Models.AppInfo app;
        final boolean offlineFirst;
        final ArrayList<String> removePackages = new ArrayList<>();
        Task(Models.AppInfo app, boolean offlineFirst) { this.app = app; this.offlineFirst = offlineFirst; }
    }

    private static final AtomicInteger REQUESTS = new AtomicInteger(7000);
    private final Host host;
    private final UsbResolver usb = new UsbResolver();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Task> queue = new ArrayList<>();
    private int index = 0;
    private int removeIndex = 0;
    private int success = 0;
    private int skipped = 0;
    private int failed = 0;
    private boolean running = false;
    private boolean waitingInstall = false;
    private boolean waitingUnknownSources = false;
    private File pendingApk;
    private String pendingSource = "";

    InstallCoordinator(Host host) { this.host = host; }

    boolean isRunning() { return running; }

    void start(List<Task> tasks) {
        LinkedHashMap<String, Task> deduped = new LinkedHashMap<>();
        for (Task t : tasks) {
            if (t == null || t.app == null) continue;
            String key = t.app.queueKey();
            if (!deduped.containsKey(key)) deduped.put(key, t);
        }
        queue.clear();
        queue.addAll(deduped.values());
        index = 0;
        removeIndex = 0;
        success = skipped = failed = 0;
        waitingInstall = false;
        waitingUnknownSources = false;
        pendingApk = null;
        pendingSource = "";
        running = !queue.isEmpty();
        host.showQueueScreen();
        if (!running) {
            host.onQueueComplete(0, 0, 0);
            return;
        }
        processCurrent();
    }

    void onHostResume() {
        if (!running) return;
        if (waitingUnknownSources) {
            if (canInstallPackages(host.activity())) {
                waitingUnknownSources = false;
                if (pendingApk != null) stageInstall(pendingApk);
                else processCurrent();
            }
        }
    }

    void onInstallResult(int status, String message) {
        if (!running || !waitingInstall) return;
        waitingInstall = false;
        pendingApk = null;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            success++;
            host.onQueueStatus(currentName(), "تم التثبيت بنجاح ✓", 100, false);
            advance();
        } else {
            failed++;
            host.onQueueError("تعذر تثبيت " + currentName(), message == null || message.isEmpty() ? "فشل PackageInstaller" : message);
        }
    }

    void retryCurrent() {
        if (!running) return;
        waitingInstall = false;
        pendingApk = null;
        processCurrent();
    }

    void skipCurrent() {
        if (!running) return;
        skipped++;
        advance();
    }

    void cancel() {
        queue.clear();
        running = false;
        waitingInstall = false;
        pendingApk = null;
    }

    void onUninstallReturned() {
        if (!running) return;
        removeIndex++;
        processRemovalsThenInstall();
    }

    private void processCurrent() {
        if (!running) return;
        if (index >= queue.size()) {
            running = false;
            host.onQueueComplete(success, skipped, failed);
            return;
        }
        removeIndex = 0;
        Task t = queue.get(index);
        host.onQueuePosition(index + 1, queue.size(), t.offlineFirst ? "USB / Online" : "Online");
        host.onQueueStatus(t.app.name, "فحص الجهاز...", 0, true);
        processRemovalsThenInstall();
    }

    private void processRemovalsThenInstall() {
        Task t = queue.get(index);
        while (removeIndex < t.removePackages.size()) {
            String pkg = t.removePackages.get(removeIndex);
            if (pkg == null || pkg.isEmpty() || pkg.equals(t.app.packageName) || !isInstalled(host.activity(), pkg)) {
                removeIndex++;
                continue;
            }
            host.onQueueStatus(t.app.name, "إزالة النسخة القديمة...", 0, true);
            host.launchUninstall(pkg);
            return;
        }

        if (isVersionSatisfied(host.activity(), t.app)) {
            skipped++;
            host.onQueueStatus(t.app.name, "موجود بأحدث إصدار ✓", 100, false);
            advance();
            return;
        }
        resolveApk(t);
    }

    private void resolveApk(Task t) {
        io.execute(() -> {
            try {
                if (t.offlineFirst) {
                    File local = usb.findApk(host.activity(), t.app);
                    if (local != null) {
                        pendingSource = "USB";
                        runUi(() -> {
                            host.onQueuePosition(index + 1, queue.size(), "USB");
                            host.onQueueStatus(t.app.name, "تم العثور عليه على الفلاشة", 100, false);
                            prepareInstall(local);
                        });
                        return;
                    }
                }
                File cache = cacheFile(host.activity(), t.app);
                if (cache.isFile() && validFile(cache, t.app)) {
                    pendingSource = "Cache";
                    runUi(() -> {
                        host.onQueuePosition(index + 1, queue.size(), "Cache");
                        host.onQueueStatus(t.app.name, "استخدام الملف المحفوظ", 100, false);
                        prepareInstall(cache);
                    });
                    return;
                }
                if (t.app.downloadUrl == null || t.app.downloadUrl.isEmpty()) throw new IllegalStateException("رابط APK غير متوفر من API");
                pendingSource = "Online";
                download(t, cache);
            } catch (Exception e) {
                runUi(() -> {
                    failed++;
                    host.onQueueError("تعذر تجهيز " + t.app.name, e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    private void download(Task t, File dest) throws Exception {
        runUi(() -> {
            host.onQueuePosition(index + 1, queue.size(), "Online");
            host.onQueueStatus(t.app.name, "جاري التنزيل...", 0, false);
        });
        HttpURLConnection con = (HttpURLConnection) new URL(t.app.downloadUrl).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(45000);
        con.setInstanceFollowRedirects(true);
        int status = con.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        long total = con.getContentLengthLong();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        InputStream in = con.getInputStream();
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[128 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int p = (int)Math.min(100, (done * 100L) / total);
                runUi(() -> host.onQueueStatus(t.app.name, "جاري التنزيل... " + p + "%", p, false));
            }
        }
        out.flush(); out.close(); in.close(); con.disconnect();
        if (!validFile(tmp, t.app)) { tmp.delete(); throw new IllegalStateException("فشل التحقق من ملف APK"); }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new IllegalStateException("تعذر حفظ APK في الكاش");
        runUi(() -> {
            host.onQueueStatus(t.app.name, "اكتمل التنزيل ✓", 100, false);
            prepareInstall(dest);
        });
    }

    private void prepareInstall(File apk) {
        pendingApk = apk;
        if (!canInstallPackages(host.activity())) {
            waitingUnknownSources = true;
            host.onQueueStatus(currentName(), "اسمح لـ Professor Installer بتثبيت التطبيقات", 100, false);
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + host.activity().getPackageName()));
            host.activity().startActivity(i);
            return;
        }
        stageInstall(apk);
    }

    private void stageInstall(File apk) {
        try {
            PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            Models.AppInfo app = queue.get(index).app;
            if (app.packageName != null && !app.packageName.isEmpty()) params.setAppPackageName(app.packageName);
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            host.onQueueStatus(app.name, "تجهيز شاشة التثبيت...", 100, true);
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            FileInputStream in = new FileInputStream(apk);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
            out.close(); in.close();

            Intent callback = new Intent(host.activity(), InstallStatusReceiver.class);
            callback.setAction("INSTALL_STATUS_" + sessionId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(host.activity(), REQUESTS.incrementAndGet(), callback, flags);
            waitingInstall = true;
            host.onQueueStatus(app.name, "بانتظار موافقة التثبيت...", 100, true);
            session.commit(pi.getIntentSender());
            session.close();
        } catch (Exception e) {
            waitingInstall = false;
            failed++;
            host.onQueueError("تعذر بدء تثبيت " + currentName(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void advance() {
        index++;
        removeIndex = 0;
        pendingApk = null;
        waitingInstall = false;
        host.activity().getWindow().getDecorView().postDelayed(this::processCurrent, 650);
    }

    private String currentName() {
        return index >= 0 && index < queue.size() ? queue.get(index).app.name : "التطبيق";
    }

    private static File cacheFile(Context context, Models.AppInfo app) {
        File dir = new File(context.getCacheDir(), "apk-cache");
        if (!dir.exists()) dir.mkdirs();
        String name = app.fileName == null || app.fileName.isEmpty() ? app.id + "-" + app.versionCode + ".apk" : app.fileName;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, name);
    }

    private static boolean validFile(File f, Models.AppInfo app) {
        if (!f.isFile() || f.length() < 1024) return false;
        return app.sha256 == null || app.sha256.isEmpty() || app.sha256.equalsIgnoreCase(UsbResolver.sha256(f));
    }

    static boolean canInstallPackages(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static boolean isInstalled(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    static boolean isVersionSatisfied(Context c, Models.AppInfo app) {
        if (app.packageName == null || app.packageName.isEmpty()) return false;
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(app.packageName, 0);
            long installed = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            return installed >= app.versionCode;
        } catch (Exception e) { return false; }
    }

    private void runUi(Runnable r) { host.activity().runOnUiThread(r); }
}
