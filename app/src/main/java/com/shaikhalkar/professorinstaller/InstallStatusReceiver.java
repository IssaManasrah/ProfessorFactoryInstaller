package com.shaikhalkar.professorinstaller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public class InstallStatusReceiver extends BroadcastReceiver {
    static final String ACTION_RESULT = "com.shaikhalkar.professorinstaller.INSTALL_RESULT";
    static final String EXTRA_RESULT_STATUS = "result_status";
    static final String EXTRA_RESULT_MESSAGE = "result_message";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }

        Intent ui = new Intent(context, MainActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ui.putExtra(EXTRA_RESULT_STATUS, status);
        ui.putExtra(EXTRA_RESULT_MESSAGE, message == null ? "" : message);
        context.startActivity(ui);
    }
}
