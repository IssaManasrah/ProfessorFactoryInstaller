package com.shaikhalkar.professorinstaller;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/**
 * Scoped Auto Click helper for Android / Google TV's package installer.
 *
 * The service only acts while Professor Installer explicitly arms it for an
 * install session, and only inside known Android Package Installer packages.
 * It presses Install / Update and then Done when those controls appear. It
 * never clicks arbitrary controls in other applications and intentionally does
 * not bypass security-warning or Play Protect prompts.
 */
public class AutoInstallAccessibilityService extends AccessibilityService {
    private static final String PREFS = "professor_auto_install";
    private static final String KEY_ARM_UNTIL = "arm_until";
    private static final long DEFAULT_ARM_MS = 180_000L;
    private static final long CLICK_DEBOUNCE_MS = 700L;

    private long lastClickAt;

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
        if (event == null || !isArmed()) return;

        CharSequence packageName = event.getPackageName();
        if (!isInstallerPackage(packageName)) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastClickAt < CLICK_DEBOUNCE_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // Prefer the final Done button first. On some PackageInstaller builds
        // it appears immediately after success while the previous Install node
        // may still briefly exist in the accessibility tree.
        if (clickMatching(root, MatchMode.DONE)) {
            lastClickAt = now;
            disarm(this);
            return;
        }

        if (clickMatching(root, MatchMode.INSTALL)) {
            // Keep the service armed after Install. Some Android TV installers
            // stay in the foreground and require Done before our queue can be
            // seen again. The PackageInstaller callback will also disarm us.
            lastClickAt = now;
        }
    }

    private boolean isArmed() {
        long until = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_ARM_UNTIL, 0L);
        if (until <= System.currentTimeMillis()) {
            if (until != 0L) disarm(this);
            return false;
        }
        return true;
    }

    private boolean isInstallerPackage(CharSequence packageName) {
        if (packageName == null) return false;
        String p = packageName.toString();
        return "com.android.packageinstaller".equals(p)
                || "com.google.android.packageinstaller".equals(p)
                || "com.google.android.permissioncontroller".equals(p)
                || "com.android.permissioncontroller".equals(p);
    }

    private boolean clickMatching(AccessibilityNodeInfo node, MatchMode mode) {
        if (node == null) return false;

        CharSequence textValue = node.getText();
        CharSequence descValue = node.getContentDescription();
        String text = normalize(textValue == null ? "" : textValue.toString());
        String desc = normalize(descValue == null ? "" : descValue.toString());
        String viewId = node.getViewIdResourceName();
        if (viewId == null) viewId = "";
        viewId = viewId.toLowerCase(Locale.ROOT);

        boolean matches;
        if (mode == MatchMode.INSTALL) {
            matches = isInstallLabel(text)
                    || isInstallLabel(desc)
                    || viewId.contains("install_button")
                    || viewId.endsWith(":id/install")
                    || viewId.contains("update_button");
        } else {
            matches = isDoneLabel(text)
                    || isDoneLabel(desc)
                    || viewId.contains("done_button")
                    || viewId.endsWith(":id/done");
        }

        if (matches && node.isEnabled()) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable != null
                    && clickable.isEnabled()
                    && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && clickMatching(child, mode)) return true;
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

    private boolean isDoneLabel(String value) {
        if (value == null || value.isEmpty()) return false;
        return "done".equals(value)
                || "finish".equals(value)
                || "تم".equals(value)
                || "إنهاء".equals(value)
                || "انهاء".equals(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onInterrupt() {
        // Event driven; there is no long-running action to cancel.
    }

    private enum MatchMode {
        INSTALL,
        DONE
    }
}
