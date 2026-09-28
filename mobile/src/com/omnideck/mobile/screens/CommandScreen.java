package com.omnideck.mobile.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.omnideck.mobile.MainActivity;

/** Placeholder — implemented in the screens workflow. */
public final class CommandScreen extends Screen {
    public CommandScreen(MainActivity a) {
        super(a);
    }

    @Override
    protected View build() {
        FrameLayout f = new FrameLayout(a);
        f.addView(ui.label("Command"), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return f;
    }
}
