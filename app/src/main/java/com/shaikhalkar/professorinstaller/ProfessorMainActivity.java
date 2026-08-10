package com.shaikhalkar.professorinstaller;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

public class ProfessorMainActivity extends MainActivity {
    private static final int REQ_READ_STORAGE = 7101;

    private boolean storageDialogShowing;
    private boolean storageSettingsOpened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (storageSettingsOpened) {
            storageSettingsOpened = false;
            if (hasFileAccess()) {
                Toast.makeText(
                        this,
                        "تم تفعيل صلاحية الملفات ✓ — الفلاشة جاهزة",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(
                        this,
                        "لم يتم تفعيل صلاحية الملفات — سيتم استخدام Online عند عدم توفر الوصول للفلاشة",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        View root = getWindow().getDecorView();
        boolean home = containsText(root, "برمجة الأجهزة")
                && containsText(root, "تصفح التطبيقات")
                && containsText(root, "الدعم");

        if (home) {
            finish();
            return;
        }

        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && isActivationKey(event.getKeyCode()) && isProgrammingCardFocused()) {
            if (!hasFileAccess()) {
                // Consume DOWN and UP so the original programming click cannot continue
                // until the employee has dealt with the USB/file access request.
                if (event.getAction() == KeyEvent.ACTION_UP) requestFileAccess();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isActivationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean isProgrammingCardFocused() {
        View focused = getCurrentFocus();
        return focused != null && containsText(focused, "برمجة الأجهزة");
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFileAccess() {
        if (hasFileAccess() || storageDialogShowing) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_READ_STORAGE);
            return;
        }

        storageDialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("صلاحية الملفات والفلاشة")
                .setMessage(
                        "حتى يبحث Professor Installer تلقائيًا داخل مجلد PROFESSOR_APPS على الفلاشة، "
                                + "يحتاج صلاحية الوصول للملفات.\n\n"
                                + "اضغط «تفعيل الآن»، ثم فعّل خيار السماح بإدارة كل الملفات للتطبيق. "
                                + "بعد الرجوع سيكمل التطبيق بشكل طبيعي.")
                .setNegativeButton("Online فقط", (d, w) -> storageDialogShowing = false)
                .setPositiveButton("تفعيل الآن", (d, w) -> {
                    storageDialogShowing = false;
                    openAllFilesAccessSettings();
                })
                .create();
        dialog.setOnDismissListener(d -> storageDialogShowing = false);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void openAllFilesAccessSettings() {
        storageSettingsOpened = true;
        try {
            Intent appIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            appIntent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(appIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
        } catch (SecurityException ignored) {
        }

        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            return;
        } catch (ActivityNotFoundException ignored) {
        } catch (SecurityException ignored) {
        }

        // Last-resort OEM fallback: at least take the employee directly to this app's
        // settings page instead of asking them to find Professor Installer manually.
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            details.setData(Uri.parse("package:" + getPackageName()));
            startActivity(details);
        } catch (Exception e) {
            storageSettingsOpened = false;
            Toast.makeText(
                    this,
                    "هذا النظام لا يوفر شاشة صلاحية الملفات للتطبيق",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_READ_STORAGE) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(
                this,
                granted
                        ? "تم تفعيل صلاحية الملفات ✓ — الفلاشة جاهزة"
                        : "لم يتم تفعيل صلاحية الملفات — سيتم استخدام Online",
                Toast.LENGTH_LONG).show();
    }

    private boolean containsText(View view, String wanted) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && wanted.contentEquals(text)) return true;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), wanted)) return true;
            }
        }
        return false;
    }
}
