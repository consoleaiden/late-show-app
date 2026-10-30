package com.lateshow.app.util;

/** 等宽时间码工具（设计规范 §9.4） */
public final class TimeUtil {
    private TimeUtil() {}

    /** 秒 → m:ss 或 h:mm:ss */
    public static String dur(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    /** "2026-10-30 10:59:06" 或 ISO8601 → "10.30 10:59" */
    public static String stamp(String raw) {
        if (raw == null || raw.isEmpty()) return "--";
        try {
            // ISO 8601: 2026-10-30T16:09:54... / 普通: 2026-10-30 10:59:06
            String date = raw.substring(0, Math.min(10, raw.length())); // yyyy-MM-dd
            String time = raw.length() >= 16 ? raw.substring(11, 16) : "";
            if (raw.charAt(10) != ' ' && raw.charAt(10) != 'T') return raw;
            String[] p = date.split("-");
            if (p.length < 3) return raw;
            return p[1] + "." + p[2] + (time.isEmpty() ? "" : " " + time);
        } catch (Exception e) {
            return raw;
        }
    }

    /** "2026-10-30T13:00:30" → "10.30 13:00" 同上，容错空值 */
    public static String stampOrEmpty(String raw) {
        return raw == null || raw.isEmpty() ? "—" : stamp(raw);
    }

    /** 数字千分位 */
    public static String thousands(int n) {
        return String.format("%,d", n);
    }

    /** 源站条数缩写：104396 → 10.4万 */
    public static String compact(int n) {
        if (n >= 10000) {
            double w = n / 10000.0;
            return String.format(w >= 10 ? "%.0f万" : "%.1f万", w);
        }
        return String.valueOf(n);
    }

    /** 当前时间 "17:40" */
    public static String nowHm() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
}
