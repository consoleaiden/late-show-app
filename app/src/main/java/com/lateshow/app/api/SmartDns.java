package com.lateshow.app.api;

import android.content.Context;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三级 DNS 兜底解析：
 * 1. 系统 DNS（运营商）
 * 2. 加密 DNS over HTTPS（阿里 → 腾讯 DNSPod → Cloudflare，解决运营商 DNS 污染/无 A 记录域名失联）
 * 3. 内置已知后端地址兜底（mosang.asia 无可用 A 记录、仅 IPv6）
 *
 * <p><b>网络感知</b>（v1.5.55 起）：
 * <ul>
 *   <li>解析结果<b>绑定当前默认网络</b>，网络一变立即失效，避免「WiFi→蜂窝」后继续用上个网络的地址；</li>
 *   <li>按当前网络能力<b>过滤地址族</b>：无 IPv6 默认路由剔掉 AAAA，无 IPv4 路由剔掉 A；</li>
 *   <li>非 WiFi/以太网（如蜂窝）下<b>剔掉私网 IPv4</b>——域名 A 记录指向 192.168.x.x 时，
 *       在蜂窝网络永远不可达，若不清除会吃满 connectTimeout 才失败；</li>
 *   <li>有 IPv6 路由时 IPv6 优先（Happy Eyeballs 简化版），避免先等一次 IPv4 超时。</li>
 * </ul>
 *
 * <p>缓存 TTL 2 分钟（原 10 分钟），并由 {@link NetMonitor} 在网络变化时主动清空。
 */
public final class SmartDns implements okhttp3.Dns {

    public static final SmartDns INSTANCE = new SmartDns();

    private static final String TAG = "LateShowDns";

    /** 缓存有效期：已绑定网络，可短一些；网络变化时 NetMonitor 还会主动清空 */
    private static final long TTL_MS = 2 * 60 * 1000L;

    /** Application context，用于读取当前网络能力（未 init 时退化为不过滤） */
    private static volatile Context appCtx;

    static final class Entry {
        final List<InetAddress> addrs;
        final long expireAt;
        final int netKey;
        final boolean v4Ok, v6Ok;

        Entry(List<InetAddress> a, long e, NetInfo net) {
            addrs = a;
            expireAt = e;
            netKey = net.key;
            v4Ok = net.hasV4;
            v6Ok = net.hasV6;
        }

