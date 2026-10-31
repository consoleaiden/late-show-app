package com.lateshow.app.api;

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
 * 2. 加密 DNS over HTTPS（阿里 → 腾讯 DNSPod → Cloudflare，解决运营商 DNS 污染/对无 A 记录域名失联）
 * 3. 内置已知后端地址兜底（mosang.asia 无 A 记录、仅 IPv6，部分运营商 DNS 查询直接失败）
 * 成功结果缓存 10 分钟，避免每次请求都走 DoH。
 */
public final class SmartDns implements okhttp3.Dns {

    public static final SmartDns INSTANCE = new SmartDns();

    private static final long TTL_MS = 10 * 60 * 1000L;

    private static final class Entry {
        final List<InetAddress> addrs;
        final long expireAt;
        Entry(List<InetAddress> a, long e) { addrs = a; expireAt = e; }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** 已知后端域名的直连兜底（域名仅 AAAA 记录；运营商 DNS 污染时直接连 IPv6） */
    private static final Map<String, String> FALLBACK = new HashMap<>();

    static {
        FALLBACK.put("mosang.asia", "2409:8a62:5a18:6240:f6b5:20ff:fe0f:d6ef");
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

        // 1. 缓存命中
        Entry ce = CACHE.get(hostname);
        if (ce != null && System.currentTimeMillis() < ce.expireAt) return ce.addrs;

        List<InetAddress> out = null;

        // 2. 系统 DNS
        try {
            out = okhttp3.Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException ignored) { }

        // 3. DoH 逐家尝试
        if (out == null || out.isEmpty()) {
            for (okhttp3.Dns p : dohProviders) {
                try {
                    List<InetAddress> r = p.lookup(hostname);
                    if (r != null && !r.isEmpty()) { out = r; break; }
                } catch (UnknownHostException ignored) { }
            }
        }

        // 4. 内置直连兜底
        if (out == null || out.isEmpty()) {
            String fb = FALLBACK.get(hostname.toLowerCase(Locale.ROOT));
            if (fb != null) {
                try {
                    out = Collections.singletonList(InetAddress.getByName(fb));
                } catch (UnknownHostException ignored) { }
            }
        }

        if (out == null || out.isEmpty()) {
            throw new UnknownHostException("无法解析 " + hostname + "（系统/DoH/兜底均失败）");
        }
        CACHE.put(hostname, new Entry(out, System.currentTimeMillis() + TTL_MS));
        return out;
    }

    private static InetAddress parseLiteral(String host) {
        try {
            return InetAddress.getByName(host.trim());
        } catch (Exception e) {
            return null; // 非字面量 IP 交给后续流程
        }
    }

    /** 外部手动写入解析结果（如设置页探测成功时缓存，减少后续 DoH 开销） */
    public static void prime(String hostname, List<InetAddress> addrs) {
        if (hostname == null || addrs == null || addrs.isEmpty()) return;
        CACHE.put(hostname, new Entry(addrs, System.currentTimeMillis() + TTL_MS));
    }
}
