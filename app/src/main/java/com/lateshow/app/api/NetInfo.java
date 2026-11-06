package com.lateshow.app.api;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 当前默认网络的能力快照。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>DNS 缓存绑定网络：网络一变，旧解析结果立刻作废（WiFi→蜂窝 切换的核心修复）</li>
 *   <li>地址族过滤：按当前网络是否真有 IPv4/IPv6 默认路由，剔除必然连不通的地址</li>
 * </ul>
 *
 * <p>典型故障场景：域名同时有 A（192.168.x.x 私网）与 AAAA（公网 IPv6），
 * 在 WiFi 下解析出私网地址后切到蜂窝，私网地址永远不可达，必须剔除。
 */
final class NetInfo {

    /** 默认网络标识；变化即视为不同网络（Network 的 hashCode 基于 netId） */
    final int key;
    final boolean hasV4;
    final boolean hasV6;
    /** WiFi / 以太网：这类网络下私网 IPv4 是可达的，不能剔除 */
    final boolean lan;

    private NetInfo(int key, boolean hasV4, boolean hasV6, boolean lan) {
        this.key = key;
        this.hasV4 = hasV4;
        this.hasV6 = hasV6;
        this.lan = lan;
    }

    /** 拿不到网络信息时「保守未知」：不过滤地址，也不按网络失效缓存 */
    static NetInfo unknown() {
        return new NetInfo(-1, true, true, true);
    }

    static NetInfo current(Context ctx) {
        if (ctx == null) return unknown();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return unknown();
            Network net = cm.getActiveNetwork();
            if (net == null) return new NetInfo(0, false, false, false);
            LinkProperties lp = cm.getLinkProperties(net);
            boolean v4 = lp != null && lp.hasIpv4DefaultRoute();
            boolean v6 = lp != null && lp.hasIpv6DefaultRoute();
            NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            boolean lan = nc != null
                    && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            return new NetInfo(net.hashCode(), v4, v6, lan);
        } catch (Exception e) {
            return unknown();
        }
    }
}
