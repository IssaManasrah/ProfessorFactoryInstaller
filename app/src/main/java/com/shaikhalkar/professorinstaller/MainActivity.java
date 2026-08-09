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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements InstallCoordinator.Host {
    private static final int NAVY = Color.rgb(7, 17, 31);
    private static final int PANEL = Color.rgb(14, 32, 54);
    private static final int PANEL_FOCUS = Color.rgb(24, 47, 76);
    private static final int GOLD = Color.rgb(215, 182, 93);
    private static final int WHITE = Color.rgb(246, 248, 251);
    private static final int MUTED = Color.rgb(165, 178, 196);
    private static final int GREEN = Color.rgb(91, 201, 147);
    private static final int REQ_UNINSTALL = 6200;

    private final ApiClient api = new ApiClient();
    private InstallCoordinator coordinator;
    private LinearLayout root;
    private TextView queueTitle;
    private TextView queueDetail;
    private TextView queuePosition;
    private TextView queueSource;
    private ProgressBar queueProgress;
    private Button queueAction;
    private Models.Catalog lastCatalog;

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
        if (intent.hasExtra(InstallStatusReceiver.EXTRA_RESULT_STATUS)) {
            int status = intent.getIntExtra(InstallStatusReceiver.EXTRA_RESULT_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = intent.getStringExtra(InstallStatusReceiver.EXTRA_RESULT_MESSAGE);
            intent.removeExtra(InstallStatusReceiver.EXTRA_RESULT_STATUS);
            intent.removeExtra(InstallStatusReceiver.EXTRA_RESULT_MESSAGE);
            coordinator.onInstallResult(status, msg);
        }
    }

    private void showHome() {
        root = baseScreen();
        addBrandHeader(root, "PROFESSOR", "منظومة تجهيز التطبيقات والدعم | شيخ الكار");

        TextView intro = label("كل ما تحتاجه في ثلاث خطوات فقط", 22, WHITE, Typeface.BOLD);
        intro.setPadding(0, dp(6), 0, dp(22));
        root.addView(intro);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setGravity(Gravity.CENTER);
        cards.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        View program = homeCard("برمجة الأجهزة", "للموظفين فقط", "اختيار الموديل وتجهيز الجهاز من USB أو Online", "01");
        View browse = homeCard("تصفح التطبيقات", "تطبيقات Professor", "عرض التطبيقات المتاحة وتنزيل الناقص أو التحديث", "02");
        View support = homeCard("الدعم", "Service Code", "إدخال كود الدعم وتنفيذ الإجراء المطلوب", "03");

        program.setOnClickListener(v -> showPinDialog());
        browse.setOnClickListener(v -> openBrowser());
        support.setOnClickListener(v -> showSupportCodeDialog());

        cards.addView(program, cardParams());
        cards.addView(browse, cardParams());
        cards.addView(support, cardParams());
        root.addView(cards, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = label("PROFESSOR • Built for Google TV", 14, MUTED, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, 0);
        root.addView(footer);
        setContentView(root);

        program.requestFocus();
    }

    private void showPinDialog() {
        EditText input = dialogInput("2580");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("برمجة الأجهزة")
                .setMessage("أدخل رقم الموظفين")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("دخول", null)
                .create();
        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                if ("2580".equals(input.getText().toString().trim())) {
                    dialog.dismiss();
                    openProgrammingModels();
                } else {
                    input.setError("الرقم غير صحيح");
                }
            });
            ok.requestFocus();
        });
        dialog.show();
    }

    private void openProgrammingModels() {
        showLoading("برمجة الأجهزة", "تحميل موديلات الأجهزة من السيرفر...");
        api.fetchCatalog("JO", new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject json) {
                Models.Catalog catalog = Models.Catalog.fromJson(json);
                lastCatalog = catalog;
                showModels(catalog);
            }
            @Override public void onError(String message) { showApiError("تعذر تحميل الموديلات", message, MainActivity.this::openProgrammingModels); }
        });
    }

    private void showModels(Models.Catalog catalog) {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, "برمجة الأجهزة", "اختر الموديل وسيتم تجهيز تطبيقاته تلقائيًا", this::showHome);
        if (catalog.groups.isEmpty()) {
            screen.addView(emptyState("لا توجد موديلات مفعّلة في API"));
            setContentView(screen);
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = verticalList();
        View first = null;
        for (Models.GroupInfo group : catalog.groups) {
            int count = group.appIds.size();
            View card = listCard(group.name, count + " تطبيق", "اختيار هذا الموديل");
            card.setOnClickListener(v -> startGroup(catalog, group));
            list.addView(card, listCardParams());
            if (first == null) first = card;
        }
        scroll.addView(list);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        new AlertDialog.Builder(this)
                .setTitle(group.name)
                .setMessage("سيتم فحص الفلاشة أولًا داخل PROFESSOR_APPS، وأي ملف ناقص سيتم تنزيله Online.\n\nعدد التطبيقات: " + tasks.size())
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("ابدأ البرمجة", (d, w) -> coordinator.start(tasks))
                .show();
    }

    private void openBrowser() {
        showLoading("تصفح التطبيقات", "تحميل التطبيقات المتاحة...");
        api.fetchCatalog("JO", new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject json) {
                Models.Catalog catalog = Models.Catalog.fromJson(json);
                lastCatalog = catalog;
                showApps(catalog);
            }
            @Override public void onError(String message) { showApiError("تعذر تحميل التطبيقات", message, MainActivity.this::openBrowser); }
        });
    }

    private void showApps(Models.Catalog catalog) {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, "تصفح التطبيقات", "التطبيقات المسموح بعرضها من API", this::showHome);
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
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
        if (first != null) first.requestFocus();
    }

    private void showSupportCodeDialog() {
        EditText input = dialogInput("مثال: 583291");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("الدعم")
                .setMessage("أدخل كود الدعم المرسل لك")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة", null)
                .create();
        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String code = input.getText().toString().replaceAll("\\D+", "");
                if (code.isEmpty()) { input.setError("أدخل الكود"); return; }
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
                    showApiError("كود بدون إجراءات", "السيرفر أعاد 0 إجراءات. أنشئ الكود من لوحة v1.3 وتأكد من اختيار التطبيق الهدف.", MainActivity.this::showSupportCodeDialog);
                    return;
                }
                previewSupport(job);
            }
            @Override public void onError(String message) {
                String friendly = "no_valid_operations".equals(message) ? "الكود موجود لكن لا يحتوي إجراءات صالحة" : message;
                showApiError("تعذر تنفيذ كود الدعم", friendly, MainActivity.this::showSupportCodeDialog);
            }
        });
    }

    private void previewSupport(Models.SupportJob job) {
        StringBuilder text = new StringBuilder();
        text.append(job.message).append("\n\n");
        int n = 1;
        for (Models.SupportOperation op : job.operations) {
            if ("replace".equals(op.action)) {
                text.append(n++).append(". استبدال النسخة القديمة ثم تثبيت ").append(op.app.name).append("\n");
            } else {
                text.append(n++).append(". تثبيت / تحديث ").append(op.app.name).append("\n");
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
            if ("replace".equals(op.action)) task.removePackages.addAll(op.removePackages);
            tasks.add(task);
        }
        if (tasks.isEmpty()) {
            toast("لا توجد إجراءات قابلة للتنفيذ");
            return;
        }
        coordinator.start(tasks);
    }

    @Override public Activity activity() { return this; }

    @Override public void showQueueScreen() {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, "جاري تجهيز الجهاز", "لا تغلق التطبيق أثناء العملية", () -> {
            if (!coordinator.isRunning()) showHome();
            else toast("انتظر انتهاء المهمة أو استخدم إلغاء");
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(34), dp(30), dp(34), dp(30));
        panel.setBackground(roundRect(PANEL, dp(22), Color.TRANSPARENT, 0));

        queueTitle = label("...", 30, WHITE, Typeface.BOLD);
        queueDetail = label("بدء المهمة", 19, MUTED, Typeface.NORMAL);
        queuePosition = label("التطبيق 0 من 0", 16, GOLD, Typeface.BOLD);
        queueSource = label("المصدر: -", 16, MUTED, Typeface.NORMAL);
        queueProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        queueProgress.setMax(100);
        queueProgress.setProgress(0);
        queueProgress.setIndeterminate(false);
        queueAction = actionButton("إلغاء المهمة");
        queueAction.setOnClickListener(v -> {
            coordinator.cancel();
            showHome();
        });

        panel.addView(queuePosition);
        panel.addView(queueTitle, marginTop(14));
        panel.addView(queueDetail, marginTop(8));
        panel.addView(queueProgress, marginTopHeight(26, dp(12)));
        panel.addView(queueSource, marginTop(16));
        panel.addView(queueAction, marginTop(28));
        screen.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(screen);
        queueAction.requestFocus();
    }

    @Override public void onQueueStatus(String title, String detail, int progress, boolean indeterminate) {
        if (queueTitle == null) return;
        queueTitle.setText(title);
        queueDetail.setText(detail);
        queueProgress.setIndeterminate(indeterminate);
        if (!indeterminate) queueProgress.setProgress(progress);
    }

    @Override public void onQueuePosition(int current, int total, String source) {
        if (queuePosition == null) return;
        queuePosition.setText("التطبيق " + current + " من " + total);
        queueSource.setText("المصدر: " + source);
    }

    @Override public void onQueueComplete(int success, int skipped, int failed) {
        new AlertDialog.Builder(this)
                .setTitle("اكتملت المهمة ✓")
                .setMessage("تم التثبيت: " + success + "\nموجود مسبقًا / تم تخطيه: " + skipped + "\nفشل: " + failed)
                .setCancelable(false)
                .setPositiveButton("العودة للرئيسية", (d, w) -> showHome())
                .show();
    }

    @Override public void onQueueError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton("إلغاء المهمة", (d, w) -> { coordinator.cancel(); showHome(); })
                .setNeutralButton("تخطي", (d, w) -> coordinator.skipCurrent())
                .setPositiveButton("إعادة المحاولة", (d, w) -> coordinator.retryCurrent())
                .show();
    }

    @Override public void launchUninstall(String packageName) {
        try {
            Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + packageName));
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(i, REQ_UNINSTALL);
        } catch (Exception e) {
            coordinator.onUninstallReturned();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNINSTALL && coordinator != null) coordinator.onUninstallReturned();
    }

    private void showLoading(String title, String detail) {
        LinearLayout screen = baseScreen();
        addSectionHeader(screen, title, detail, this::showHome);
        ProgressBar p = new ProgressBar(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(p, new LinearLayout.LayoutParams(dp(70), dp(70)));
        screen.addView(wrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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

    private void addBrandHeader(LinearLayout parent, String title, String subtitle) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView mark = label("P", 34, NAVY, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundRect(GOLD, dp(16), Color.TRANSPARENT, 0));
        bar.addView(mark, new LinearLayout.LayoutParams(dp(62), dp(62)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(18), 0, 0, 0);
        text.addView(label(title, 30, WHITE, Typeface.BOLD));
        text.addView(label(subtitle, 15, MUTED, Typeface.NORMAL));
        bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(bar);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(29, 48, 70));
        parent.addView(line, marginTopHeight(20, 1));
    }

    private void addSectionHeader(LinearLayout parent, String title, String subtitle, Runnable back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button b = actionButton("الرئيسية");
        b.setOnClickListener(v -> back.run());
        bar.addView(b, new LinearLayout.LayoutParams(dp(150), dp(54)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(18), 0, 0, 0);
        text.addView(label(title, 28, WHITE, Typeface.BOLD));
        text.addView(label(subtitle, 15, MUTED, Typeface.NORMAL));
        bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(bar);
        parent.addView(new View(this), marginTopHeight(20, 1));
    }

    private View homeCard(String title, String eyebrow, String subtitle, String number) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(26), dp(24), dp(26), dp(24));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(roundRect(PANEL, dp(22), Color.rgb(31, 53, 78), dp(1)));

        TextView num = label(number, 15, GOLD, Typeface.BOLD);
        TextView eye = label(eyebrow, 14, GOLD, Typeface.BOLD);
        TextView t = label(title, 25, WHITE, Typeface.BOLD);
        TextView sub = label(subtitle, 15, MUTED, Typeface.NORMAL);
        sub.setMaxLines(2);
        card.addView(num);
        card.addView(eye, marginTop(14));
        card.addView(t, marginTop(8));
        card.addView(sub, marginTop(10));
        applyFocus(card);
        return card;
    }

    private View listCard(String title, String meta, String action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(18), dp(24), dp(18));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(roundRect(PANEL, dp(18), Color.rgb(31, 53, 78), dp(1)));

        TextView act = label(action, 15, GOLD, Typeface.BOLD);
        act.setGravity(Gravity.CENTER);
        act.setBackground(roundRect(Color.rgb(23, 45, 70), dp(12), Color.rgb(62, 82, 105), dp(1)));
        act.setPadding(dp(18), dp(10), dp(18), dp(10));
        card.addView(act, new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(20), 0, 0, 0);
        txt.addView(label(title, 21, WHITE, Typeface.BOLD));
        txt.addView(label(meta, 14, MUTED, Typeface.NORMAL), marginTop(5));
        card.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        applyFocus(card);
        return card;
    }

    private void applyFocus(View v) {
        v.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(roundRect(focused ? PANEL_FOCUS : PANEL, dp(22), focused ? GOLD : Color.rgb(31, 53, 78), dp(focused ? 3 : 1)));
            view.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(120).start();
        });
    }

    private LinearLayout verticalList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(18), dp(4), dp(18));
        return list;
    }

    private TextView emptyState(String text) {
        TextView t = label(text, 20, MUTED, Typeface.NORMAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(70), dp(20), dp(70));
        return t;
    }

    private TextView label(String text, int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans", style));
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return t;
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setBackground(roundRect(Color.rgb(21, 42, 66), dp(12), Color.rgb(50, 72, 96), dp(1)));
        b.setOnFocusChangeListener((v, f) -> v.setBackground(roundRect(f ? GOLD : Color.rgb(21, 42, 66), dp(12), f ? GOLD : Color.rgb(50, 72, 96), dp(2))));
        return b;
    }

    private EditText dialogInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setSelectAllOnFocus(true);
        int pad = dp(18);
        e.setPadding(pad, pad, pad, pad);
        return e;
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(10), dp(6), dp(10), dp(6));
        return p;
    }

    private LinearLayout.LayoutParams listCardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams marginTopHeight(int top, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

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
