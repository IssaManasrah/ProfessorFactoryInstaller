package com.shaikhalkar.professorinstaller;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.util.concurrent.atomic.AtomicInteger;

public class InstallStatusReceiver extends BroadcastReceiver {
    static final String ACTION_RESULT = "com.shaikhalkar.professorinstaller.INSTALL_RESULT";
    static final String EXTRA_RESULT_STATUS = "result_status";
    static final String EXTRA_RESULT_MESSAGE = "result_message";

    private static final AtomicInteger ACTIVITY_REQUESTS = new AtomicInteger(24000);

    @Override
    public void onReceive(Context context, Intent intent) {
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

            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launchActivitySafely(context, confirm)) {
                    launchResultUi(
                            context,
                            PackageInstaller.STATUS_FAILURE,
                            "تعذر فتح شاشة تثبيت Android");
                }
            } else {
                launchResultUi(
                        context,
                        PackageInstaller.STATUS_FAILURE,
                        "Android طلب موافقة المستخدم لكنه لم يرسل شاشة التثبيت");
            }
            return;
        }

        launchResultUi(context, status, message == null ? "" : message);
    }

    private static void launchResultUi(Context context, int status, String message) {
        Intent ui = new Intent(context, MainActivity.class);
        ui.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ui.putExtra(EXTRA_RESULT_STATUS, status);
        ui.putExtra(EXTRA_RESULT_MESSAGE, message == null ? "" : message);

        if (!launchActivitySafely(context, ui)) {
            try {
                context.startActivity(ui);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Android 14+ restricts activity launches that originate from background callbacks.
     * PackageInstaller delivers STATUS_PENDING_USER_ACTION through this BroadcastReceiver,
     * so we explicitly grant background-activity-launch privileges to the PendingIntent.
     */
    private static boolean launchActivitySafely(Context context, Intent target) {
        try {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            android.os.Bundle creatorOptions = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                creatorOptions = options.toBundle();
            }

            PendingIntent pending = PendingIntent.getActivity(
                    context,
                    ACTIVITY_REQUESTS.incrementAndGet(),
                    target,
                    flags,
                    creatorOptions);

            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions sendOptions = ActivityOptions.makeBasic();
                sendOptions.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pending.send(
                        context,
                        0,
                        null,
                        null,
                        null,
                        null,
                        sendOptions.toBundle());
            } else {
                pending.send();
            }
            return true;
        } catch (Exception first) {
            try {
                context.startActivity(target);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
