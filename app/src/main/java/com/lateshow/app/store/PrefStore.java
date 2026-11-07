package com.lateshow.app.store;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lateshow.app.model.Models;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** 本地持久化：后端地址 / 主题 / 收藏 / 播放历史。单例。 */
public final class PrefStore {

    public static final String DEFAULT_BASE_URL = "http://mosang.asia:8181";
    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_AUTO = 2;

    /** 收藏条目（含离线播放所需的详情摘要；完整线路/选集见 OfflineMedia） */
    public static class FavItem {
        public String slug;
        public String sourceName;
        public long vodId;
        public String title;
        public String pic;
        public String remarks;
        public String typeName;
        public String content;   // 简介（离线详情页展示）
        public int epCount;      // 分集数（离线选集网格标题）
        public boolean cached;   // 是否已缓存完整播放数据（线路 + 每集播放地址）
        public long savedAt;
    }

    /** 播放历史条目（断点续播 + 离线播放兜底） */
    public static class HistItem {
        public String slug;
        public String sourceName;
        public long vodId;
        public String title;
        public String pic;
        public String remarks;
        public String typeName;
        public String content;
        public int epCount;
        public boolean cached;
        /** 旧字段：线路在当次列表里的数组下标。仅用于兼容升级前的历史，不再作为主判断 */
        public int lineIndex;
        /**
         * VodSync v1.11.0：线路稳定标识（vod_play_list[].original_index）。
         * 服务端会剔除分享页线路并做直链优先排序，数组下标会错位，必须按原始下标记忆。
         * null = 升级前的老历史或服务端未提供该字段，此时回退到 lineIndex。
         */
        public Integer lineKey;
        public int epIndex;
        public String epName;
        public long playedSec;
        public long totalSec;
        public long lastPlayedAt;
    }

    private static volatile PrefStore sInstance;
    private final SharedPreferences sp;
    private final Gson gson = new Gson();

