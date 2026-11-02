package com.lateshow.app;

import android.app.Application;

import com.lateshow.app.util.ThemeManager;

/** 进程入口：在任何 Activity 创建前按用户偏好应用日夜模式，避免首帧/恢复时主题错乱 */
public class LateShowApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.apply(this);
    }
}
