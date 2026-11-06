package com.lateshow.app.api;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 默认网络变化监听。
 *
 * <p>WiFi ↔ 蜂窝 切换时，两处状态必须立刻作废，否则会继续用上个网络的地址去连：
 * <ul>
 *   <li>{@link SmartDns} 的解析缓存（WiFi 下可能解析出私网 IPv4）</li>
 *   <li>OkHttp 连接池里的长连接（母网卡地址已消失，复用必失败）</li>
 * </ul>
 *
 * <p>回调事件会成串到达（onAvailable / onCapabilitiesChanged / onLinkPropertiesChanged），
 * 这里做 400ms 合并，避免重复清理。
 */
public final class NetMonitor {

    private static final String TAG = "LateShowNet";
    private static volatile boolean installed;
    private static volatile long lastHandled;

    private NetMonitor() { }

    /** 进程启动时调用一次（LateShowApp.onCreate） */
    public static void init(Context ctx) {
        if (installed || ctx == null) return;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getApplicationContext()
                            .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { onChanged("available"); }
                @Override public void onLost(Network n) { onChanged("lost"); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    onChanged("capabilities");
                }
                @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                    onChanged("linkProperties");
                }
            });
            installed = true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "网络回调注册失败（降级为 DNS 缓存短 TTL 兜底）：" + e);
        }
    }

    private static void onChanged(String why) {
        long now = System.currentTimeMillis();
        if (now - lastHandled < 400) return;   // 事件风暴节流
        lastHandled = now;
        SmartDns.invalidate();
        VodSyncApi.onNetworkChanged();
        android.util.Log.d(TAG, "网络变化（" + why + "）→ 已清 DNS 缓存与连接池");
    }
}
