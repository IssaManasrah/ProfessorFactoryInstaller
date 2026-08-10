package com.shaikhalkar.professorinstaller;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Tiny foreground bridge used only to launch Android's PackageInstaller confirmation UI.
 * This avoids background-activity-launch restrictions when the PackageInstaller callback
 * first arrives through a BroadcastReceiver.
 */
public class InstallBridgeActivity extends Activity {
    static final String EXTRA_CONFIRM_INTENT = "confirm_intent";

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

    private void handle(Intent source) {
        Intent confirm = null;
        if (source != null) {
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = source.getParcelableExtra(EXTRA_CONFIRM_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirm = source.getParcelableExtra(EXTRA_CONFIRM_INTENT);
            }
        }

        if (confirm == null) {
            finish();
            return;
        }

        try {
            confirm.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            startActivity(confirm);
        } catch (Exception first) {
            try {
                confirm.setFlags(confirm.getFlags() & ~Intent.FLAG_ACTIVITY_FORWARD_RESULT);
                startActivity(confirm);
            } catch (Exception ignored) {
                Intent ui = new Intent(this, ProfessorMainActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                ui.putExtra(
                        InstallStatusReceiver.EXTRA_RESULT_STATUS,
                        android.content.pm.PackageInstaller.STATUS_FAILURE);
                ui.putExtra(
                        InstallStatusReceiver.EXTRA_RESULT_MESSAGE,
                        "تعذر فتح شاشة تثبيت Android");
                startActivity(ui);
            }
        }
        finish();
    }
}
