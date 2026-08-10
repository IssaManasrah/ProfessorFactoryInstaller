package com.shaikhalkar.professorinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;

/**
 * Foreground trampoline for PackageInstaller callbacks.
 *
 * Why this exists:
 * Some Android TV / Google TV builds block an installer confirmation Activity
 * when it is launched from a background BroadcastReceiver. Using an Activity
 * as the PackageInstaller status receiver keeps the user-action handoff in the
 * foreground and makes one-confirmation multi-package installs much more reliable.
 */
public class InstallApprovalActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }

            if (confirm == null) {
                forwardResult(
                        PackageInstaller.STATUS_FAILURE,
                        "Android طلب موافقة المستخدم لكنه لم يرسل شاشة التثبيت");
                return;
            }

            try {
                startActivity(confirm);
                finish();
            } catch (Exception e) {
                forwardResult(
                        PackageInstaller.STATUS_FAILURE,
                        "تعذر فتح شاشة تثبيت Android: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            return;
        }

        forwardResult(status, message == null ? "" : message);
    }

    private void forwardResult(int status, String message) {
        Intent ui = new Intent(this, ProfessorMainActivity.class);
        ui.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ui.putExtra(InstallStatusReceiver.EXTRA_RESULT_STATUS, status);
        ui.putExtra(InstallStatusReceiver.EXTRA_RESULT_MESSAGE, message == null ? "" : message);
        startActivity(ui);
        finish();
    }
}
