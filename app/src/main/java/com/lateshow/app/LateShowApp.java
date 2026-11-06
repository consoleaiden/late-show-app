package com.lateshow.app;

import android.app.Application;

import com.lateshow.app.api.NetMonitor;
import com.lateshow.app.api.SmartDns;
import com.lateshow.app.util.ThemeManager;

/** 进程入口：在任何 Activity 创建前按用户偏好应用日夜模式，避免首帧/恢复时主题错乱 */
public class LateShowApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.apply(this);
        // 网络感知：DNS 解析需读取当前网络能力（IPv4/IPv6 默认路由、是否局域网），
        // 并在 WiFi ↔ 蜂窝 切换时立即作废旧解析结果与连接池
        SmartDns.init(this);
        NetMonitor.init(this);
    }
}
