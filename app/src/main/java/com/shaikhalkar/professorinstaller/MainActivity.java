package com.shaikhalkar.professorinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;

public class MainActivity extends Activity implements InstallCoordinator.Host {
    private static final int NAVY = Color.rgb(5, 15, 29);
    private static final int NAVY_2 = Color.rgb(9, 26, 46);
    private static final int PANEL = Color.rgb(12, 35, 60);
    private static final int PANEL_FOCUS = Color.rgb(19, 55, 91);
    private static final int GOLD = Color.rgb(225, 188, 75);
    private static final int CYAN = Color.rgb(72, 196, 230);
    private static final int WHITE = Color.rgb(247, 250, 254);
    private static final int MUTED = Color.rgb(166, 184, 205);
    private static final int GREEN = Color.rgb(87, 207, 146);
    private static final int REQ_UNINSTALL = 6200;

    // SHA-256 of the employee PIN. The PIN itself is never displayed or stored as plain text.
    private static final String EMPLOYEE_PIN_HASH =
            "ed946f65d2c785d90e827c5ffd879ce3b49c68d4c88013074176a7e73bc58bcf";

    private final ApiClient api = new ApiClient();
    private InstallCoordinator coordinator;

    private TextView queueTitle;
    private TextView queueDetail;
    private TextView queuePosition;
    private TextView queueSource;
    private ProgressBar queueProgress;
    private Button queueAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        coordinator = new InstallCoordinator(this);
        handleInstallResultIntent(getIntent());
        showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallResultIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (coordinator != null) coordinator.onHostResume();
    }

    private void handleInstallResultIntent(Intent intent) {
        if (intent == null || coordinator == null) return;
        if (!intent.hasExtra(InstallStatusReceiver.EXTRA_RESULT_STATUS)) return;
        int status = intent.getIntExtra(
                InstallStatusReceiver.EXTRA_RESULT_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(InstallStatusReceiver.EXTRA_RESULT_MESSAGE);
        intent.removeExtra(InstallStatusReceiver.EXTRA_RESULT_STATUS);
        intent.removeExtra(InstallStatusReceiver.EXTRA_RESULT_MESSAGE);
        coordinator.onInstallResult(status, msg);
    }

    private void showHome() {
        LinearLayout screen = baseScreen();
        addBrandHeader(screen);

        TextView title = label("Professor Installer", 28, WHITE, Typeface.BOLD);
        TextView subtitle = label("برمجة أسرع • تطبيقات منظمة • دعم مباشر", 16, MUTED, Typeface.NORMAL);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.addView(title, marginTop(16));
        screen.addView(subtitle, marginTop(4));

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setGravity(Gravity.CENTER);
        cards.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        cards.setPadding(0, dp(18), 0, dp(10));

        View programming = homeCard(
                "⚙",
                "برمجة الأجهزة",
                "للموظفين فقط",
                "اختر الموديل واترك Professor Installer يجهز الجهاز");
        View browse = homeCard(
                "▦",
                "تصفح التطبيقات",
                "تطبيقات Professor",
                "التطبيقات الظاهرة فقط حسب لوحة التحكم");
        View support = homeCard(
                "?",
                "الدعم",
                "Service Code",
                "أدخل كود الدعم ونفّذ المهمة المطلوبة");

        programming.setOnClickListener(v -> showPinDialog());
        browse.setOnClickListener(v -> openBrowser());
        support.setOnClickListener(v -> showSupportCodeDialog());

        cards.addView(programming, homeCardParams());
        cards.addView(browse, homeCardParams());
        cards.addView(support, homeCardParams());
        screen.addView(cards, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = label("PROFESSOR  •  SHAIKH ALKAR  •  GOOGLE TV", 13, MUTED, Typeface.BOLD);
        footer.setGravity(Gravity.CENTER);
        screen.addView(footer, marginTop(8));

        setContentView(screen);
        programming.requestFocus();
    }

    private void showPinDialog() {
        EditText input = dialogInput("••••");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("برمجة الأجهزة")
                .setMessage("أدخل الرقم السري")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("دخول", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String entered = input.getText().toString().trim();
                if (EMPLOYEE_PIN_HASH.equals(sha256(entered))) {
                    input.setText("");
                    dialog.dismiss();
                    openProgrammingModels();
                } else {
                    input.setText("");
                    input.setError("الرقم السري غير صحيح");
                }
            });
            ok.requestFocus();
        });
        dialog.show();
    }

    private void openProgrammingModels() {
        showLoading("برمجة الأجهزة", "تحميل الموديلات من السيرفر...");
        api.fetchCatalog("JO", new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject json) {
                showModels(Models.Catalog.fromJson(json));
            }

            @Override public void onError(String message) {
                showApiError("تعذر تحميل الموديلات", message, MainActivity.this::openProgrammingModels);
            }
        });
    }

    private void showModels(Models.Catalog catalog) {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, "برمجة الأجهزة", "اختر الموديل المطلوب", this::showHome);

        if (catalog.groups.isEmpty()) {
            screen.addView(emptyState("لا توجد موديلات مفعّلة في API"));
            setContentView(screen);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = verticalList();
        View first = null;
        for (Models.GroupInfo group : catalog.groups) {
            View card = listCard(
                    group.name,
                    group.appIds.size() + " تطبيق",
                    "ابدأ البرمجة");
            card.setOnClickListener(v -> startGroup(catalog, group));
            list.addView(card, listCardParams());
            if (first == null) first = card;
        }
        scroll.addView(list);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
        if (first != null) first.requestFocus();
    }

    private void startGroup(Models.Catalog catalog, Models.GroupInfo group) {
        ArrayList<InstallCoordinator.Task> tasks = new ArrayList<>();
        for (String id : group.appIds) {
            Models.AppInfo app = catalog.findApp(id);
            if (app != null) tasks.add(new InstallCoordinator.Task(app, true));
        }
        if (tasks.isEmpty()) {
            toast("لا توجد تطبيقات صالحة لهذا الموديل");
            return;
        }

        String detail = "عدد التطبيقات: " + tasks.size()
                + "\n\nسيتم البحث أولًا داخل PROFESSOR_APPS على الفلاشة، ثم Online عند الحاجة."
                + "\n\nعلى Android 10+ سيحاول التطبيق تجميع التثبيتات في دفعة واحدة لتقليل عدد مرات الموافقة.";

        new AlertDialog.Builder(this)
                .setTitle(group.name)
                .setMessage(detail)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("ابدأ البرمجة", (d, w) -> coordinator.start(tasks))
                .show();
    }

    private void openBrowser() {
        showLoading("تصفح التطبيقات", "تحميل التطبيقات الظاهرة...");
        api.fetchCatalog("JO", new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject json) {
                showApps(Models.Catalog.fromJson(json));
            }

            @Override public void onError(String message) {
                showApiError("تعذر تحميل التطبيقات", message, MainActivity.this::openBrowser);
            }
        });
    }

    private void showApps(Models.Catalog catalog) {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, "تصفح التطبيقات", "التطبيقات المسموح بعرضها حسب API", this::showHome);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = verticalList();
        View first = null;
        for (Models.AppInfo app : catalog.apps) {
            boolean installed = InstallCoordinator.isVersionSatisfied(this, app);
            String meta = app.versionName.isEmpty() ? "" : "الإصدار " + app.versionName;
            String status = installed ? "مثبت ✓" : "تثبيت / تحديث";
            View card = listCard(app.name, meta, status);
            card.setOnClickListener(v -> {
                ArrayList<InstallCoordinator.Task> tasks = new ArrayList<>();
                tasks.add(new InstallCoordinator.Task(app, false));
                coordinator.start(tasks);
            });
            list.addView(card, listCardParams());
            if (first == null) first = card;
        }

        if (catalog.apps.isEmpty()) list.addView(emptyState("لا توجد تطبيقات ظاهرة حاليًا"));
        scroll.addView(list);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
        if (first != null) first.requestFocus();
    }

    private void showSupportCodeDialog() {
        EditText input = dialogInput("مثال: 583291");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("الدعم")
                .setMessage("أدخل كود الدعم")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String code = input.getText().toString().replaceAll("\\D+", "");
                if (code.isEmpty()) {
                    input.setError("أدخل الكود");
                    return;
                }
                dialog.dismiss();
                loadSupportCode(code);
            });
            ok.requestFocus();
        });
        dialog.show();
    }

    private void loadSupportCode(String code) {
        showLoading("الدعم", "فحص كود الدعم...");
        api.fetchSupport(code, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject json) {
                Models.SupportJob job = Models.SupportJob.fromJson(json);
                int serverCount = json.optInt("operationCount", job.operations.size());
                if (serverCount <= 0 || job.operations.isEmpty()) {
                    showApiError(
                            "كود بدون إجراءات",
                            "السيرفر أعاد 0 إجراءات. راجع الكود من لوحة التحكم.",
                            MainActivity.this::showSupportCodeDialog);
                    return;
                }
                previewSupport(job);
            }

            @Override public void onError(String message) {
                String friendly = "no_valid_operations".equals(message)
                        ? "الكود موجود لكن لا يحتوي إجراءات صالحة"
                        : message;
                showApiError(
                        "تعذر تنفيذ كود الدعم",
                        friendly,
                        MainActivity.this::showSupportCodeDialog);
            }
        });
    }

    private void previewSupport(Models.SupportJob job) {
        StringBuilder text = new StringBuilder();
        if (job.message != null && !job.message.isEmpty()) {
            text.append(job.message).append("\n\n");
        }
        int n = 1;
        for (Models.SupportOperation op : job.operations) {
            if ("replace".equals(op.action)) {
                text.append(n++)
                        .append(". حذف النسخة القديمة ثم تثبيت ")
                        .append(op.app == null ? "التطبيق الجديد" : op.app.name)
                        .append("\n");
            } else {
                text.append(n++)
                        .append(". تثبيت / تحديث ")
                        .append(op.app == null ? "التطبيق" : op.app.name)
                        .append("\n");
            }
        }
        text.append("\nعدد الإجراءات: ").append(job.operations.size());

        new AlertDialog.Builder(this)
                .setTitle("مهمة الدعم")
                .setMessage(text.toString())
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تنفيذ", (d, w) -> startSupportJob(job))
                .show();
    }

    private void startSupportJob(Models.SupportJob job) {
        ArrayList<InstallCoordinator.Task> tasks = new ArrayList<>();
        for (Models.SupportOperation op : job.operations) {
            if (op.app == null || op.app.id.isEmpty()) continue;
            InstallCoordinator.Task task = new InstallCoordinator.Task(op.app, false);
            if ("replace".equals(op.action)) {
                task.removePackages.addAll(op.removePackages);
            }
            tasks.add(task);
        }
        if (tasks.isEmpty()) {
            toast("لا توجد إجراءات قابلة للتنفيذ");
            return;
        }
        coordinator.start(tasks);
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void showQueueScreen() {
        LinearLayout screen = baseScreen();
        addSectionHeader(
                screen,
                "جاري تجهيز الجهاز",
                "اترك Professor Installer مفتوحًا حتى تنتهي العملية",
                () -> {
                    if (!coordinator.isRunning()) showHome();
                    else toast("المهمة قيد التنفيذ");
                });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(38), dp(32), dp(38), dp(32));
        panel.setBackground(roundRect(PANEL, dp(24), Color.rgb(26, 70, 108), dp(1)));

        queuePosition = label("التطبيق 0 من 0", 16, GOLD, Typeface.BOLD);
        queueTitle = label("...", 32, WHITE, Typeface.BOLD);
        queueDetail = label("بدء المهمة", 18, MUTED, Typeface.NORMAL);
        queueProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        queueProgress.setMax(100);
        queueProgress.setProgress(0);
        queueProgress.setIndeterminate(false);
        queueSource = label("المصدر: -", 15, CYAN, Typeface.BOLD);
        queueAction = actionButton("إلغاء المهمة");
        queueAction.setOnClickListener(v -> {
            coordinator.cancel();
            showHome();
        });

        panel.addView(queuePosition);
        panel.addView(queueTitle, marginTop(14));
        panel.addView(queueDetail, marginTop(8));
        panel.addView(queueProgress, marginTopHeight(28, dp(12)));
        panel.addView(queueSource, marginTop(16));
        panel.addView(queueAction, marginTop(28));

        screen.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
    }

    @Override
    public void onQueueStatus(String title, String detail, int progress, boolean indeterminate) {
        if (queueTitle == null || queueDetail == null || queueProgress == null) return;
        queueTitle.setText(title == null ? "" : title);
        queueDetail.setText(detail == null ? "" : detail);
        queueProgress.setIndeterminate(indeterminate);
        if (!indeterminate) queueProgress.setProgress(Math.max(0, Math.min(100, progress)));
    }

    @Override
    public void onQueuePosition(int current, int total, String source) {
        if (queuePosition != null) queuePosition.setText("التطبيق " + current + " من " + total);
        if (queueSource != null) queueSource.setText("المصدر: " + source);
    }

    @Override
    public void onQueueComplete(int success, int skipped, int failed) {
        new AlertDialog.Builder(this)
                .setTitle("اكتملت المهمة ✓")
                .setMessage(
                        "تم التثبيت: " + success
                                + "\nموجود مسبقًا / تم تخطيه: " + skipped
                                + "\nفشل: " + failed)
                .setCancelable(false)
                .setPositiveButton("العودة للرئيسية", (d, w) -> showHome())
                .show();
    }

    @Override
    public void onQueueError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton("إلغاء المهمة", (d, w) -> {
                    coordinator.cancel();
                    showHome();
                })
                .setNeutralButton("تخطي", (d, w) -> coordinator.skipCurrent())
                .setPositiveButton("إعادة المحاولة", (d, w) -> coordinator.retryCurrent())
                .show();
    }

    @Override
    public void launchUninstall(String packageName) {
        try {
            Intent intent = new Intent(
                    Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.parse("package:" + packageName));
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(intent, REQ_UNINSTALL);
        } catch (Exception e) {
            coordinator.onUninstallReturned();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL && coordinator != null) {
            coordinator.onUninstallReturned();
        }
    }

    private void showLoading(String title, String detail) {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, title, detail, this::showHome);
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        ProgressBar p = new ProgressBar(this);
        center.addView(p, new LinearLayout.LayoutParams(dp(68), dp(68)));
        screen.addView(center, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
    }

    private void showApiError(String title, String message, Runnable retry) {
        showHome();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إعادة المحاولة", (d, w) -> retry.run())
                .show();
    }

    private LinearLayout baseScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(46), dp(28), dp(46), dp(28));
        screen.setBackgroundColor(NAVY);
        screen.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return screen;
    }

    private void addBrandHeader(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView mark = label("P", 34, NAVY, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundRect(GOLD, dp(16), Color.TRANSPARENT, 0));
        bar.addView(mark, new LinearLayout.LayoutParams(dp(62), dp(62)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(18), 0, 0, 0);
        brand.addView(label("PROFESSOR", 28, WHITE, Typeface.BOLD));
        brand.addView(label("Powered by Shaikh Alkar", 14, MUTED, Typeface.NORMAL));
        bar.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = label("Google TV", 13, GREEN, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(16), dp(8), dp(16), dp(8));
        badge.setBackground(roundRect(NAVY_2, dp(99), Color.rgb(37, 82, 114), dp(1)));
        bar.addView(badge);

        parent.addView(bar);
    }

    private void addSectionHeader(
            LinearLayout parent,
            String title,
            String subtitle,
            Runnable back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Button home = actionButton("الرئيسية");
        home.setOnClickListener(v -> back.run());
        bar.addView(home, new LinearLayout.LayoutParams(dp(150), dp(54)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(20), 0, 0, 0);
        text.addView(label(title, 28, WHITE, Typeface.BOLD));
        text.addView(label(subtitle, 15, MUTED, Typeface.NORMAL));
        bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(bar);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(24, 52, 79));
        parent.addView(line, marginTopHeight(18, 1));
    }

    private View homeCard(String icon, String title, String eyebrow, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(26), dp(24), dp(26), dp(24));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(roundRect(PANEL, dp(24), Color.rgb(26, 62, 96), dp(1)));

        TextView iconView = label(icon, 38, GOLD, Typeface.BOLD);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(roundRect(NAVY_2, dp(22), Color.rgb(47, 86, 120), dp(1)));
        card.addView(iconView, new LinearLayout.LayoutParams(dp(78), dp(78)));

        TextView eye = label(eyebrow, 13, CYAN, Typeface.BOLD);
        eye.setGravity(Gravity.CENTER);
        TextView titleView = label(title, 24, WHITE, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        TextView sub = label(subtitle, 14, MUTED, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        sub.setMaxLines(2);

        card.addView(eye, marginTop(18));
        card.addView(titleView, marginTop(7));
        card.addView(sub, marginTop(10));
        applyFocus(card);
        return card;
    }

    private View listCard(String title, String meta, String action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(16), dp(24), dp(16));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(roundRect(PANEL, dp(18), Color.rgb(27, 62, 95), dp(1)));

        TextView actionView = label(action, 14, GOLD, Typeface.BOLD);
        actionView.setGravity(Gravity.CENTER);
        actionView.setPadding(dp(16), dp(10), dp(16), dp(10));
        actionView.setBackground(roundRect(NAVY_2, dp(12), Color.rgb(42, 78, 110), dp(1)));
        card.addView(actionView, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(20), 0, 0, 0);
        text.addView(label(title, 21, WHITE, Typeface.BOLD));
        text.addView(label(meta, 14, MUTED, Typeface.NORMAL), marginTop(5));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        applyFocus(card);
        return card;
    }

    private void applyFocus(View view) {
        view.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(roundRect(
                    focused ? PANEL_FOCUS : PANEL,
                    dp(24),
                    focused ? GOLD : Color.rgb(26, 62, 96),
                    dp(focused ? 3 : 1)));
            v.animate()
                    .scaleX(focused ? 1.035f : 1f)
                    .scaleY(focused ? 1.035f : 1f)
                    .setDuration(120)
                    .start();
        });
    }

    private LinearLayout verticalList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(18), dp(4), dp(18));
        return list;
    }

    private TextView emptyState(String text) {
        TextView view = label(text, 20, MUTED, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(20), dp(70), dp(20), dp(70));
        return view;
    }

    private TextView label(String text, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setBackground(roundRect(NAVY_2, dp(12), Color.rgb(42, 78, 110), dp(1)));
        button.setOnFocusChangeListener((v, focused) ->
                v.setBackground(roundRect(
                        focused ? GOLD : NAVY_2,
                        dp(12),
                        focused ? GOLD : Color.rgb(42, 78, 110),
                        dp(focused ? 2 : 1))));
        return button;
    }

    private EditText dialogInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        int pad = dp(18);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams homeCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(10), dp(6), dp(10), dp(6));
        return params;
    }

    private LinearLayout.LayoutParams listCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams marginTopHeight(int top, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        if (coordinator != null && coordinator.isRunning()) {
            toast("المهمة قيد التنفيذ");
            return;
        }
        showHome();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }
}
