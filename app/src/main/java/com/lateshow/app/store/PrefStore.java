package com.lateshow.app.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lateshow.app.api.Catalogs;
import com.lateshow.app.api.SearchIntent;
import com.lateshow.app.api.ServerCaps;
import com.lateshow.app.model.Models;
import com.lateshow.app.util.ApiCache;
import com.lateshow.app.util.AppLog;
import com.lateshow.app.util.SecureStore;
import com.lateshow.app.util.SourceCache;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地持久化：后端地址 / 主题 / 收藏 / 播放历史。单例。
 *
 * 审计 A-17/A-18/A-23：所有 load-modify-write 统一在同一把锁内完成，并在进程内维护
 * 收藏/历史的内存副本与索引：
 * <ul>
 *   <li>{@link #isFav} 从 O(n) 整表反序列化变成 O(1) 索引查询；</li>
 *   <li>历史写入带 {@code lastPlayedAt} 时间戳仲裁与字段合并，内嵌↔全屏切换窗口内
 *       旧回调不能再把新进度覆盖掉；</li>
 *   <li>JSON 损坏时不会「返回空列表 → 下次写回清空用户数据」，而是标记该键损坏并拒绝写回。</li>
 * </ul>
 */
public final class PrefStore {

    /** 当前默认入口仍是 HTTP；已在 network_security_config.xml 中白名单放行。
     *  后端 HTTPS 就绪后（后端协同 B-09）改为 https:// 并删除明文白名单。 */
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

    /** 所有列表读写的同一把锁（含后台线程与播放器 5s tick 的并发写） */
    private static final Object LOCK = new Object();
    private final Set<String> corruptKeys = ConcurrentHashMap.newKeySet();

    private List<FavItem> favCache;
    private List<HistItem> histCache;
    private List<SavedSource> savedCache;
    private final Map<String, FavItem> favIndex = new HashMap<>();
    private final Map<String, HistItem> histIndex = new HashMap<>();

    /** apiToken 进程内缓存：避免每次请求都做一次 Keystore 解密 */
    private volatile String tokenCache = null;

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

    private static boolean eq(String a, String b) {
        return a != null && a.equals(b);
    }

    private static String key(String slug, long vodId) {
        return (slug == null ? "_" : slug) + "_" + vodId;
    }

    // ---------- 播放源 ----------

    public String baseUrl() {
        String v = sp.getString("base_url", DEFAULT_BASE_URL);
        if (v == null || v.trim().isEmpty()) v = DEFAULT_BASE_URL;
        return trimSlash(v.trim());
    }

    /**
     * 审计 A-08：地址变化后立刻失效所有跨主机缓存与源站展示名，
     * 并重置后端能力门控（避免把上一台服务器的 tag/回填能力误用到新主机）。
     */
    public void setBaseUrl(String url) {
        String next = trimSlash(url == null ? "" : url.trim());
        String old = baseUrl();
        sp.edit().putString("base_url", next).apply();
        if (!next.equals(old)) onCredentialsChanged();
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 地址校验统一出口（审计 A-32 / D-10），返回错误信息或 null */
    public static String validateBaseUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return "请输入后端地址";
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "地址需以 http:// 或 https:// 开头";
        }
        if (u.startsWith("http://") && !isCleartextAllowedHost(u)) {
            return "该 HTTP 主机不在网络安全白名单内，请改用 HTTPS 或联系管理员放行";
        }
        return null;
    }

    private static boolean isCleartextAllowedHost(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host == null) return false;
            return host.equalsIgnoreCase("mosang.asia")
                    || host.toLowerCase(java.util.Locale.US).endsWith(".mosang.asia")
                    || host.equals("100.84.176.119")
                    || host.equals("localhost") || host.equals("10.0.2.2");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 播放源多地址管理 ----------

    /**
     * 已保存的播放源。
     *
     * 审计 A-05：{@code token} 是运行时解密出来的明文，标记为 transient，
     * Gson 不会把它写进 {@code saved_sources}；落盘的只有 {@code tokenEnc}（enc1: 密文）。
     */
    public static class SavedSource {
        public String name;
        public String url;
        public transient String token;
        public String tokenEnc;
    }

    private static final int MAX_SAVED_SOURCES = 10;

    public List<SavedSource> savedSources() {
        synchronized (LOCK) {
            if (savedCache == null) savedCache = loadSavedSources();
            List<SavedSource> out = new ArrayList<>();
            for (SavedSource s : savedCache) out.add(copySaved(s));
            return out;
        }
    }

    /** 保存成功后写入列表：按地址去重，存在则更新 Token/名称；超出上限淘汰最久未用的尾部 */
    public void upsertSavedSource(String url, String token, String name) {
        synchronized (LOCK) {
            List<SavedSource> list = savedSourcesLocked();
            String k = trimSlash(url == null ? "" : url.trim());
            for (SavedSource it : list) {
                if (it.url != null && trimSlash(it.url).equals(k)) {
                    if (token != null) it.token = token.trim();
                    if (name != null && !name.trim().isEmpty()) it.name = name.trim();
                    saveSavedSourcesLocked(list);
                    return;
                }
            }
            SavedSource it = new SavedSource();
            it.url = k;
            it.token = token == null ? "" : token.trim();
            it.name = (name == null || name.trim().isEmpty()) ? hostOf(k) : name.trim();
            list.add(0, it);
            while (list.size() > MAX_SAVED_SOURCES) list.remove(list.size() - 1);
            saveSavedSourcesLocked(list);
        }
    }

    /** 编辑已有播放源：按「编辑前的地址」定位，就地更新名称 / 地址 / Token，并保持位置 */
    public boolean updateSavedSource(String oldUrl, String name, String url, String token) {
        synchronized (LOCK) {
            List<SavedSource> list = savedSourcesLocked();
            String oldKey = trimSlash(oldUrl == null ? "" : oldUrl.trim());
            int target = -1;
            for (int i = 0; i < list.size(); i++) {
                SavedSource s = list.get(i);
                if (s.url != null && trimSlash(s.url).equals(oldKey)) {
                    target = i;
                    break;
                }
            }
            if (target < 0) {
                upsertSavedSource(url, token, name);
                return false;
            }
            String newUrl = trimSlash(url == null ? "" : url.trim());
            SavedSource s = list.get(target);
            s.name = (name == null || name.trim().isEmpty()) ? hostOf(newUrl) : name.trim();
            s.url = newUrl;
            if (token != null) s.token = token.trim();
            for (int i = list.size() - 1; i >= 0; i--) {
                if (i == target) continue;
                SavedSource other = list.get(i);
                if (other.url != null && trimSlash(other.url).equals(newUrl)) list.remove(i);
            }
            saveSavedSourcesLocked(list);
            return true;
        }
    }

    public void removeSavedSource(String url) {
        synchronized (LOCK) {
            List<SavedSource> list = savedSourcesLocked();
            String k = trimSlash(url == null ? "" : url.trim());
            boolean changed = list.removeIf(it -> it.url != null && trimSlash(it.url).equals(k));
            if (changed) saveSavedSourcesLocked(list);
        }
    }

    private List<SavedSource> savedSourcesLocked() {
        if (savedCache == null) savedCache = loadSavedSources();
        return savedCache;
    }

    private void saveSavedSources(List<SavedSource> list) {
        synchronized (LOCK) {
            saveSavedSourcesLocked(list);
        }
    }

    private void saveSavedSourcesLocked(List<SavedSource> list) {
        if (!guardWrite("saved_sources")) return;
        List<SavedSource> copy = new ArrayList<>();
        for (SavedSource s : list) {
            SavedSource c = copySaved(s);
            try {
                c.tokenEnc = c.token == null || c.token.isEmpty()
                        ? "" : SecureStore.encryptForStorage(c.token);
            } catch (SecureStore.SecureStoreException e) {
                AppLog.e("PrefStore", "播放源 Token 加密失败，该条不落盘 Token：" + e.getMessage());
                c.tokenEnc = "";
            }
            copy.add(c);
        }
        savedCache = copy;
        sp.edit().putString("saved_sources", gson.toJson(copy)).apply();
    }

    private List<SavedSource> loadSavedSources() {
        List<SavedSource> list = loadList("saved_sources",
                new TypeToken<List<SavedSource>>() {}.getType());
        for (SavedSource s : list) {
            if (s.tokenEnc != null && !s.tokenEnc.isEmpty()) {
                try {
                    s.token = SecureStore.decryptFromStorage(s.tokenEnc);
                } catch (Exception e) {
                    s.token = "";   // 单条解密失败不影响其它条目；明文不写回
                    AppLog.e("PrefStore", "播放源 Token 解密失败：" + e.getMessage());
                }
            }
        }
        return list;
    }

    private static SavedSource copySaved(SavedSource s) {
        SavedSource c = new SavedSource();
        c.name = s.name;
        c.url = s.url;
        c.token = s.token;
        c.tokenEnc = s.tokenEnc;
        return c;
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

    public static final int MAX_SEARCH_HISTORY = 100;
    private static final String KEY_SEARCH_HISTORY = "search_history";

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

    public void addSearchHistory(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) return;
        synchronized (LOCK) {
            List<String> list = searchHistory();
            for (int i = 0; i < list.size(); i++) {
                String it = list.get(i);
                if (it != null && it.equalsIgnoreCase(k)) {
                    list.remove(i);
                    break;
                }
            }
            list.add(0, k);
            while (list.size() > MAX_SEARCH_HISTORY) list.remove(list.size() - 1);
            sp.edit().putString(KEY_SEARCH_HISTORY, gson.toJson(list)).apply();
        }
    }

    public void removeSearchHistory(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) return;
        synchronized (LOCK) {
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
    }

    public void clearSearchHistory() {
        sp.edit().remove(KEY_SEARCH_HISTORY).apply();
    }

    // ---------- 数据源模式 ----------

    public int dataMode() {
        return sp.getInt("data_mode", 0) == 1 ? 1 : 0;
    }

    public void setDataMode(int mode) {
        sp.edit().putInt("data_mode", mode == 1 ? 1 : 0).apply();
    }

    // ---------- 直连模式代理访问 API 列表 ----------

    public static final String[] DEFAULT_PROXIES = {
            "https://pz.v88.qzz.io/?url=",
            "https://mconfig.viptv.work/?url=",
    };

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

    public static String validateProxy(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return "代理地址不能为空";
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "代理地址需以 http:// 或 https:// 开头";
        return null;
    }

    // ---------- API Token ----------

    /**
     * API Token：Keystore 加密后存本地；空串表示未填写。
     * 进程内缓存解密结果，避免每个网络请求都做一次 Keystore 操作。
     * Keystore 异常时返回空串，但错误原因可从 {@link SecureStore#lastError()} 查询（不再静默）。
     */
    public String apiToken() {
        String c = tokenCache;
        if (c != null) return c;
        synchronized (LOCK) {
            if (tokenCache != null) return tokenCache;
            String v;
            try {
                v = SecureStore.get(sp, "api_token");
            } catch (SecureStore.SecureStoreException e) {
                v = "";
            }
            if (v == null) v = "";
            tokenCache = v.trim();
            return tokenCache;
        }
    }

    public void setApiToken(String token) {
        String next = token == null ? "" : token.trim();
        String old = apiToken();
        try {
            SecureStore.put(sp, "api_token", next);
        } catch (SecureStore.SecureStoreException e) {
            AppLog.e("PrefStore", "Token 写入失败：" + e.getMessage());
            return;
        }
        tokenCache = next;
        if (!next.equals(old)) onCredentialsChanged();
    }

    /** 凭据（地址或 Token）变化：清接口缓存、源站名映射、能力门控与检索意图 */
    private void onCredentialsChanged() {
        ApiCache.clear();
        SourceCache.clear();
        SearchIntent.clear();
        ServerCaps.update("", null);
        Catalogs.markDirty();
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

    public int skipIntroSec() {
        return clampSkip(sp.getInt("skip_intro_sec", 0));
    }

    public void setSkipIntroSec(int sec) {
        sp.edit().putInt("skip_intro_sec", clampSkip(sec)).apply();
    }

    public int skipEndingSec() {
        return clampSkip(sp.getInt("skip_ending_sec", 0));
    }

    public void setSkipEndingSec(int sec) {
        sp.edit().putInt("skip_ending_sec", clampSkip(sec)).apply();
    }

    private static int clampSkip(int sec) {
        if (sec < 0) return 0;
        return Math.min(sec, 600);
    }

    // ---------- 播放偏好：播放地址 / 下载按钮显隐 ----------

    public boolean showStreamTools() {
        return sp.getBoolean("show_stream_tools", false);
    }

    public void setShowStreamTools(boolean show) {
        sp.edit().putBoolean("show_stream_tools", show).apply();
    }

    // ---------- 播放倍速 ----------

    public float playSpeed() {
        float v = sp.getFloat("play_speed", 1f);
        for (float s : SPEEDS) if (s == v) return v;
        return 1f;
    }

    public void setPlaySpeed(float v) {
        sp.edit().putFloat("play_speed", v).apply();
    }

    public static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    // ---------- 收藏 ----------

    /** O(1) 索引查询（审计 A-23）；slug 判空避免旧数据 NPE（A-17） */
    public boolean isFav(String slug, long vodId) {
        if (slug == null) return false;
        synchronized (LOCK) {
            ensureFavsLocked();
            return favIndex.containsKey(key(slug, vodId));
        }
    }

    public void toggleFav(FavItem item) {
        if (item == null || item.slug == null) return;
        synchronized (LOCK) {
            List<FavItem> list = favsLocked();
            for (int i = 0; i < list.size(); i++) {
                FavItem it = list.get(i);
                if (eq(it.slug, item.slug) && it.vodId == item.vodId) {
                    list.remove(i);
                    saveFavsLocked(list);
                    return;
                }
            }
            // 审计 A-16：新增收藏必须带收藏时间，否则卡片显示 01.01 00:00
            if (item.savedAt <= 0) item.savedAt = System.currentTimeMillis();
            list.add(0, item);
            saveFavsLocked(list);
        }
    }

    public List<FavItem> favs() {
        synchronized (LOCK) {
            ensureFavsLocked();
            return new ArrayList<>(favCache);
        }
    }

    public void removeFav(String slug, long vodId) {
        if (slug == null) return;
        synchronized (LOCK) {
            List<FavItem> list = favsLocked();
            boolean changed = list.removeIf(it -> eq(it.slug, slug) && it.vodId == vodId);
            if (changed) saveFavsLocked(list);
        }
    }

    private List<FavItem> favsLocked() {
        ensureFavsLocked();
        return favCache;
    }

    private void ensureFavsLocked() {
        if (favCache == null) {
            favCache = loadList("favs", new TypeToken<List<FavItem>>() {}.getType());
            rebuildFavIndex();
        }
    }

    private void rebuildFavIndex() {
        favIndex.clear();
        for (FavItem f : favCache) if (f != null && f.slug != null) favIndex.put(key(f.slug, f.vodId), f);
    }

    private void saveFavsLocked(List<FavItem> list) {
        if (!guardWrite("favs")) return;
        favCache = new ArrayList<>(list);
        rebuildFavIndex();
        sp.edit().putString("favs", gson.toJson(favCache)).apply();
    }

    /** 用详情响应补齐收藏/历史条目的缓存元信息（简介、分类、分集数、离线可播标记） */
    public void fillOfflineMeta(String slug, long vodId, String typeName, String content,
                                int epCount, boolean cached) {
        if (slug == null) return;
        synchronized (LOCK) {
            List<FavItem> fs = favsLocked();
            boolean fChanged = false;
            for (FavItem f : fs) {
                if (eq(f.slug, slug) && f.vodId == vodId) {
                    if (typeName != null) f.typeName = typeName;
                    if (content != null) f.content = content;
                    if (epCount > 0) f.epCount = epCount;
                    f.cached = cached;
                    fChanged = true;
                }
            }
            if (fChanged) saveFavsLocked(fs);

            List<HistItem> hs = histsLocked();
            boolean hChanged = false;
            for (HistItem h : hs) {
                if (eq(h.slug, slug) && h.vodId == vodId) {
                    if (typeName != null) h.typeName = typeName;
                    if (content != null) h.content = content;
                    if (epCount > 0) h.epCount = epCount;
                    h.cached = cached;
                    hChanged = true;
                }
            }
            if (hChanged) saveHistsLocked(hs);
        }
    }

    public void setOfflineCached(String slug, long vodId, boolean cached) {
        fillOfflineMeta(slug, vodId, null, null, 0, cached);
    }

    /** 离线文件被 LRU 淘汰时由 OfflineMedia 回调，回写「可离线」标记（审计 A-15） */
    public void clearCachedByKey(String offlineKey) {
        if (offlineKey == null) return;
        synchronized (LOCK) {
            List<FavItem> fs = favsLocked();
            boolean fc = false;
            for (FavItem f : fs) {
                if (f.slug != null && key(f.slug, f.vodId).equals(offlineKey) && f.cached) {
                    f.cached = false;
                    fc = true;
                }
            }
            if (fc) saveFavsLocked(fs);
            List<HistItem> hs = histsLocked();
            boolean hc = false;
            for (HistItem h : hs) {
                if (h.slug != null && key(h.slug, h.vodId).equals(offlineKey) && h.cached) {
                    h.cached = false;
                    hc = true;
                }
            }
            if (hc) saveHistsLocked(hs);
        }
    }

    // ---------- 播放历史 ----------

    public HistItem getHist(String slug, long vodId) {
        if (slug == null) return null;
        synchronized (LOCK) {
            ensureHistsLocked();
            return histIndex.get(key(slug, vodId));
        }
    }

    /**
     * 记录/更新播放进度，置顶（审计 A-14）。
     * <ul>
     *   <li>时间戳仲裁：晚于已存条目的写入才生效，旧的异步回调不能回退进度；</li>
     *   <li>字段合并：保留离线展示字段，避免整条替换把简介/集数/可离线标记清空；</li>
     *   <li>全部在同一把锁内完成，后台线程与播放器 tick 并发写不丢数据。</li>
     * </ul>
     */
    public void saveHist(HistItem item) {
        if (item == null || item.slug == null) return;
        synchronized (LOCK) {
            List<HistItem> list = histsLocked();
            HistItem old = null;
            int oldPos = -1;
            for (int i = 0; i < list.size(); i++) {
                HistItem it = list.get(i);
                if (eq(it.slug, item.slug) && it.vodId == item.vodId) {
                    old = it;
                    oldPos = i;
                    break;
                }
            }
            if (old != null && old.lastPlayedAt > item.lastPlayedAt) {
                AppLog.d("PrefStore", "丢弃过期进度写入：" + item.slug + "/" + item.vodId);
                return;
            }
            HistItem merged = mergeHist(old, item);
            if (oldPos >= 0) list.remove(oldPos);
            list.add(0, merged);
            while (list.size() > 200) list.remove(list.size() - 1);
            saveHistsLocked(list);
        }
    }

    private static HistItem mergeHist(HistItem old, HistItem fresh) {
        if (old == null) return fresh;
        if (fresh.sourceName == null) fresh.sourceName = old.sourceName;
        if (fresh.title == null) fresh.title = old.title;
        if (fresh.pic == null) fresh.pic = old.pic;
        if (fresh.remarks == null) fresh.remarks = old.remarks;
        if (fresh.typeName == null) fresh.typeName = old.typeName;
        if (fresh.content == null) fresh.content = old.content;
        if (fresh.epCount <= 0) fresh.epCount = old.epCount;
        if (!fresh.cached) fresh.cached = old.cached;
        if (fresh.epName == null) fresh.epName = old.epName;
        if (fresh.totalSec <= 0) fresh.totalSec = old.totalSec;
        fresh.lastPlayedAt = Math.max(fresh.lastPlayedAt, old.lastPlayedAt);
        return fresh;
    }

    public void clearHist() {
        synchronized (LOCK) {
            histCache = new ArrayList<>();
            histIndex.clear();
            corruptKeys.remove("hists");
            sp.edit().remove("hists").apply();
        }
    }

    public void removeHist(String slug, long vodId) {
        if (slug == null) return;
        synchronized (LOCK) {
            List<HistItem> list = histsLocked();
            boolean changed = list.removeIf(it -> eq(it.slug, slug) && it.vodId == vodId);
            if (changed) saveHistsLocked(list);
        }
    }

    public List<HistItem> hists() {
        synchronized (LOCK) {
            ensureHistsLocked();
            return new ArrayList<>(histCache);
        }
    }

    private List<HistItem> histsLocked() {
        ensureHistsLocked();
        return histCache;
    }

    private void ensureHistsLocked() {
        if (histCache == null) {
            histCache = loadList("hists", new TypeToken<List<HistItem>>() {}.getType());
            rebuildHistIndex();
        }
    }

    private void rebuildHistIndex() {
        histIndex.clear();
        for (HistItem h : histCache) if (h != null && h.slug != null) histIndex.put(key(h.slug, h.vodId), h);
    }

    private void saveHistsLocked(List<HistItem> list) {
        if (!guardWrite("hists")) return;
        histCache = new ArrayList<>(list);
        rebuildHistIndex();
        sp.edit().putString("hists", gson.toJson(histCache)).apply();
    }

    // ---------- JSON 损坏保护（审计 A-17） ----------

    private <T> List<T> loadList(String key, Type type) {
        String s = sp.getString(key, null);
        if (s == null) {
            corruptKeys.remove(key);
            return new ArrayList<>();
        }
        try {
            List<T> list = gson.fromJson(s, type);
            corruptKeys.remove(key);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            corruptKeys.add(key);
            AppLog.e("PrefStore", "列表解析失败，已阻止写回以避免清空用户数据：" + key);
            return new ArrayList<>();
        }
    }

    /** 数据损坏时拒绝写回，避免「读空 → 整表覆盖」一次性清空收藏/历史/播放源 */
    private boolean guardWrite(String key) {
        if (corruptKeys.contains(key)) {
            AppLog.e("PrefStore", "拒绝写入损坏键 " + key + "，请先备份并清理该键");
            return false;
        }
        return true;
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
