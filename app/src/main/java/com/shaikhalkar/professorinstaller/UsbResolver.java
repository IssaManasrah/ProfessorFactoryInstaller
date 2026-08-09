package com.shaikhalkar.professorinstaller;

import android.content.Context;
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
        if (app == null || app.fileName == null || app.fileName.isEmpty()) return null;
        for (File root : candidateRoots(context)) {
            File f = new File(new File(root, FOLDER), app.fileName);
            if (f.isFile() && f.canRead()) {
                if (app.sha256 == null || app.sha256.isEmpty() || app.sha256.equalsIgnoreCase(sha256(f))) return f;
            }
        }
        return null;
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

        File storage = new File("/storage");
        File[] children = storage.listFiles();
        if (children != null) {
            for (File f : children) {
                String n = f.getName();
                if (f.isDirectory() && !"emulated".equalsIgnoreCase(n) && !"self".equalsIgnoreCase(n) && !out.contains(f)) out.add(f);
            }
        }
        return out;
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
