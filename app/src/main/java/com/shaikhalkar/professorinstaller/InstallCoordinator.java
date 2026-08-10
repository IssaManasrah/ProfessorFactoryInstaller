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
        final File apk;
        final String source;

        ResolvedTask(File apk, String source) {
            this.apk = apk;
            this.source = source;
        }
    }

    private static final class PreparedTask {
        final Task task;
        final File apk;
        final String source;

        PreparedTask(Task task, File apk, String source) {
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
    private final ArrayList<PreparedTask> batchPrepared = new ArrayList<>();

    private int index;
    private int removeIndex;
    private int success;
    private int skipped;
    private int failed;
    private int activeSessionId = -1;
    private int batchPrepareIndex;
    private int batchInstallCount;

    private boolean running;
    private boolean waitingInstall;
    private boolean waitingUnknownSources;
    private boolean batchMode;
    private boolean batchPreparing;
    private File pendingApk;
    private String pendingSource = "-";

    InstallCoordinator(Host host) {
        this.host = host;
    }

    boolean isRunning() {
        return running;
    }

    void start(List<Task> tasks) {
        cleanupOwnedSessions();

        LinkedHashMap<String, Task> deduped = new LinkedHashMap<>();
        for (Task task : tasks) {
            if (task == null || task.app == null) continue;
            String key = task.app.queueKey();
            if (!deduped.containsKey(key)) deduped.put(key, task);
        }

        queue.clear();
        queue.addAll(deduped.values());
        batchPrepared.clear();
        index = 0;
        removeIndex = 0;
        success = 0;
        skipped = 0;
        failed = 0;
        activeSessionId = -1;
        batchPrepareIndex = 0;
        batchInstallCount = 0;
        running = !queue.isEmpty();
        waitingInstall = false;
        waitingUnknownSources = false;
        batchPreparing = false;
        pendingApk = null;
        pendingSource = "-";

        // Device programming tasks are created with offlineFirst=true. Keep
        // browser/support tasks sequential so replace/delete action ordering is
        // never changed. Multi-package sessions are available from Android 10.
        batchMode = Build.VERSION.SDK_INT >= 29 && isProgrammingBatch(queue);

        host.showQueueScreen();
        if (!running) {
            host.onQueueComplete(0, 0, 0);
            return;
        }

        if (!canInstallPackages(host.activity())) {
            waitingUnknownSources = true;
            host.onQueueStatus(
                    "صلاحية التثبيت",
                    "اسمح لـ Professor Installer بتثبيت التطبيقات مرة واحدة",
                    0,
                    true);
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + host.activity().getPackageName()));
            host.activity().startActivity(intent);
            return;
        }

        if (batchMode) prepareProgrammingBatch();
        else processCurrent();
    }

    private boolean isProgrammingBatch(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return false;
        for (Task task : tasks) {
            if (task == null || !task.offlineFirst || !task.removePackages.isEmpty()) return false;
        }
        return true;
    }

    void onHostResume() {
        if (!running || !waitingUnknownSources) return;
        if (!canInstallPackages(host.activity())) return;

        waitingUnknownSources = false;
        if (batchMode) {
            prepareProgrammingBatch();
        } else if (pendingApk != null) {
            stageInstall(pendingApk);
        } else {
            processCurrent();
        }
    }

    void onInstallResult(int status, String message) {
        if (!running || !waitingInstall) return;

        waitingInstall = false;
        activeSessionId = -1;

        if (batchMode) {
            if (status == PackageInstaller.STATUS_SUCCESS) {
                success += batchInstallCount;
                running = false;
                host.onQueueStatus(
                        "اكتملت برمجة الجهاز",
                        "تم تثبيت " + batchInstallCount + " تطبيق بنجاح ✓",
                        100,
                        false);
                host.onQueueComplete(success, skipped, failed);
                batchPrepared.clear();
                return;
            }

            failed += batchInstallCount;
            host.onQueueError(
                    "تعذر تثبيت مجموعة التطبيقات",
                    friendlyInstallMessage(message));
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            success++;
            host.onQueueStatus(currentName(), "تم التثبيت بنجاح ✓", 100, false);
            advance();
            return;
        }

        failed++;
        host.onQueueError(
                "تعذر تثبيت " + currentName(),
                friendlyInstallMessage(message));
    }

    void retryCurrent() {
        if (!running) return;
        abandonActiveSession();
        waitingInstall = false;
        if (batchMode) {
            if (!batchPrepared.isEmpty()) stageProgrammingBatch();
            else prepareProgrammingBatch();
        } else if (pendingApk != null) {
            stageInstall(pendingApk);
        } else {
            processCurrent();
        }
    }

    void skipCurrent() {
        if (!running) return;
        abandonActiveSession();
        if (batchMode) {
            skipped += batchInstallCount;
            running = false;
            batchPrepared.clear();
            host.onQueueComplete(success, skipped, failed);
            return;
        }
        skipped++;
        advance();
    }

    void cancel() {
        abandonActiveSession();
        cleanupOwnedSessions();
        queue.clear();
        batchPrepared.clear();
        running = false;
        waitingInstall = false;
        waitingUnknownSources = false;
        batchPreparing = false;
        batchMode = false;
        pendingApk = null;
        pendingSource = "-";
    }

    void onUninstallReturned() {
        if (batchMode) return;
        if (!running || index >= queue.size()) return;

        Task task = queue.get(index);
        if (removeIndex >= task.removePackages.size()) {
            processRemovalsThenInstall();
            return;
        }

        String pkg = task.removePackages.get(removeIndex);
        if (pkg != null && !pkg.isEmpty() && isInstalled(host.activity(), pkg)) {
            host.onQueueError(
                    "تعذر حذف التطبيق القديم",
                    "التطبيق " + pkg + " ما زال موجودًا. وافق على شاشة الحذف ثم أعد المحاولة.");
            return;
        }

        removeIndex++;
        processRemovalsThenInstall();
    }

    private void prepareProgrammingBatch() {
        if (!running || waitingInstall || batchPreparing) return;
        batchPreparing = true;
        batchPrepared.clear();
        batchPrepareIndex = 0;
        batchInstallCount = 0;
        host.onQueueStatus(
                "تجهيز الجهاز",
                "يمكنك ترك الجهاز الآن؛ سيتم تجهيز جميع التطبيقات أولًا",
                0,
                true);
        prepareNextProgrammingItem();
    }

    private void prepareNextProgrammingItem() {
        if (!running || !batchMode) return;

        if (batchPrepareIndex >= queue.size()) {
            batchPreparing = false;
            batchInstallCount = batchPrepared.size();
            if (batchPrepared.isEmpty()) {
                running = false;
                host.onQueueStatus(
                        "الجهاز جاهز",
                        failed > 0 ? "لم توجد تطبيقات جاهزة للتثبيت" : "كل التطبيقات موجودة بأحدث إصدار ✓",
                        100,
                        false);
                host.onQueueComplete(success, skipped, failed);
                return;
            }
            stageProgrammingBatch();
            return;
        }

        final int position = batchPrepareIndex + 1;
        final int total = queue.size();
        final Task task = queue.get(batchPrepareIndex);

        host.onQueuePosition(position, total, task.offlineFirst ? "USB / Online" : "Online");
        host.onQueueStatus(task.app.name, "فحص الجهاز...", 0, true);

        if (isVersionSatisfied(host.activity(), task.app)) {
            skipped++;
            host.onQueueStatus(task.app.name, "موجود بأحدث إصدار ✓", 100, false);
            batchPrepareIndex++;
            host.activity().getWindow().getDecorView().postDelayed(this::prepareNextProgrammingItem, 120);
            return;
        }

        io.execute(() -> {
            try {
                ResolvedTask resolved = resolveBlocking(task, position, total);
                String issue = compatibilityIssue(resolved.apk, task.app);
                runUi(() -> {
                    if (!running || !batchMode) return;
                    if (issue != null) {
                        failed++;
                        host.onQueueStatus(task.app.name, "تم تخطي APK غير المتوافق", 100, false);
                    } else {
                        batchPrepared.add(new PreparedTask(task, resolved.apk, resolved.source));
                        host.onQueueStatus(
                                task.app.name,
                                "جاهز ضمن دفعة التثبيت ✓",
                                100,
                                false);
                    }
                    batchPrepareIndex++;
                    prepareNextProgrammingItem();
                });
            } catch (Exception e) {
                runUi(() -> {
                    if (!running || !batchMode) return;
                    failed++;
                    host.onQueueStatus(
                            task.app.name,
                            "تعذر التجهيز: " + (e.getMessage() == null ? e.toString() : e.getMessage()),
                            100,
                            false);
                    batchPrepareIndex++;
                    prepareNextProgrammingItem();
                });
            }
        });
    }

    private void stageProgrammingBatch() {
        if (!running || waitingInstall || batchPrepared.isEmpty()) return;

        if (!canInstallPackages(host.activity())) {
            waitingUnknownSources = true;
            host.onQueueStatus(
                    "صلاحية التثبيت",
                    "اسمح لـ Professor Installer بتثبيت التطبيقات",
                    100,
                    false);
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + host.activity().getPackageName()));
            host.activity().startActivity(intent);
            return;
        }

        PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
        PackageInstaller.Session parent = null;
        int parentId = -1;
        ArrayList<Integer> createdSessions = new ArrayList<>();

        try {
            PackageInstaller.SessionParams parentParams =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parentParams.setMultiPackage();
            if (Build.VERSION.SDK_INT >= 31) {
                parentParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                parentParams.setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK);
            }

            parentId = installer.createSession(parentParams);
            createdSessions.add(parentId);
            activeSessionId = parentId;
            parent = installer.openSession(parentId);

            int staged = 0;
            for (PreparedTask prepared : batchPrepared) {
                Models.AppInfo app = prepared.task.app;
                PackageInstaller.SessionParams childParams =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (app.packageName != null && !app.packageName.isEmpty()) {
                    childParams.setAppPackageName(app.packageName);
                }
                childParams.setSize(prepared.apk.length());
                if (Build.VERSION.SDK_INT >= 31) {
                    childParams.setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK);
                }

                int childId = installer.createSession(childParams);
                createdSessions.add(childId);

                PackageInstaller.Session child = installer.openSession(childId);
                try {
                    try (OutputStream output = child.openWrite("base.apk", 0, prepared.apk.length());
                         FileInputStream input = new FileInputStream(prepared.apk)) {
                        byte[] buffer = new byte[128 * 1024];
                        int read;
                        while ((read = input.read(buffer)) > 0) {
                            output.write(buffer, 0, read);
                        }
                        child.fsync(output);
                    }
                } finally {
                    try { child.close(); } catch (Exception ignored) {}
                }

                parent.addChildSessionId(childId);
                staged++;
                int progress = (int) ((staged * 100L) / batchPrepared.size());
                host.onQueuePosition(staged, batchPrepared.size(), prepared.source);
                host.onQueueStatus(
                        "تجهيز دفعة التثبيت",
                        "تم تجهيز " + staged + " من " + batchPrepared.size(),
                        progress,
                        false);
            }

            Intent callback = new Intent(host.activity(), InstallApprovalActivity.class);
            callback.setAction("INSTALL_STATUS_" + parentId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultIntent = PendingIntent.getActivity(
                    host.activity(),
                    REQUESTS.incrementAndGet(),
                    callback,
                    flags);

            waitingInstall = true;
            host.onQueuePosition(batchPrepared.size(), batchPrepared.size(), "جاهز");
            host.onQueueStatus(
                    "الجهاز جاهز للتثبيت",
                    "تم تجهيز " + batchPrepared.size()
                            + " تطبيق. ارجع للجهاز ووافق على شاشة Android لإكمال الدفعة.",
                    100,
                    false);

            parent.commit(resultIntent.getIntentSender());
        } catch (Exception e) {
            waitingInstall = false;
            for (int sessionId : createdSessions) {
                try { installer.abandonSession(sessionId); } catch (Exception ignored) {}
            }
            activeSessionId = -1;
            host.onQueueError(
                    "تعذر إنشاء دفعة التثبيت",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (parent != null) {
                try { parent.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void processCurrent() {
        if (batchMode) return;
        if (!running || waitingInstall) return;
        if (index >= queue.size()) {
            running = false;
            cleanupOwnedSessions();
            host.onQueueComplete(success, skipped, failed);
            return;
        }

        pendingApk = null;
        pendingSource = "-";
        removeIndex = 0;

        Task task = queue.get(index);
        host.onQueuePosition(
                index + 1,
                queue.size(),
                task.offlineFirst ? "USB / Online" : "Online");
        host.onQueueStatus(task.app.name, "فحص الجهاز...", 0, true);
        processRemovalsThenInstall();
    }

    private void processRemovalsThenInstall() {
        if (!running || waitingInstall || index >= queue.size()) return;
        Task task = queue.get(index);

        while (removeIndex < task.removePackages.size()) {
            String pkg = task.removePackages.get(removeIndex);
            if (pkg == null || pkg.isEmpty() || !isInstalled(host.activity(), pkg)) {
                removeIndex++;
                continue;
            }

            host.onQueueStatus(
                    task.app.name,
                    "حذف التطبيق القديم: " + pkg,
                    0,
                    true);
            host.launchUninstall(pkg);
            return;
        }

        if (isVersionSatisfied(host.activity(), task.app)) {
            skipped++;
            host.onQueueStatus(task.app.name, "موجود بأحدث إصدار ✓", 100, false);
            advance();
            return;
        }

        resolveApk(task);
    }

    private void resolveApk(Task task) {
        final int position = index + 1;
        final int total = queue.size();

        io.execute(() -> {
            try {
                ResolvedTask resolved = resolveBlocking(task, position, total);
                String issue = compatibilityIssue(resolved.apk, task.app);
                if (issue != null) {
                    runUi(() -> {
                        failed++;
                        host.onQueueError("نسخة APK غير متوافقة", issue);
                    });
                    return;
                }

                runUi(() -> {
                    if (!running || waitingInstall || index >= queue.size()) return;
                    pendingApk = resolved.apk;
                    pendingSource = resolved.source;
                    host.onQueuePosition(position, total, resolved.source);
                    prepareInstall(resolved.apk);
                });
            } catch (Exception e) {
                runUi(() -> {
                    failed++;
                    host.onQueueError(
                            "تعذر تجهيز " + task.app.name,
                            e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    private ResolvedTask resolveBlocking(Task task, int position, int total) throws Exception {
        if (task.offlineFirst) {
            File local = usb.findApk(host.activity(), task.app);
            if (local != null) {
                runUi(() -> {
                    host.onQueuePosition(position, total, "USB");
                    host.onQueueStatus(task.app.name, "تم العثور عليه على الفلاشة ✓", 100, false);
                });
                return new ResolvedTask(local, "USB");
            }
        }

        File cache = cacheFile(host.activity(), task.app);
        if (cache.isFile() && validDownloadedFile(cache, task.app)) {
            runUi(() -> {
                host.onQueuePosition(position, total, "Cache");
                host.onQueueStatus(task.app.name, "استخدام الملف المحفوظ ✓", 100, false);
            });
            return new ResolvedTask(cache, "Cache");
        }

        if (task.app.downloadUrl == null || task.app.downloadUrl.isEmpty()) {
            throw new IllegalStateException("رابط APK غير متوفر من API للتطبيق " + task.app.name);
        }

        runUi(() -> {
            host.onQueuePosition(position, total, "Online");
            host.onQueueStatus(task.app.name, "جاري التنزيل...", 0, false);
        });

        downloadBlocking(task, cache);
        return new ResolvedTask(cache, "Online");
    }

    private void downloadBlocking(Task task, File destination) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(task.app.downloadUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(true);

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status + " للتطبيق " + task.app.name);
        }

        long total = connection.getContentLengthLong();
        File part = new File(destination.getParentFile(), destination.getName() + ".part");

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(part)) {
            byte[] buffer = new byte[128 * 1024];
            long done = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int progress = (int) Math.min(100, (done * 100L) / total);
                    runUi(() -> host.onQueueStatus(
                            task.app.name,
                            "جاري التنزيل... " + progress + "%",
                            progress,
                            false));
                }
            }
            output.flush();
        } finally {
            connection.disconnect();
        }

        if (!validDownloadedFile(part, task.app)) {
            part.delete();
            throw new IllegalStateException("فشل التحقق من ملف APK للتطبيق " + task.app.name);
        }

        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("تعذر تحديث الملف الموجود في الكاش");
        }
        if (!part.renameTo(destination)) {
            throw new IllegalStateException("تعذر حفظ APK في الكاش");
        }

        runUi(() -> host.onQueueStatus(task.app.name, "اكتمل التنزيل ✓", 100, false));
    }

    private void prepareInstall(File apk) {
        if (!canInstallPackages(host.activity())) {
            waitingUnknownSources = true;
            host.onQueueStatus(
                    currentName(),
                    "اسمح لـ Professor Installer بتثبيت التطبيقات",
                    100,
                    false);
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + host.activity().getPackageName()));
            host.activity().startActivity(intent);
            return;
        }
        stageInstall(apk);
    }

    private void stageInstall(File apk) {
        if (!running || waitingInstall || index >= queue.size()) return;

        PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int createdSessionId = -1;

        try {
            Models.AppInfo app = queue.get(index).app;
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (app.packageName != null && !app.packageName.isEmpty()) {
                params.setAppPackageName(app.packageName);
            }
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }

            createdSessionId = installer.createSession(params);
            activeSessionId = createdSessionId;
            session = installer.openSession(createdSessionId);

            host.onQueuePosition(index + 1, queue.size(), pendingSource);
            host.onQueueStatus(app.name, "تجهيز شاشة التثبيت...", 100, true);

            try (OutputStream output = session.openWrite("base.apk", 0, apk.length());
                 FileInputStream input = new FileInputStream(apk)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }

            Intent callback = new Intent(host.activity(), InstallApprovalActivity.class);
            callback.setAction("INSTALL_STATUS_" + createdSessionId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultIntent = PendingIntent.getActivity(
                    host.activity(),
                    REQUESTS.incrementAndGet(),
                    callback,
                    flags);

            waitingInstall = true;
            host.onQueueStatus(
                    app.name,
                    "بانتظار ضغط Install من شاشة Android...",
                    100,
                    true);

            session.commit(resultIntent.getIntentSender());
        } catch (Exception e) {
            waitingInstall = false;
            failed++;
            if (createdSessionId >= 0) {
                try { installer.abandonSession(createdSessionId); } catch (Exception ignored) {}
            }
            activeSessionId = -1;
            host.onQueueError(
                    "تعذر بدء تثبيت " + currentName(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void abandonActiveSession() {
        if (activeSessionId < 0) return;
        try {
            host.activity().getPackageManager().getPackageInstaller().abandonSession(activeSessionId);
        } catch (Exception ignored) {
        }
        activeSessionId = -1;
    }

    private void cleanupOwnedSessions() {
        try {
            PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
            List<PackageInstaller.SessionInfo> sessions = installer.getMySessions();
            for (PackageInstaller.SessionInfo info : sessions) {
                if (info == null) continue;
                try { installer.abandonSession(info.getSessionId()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        }
        activeSessionId = -1;
    }

    private String compatibilityIssue(File apk, Models.AppInfo app) {
        try {
            PackageManager pm = host.activity().getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (archive == null || archive.packageName == null || archive.packageName.isEmpty()) {
                return "تعذر قراءة معلومات APK. الملف قد يكون تالفًا أو غير مكتمل.";
            }

            boolean packageOk = app.packageName == null || app.packageName.isEmpty();
            if (!packageOk && app.packageName.equals(archive.packageName)) packageOk = true;
            if (!packageOk) {
                for (String p : app.allPackageNames) {
                    if (archive.packageName.equals(p)) {
                        packageOk = true;
                        break;
                    }
                }
            }
            if (!packageOk) {
                return "الـPackage داخل الملف هو " + archive.packageName
                        + " بينما الـAPI يتوقع " + app.packageName + ".";
            }

            if (archive.sharedUserId != null && archive.sharedUserId.startsWith("android.uid.")) {
                return "هذا APK نسخة نظامية تستخدم shared user: " + archive.sharedUserId
                        + ". تحتاج توقيع منصة المصنع المطابق لنفس Firmware.";
            }
            return null;
        } catch (Exception e) {
            return "تعذر فحص توافق APK: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String friendlyInstallMessage(String message) {
        String text = message == null ? "" : message;
        if (text.contains("Too many active sessions")) {
            return "وجد Android جلسات تثبيت قديمة معلقة. أعد المحاولة وسيتم تنظيف الجلسات القديمة تلقائيًا.";
        }
        if (text.contains("No child sessions found")) {
            return "تعذر إنشاء مجموعة التثبيت على هذا النظام. أعد المحاولة أو استخدم التثبيت الفردي.";
        }
        if (text.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")) {
            return "الـAPK نسخة System وتوقيعها لا يطابق توقيع Firmware.";
        }
        if (text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || text.contains("signatures do not match")) {
            return "يوجد إصدار مثبت بنفس Package لكن بتوقيع مختلف. احذفه أولًا أو استخدم APK بنفس التوقيع.";
        }
        return text.isEmpty() ? "فشل PackageInstaller بدون رسالة إضافية" : text;
    }

    private void advance() {
        index++;
        removeIndex = 0;
        pendingApk = null;
        pendingSource = "-";
        waitingInstall = false;
        activeSessionId = -1;
        host.activity().getWindow().getDecorView().postDelayed(this::processCurrent, 650);
    }

    private String currentName() {
        return index >= 0 && index < queue.size()
                ? queue.get(index).app.name
                : "التطبيق";
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

    private static boolean validDownloadedFile(File file, Models.AppInfo app) {
        if (!file.isFile() || file.length() < 1024) return false;
        return app.sha256 == null
                || app.sha256.isEmpty()
                || app.sha256.equalsIgnoreCase(UsbResolver.sha256(file));
    }

    static boolean canInstallPackages(Context context) {
        return Build.VERSION.SDK_INT < 26
                || context.getPackageManager().canRequestPackageInstalls();
    }

    static boolean isInstalled(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isVersionSatisfied(Context context, Models.AppInfo app) {
        if (app == null) return false;
        ArrayList<String> packages = new ArrayList<>();
        if (app.packageName != null && !app.packageName.isEmpty()) packages.add(app.packageName);
        for (String p : app.allPackageNames) if (!packages.contains(p)) packages.add(p);

        for (String packageName : packages) {
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
                long installed = Build.VERSION.SDK_INT >= 28
                        ? info.getLongVersionCode()
                        : info.versionCode;
                if (installed >= app.versionCode) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void runUi(Runnable runnable) {
        host.activity().runOnUiThread(runnable);
    }
}