        /** 未过期且网络能力一致才算命中 */
        boolean valid(NetInfo net, long now) {
            return now < expireAt && netKey == net.key && v4Ok == net.hasV4 && v6Ok == net.hasV6;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /**
     * 已知后端域名的直连兜底（后缀匹配，命中子域名）。
     * 注：IPv6 前缀由运营商分配、会轮换，这里只是 DoH 全挂时的最后一档，
     * 拿到新地址后请随版本更新。
     */
    private static final Map<String, String> FALLBACK = new HashMap<>();

    static {
        FALLBACK.put("mosang.asia", "2409:8a62:5a18:5e70:f6b5:20ff:fe0f:d6ef");
    }

    private final List<okhttp3.Dns> dohProviders = new ArrayList<>();
    /** 供 DoH 使用的裸客户端（不能递归使用本 DNS） */
    private final okhttp3.OkHttpClient bareClient = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private SmartDns() {
        addDoh("https://dns.alidns.com/dns-query", "223.5.5.5", "223.6.6.6");
        addDoh("https://doh.pub/dns-query", "1.12.12.12", "120.53.53.53");
        addDoh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1");
    }

    /** 进程启动时注入 context（LateShowApp.onCreate） */
    public static void init(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    /** 网络变化时清空解析缓存（NetMonitor 调用） */
    public static void invalidate() {
        CACHE.clear();
    }

    private void addDoh(String url, String... bootIps) {
        try {
            okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(url);
            if (u == null) return;
            List<InetAddress> boots = new ArrayList<>();
            for (String ip : bootIps) boots.add(InetAddress.getByName(ip));
            okhttp3.dnsoverhttps.DnsOverHttps doh =
                    new okhttp3.dnsoverhttps.DnsOverHttps.Builder()
                            .client(bareClient)
                            .url(u)
                            .bootstrapDnsHosts(boots)
                            .build();
            dohProviders.add(doh);
        } catch (Exception ignored) {
            // 单个 DoH 初始化失败不影响其它兜底
        }
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        // 0. 用户直接填 IP 的情况
        InetAddress literal = parseLiteral(hostname);
        if (literal != null) return Collections.singletonList(literal);

        NetInfo net = NetInfo.current(appCtx);
        long now = System.currentTimeMillis();

        // 1. 缓存命中（需网络一致）
        Entry ce = CACHE.get(hostname);
        if (ce != null && ce.valid(net, now)) return ce.addrs;

        List<InetAddress> raw = null;

        // 2. 系统 DNS
        try {
            raw = okhttp3.Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException ignored) { }

        // 3. DoH 逐家尝试
        if (raw == null || raw.isEmpty()) {
            for (okhttp3.Dns p : dohProviders) {
                try {
                    List<InetAddress> r = p.lookup(hostname);
                    if (r != null && !r.isEmpty()) { raw = r; break; }
                } catch (UnknownHostException ignored) { }
            }
        }

        // 4. 内置直连兜底（后缀匹配，覆盖子域名如 vod.mosang.asia）
        if (raw == null || raw.isEmpty()) {
            String fb = fallbackFor(hostname);
            if (fb != null) {
                try {
                    raw = Collections.singletonList(InetAddress.getByName(fb));
                } catch (UnknownHostException ignored) { }
            }
        }

        if (raw == null || raw.isEmpty()) {
            throw new UnknownHostException("无法解析 " + hostname + "（系统/DoH/兜底均失败）");
        }

        // 5. 按当前网络能力过滤 + 排序（此处可能抛「无可用地址」的明确原因）
        List<InetAddress> usable = filter(raw, net, hostname);
        android.util.Log.d(TAG, hostname + " raw=" + fmt(raw) + " 可用=" + fmt(usable)
                + " net=" + net.key + " v4=" + net.hasV4 + " v6=" + net.hasV6 + " lan=" + net.lan);
        CACHE.put(hostname, new Entry(usable, now + TTL_MS, net));
        return usable;
    }

    /**
     * 剔除当前网络必然连不通的地址，并按地址族优先级排序。
     * 过滤后为空时抛异常，让 UI 立刻给出可读原因（而不是等 10s 连接超时）。
     */
    private static List<InetAddress> filter(List<InetAddress> raw, NetInfo net, String host)
            throws UnknownHostException {
        List<InetAddress> v6 = new ArrayList<>();
        List<InetAddress> v4 = new ArrayList<>();
        for (InetAddress a : raw) {
            if (a instanceof Inet6Address) {
                if (net.hasV6) v6.add(a);
            } else if (a instanceof Inet4Address) {
                if (!net.hasV4) continue;
                // 蜂窝等非局域网环境：私网 IPv4 一定不可达（域名 A 记录指向家庭内网时常见）
                if (!net.lan && isPrivateV4(a)) continue;
                v4.add(a);
            }
        }
        if (v6.isEmpty() && v4.isEmpty()) {
            String why = !net.hasV6 && !net.hasV4
                    ? "当前网络不可用"
                    : (!net.hasV6 && hasV6(raw) ? "当前 WiFi 无 IPv6，而该后端仅有 IPv6 地址" : "地址族与当前网络不匹配");
            throw new UnknownHostException(host + " 无可用地址（" + why + "）");
        }
        List<InetAddress> out = new ArrayList<>(v6.size() + v4.size());
        if (net.hasV6) {   // 有 IPv6 时优先 v6，避免先等一次 v4 超时
            out.addAll(v6);
            out.addAll(v4);
        } else {
            out.addAll(v4);
            out.addAll(v6);
        }
        return out;
    }

    private static boolean hasV6(List<InetAddress> list) {
        for (InetAddress a : list) if (a instanceof Inet6Address) return true;
        return false;
    }

    /** 私网 / 回环 / 链路本地 / CGNAT：非局域网环境下不可达 */
    private static boolean isPrivateV4(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 4) return false;
        int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
        if (b0 == 10 || b0 == 127) return true;
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
        if (b0 == 192 && b1 == 168) return true;
        if (b0 == 169 && b1 == 254) return true;
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // 运营商级 NAT
        return false;
    }

    /** 兜底查找：精确或后缀匹配（vod.mosang.asia → mosang.asia） */
    private static String fallbackFor(String hostname) {
        String h = hostname.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : FALLBACK.entrySet()) {
            String k = e.getKey();
            if (h.equals(k) || h.endsWith("." + k)) return e.getValue();
        }
        return null;
    }

    private static InetAddress parseLiteral(String host) {
        String h = host.trim();
        // 仅对真正的 IP 字面量走直连，普通域名交给下面的系统 DNS / DoH 流程
        boolean ipv4 = h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
        boolean ipv6 = h.indexOf(':') >= 0 && h.matches("^[0-9a-fA-F:.]+$");
        if (!ipv4 && !ipv6) return null;
        try {
            return InetAddress.getByName(h);
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(List<InetAddress> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getHostAddress());
        }
        return sb.append(']').toString();
    }

    /** 外部手动写入解析结果（如设置页探测成功时缓存，减少后续 DoH 开销） */
    public static void prime(String hostname, List<InetAddress> addrs) {
        if (hostname == null || addrs == null || addrs.isEmpty()) return;
        NetInfo net = NetInfo.current(appCtx);
        CACHE.put(hostname, new Entry(addrs, System.currentTimeMillis() + TTL_MS, net));
    }
}
