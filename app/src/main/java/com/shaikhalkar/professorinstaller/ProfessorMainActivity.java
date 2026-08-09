package com.shaikhalkar.professorinstaller;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class ProfessorMainActivity extends MainActivity {
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
