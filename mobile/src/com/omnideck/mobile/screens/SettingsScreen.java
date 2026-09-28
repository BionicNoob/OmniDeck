package com.omnideck.mobile.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.omnideck.mobile.MainActivity;

/** Placeholder — implemented in the screens workflow. */
public final class SettingsScreen extends Screen {
    public SettingsScreen(MainActivity a) {
        super(a);
    }

    @Override
    protected View build() {
        FrameLayout f = new FrameLayout(a);
        f.addView(ui.label("Settings"), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return f;
    }
}
