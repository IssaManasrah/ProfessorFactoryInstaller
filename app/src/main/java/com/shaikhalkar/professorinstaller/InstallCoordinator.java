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

    private int index;
    private int removeIndex;
    private int success;
    private int skipped;
    private int failed;
    private int batchInstallCount;

    private boolean running;
    private boolean waitingInstall;
    private boolean waitingUnknownSources;
    private boolean batchMode;
    private File pendingApk;
    private String pendingSource = "-";
    private String batchSummary = "";

    InstallCoordinator(Host host) {
        this.host = host;
    }

    boolean isRunning() {
        return running;
    }

    void start(List<Task> tasks) {
        LinkedHashMap<String, Task> deduped = new LinkedHashMap<>();
        for (Task task : tasks) {
            if (task == null || task.app == null) continue;
            String key = task.app.queueKey();
            if (!deduped.containsKey(key)) deduped.put(key, task);
        }

        queue.clear();
        queue.addAll(deduped.values());
        index = 0;
        removeIndex = 0;
        success = 0;
        skipped = 0;
        failed = 0;
        batchInstallCount = 0;
        running = !queue.isEmpty();
        waitingInstall = false;
        waitingUnknownSources = false;
        pendingApk = null;
        pendingSource = "-";
        batchSummary = "";
        batchMode = canUseOneConfirmationBatch(queue);

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

        beginExecution();
    }

    void onHostResume() {
        if (!running || !waitingUnknownSources) return;
        if (!canInstallPackages(host.activity())) return;

        waitingUnknownSources = false;
        if (batchMode) {
            prepareBatch();
        } else if (pendingApk != null) {
            stageInstall(pendingApk);
        } else {
            processCurrent();
        }
    }

    void onInstallResult(int status, String message) {
        if (!running || !waitingInstall) return;
        waitingInstall = false;

        if (batchMode) {
            if (status == PackageInstaller.STATUS_SUCCESS) {
                success += batchInstallCount;
                running = false;
                host.onQueueStatus(
                        "تم تجهيز الجهاز ✓",
                        "تم تثبيت المجموعة كاملة بنجاح",
                        100,
                        false);
                host.onQueueComplete(success, skipped, failed);
            } else {
                failed += batchInstallCount;
                host.onQueueError(
                        "تعذر تثبيت مجموعة التطبيقات",
                        friendlyBatchInstallMessage(message));
            }
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
        waitingInstall = false;
        if (batchMode) {
            failed = Math.max(0, failed - batchInstallCount);
            prepareBatch();
        } else if (pendingApk != null) {
            stageInstall(pendingApk);
        } else {
            processCurrent();
        }
    }

    void skipCurrent() {
        if (!running) return;
        if (batchMode) {
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
        batchMode = false;
        pendingApk = null;
        pendingSource = "-";
        batchSummary = "";
    }

    void onUninstallReturned() {
        if (!running || batchMode || index >= queue.size()) return;

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

    private void beginExecution() {
        if (batchMode) prepareBatch();
        else processCurrent();
    }

    private boolean canUseOneConfirmationBatch(List<Task> tasks) {
        if (Build.VERSION.SDK_INT < 29 || tasks.size() < 2) return false;

        // One-confirmation mode is reserved for device programming groups.
        // Support jobs may include removals/replacements and stay sequential.
        for (Task task : tasks) {
            if (!task.offlineFirst || !task.removePackages.isEmpty()) return false;
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

                    Task task = queue.get(i);
                    final int position = i + 1;
                    runUi(() -> {
                        host.onQueuePosition(position, total, "USB / Online");
                        host.onQueueStatus(task.app.name, "فحص وتجهيز التطبيق...", 0, true);
                    });

                    if (isVersionSatisfied(host.activity(), task.app)) {
                        skipped++;
                        runUi(() -> host.onQueueStatus(
                                task.app.name,
                                "موجود بأحدث إصدار ✓",
                                100,
                                false));
                        continue;
                    }

                    ResolvedTask resolved = resolveBlocking(task, position, total);
                    String issue = compatibilityIssue(resolved.apk, task.app);
                    if (issue != null) {
                        final String problem = issue;
                        runUi(() -> host.onQueueError(
                                "تطبيق غير صالح للمجموعة: " + task.app.name,
                                problem));
                        return;
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
                runUi(() -> host.onQueueError(
                        "تعذر تجهيز مجموعة التطبيقات",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    private void stageBatch(List<ResolvedTask> ready) throws Exception {
        PackageInstaller installer = host.activity().getPackageManager().getPackageInstaller();
        ArrayList<Integer> childIds = new ArrayList<>();
        int parentId = -1;
        PackageInstaller.Session parent = null;

        try {
            PackageInstaller.SessionParams parentParams =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parentParams.setMultiPackage();

            // IMPORTANT: one Android confirmation for the whole parent session.
            // Do not request silent install and do not set the BULK install scenario.
            if (Build.VERSION.SDK_INT >= 31) {
                parentParams.setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }

            parentId = installer.createSession(parentParams);
            parent = installer.openSession(parentId);

            StringBuilder summary = new StringBuilder();
            int position = 0;
            for (ResolvedTask resolved : ready) {
                position++;
                final int displayPosition = position;
                runUi(() -> {
                    host.onQueuePosition(displayPosition, ready.size(), resolved.source);
                    host.onQueueStatus(
                            resolved.task.app.name,
                            "تجهيز ضمن مجموعة التثبيت...",
                            100,
                            true);
                });

                PackageInstaller.SessionParams childParams =
                        new PackageInstaller.SessionParams(
                                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (resolved.task.app.packageName != null
                        && !resolved.task.app.packageName.isEmpty()) {
                    childParams.setAppPackageName(resolved.task.app.packageName);
                }
                childParams.setSize(resolved.apk.length());

                int childId = installer.createSession(childParams);
                childIds.add(childId);

                PackageInstaller.Session child = installer.openSession(childId);
                try (OutputStream output = child.openWrite("base.apk", 0, resolved.apk.length());
                     FileInputStream input = new FileInputStream(resolved.apk)) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                    child.fsync(output);
                } finally {
                    child.close();
                }

                parent.addChildSessionId(childId);

                if (summary.length() > 0) summary.append("\n");
                summary.append("• ")
                        .append(resolved.task.app.name)
                        .append(" — ")
                        .append(resolved.source);
            }

            Intent callback = new Intent(host.activity(), InstallStatusReceiver.class);
            callback.setAction("INSTALL_BATCH_STATUS_" + parentId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultIntent = PendingIntent.getBroadcast(
                    host.activity(),
                    REQUESTS.incrementAndGet(),
                    callback,
                    flags);

            batchInstallCount = ready.size();
            batchSummary = summary.toString();
            waitingInstall = true;

            runUi(() -> {
                host.onQueuePosition(batchInstallCount, batchInstallCount, "USB / Online");
                host.onQueueStatus(
                        "جاهز لتثبيت " + batchInstallCount + " تطبيق",
                        "اضغط Install مرة واحدة من شاشة Android لتثبيت المجموعة كاملة",
                        100,
                        false);
            });

            parent.commit(resultIntent.getIntentSender());
        } catch (Exception e) {
            if (parentId >= 0) {
                try {
                    installer.abandonSession(parentId);
                } catch (Exception ignored) {
                }
            }
            for (Integer childId : childIds) {
                try {
                    installer.abandonSession(childId);
                } catch (Exception ignored) {
                }
            }
            throw e;
        } finally {
            if (parent != null) {
                try {
                    parent.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void processCurrent() {
        if (!running) return;
        if (index >= queue.size()) {
            running = false;
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
        if (!running || index >= queue.size()) return;
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

        resolveSingleApk(task);
    }

    private void resolveSingleApk(Task task) {
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
                    pendingApk = resolved.apk;
                    pendingSource = resolved.source;
                    host.onQueuePosition(position, total, resolved.source);
                    prepareSingleInstall(resolved.apk);
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
            if (local != null && validFile(local, task.app)) {
                runUi(() -> {
                    host.onQueuePosition(position, total, "USB");
                    host.onQueueStatus(task.app.name, "تم العثور عليه على الفلاشة ✓", 100, false);
                });
                return new ResolvedTask(task, local, "USB");
            }
        }

        File cache = cacheFile(host.activity(), task.app);
        if (cache.isFile() && validFile(cache, task.app)) {
            runUi(() -> {
                host.onQueuePosition(position, total, "Cache");
                host.onQueueStatus(task.app.name, "استخدام الملف المحفوظ ✓", 100, false);
            });
            return new ResolvedTask(task, cache, "Cache");
        }

        if (task.app.downloadUrl == null || task.app.downloadUrl.isEmpty()) {
            throw new IllegalStateException(
                    "رابط APK غير متوفر من API للتطبيق " + task.app.name);
        }

        runUi(() -> {
            host.onQueuePosition(position, total, "Online");
            host.onQueueStatus(task.app.name, "جاري التنزيل...", 0, false);
        });

        downloadBlocking(task, cache);
        return new ResolvedTask(task, cache, "Online");
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
            throw new IllegalStateException(
                    "HTTP " + status + " للتطبيق " + task.app.name);
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

        if (!validFile(part, task.app)) {
            part.delete();
            throw new IllegalStateException(
                    "فشل التحقق من ملف APK للتطبيق " + task.app.name);
        }

        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("تعذر تحديث الملف الموجود في الكاش");
        }
        if (!part.renameTo(destination)) {
            throw new IllegalStateException("تعذر حفظ APK في الكاش");
        }

        runUi(() -> host.onQueueStatus(task.app.name, "اكتمل التنزيل ✓", 100, false));
    }

    private void prepareSingleInstall(File apk) {
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
        if (!running || index >= queue.size()) return;

        PackageInstaller.Session session = null;
        try {
            PackageInstaller installer =
                    host.activity().getPackageManager().getPackageInstaller();
            Models.AppInfo app = queue.get(index).app;

            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (app.packageName != null && !app.packageName.isEmpty()) {
                params.setAppPackageName(app.packageName);
            }
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }

            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

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

            Intent callback = new Intent(host.activity(), InstallStatusReceiver.class);
            callback.setAction("INSTALL_STATUS_" + sessionId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultIntent = PendingIntent.getBroadcast(
                    host.activity(),
                    REQUESTS.incrementAndGet(),
                    callback,
                    flags);

            waitingInstall = true;
            host.onQueueStatus(
                    app.name,
                    "بانتظار موافقة Android...",
                    100,
                    true);
            session.commit(resultIntent.getIntentSender());
        } catch (Exception e) {
            waitingInstall = false;
            failed++;
            host.onQueueError(
                    "تعذر بدء تثبيت " + currentName(),
                    e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String compatibilityIssue(File apk, Models.AppInfo app) {
        try {
            PackageManager pm = host.activity().getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (archive == null || archive.packageName == null || archive.packageName.isEmpty()) {
                return "تعذر قراءة معلومات APK. الملف قد يكون تالفًا أو غير مكتمل.";
            }

            if (app.packageName != null
                    && !app.packageName.isEmpty()
                    && !app.packageName.equals(archive.packageName)) {
                return "الـPackage داخل الملف هو " + archive.packageName
                        + " بينما الـAPI يتوقع " + app.packageName
                        + ". راجع ملف التطبيق في اللوحة.";
            }

            if (archive.sharedUserId != null
                    && archive.sharedUserId.startsWith("android.uid.")) {
                return "هذا APK نسخة نظامية تستخدم shared user: " + archive.sharedUserId
                        + ". تحتاج توقيع منصة المصنع المطابق لنفس Firmware.";
            }

            return null;
        } catch (Exception e) {
            return "تعذر فحص توافق APK: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String friendlyBatchInstallMessage(String message) {
        String text = message == null ? "" : message;
        StringBuilder out = new StringBuilder();

        if (text.contains("INSTALL_FAILED_VERIFICATION_FAILURE")
                || text.contains("Install not allowed")) {
            out.append("Android رفض التحقق من مجموعة التثبيت. هذه النسخة تستخدم موافقة Android واحدة فقط ")
                    .append("ولا تطلب Silent Install أو BULK mode.");
        } else if (text.isEmpty()) {
            out.append("فشلت مجموعة التثبيت بدون رسالة إضافية من Android.");
        } else {
            out.append(text);
        }

        if (!batchSummary.isEmpty()) {
            out.append("\n\nالتطبيقات التي كانت ضمن المجموعة:\n").append(batchSummary);
        }
        return out.toString();
    }

    private String friendlyInstallMessage(String message) {
        String text = message == null ? "" : message;

        if (text.contains("INSTALL_FAILED_VERIFICATION_FAILURE")
                || text.contains("Install not allowed")) {
            return "Android رفض التثبيت أثناء التحقق من الحزمة.";
        }

        if (text.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")
                || text.contains("shared user android.uid.system")) {
            return "الـAPK نسخة System وتوقيعها لا يطابق توقيع Firmware.";
        }

        if (text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || text.contains("signatures do not match")) {
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

    private static boolean validFile(File file, Models.AppInfo app) {
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
        if (app.packageName == null || app.packageName.isEmpty()) return false;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(app.packageName, 0);
            long installed = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode;
            return installed >= app.versionCode;
        } catch (Exception e) {
            return false;
        }
    }

    private void runUi(Runnable runnable) {
        host.activity().runOnUiThread(runnable);
    }
}
