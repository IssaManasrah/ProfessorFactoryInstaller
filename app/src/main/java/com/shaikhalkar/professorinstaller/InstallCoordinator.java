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
        Task(Models.AppInfo app, boolean offlineFirst) {
            this.app = app;
            this.offlineFirst = offlineFirst;
        }
    }

    private static final class ResolvedTask {
        final Task task;
        final File apk;
        final String source;
        ResolvedTask(Task task, File apk, String source) {
            this.task = task;
            this.apk = apk;
            this.source = source;
        }
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
    private int batchInstallCount = 0;
    private boolean running = false;
    private boolean waitingInstall = false;
    private boolean waitingUnknownSources = false;
    private boolean batchMode = false;
    private File pendingApk;

    InstallCoordinator(Host host) {
        this.host = host;
    }

    boolean isRunning() {
        return running;
    }

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
        success = 0;
        skipped = 0;
        failed = 0;
        batchInstallCount = 0;
        waitingInstall = false;
        waitingUnknownSources = false;
        pendingApk = null;
        running = !queue.isEmpty();
        batchMode = canUseBatch(queue);

        host.showQueueScreen();
        if (!running) {
            host.onQueueComplete(0, 0, 0);
            return;
        }

        if (!canInstallPackages(host.activity())) {
            waitingUnknownSources = true;
            host.onQueueStatus("صلاحية التثبيت", "اسمح لـ Professor Installer بتثبيت التطبيقات مرة واحدة", 0, true);
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + host.activity().getPackageName()));
            host.activity().startActivity(i);
            return;
        }

        beginExecution();
    }

    void onHostResume() {
        if (!running || !waitingUnknownSources) return;
        if (canInstallPackages(host.activity())) {
            waitingUnknownSources = false;
            if (pendingApk != null && !batchMode) stageInstall(pendingApk);
            else beginExecution();
        }
    }

    void onInstallResult(int status, String message) {
        if (!running || !waitingInstall) return;
        waitingInstall = false;
        pendingApk = null;

        if (batchMode) {
            if (status == PackageInstaller.STATUS_SUCCESS) {
                success += batchInstallCount;
                host.onQueueStatus("تم تجهيز الجهاز", "تم تثبيت المجموعة بنجاح ✓", 100, false);
                running = false;
                host.onQueueComplete(success, skipped, failed);
            } else {
                host.onQueueError("تعذر التثبيت الجماعي", friendlyInstallMessage(message));
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            success++;
            host.onQueueStatus(currentName(), "تم التثبيت بنجاح ✓", 100, false);
            advance();
        } else {
            failed++;
            host.onQueueError("تعذر تثبيت " + currentName(), friendlyInstallMessage(message));
        }
    }

    void retryCurrent() {
        if (!running) return;
        waitingInstall = false;
        pendingApk = null;
        if (batchMode) prepareBatch();
        else processCurrent();
    }

    void skipCurrent() {
        if (!running) return;
        if (batchMode) {
            failed += batchInstallCount;
            running = false;
            host.onQueueComplete(success, skipped, failed);
            return;
        }
        skipped++;
        advance();
    }

    void cancel() {
        queue.clear();
        running = false;
        waitingInstall = false;
        waitingUnknownSources = false;
        pendingApk = null;
    }

    void onUninstallReturned() {
        if (!running || batchMode || index >= queue.size()) return;
        Task t = queue.get(index);
        if (removeIndex >= t.removePackages.size()) {
            processRemovalsThenInstall();
            return;
        }

        String pkg = t.removePackages.get(removeIndex);
        if (pkg != null && !pkg.isEmpty() && isInstalled(host.activity(), pkg)) {
            host.onQueueError("تعذر حذف التطبيق القديم",
                    "التطبيق " + pkg + " ما زال موجودًا على الجهاز. وافق على شاشة الحذف ثم أعد المحاولة.");
            return;
        }

        removeIndex++;
        processRemovalsThenInstall();
    }

    private void beginExecution() {
        if (batchMode) prepareBatch();
        else processCurrent();
    }

    private boolean canUseBatch(List<Task> tasks) {
        if (Build.VERSION.SDK_INT < 29 || tasks.size() < 2) return false;
        for (Task t : tasks) {
            if (!t.removePackages.isEmpty()) return false;
        }
        return true;
    }

    private void prepareBatch() {
        if (!running) return;
        io.execute(() -> {
            ArrayList<ResolvedTask> ready = new ArrayList<>();
            try {
                int total = queue.size();
                for (int i = 0; i < total; i++) {
                    if (!running) return;
                    Task t = queue.get(i);
                    final int pos = i + 1;
                    runUi(() -> {
                        host.onQueuePosition(pos, total, t.offlineFirst ? "USB / Online" : "Online");
                        host.onQueueStatus(t.app.name, "فحص التطبيق...", 0, true);
                    });

                    if (isVersionSatisfied(host.activity(), t.app)) {
                        skipped++;
                        runUi(() -> host.onQueueStatus(t.app.name, "موجود بأحدث إصدار ✓", 100, false));
                        continue;
                    }

                    ResolvedTask resolved = resolveBlocking(t, pos, total);
                    String issue = compatibilityIssue(resolved.apk, t.app);
                    if (issue != null) {
                        skipped++;
                        runUi(() -> host.onQueueStatus(t.app.name, issue, 100, false));
                        continue;
                    }
                    ready.add(resolved);
                }

                if (ready.isEmpty()) {
                    runUi(() -> {
                        running = false;
                        host.onQueueComplete(success, skipped, failed);
                    });
                    return;
                }

                stageBatch(ready);
            } catch (Exception e) {
                runUi(() -> {
                    failed++;
                    host.onQueueError("تعذر تجهيز مجموعة التطبيقات",
                            e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    private ResolvedTask resolveBlocking(Task t, int pos, int total) throws Exception {
        if (t.offlineFirst) {
            File local = usb.findApk(host.activity(), t.app);
            if (local != null) {
                runUi(() -> {
                    host.onQueuePosition(pos, total, "USB");
                    host.onQueueStatus(t.app.name, "تم العثور عليه على الفلاشة ✓", 100, false);
                });
                return new ResolvedTask(t, local, "USB");
            }
        }

        File cache = cacheFile(host.activity(), t.app);
        if (cache.isFile() && validFile(cache, t.app)) {
            runUi(() -> {
                host.onQueuePosition(pos, total, "Cache");
                host.onQueueStatus(t.app.name, "استخدام الملف المحفوظ ✓", 100, false);
            });
            return new ResolvedTask(t, cache, "Cache");
        }

        if (t.app.downloadUrl == null || t.app.downloadUrl.isEmpty()) {
            throw new IllegalStateException("رابط APK غير متوفر من API للتطبيق " + t.app.name);
        }

        runUi(() -> {
            host.onQueuePosition(pos, total, "Online");
            host.onQueueStatus(t.app.name, "جاري التنزيل...", 0, false);
        });
        downloadBlocking(t, cache);
        return new ResolvedTask(t, cache, "Online");
    }

    private void downloadBlocking(Task t, File dest) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(t.app.downloadUrl).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(45000);
        con.setInstanceFollowRedirects(true);
        int status = con.getResponseCode();
        if (status < 200 || status >= 300) {
            con.disconnect();
            throw new IllegalStateException("HTTP " + status + " للتطبيق " + t.app.name);
        }

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
                int p = (int) Math.min(100, (done * 100L) / total);
                runUi(() -> host.onQueueStatus(t.app.name, "جاري التنزيل... " + p + "%", p, false));
            }
        }
        out.flush();
        out.close();
        in.close();
        con.disconnect();

        if (!validFile(tmp, t.app)) {
            tmp.delete();
            throw new IllegalStateException("فشل التحقق من ملف APK للتطبيق " + t.app.name);
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new IllegalStateException("تعذر حفظ APK في الكاش");
        runUi(() -> host.onQueueStatus(t.app.name, "اكتمل التنزيل ✓", 100, false));
    }

    private void stageBatch(List<ResolvedTask> ready) throws Exception {
        PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
        ArrayList<Integer> childIds = new ArrayList<>();
        int parentId = -1;
        try {
            PackageInstaller.SessionParams parentParams =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parentParams.setMultiPackage();
            if (Build.VERSION.SDK_INT >= 31) {
                parentParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                parentParams.setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK);
            }
            parentId = installer.createSession(parentParams);
            PackageInstaller.Session parent = installer.openSession(parentId);

            int pos = 0;
            for (ResolvedTask r : ready) {
                pos++;
                final int fp = pos;
                runUi(() -> {
                    host.onQueuePosition(fp, ready.size(), r.source);
                    host.onQueueStatus(r.task.app.name, "تجهيز التثبيت الجماعي...", 100, true);
                });

                PackageInstaller.SessionParams childParams =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (r.task.app.packageName != null && !r.task.app.packageName.isEmpty()) {
                    childParams.setAppPackageName(r.task.app.packageName);
                }
                childParams.setSize(r.apk.length());
                if (Build.VERSION.SDK_INT >= 31) {
                    childParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                    childParams.setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK);
                }

                int childId = installer.createSession(childParams);
                childIds.add(childId);
                PackageInstaller.Session child = installer.openSession(childId);
                OutputStream out = child.openWrite("base.apk", 0, r.apk.length());
                FileInputStream in = new FileInputStream(r.apk);
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                child.fsync(out);
                out.close();
                in.close();
                child.close();
                parent.addChildSessionId(childId);
            }

            Intent callback = new Intent(host.activity(), InstallStatusReceiver.class);
            callback.setAction("INSTALL_BATCH_STATUS_" + parentId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(
                    host.activity(), REQUESTS.incrementAndGet(), callback, flags);

            batchInstallCount = ready.size();
            waitingInstall = true;
            runUi(() -> host.onQueueStatus(
                    "جاهز للتثبيت",
                    "تم تجهيز " + batchInstallCount + " تطبيق. وافق على شاشة Android لإكمال المجموعة.",
                    100,
                    true));
            parent.commit(pi.getIntentSender());
            parent.close();
        } catch (Exception e) {
            if (parentId >= 0) {
                try { installer.abandonSession(parentId); } catch (Exception ignored) {}
            }
            for (Integer id : childIds) {
                try { installer.abandonSession(id); } catch (Exception ignored) {}
            }
            throw e;
        }
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
            if (pkg == null || pkg.isEmpty() || !isInstalled(host.activity(), pkg)) {
                removeIndex++;
                continue;
            }
            host.onQueueStatus(t.app.name, "حذف التطبيق القديم: " + pkg, 0, true);
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
                ResolvedTask resolved = resolveBlocking(t, index + 1, queue.size());
                runUi(() -> prepareInstall(resolved.apk));
            } catch (Exception e) {
                runUi(() -> {
                    failed++;
                    host.onQueueError("تعذر تجهيز " + t.app.name,
                            e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    private void prepareInstall(File apk) {
        Models.AppInfo app = queue.get(index).app;
        String issue = compatibilityIssue(apk, app);
        if (issue != null) {
            failed++;
            host.onQueueError("نسخة APK غير متوافقة", issue);
            return;
        }

        pendingApk = apk;
        if (!canInstallPackages(host.activity())) {
            waitingUnknownSources = true;
            host.onQueueStatus(currentName(), "اسمح لـ Professor Installer بتثبيت التطبيقات", 100, false);
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + host.activity().getPackageName()));
            host.activity().startActivity(i);
            return;
        }
        stageInstall(apk);
    }

    private void stageInstall(File apk) {
        try {
            PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            Models.AppInfo app = queue.get(index).app;
            if (app.packageName != null && !app.packageName.isEmpty()) params.setAppPackageName(app.packageName);
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            host.onQueueStatus(app.name, "تجهيز شاشة التثبيت...", 100, true);
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            FileInputStream in = new FileInputStream(apk);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            session.fsync(out);
            out.close();
            in.close();

            Intent callback = new Intent(host.activity(), InstallStatusReceiver.class);
            callback.setAction("INSTALL_STATUS_" + sessionId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(
                    host.activity(), REQUESTS.incrementAndGet(), callback, flags);
            waitingInstall = true;
            host.onQueueStatus(app.name, "بانتظار موافقة Android عند الحاجة...", 100, true);
            session.commit(pi.getIntentSender());
            session.close();
        } catch (Exception e) {
            waitingInstall = false;
            failed++;
            host.onQueueError("تعذر بدء تثبيت " + currentName(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String compatibilityIssue(File apk, Models.AppInfo app) {
        try {
            PackageManager pm = host.activity().getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (archive == null || archive.packageName == null || archive.packageName.isEmpty()) {
                return "تعذر قراءة معلومات APK. الملف قد يكون تالفًا أو غير مكتمل.";
            }
            if (app.packageName != null && !app.packageName.isEmpty()
                    && !app.packageName.equals(archive.packageName)) {
                return "الـPackage داخل الملف هو " + archive.packageName
                        + " بينما الـAPI يتوقع " + app.packageName + ". راجع ملف التطبيق في اللوحة.";
            }
            if (archive.sharedUserId != null
                    && archive.sharedUserId.startsWith("android.uid.")) {
                return "هذا APK نسخة نظامية تستخدم shared user: " + archive.sharedUserId
                        + ". تحتاج توقيع منصة المصنع المطابق لنفس Firmware، ولا يمكن تثبيتها كتطبيق عادي. "
                        + "ارفع من اللوحة نسخة User عادية أو نسخة Platform-signed الصحيحة.";
            }
            return null;
        } catch (Exception e) {
            return "تعذر فحص توافق APK: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String friendlyInstallMessage(String message) {
        String m = message == null ? "" : message;
        if (m.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")
                || m.contains("shared user android.uid.system")) {
            return "الـAPK نسخة System وتوقيعها لا يطابق توقيع Firmware. استخدم نسخة User عادية "
                    + "أو نسخة موقعة بمفتاح منصة المصنع الصحيح.";
        }
        if (m.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || m.contains("signatures do not match")) {
            return "يوجد إصدار مثبت بنفس Package لكن بتوقيع مختلف. احذفه أولًا أو استخدم APK بنفس التوقيع.";
        }
        if (m.isEmpty()) return "فشل PackageInstaller بدون رسالة إضافية";
        return m;
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
        String name = app.fileName == null || app.fileName.isEmpty()
                ? app.id + "-" + app.versionCode + ".apk"
                : app.fileName;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, name);
    }

    private static boolean validFile(File f, Models.AppInfo app) {
        if (!f.isFile() || f.length() < 1024) return false;
        return app.sha256 == null || app.sha256.isEmpty()
                || app.sha256.equalsIgnoreCase(UsbResolver.sha256(f));
    }

    static boolean canInstallPackages(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static boolean isInstalled(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isVersionSatisfied(Context c, Models.AppInfo app) {
        if (app.packageName == null || app.packageName.isEmpty()) return false;
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(app.packageName, 0);
            long installed = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            return installed >= app.versionCode;
        } catch (Exception e) {
            return false;
        }
    }

    private void runUi(Runnable r) {
        host.activity().runOnUiThread(r);
    }
}
