package com.lateshow.app.util;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.lateshow.app.store.PrefStore;

/** 主题统一入口：尽早应用 + 配置一致性自检（防止重启后主题样式与资源解析不一致的混色） */
public final class ThemeManager {

    private ThemeManager() {}

    /** 按用户偏好应用日夜模式（PrefStore 未初始化时先初始化） */
    public static void apply(Context c) {
        PrefStore.init(c.getApplicationContext());
        int t = PrefStore.get().theme();
        AppCompatDelegate.setDefaultNightMode(
                t == PrefStore.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : t == PrefStore.THEME_AUTO ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES);
    }

    /** 偏好换算出的期望夜间状态 */
    public static boolean expectNight(Context c) {
        int t = PrefStore.get().theme();
        if (t == PrefStore.THEME_DARK) return true;
        if (t == PrefStore.THEME_LIGHT) return false;
        // 跟随系统：取应用级配置（未被子 Activity 覆盖的系统 uiMode）
        int ui = c.getApplicationContext().getResources().getConfiguration().uiMode;
        return (ui & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 当前 Activity 实际解析到的夜间状态 */
    public static boolean actualNight(Context c) {
        int ui = c.getResources().getConfiguration().uiMode;
        return (ui & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
