package com.shaikhalkar.professorinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Foreground trampoline for PackageInstaller callbacks.
 *
 * Some Android TV / Google TV builds block an installer confirmation Activity
 * when it is launched from a background BroadcastReceiver. Using an Activity
 * as the PackageInstaller status receiver keeps the user-action handoff in the
 * foreground. The current installer uses one PackageInstaller session per APK.
 */
public class InstallApprovalActivity extends Activity {

    private static final String ACTION_PREFIX = "INSTALL_STATUS_";
    private static final int REQ_ACCESSIBILITY = 7201;

    private Intent pendingConfirmation;
    private int pendingSessionId = -1;

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ACCESSIBILITY || pendingConfirmation == null) return;

        Intent confirm = pendingConfirmation;
        pendingConfirmation = null;
        int sessionId = pendingSessionId;
        pendingSessionId = -1;

        if (AutoInstallAccessibilityService.isEnabled(this)) {
            launchInstaller(confirm, true, sessionId);
        } else {
            // User returned without enabling Auto Click. Continue manually so
            // programming never gets stuck.
            launchInstaller(confirm, false, sessionId);
        }
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
        int sessionId = sessionIdFromAction(intent.getAction());

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }

            if (confirm == null) {
                abandonQuietly(sessionId);
                forwardResult(
                        PackageInstaller.STATUS_FAILURE,
                        "Android طلب موافقة المستخدم لكنه لم يرسل شاشة التثبيت");
                return;
            }

            if (AutoInstallAccessibilityService.isEnabled(this)) {
                launchInstaller(confirm, true, sessionId);
            } else {
                offerAutoClickSetup(confirm, sessionId);
            }
            return;
        }

        AutoInstallAccessibilityService.disarm(this);

        if (status != PackageInstaller.STATUS_SUCCESS) {
            // Defensive cleanup: some OEM PackageInstaller builds keep a failed
            // session alive. Leaving it behind eventually causes
            // "Too many active sessions for UID" after repeated retries.
            abandonQuietly(sessionId);
        }

        forwardResult(status, message == null ? "" : message);
    }

    private void offerAutoClickSetup(Intent confirm, int sessionId) {
        pendingConfirmation = confirm;
        pendingSessionId = sessionId;

        new AlertDialog.Builder(this)
                .setTitle("Auto Click للتثبيت")
                .setMessage(
                        "لتثبيت التطبيقات بشكل شبه أوتوماتيكي، فعّل خدمة Professor Auto Install مرة واحدة من إمكانية الوصول. "
                                + "بعدها سيضغط التطبيق زر Install / تثبيت تلقائيًا عند كل عملية برمجة.\n\n"
                                + "إذا اخترت التثبيت اليدوي سيكمل Professor Installer بشكل طبيعي بدون Auto Click.")
                .setCancelable(false)
                .setPositiveButton("تفعيل Auto Click", (d, w) -> {
                    try {
                        startActivityForResult(
                                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                REQ_ACCESSIBILITY);
                    } catch (Exception e) {
                        Intent manual = pendingConfirmation;
                        pendingConfirmation = null;
                        int id = pendingSessionId;
                        pendingSessionId = -1;
                        launchInstaller(manual, false, id);
                    }
                })
                .setNegativeButton("تثبيت يدوي", (d, w) -> {
                    Intent manual = pendingConfirmation;
                    pendingConfirmation = null;
                    int id = pendingSessionId;
                    pendingSessionId = -1;
                    launchInstaller(manual, false, id);
                })
                .show();
    }

    private void launchInstaller(Intent confirm, boolean autoClick, int sessionId) {
        if (confirm == null) {
            abandonQuietly(sessionId);
            forwardResult(PackageInstaller.STATUS_FAILURE, "تعذر فتح شاشة التثبيت");
            return;
        }

        if (autoClick) {
            AutoInstallAccessibilityService.arm(this);
        } else {
            AutoInstallAccessibilityService.disarm(this);
        }

        try {
            startActivity(confirm);
            finish();
        } catch (Exception e) {
            AutoInstallAccessibilityService.disarm(this);
            abandonQuietly(sessionId);
            forwardResult(
                    PackageInstaller.STATUS_FAILURE,
                    "تعذر فتح شاشة تثبيت Android: "
                            + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private int sessionIdFromAction(String action) {
        if (action == null || !action.startsWith(ACTION_PREFIX)) return -1;
        try {
            return Integer.parseInt(action.substring(ACTION_PREFIX.length()));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void abandonQuietly(int sessionId) {
        if (sessionId < 0) return;
        try {
            getPackageManager().getPackageInstaller().abandonSession(sessionId);
        } catch (Exception ignored) {
        }
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
