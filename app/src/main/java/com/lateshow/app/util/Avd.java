package com.lateshow.app.util;

import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat;

/**
 * 动态图标（AnimatedVectorDrawable）启动器。
 * AVD 不会自动播放，需要在视图挂载后遍历启动一次；已运行中的不重复启动。
 */
public final class Avd {

    private Avd() {}

    /** 递归启动视图树内所有未运行的 AVD（含 ImageView src 与 TextView 环绕图标） */
    public static void startTree(View root) {
        if (root == null) return;
        if (root instanceof ImageView) {
            start(((ImageView) root).getDrawable());
        }
        if (root instanceof TextView) {
            for (Drawable d : ((TextView) root).getCompoundDrawables()) start(d);
            for (Drawable d : ((TextView) root).getCompoundDrawablesRelative()) start(d);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) startTree(g.getChildAt(i));
        }
    }

    private static void start(Drawable d) {
        if (d == null) return;
        while (d instanceof InsetDrawable) d = ((InsetDrawable) d).getDrawable();
        if (d instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) d;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) start(ld.getDrawable(i));
            return;
        }
        if (d instanceof AnimatedVectorDrawable) {
            if (!d.isRunning()) d.start();
        } else if (d instanceof AnimatedVectorDrawableCompat) {
            AnimatedVectorDrawableCompat avd = (AnimatedVectorDrawableCompat) d;
            if (!avd.isRunning()) avd.start();
        }
    }
}