    private PrefStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("lateshow", Context.MODE_PRIVATE);
    }

    public static PrefStore get() {
        if (sInstance == null) throw new IllegalStateException("PrefStore.init(context) 未调用");
        return sInstance;
    }

    public static void init(Context ctx) {
        if (sInstance == null) {
            synchronized (PrefStore.class) {
                if (sInstance == null) sInstance = new PrefStore(ctx);
            }
        }
    }

    // ---------- 播放源 ----------

    public String baseUrl() {
        String v = sp.getString("base_url", DEFAULT_BASE_URL);
        if (v == null || v.trim().isEmpty()) v = DEFAULT_BASE_URL;
        return trimSlash(v.trim());
    }

    public void setBaseUrl(String url) {
        sp.edit().putString("base_url", trimSlash(url.trim())).apply();
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ---------- 播放源多地址管理（保存成功的地址 + Token 列表） ----------

    /** 已保存的播放源：名称 + 后端地址 + API Token（Token 经 Keystore 加密存储） */
    public static class SavedSource {
        public String name;
        public String url;
        public String token;
    }

    private static final int MAX_SAVED_SOURCES = 10;

    public List<SavedSource> savedSources() {
        return loadList("saved_sources", new TypeToken<List<SavedSource>>() {}.getType());
    }

    /** 保存成功后写入列表：按地址去重，存在则更新 Token/名称；超出上限淘汰最久未用的尾部 */
    public void upsertSavedSource(String url, String token, String name) {
        List<SavedSource> list = savedSources();
        String key = trimSlash(url == null ? "" : url.trim());
        for (SavedSource it : list) {
            if (it.url != null && trimSlash(it.url).equals(key)) {
                if (token != null) it.token = token.trim();
                if (name != null && !name.trim().isEmpty()) it.name = name.trim();
                saveSavedSources(list);
                return;
            }
        }
        SavedSource it = new SavedSource();
        it.url = key;
        it.token = token == null ? "" : token.trim();
        it.name = (name == null || name.trim().isEmpty()) ? hostOf(key) : name.trim();
        list.add(0, it);
        while (list.size() > MAX_SAVED_SOURCES) list.remove(list.size() - 1);
        saveSavedSources(list);
    }

    public void removeSavedSource(String url) {
        List<SavedSource> list = savedSources();
        String key = trimSlash(url == null ? "" : url.trim());
        boolean changed = list.removeIf(it -> it.url != null && trimSlash(it.url).equals(key));
        if (changed) saveSavedSources(list);
    }

    private void saveSavedSources(List<SavedSource> list) {
        sp.edit().putString("saved_sources", gson.toJson(list)).apply();
    }

    /** 编辑列表后整体写回（地址去重 / 更新 Token 等） */
    public void saveSavedSourcesList(List<SavedSource> list) {
        saveSavedSources(list);
    }

    private static String hostOf(String url) {
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String h = u.getHost();
            return h == null || h.isEmpty() ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    // ---------- 搜索历史 ----------

    /** 历史记录上限：超出后淘汰最旧的（列表尾部） */
    public static final int MAX_SEARCH_HISTORY = 100;
    private static final String KEY_SEARCH_HISTORY = "search_history";

    /** 搜索历史：最新在前；解析失败时返回空列表 */
    public List<String> searchHistory() {
        String s = sp.getString(KEY_SEARCH_HISTORY, null);
        if (s != null) {
            try {
                List<String> list = gson.fromJson(s, new TypeToken<List<String>>() {}.getType());
                if (list != null) return list;
            } catch (Exception ignored) { }
        }
        return new ArrayList<>();
    }

    /**
     * 记录一次搜索：去重（忽略大小写）后置顶，超过 {@link #MAX_SEARCH_HISTORY} 条淘汰最旧。
     * 空串直接忽略，避免记录无意义关键词。
     */
    public void addSearchHistory(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) return;
        List<String> list = searchHistory();
        for (int i = 0; i < list.size(); i++) {
            String it = list.get(i);
            if (it != null && it.equalsIgnoreCase(k)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, k);
        while (list.size() > MAX_SEARCH_HISTORY) {
            list.remove(list.size() - 1);
        }
        sp.edit().putString(KEY_SEARCH_HISTORY, gson.toJson(list)).apply();
    }

    /** 删除单条历史（忽略大小写匹配） */
    public void removeSearchHistory(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) return;
        List<String> list = searchHistory();
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            String it = list.get(i);
            if (it != null && it.equalsIgnoreCase(k)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) sp.edit().putString(KEY_SEARCH_HISTORY, gson.toJson(list)).apply();
    }

    /** 一键清空全部搜索历史 */
    public void clearSearchHistory() {
        sp.edit().remove(KEY_SEARCH_HISTORY).apply();
    }

    // ---------- 数据源模式 ----------

    /** 数据源模式：0=NAS 同步库（默认），1=资源站直连（NAS 关机时的兜底） */
    public int dataMode() {
        return sp.getInt("data_mode", 0) == 1 ? 1 : 0;
    }

    public void setDataMode(int mode) {
        sp.edit().putInt("data_mode", mode == 1 ? 1 : 0).apply();
    }

    // ---------- 直连模式代理访问 API 列表 ----------

    /** 内置默认代理（与 VodSync 后端 PROXY_URLS 一致），用户可在设置页增删改 */
    public static final String[] DEFAULT_PROXIES = {
            "https://pz.v88.qzz.io/?url=",
            "https://mconfig.viptv.work/?url=",
    };

    /** 代理列表：未自定义过时返回内置默认；按列表顺序依次尝试 */
    public List<String> proxies() {
        String s = sp.getString("proxy_list", null);
        if (s != null) {
            try {
                List<String> list = gson.fromJson(s, new TypeToken<List<String>>() {}.getType());
                if (list != null) return list;
            } catch (Exception ignored) { }
        }
        List<String> def = new ArrayList<>();
        for (String p : DEFAULT_PROXIES) def.add(p);
        return def;
    }

    public void setProxies(List<String> list) {
        sp.edit().putString("proxy_list", gson.toJson(list == null ? new ArrayList<>() : list)).apply();
    }

    /** 新增/修改代理（index=-1 表示新增）；校验：http(s) 开头；返回错误信息，null 表示通过 */
    public static String validateProxy(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return "代理地址不能为空";
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "代理地址需以 http:// 或 https:// 开头";
        return null;
    }

    // ---------- API Token（服务端 v1.5.0+ 访问控制） ----------

    /** API Token：Keystore 加密后存本地；空串表示未填写 */
    public String apiToken() {
        String t = com.lateshow.app.util.SecureStore.get(sp, "api_token");
        return t == null ? "" : t.trim();
    }

    public void setApiToken(String token) {
        com.lateshow.app.util.SecureStore.put(sp, "api_token", token == null ? "" : token.trim());
    }

    public boolean hasToken() {
        return !apiToken().isEmpty();
    }

    // ---------- 主题 ----------

    public int theme() {
        return sp.getInt("theme", THEME_DARK);
    }

    public void setTheme(int t) {
        sp.edit().putInt("theme", t).apply();
    }

    // ---------- 播放偏好：跳过片头/片尾 ----------

    /** 跳过片头秒数：新视频（无历史进度）从该秒开始播放；0 = 不跳过 */
    public int skipIntroSec() {
        return clampSkip(sp.getInt("skip_intro_sec", 0));
    }

    public void setSkipIntroSec(int sec) {
        sp.edit().putInt("skip_intro_sec", clampSkip(sec)).apply();
    }

    /** 跳过片尾秒数：播放到「总时长 - 该秒数」时自动结束；0 = 不跳过 */
    public int skipEndingSec() {
        return clampSkip(sp.getInt("skip_ending_sec", 0));
    }

    public void setSkipEndingSec(int sec) {
        sp.edit().putInt("skip_ending_sec", clampSkip(sec)).apply();
    }

    private static int clampSkip(int sec) {
        if (sec < 0) return 0;
        return Math.min(sec, 600); // 上限 10 分钟
    }

    // ---------- 播放倍速 ----------

    /** 上次使用的播放倍速（内嵌/全屏共用），默认 1.0 */
    public float playSpeed() {
        float v = sp.getFloat("play_speed", 1f);
        for (float s : SPEEDS) if (s == v) return v;
        return 1f;
    }

    public void setPlaySpeed(float v) {
        sp.edit().putFloat("play_speed", v).apply();
    }

    /** 可选倍速档位 */
    public static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    // ---------- 收藏 ----------

    public boolean isFav(String slug, long vodId) {
        for (FavItem it : favs()) {
            if (it.slug.equals(slug) && it.vodId == vodId) return true;
        }
        return false;
    }

    public void toggleFav(FavItem item) {
        List<FavItem> list = favs();
        for (int i = 0; i < list.size(); i++) {
            FavItem it = list.get(i);
            if (it.slug.equals(item.slug) && it.vodId == item.vodId) {
                list.remove(i);
                saveFavs(list);
                return;
            }
        }
        list.add(0, item);
        saveFavs(list);
    }

    public List<FavItem> favs() {
        return loadList("favs", new TypeToken<List<FavItem>>() {}.getType());
    }

    /** 删除单条收藏（列表封面上的删除按钮） */
    public void removeFav(String slug, long vodId) {
        List<FavItem> list = favs();
        boolean changed = list.removeIf(it -> it.slug != null && it.slug.equals(slug) && it.vodId == vodId);
        if (changed) saveFavs(list);
    }

    private void saveFavs(List<FavItem> list) {
        sp.edit().putString("favs", gson.toJson(list)).apply();
    }

    /** 用详情响应补齐收藏/历史条目的缓存元信息（简介、分类、分集数、离线可播标记） */
    public void fillOfflineMeta(String slug, long vodId, String typeName, String content, int epCount, boolean cached) {
        List<FavItem> fs = favs();
        boolean fChanged = false;
        for (FavItem f : fs) {
            if (f.slug != null && f.slug.equals(slug) && f.vodId == vodId) {
                if (typeName != null) f.typeName = typeName;
                if (content != null) f.content = content;
                if (epCount > 0) f.epCount = epCount;
                f.cached = cached;
                fChanged = true;
            }
        }
        if (fChanged) saveFavs(fs);

        List<HistItem> hs = hists();
        boolean hChanged = false;
        for (HistItem h : hs) {
            if (h.slug != null && h.slug.equals(slug) && h.vodId == vodId) {
                if (typeName != null) h.typeName = typeName;
                if (content != null) h.content = content;
                if (epCount > 0) h.epCount = epCount;
                h.cached = cached;
                hChanged = true;
            }
        }
        if (hChanged) sp.edit().putString("hists", gson.toJson(hs)).apply();
    }

    /** 仅更新离线可播标记（取消收藏且无历史时置 false） */
    public void setOfflineCached(String slug, long vodId, boolean cached) {
        fillOfflineMeta(slug, vodId, null, null, 0, cached);
    }

    // ---------- 播放历史 ----------

    public HistItem getHist(String slug, long vodId) {
        for (HistItem it : hists()) {
            if (it.slug.equals(slug) && it.vodId == vodId) return it;
        }
        return null;
    }

    /** 记录/更新播放进度，置顶 */
    public void saveHist(HistItem item) {
        List<HistItem> list = hists();
        for (int i = 0; i < list.size(); i++) {
            HistItem it = list.get(i);
            if (it.slug.equals(item.slug) && it.vodId == item.vodId) {
                list.remove(i);
                break;
            }
        }
        list.add(0, item);
        while (list.size() > 200) list.remove(list.size() - 1);
        sp.edit().putString("hists", gson.toJson(list)).apply();
    }

    public void clearHist() {
        sp.edit().remove("hists").apply();
    }

    /** 删除单条播放历史（列表封面上的删除按钮） */
    public void removeHist(String slug, long vodId) {
        List<HistItem> list = hists();
        boolean changed = list.removeIf(it -> it.slug != null && it.slug.equals(slug) && it.vodId == vodId);
        if (changed) sp.edit().putString("hists", gson.toJson(list)).apply();
    }

    public List<HistItem> hists() {
        return loadList("hists", new TypeToken<List<HistItem>>() {}.getType());
    }

    // ---------- 源站自动同步勾选缓存（可选） ----------

    private <T> List<T> loadList(String key, Type type) {
        String s = sp.getString(key, null);
        if (s == null) return new ArrayList<>();
        try {
            List<T> list = gson.fromJson(s, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---------- 源站选择记忆 ----------

    public String currentSourceSlug() {
        return sp.getString("cur_source", "");
    }

    public String currentSourceName() {
        return sp.getString("cur_source_name", "全部源站");
    }

    public void setCurrentSource(String slug, String name) {
        sp.edit().putString("cur_source", slug).putString("cur_source_name", name).apply();
    }

    /** 首页使用的 Models.VideoSummary 快照转换（用于写收藏/历史） */
    public static FavItem toFav(Models.VideoSummary v) {
        FavItem f = new FavItem();
        f.slug = v.source;
        f.sourceName = v.sourceName != null ? v.sourceName : v.source;
        f.vodId = v.vodId;
        f.title = v.vodName;
        f.pic = v.vodPic;
        f.remarks = v.vodRemarks;
        f.typeName = v.typeName;
        f.savedAt = System.currentTimeMillis();
        return f;
    }

    public static HistItem toHist(Models.VideoDetail v) {
        HistItem h = new HistItem();
        h.slug = v.source;
        h.sourceName = v.sourceName != null ? v.sourceName : v.source;
        h.vodId = v.vodId;
        h.title = v.vodName;
        h.pic = v.vodPic;
        h.remarks = v.vodRemarks;
        h.lastPlayedAt = System.currentTimeMillis();
        return h;
    }
}
