package com.shaikhalkar.professorinstaller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

final class UsbResolver {
    private static final String FOLDER = "PROFESSOR_APPS";

    File findApk(Context context, Models.AppInfo app) {
        if (app == null) return null;

        File best = null;
        long bestVersion = -1;

        for (File root : candidateRoots(context)) {
            File folder = new File(root, FOLDER);
            if (!folder.isDirectory() || !folder.canRead()) continue;

            // Fast path: use the exact API filename when present, but do not reject a
            // valid USB APK merely because the filename or SHA metadata changed.
            if (app.fileName != null && !app.fileName.isEmpty()) {
                File exact = new File(folder, app.fileName);
                if (exact.isFile() && exact.canRead() && matchesApp(context, exact, app)) {
                    return exact;
                }
            }

            // Robust path: scan every APK in PROFESSOR_APPS and match the APK's real
            // package name + version. This lets employees rename files on the USB.
            File[] files = folder.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f == null || !f.isFile() || !f.canRead()) continue;
                String n = f.getName().toLowerCase();
                if (!n.endsWith(".apk")) continue;

                PackageInfo info = archiveInfo(context, f);
                if (info == null || info.packageName == null) continue;
                if (!packageMatches(app, info.packageName)) continue;

                long version = versionCode(info);
                if (app.versionCode > 0 && version < app.versionCode) continue;

                if (best == null || version > bestVersion) {
                    best = f;
                    bestVersion = version;
                }
            }
        }

        return best;
    }

    private boolean matchesApp(Context context, File apk, Models.AppInfo app) {
        PackageInfo info = archiveInfo(context, apk);
        if (info == null || info.packageName == null) return false;
        if (!packageMatches(app, info.packageName)) return false;
        long version = versionCode(info);
        return app.versionCode <= 0 || version >= app.versionCode;
    }

    private boolean packageMatches(Models.AppInfo app, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (packageName.equals(app.packageName)) return true;
        for (String p : app.allPackageNames) {
            if (packageName.equals(p)) return true;
        }
        return false;
    }

    private PackageInfo archiveInfo(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private long versionCode(PackageInfo info) {
        if (info == null) return -1;
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        //noinspection deprecation
        return info.versionCode;
    }

    private List<File> candidateRoots(Context context) {
        ArrayList<File> out = new ArrayList<>();
        try {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    File dir = v.getDirectory();
                    if (dir != null && v.isRemovable() && !out.contains(dir)) out.add(dir);
                }
            }
        } catch (Throwable ignored) {}

        addChildren(out, new File("/storage"));
        addChildren(out, new File("/mnt/media_rw"));
        return out;
    }

    private void addChildren(List<File> out, File parent) {
        try {
            File[] children = parent.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (f == null || !f.isDirectory()) continue;
                String n = f.getName();
                if ("emulated".equalsIgnoreCase(n) || "self".equalsIgnoreCase(n)) continue;
                if (!out.contains(f)) out.add(f);
            }
        } catch (Throwable ignored) {}
    }

    static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
