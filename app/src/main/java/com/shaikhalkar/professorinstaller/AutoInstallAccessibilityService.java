package com.shaikhalkar.professorinstaller;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/**
 * Optional Auto Click for Android's package installer.
 *
 * The service only acts for a short, explicitly armed window created by
 * Professor Installer immediately before it launches Android's installer.
 * It only clicks Install/Update buttons inside known Package Installer
 * packages; it does not click arbitrary UI in other apps.
 */
public class AutoInstallAccessibilityService extends AccessibilityService {
    private static final String PREFS = "professor_auto_install";
    private static final String KEY_ARM_UNTIL = "arm_until";
    private static final long DEFAULT_ARM_MS = 90_000L;

    public static void arm(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_ARM_UNTIL, System.currentTimeMillis() + DEFAULT_ARM_MS)
                .apply();
    }

    public static void disarm(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_ARM_UNTIL, 0L)
                .apply();
    }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.trim().isEmpty()) return false;
        String me = new ComponentName(context, AutoInstallAccessibilityService.class)
                .flattenToString()
                .toLowerCase(Locale.ROOT);
        return enabled.toLowerCase(Locale.ROOT).contains(me);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        long until = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_ARM_UNTIL, 0L);
        if (until <= System.currentTimeMillis()) return;

        CharSequence packageName = event.getPackageName();
        if (!isInstallerPackage(packageName)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (clickInstallOrUpdate(root)) {
            // One installer confirmation per armed PackageInstaller session.
            disarm(this);
        }
    }

    private boolean isInstallerPackage(CharSequence packageName) {
        if (packageName == null) return false;
        String p = packageName.toString();
        return "com.android.packageinstaller".equals(p)
                || "com.google.android.packageinstaller".equals(p)
                || "com.google.android.permissioncontroller".equals(p);
    }

    private boolean clickInstallOrUpdate(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence textValue = node.getText();
        CharSequence descValue = node.getContentDescription();
        String text = normalize(textValue == null ? "" : textValue.toString());
        String desc = normalize(descValue == null ? "" : descValue.toString());
        String viewId = node.getViewIdResourceName();
        if (viewId == null) viewId = "";
        viewId = viewId.toLowerCase(Locale.ROOT);

        boolean installText = isInstallLabel(text) || isInstallLabel(desc);
        boolean installId = viewId.contains("install_button")
                || viewId.endsWith(":id/install")
                || viewId.contains("update_button");

        if ((installText || installId) && node.isEnabled()) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable != null
                    && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && clickInstallOrUpdate(child)) return true;
        }
        return false;
    }

    private boolean isInstallLabel(String value) {
        if (value == null || value.isEmpty()) return false;
        return "install".equals(value)
                || "update".equals(value)
                || "تثبيت".equals(value)
                || "تحديث".equals(value)
                || value.startsWith("install ")
                || value.startsWith("update ");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt; the service is event driven.
    }
}
