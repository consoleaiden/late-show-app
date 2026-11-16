package com.lateshow.app.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lateshow.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题引擎：预设 + 自定义配色方案的持久化、解析与运行时换肤。
 *
 * 设计要点：
 *  <ul>
 *    <li>主题方案（{@link ThemeScheme}）是一份 JSON，存在 SharedPreferences；</li>
 *    <li>亮色 / 暗色各自绑定一个方案 id（{@code lightSchemeId} / {@code darkSchemeId}），
 *        默认「方寸」= 原应用内置明暗配色；</li>
 *    <li>{@link #applyRuntime(Context, View)} 把当前生效方案的解析色写到窗口背景 / 系统栏 /
 *        各毛玻璃栏，Activity 重建后即可复用（无需每次 onCreate 都读盘）；</li>
 *    <li>辅助色（{@code accent2}）默认由主色按 HSL 偏移自动生成，用户可覆盖为任意值。</li>
 *  </ul>
 */
public final class ThemeEngine {

    /** 保留原内置配色的方案 id（不可删除） */
    public static final String ID_FANG_CUN = "fangcun";

    // -------------------- 毛玻璃默认值 --------------------
    // 毛玻璃（模糊 + 白雾）是「有默认值」的：预设与旧版落盘方案都取这组值，
    // 保证用户没主动调过时观感稳定，也避免老方案缺字段反序列化成 0 导致玻璃消失。
    public static final boolean DEF_GLASS_ON = true;
    public static final int DEF_GLASS_BLUR = 36;    // 模糊强度 0~100
    public static final int DEF_GLASS_FROST = 40;   // 白雾浓度 0~100
    public static final int DEF_GLASS_ALPHA = 88;   // 底色浓度 0~100

    private static volatile ThemeEngine sInstance;

    private final SharedPreferences sp;
    private final Gson gson = new Gson();

    // ---- 进程内缓存：避免高频读盘（列表滑动、滚动等场景都会取色） ----
    @Nullable private List<ThemeScheme> cachedList;
    @Nullable private ThemeScheme cachedLight, cachedDark;
    private int cacheVersion = -1;

    private static final String KEY_SCHEMES = "theme_schemes";
    private static final String KEY_LIGHT = "theme_light_id";
    private static final String KEY_DARK = "theme_dark_id";

    private ThemeEngine(Context ctx) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences("lateshow_theme", Context.MODE_PRIVATE);
    }

    public static ThemeEngine get() {
        if (sInstance == null) {
            throw new IllegalStateException("ThemeEngine.init(context) 未调用");
        }
        return sInstance;
    }

    public static void init(Context ctx) {
        if (sInstance == null) {
            synchronized (ThemeEngine.class) {
                if (sInstance == null) sInstance = new ThemeEngine(ctx);
            }
        }
    }

    // ==================== 数据模型 ====================

    /**
     * 一套主题配色方案。
     *
     * 可配置项（全部开放给用户）：
     *  - name            方案名
     *  - primary         主色（选中态 / 按钮 / 强调）
     *  - secondary       辅助色（自动生成，可改）
     *  - bgMode          页面背景：纯色 / 渐变
     *  - bgColor         纯色背景
     *  - bgFrom/bgTo     渐变色（bgMode=1 时生效）
     *  - bgAngle         渐变角度（度，0=从左到右，90=从上到下，顺时针旋转）
     *  - surface         卡片面 / 顶栏玻璃底
     *  - textPrimary     一级文字
     *  - textSecondary   二级文字
     *  - navAlpha        底部导航栏不透明度（0~100）
     *  - glassOn         毛玻璃总开关（默认开）
     *  - barBlur         毛玻璃模糊强度（0~100）
     *  - barFrost        毛玻璃白雾浓度（0~100）
     *  - barAlpha        毛玻璃底色浓度（0~100）
     */
    public static class ThemeScheme {
        public String id;
        public String name;
        public int primary;
        public int secondary;
        public boolean secondaryAuto;   // true = 跟随主色自动生成
        public int bgMode;              // 0 纯色 / 1 渐变
        public int bgColor;
        public int bgFrom;
        public int bgTo;
        public int bgAngle;
        public int surface;
        public int textPrimary;
        public int textSecondary;
        public int navAlpha;
        /** 毛玻璃总开关（默认开）；字段初始化保证老方案 JSON 缺 key 时不会变成「关」 */
        public boolean glassOn = DEF_GLASS_ON;
        public int barBlur = DEF_GLASS_BLUR;
        public int barFrost = DEF_GLASS_FROST;
        public int barAlpha = DEF_GLASS_ALPHA;

        public ThemeScheme() {}

        /** 复制构造（编辑时基于副本修改） */
        public ThemeScheme copy() {
            ThemeScheme c = new ThemeScheme();
            c.id = id;
            c.name = name;
            c.primary = primary;
            c.secondary = secondary;
            c.secondaryAuto = secondaryAuto;
            c.bgMode = bgMode;
            c.bgColor = bgColor;
            c.bgFrom = bgFrom;
            c.bgTo = bgTo;
            c.bgAngle = bgAngle;
            c.surface = surface;
            c.textPrimary = textPrimary;
            c.textSecondary = textSecondary;
            c.navAlpha = navAlpha;
            c.glassOn = glassOn;
            c.barBlur = barBlur;
            c.barFrost = barFrost;
            c.barAlpha = barAlpha;
            return c;
        }
    }

    // ==================== 预设包（Ant Design 色板派生） ====================

    /**
     * Ant Design 九种基本色（规范 spec/colors「ANTD Color」）。
     *
     * <p>规范页 Magenta 一项原文写作 {@code #E9259}（只有 5 位十六进制，为该页笔误），
     * 这里取 Ant Design 标准玫红 {@code #EB2F96}。
     */
    public static final int AD_GREY    = 0xFFCCCCCC;
    public static final int AD_RED     = 0xFFE01515;
    public static final int AD_GREEN   = 0xFF60BE29;
    public static final int AD_BLUE    = 0xFF00A0E8;
    public static final int AD_MAGENTA = 0xFFEB2F96;
    public static final int AD_ORANGE  = 0xFFFF6100;
    public static final int AD_PURPLE  = 0xFF5E30B5;
    public static final int AD_YELLOW  = 0xFFFAC450;
    public static final int AD_CYAN    = 0xFF01BAD2;

    /** 中性灰阶基色（规范 Grey）：文字与中性底都从它派生 */
    private static final int AD_NEUTRAL = AD_GREY;
    /** 暗底主 / 次文字（中性，不随方案色相走，保证深色底上的稳定对比） */
    private static final int INK_DARK_HI = 0xFFECEDEF;
    private static final int INK_DARK_MD = 0xFF98A0AA;
    /** 亮底表面 */
    private static final int SURFACE_LIGHT = 0xFFFFFFFF;

    /** 派生色调：0 = 亮色（浅底白面），1 = 亮色·浓（底再深一档），2 = 暗色（深底反白字） */
    private static final int TONE_LIGHT = 0;
    private static final int TONE_LIGHT_DEEP = 1;
    private static final int TONE_DARK = 2;

    /**
     * 按规范「叠白 / 叠黑」规则生成 10 档色阶：0 档最浅（做底色），4 档为基色，9 档最深。
     *
     * <p>规范给的是「n+5% 递进」的精神；这里把叠加比例拉开到可用区间，
     * 否则 5% 一步既得不到足够浅的底、也得不到足够深的面。
     */
    public static int[] scale10(int base) {
        int[] c = new int[10];
        c[4] = base;
        float[] tint = {0.85f, 0.65f, 0.45f, 0.25f};   // 3~0 档：叠白提浅
        for (int i = 0; i < 4; i++) c[3 - i] = mix(base, 0xFFFFFFFF, tint[i]);
        float[] shade = {0.20f, 0.35f, 0.50f, 0.65f, 0.80f};   // 5~9 档：叠黑压暗
        for (int i = 0; i < 5; i++) c[5 + i] = mix(base, 0xFF000000, shade[i]);
        return c;
    }

    /** 两色按 p 比例混合（p 为 to 的占比） */
    public static int mix(int from, int to, float p) {
        return Color.argb(Color.alpha(from),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * p),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * p),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * p));
    }

    /**
     * 预设主题：全部由 Ant Design 九种基本色派生。
     *
     * <p>保留原有 id 与命名（用户已保存 / 已绑定的方案不受影响），但每个方案的整套色板
     * 改为从 Ant Design 基色的 10 档色阶里取：亮色方案取叠白端做底 + 白表面 + 灰阶文字，
     * 暗色方案取叠黑端做底与面 + 反白文字。命名沿用产品既有意象，基色按意象色相对应到
     * 最近的 Ant Design 基本色。
     */
    public static List<ThemeScheme> presets() {
        List<ThemeScheme> list = new ArrayList<>();
        // ① 方寸（内置品牌配色，始终以代码为准、置于首位）
        list.add(scheme(ID_FANG_CUN, "方寸",
                0xFFFF5A36, 0xFFFE5A35,
                0xFFF2ECE6, 0xFF1B1412, 0xFFEDE5DD, true));
        // ② 以下 20 个：Ant Design 基本色 × 三种色调
        list.add(adPreset("mint", "薄荷气泡", AD_CYAN, TONE_LIGHT));
        list.add(adPreset("neon", "暗夜霓虹", AD_PURPLE, TONE_DARK));
        list.add(adPreset("lemon", "柠檬汽水", AD_YELLOW, TONE_LIGHT_DEEP));
        list.add(adPreset("mono", "经典墨黑", AD_GREY, TONE_DARK));
        list.add(adPreset("ocean", "碧海蓝天", AD_BLUE, TONE_LIGHT));
        list.add(adPreset("berry", "蓝莓软糖", AD_MAGENTA, TONE_LIGHT));
        list.add(adPreset("latte", "奶油拿铁", AD_YELLOW, TONE_LIGHT));
        list.add(adPreset("loft", "灰调公寓", AD_GREY, TONE_LIGHT));
        list.add(adPreset("peach", "蜜桃软糖", AD_RED, TONE_LIGHT));
        list.add(adPreset("sunset", "珊瑚日落", AD_ORANGE, TONE_LIGHT_DEEP));
        list.add(adPreset("forest", "雨林光影", AD_GREEN, TONE_DARK));
        list.add(adPreset("sky", "晴空万里", AD_CYAN, TONE_LIGHT_DEEP));
        list.add(adPreset("lavender", "薰衣草紫", AD_PURPLE, TONE_LIGHT));
        list.add(adPreset("deepsea", "深海蓝调", AD_BLUE, TONE_DARK));
        list.add(adPreset("taro", "紫芋奶盖", AD_MAGENTA, TONE_LIGHT_DEEP));
        list.add(adPreset("matcha", "抹茶牛乳", AD_GREEN, TONE_LIGHT));
        list.add(adPreset("soda", "薄荷汽水", AD_CYAN, TONE_DARK));
        list.add(adPreset("grape", "葡萄冰茶", AD_PURPLE, TONE_LIGHT_DEEP));
        list.add(adPreset("orange", "活力橙", AD_ORANGE, TONE_LIGHT));
        list.add(adPreset("calm", "静谧蓝", AD_BLUE, TONE_LIGHT_DEEP));
        return list;
    }

    /** 由 Ant Design 基色派生一整套方案（主色 / 辅助色 / 底 / 面 / 文字全部来自同一色阶） */
    private static ThemeScheme adPreset(String id, String name, int base, int tone) {
        int[] sc = scale10(base);
        int[] g = scale10(AD_NEUTRAL);
        ThemeScheme s = new ThemeScheme();
        s.id = id;
        s.name = name;
        s.primary = base;
        s.secondaryAuto = false;
        s.bgMode = 0;
        s.bgAngle = 135;
        s.navAlpha = 88;
        s.glassOn = DEF_GLASS_ON;
        s.barBlur = DEF_GLASS_BLUR;
        s.barFrost = DEF_GLASS_FROST;
        s.barAlpha = DEF_GLASS_ALPHA;
        if (tone == TONE_DARK) {
            s.secondary = sc[2];
            s.bgColor = s.bgFrom = sc[9];
            s.bgTo = sc[8];
            s.surface = sc[8];
            s.textPrimary = INK_DARK_HI;
            s.textSecondary = INK_DARK_MD;
        } else {
            s.secondary = sc[6];
            s.surface = SURFACE_LIGHT;
            s.textPrimary = g[9];
            s.textSecondary = g[6];
            if (tone == TONE_LIGHT_DEEP) {
                s.bgColor = s.bgFrom = sc[1];
                s.bgTo = sc[2];
            } else {
                s.bgColor = s.bgFrom = sc[0];
                s.bgTo = sc[1];
            }
        }
        return s;
    }

    private static ThemeScheme scheme(String id, String name, int primary, int surface,
                                      int bgColor, int surface2, int bgTo,
                                      boolean secondaryAuto) {
        return scheme(id, name, primary, surface, bgColor, surface2, bgTo, secondaryAuto, 0);
    }

    private static ThemeScheme scheme(String id, String name, int primary, int surface,
                                      int bgColor, int surface2, int bgTo,
                                      boolean secondaryAuto, int secondary) {
        ThemeScheme s = new ThemeScheme();
        s.id = id;
        s.name = name;
        s.primary = primary;
        s.secondaryAuto = secondaryAuto;
        s.secondary = secondaryAuto ? autoSecondary(primary) : secondary;
        s.bgMode = 0;
        s.bgColor = bgColor;
        s.bgFrom = bgColor;
        s.bgTo = bgTo;
        s.bgAngle = 135;
        s.surface = surface2;
        s.textPrimary = isLight(surface2) ? 0xFF1B2026 : 0xFFECEDEF;
        s.textSecondary = isLight(surface2) ? 0xFF5C6672 : 0xFF98A0AA;
        s.navAlpha = 88;
        s.glassOn = DEF_GLASS_ON;
        s.barBlur = DEF_GLASS_BLUR;
        s.barFrost = DEF_GLASS_FROST;
        s.barAlpha = DEF_GLASS_ALPHA;
        return s;
    }

    /** 由主色按 HSL 偏移自动生成辅助色（色相 +25°，明度轻提，保证同族协调） */
    public static int autoSecondary(int primary) {
        float[] hsl = new float[3];
        Color.colorToHSV(primary, hsl);
        float h = (hsl[0] + 25f) % 360f;
        float s = Math.max(0.18f, Math.min(0.9f, hsl[1]));
        float l = Math.max(0.32f, Math.min(0.72f, hsl[2] * 0.55f + 0.25f));
        return Color.HSVToColor(new float[]{h, s, l});
    }

    private static boolean isLight(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return lum >= 160;
    }

    // ==================== 持久化 ====================

    /**
     * 全部方案 = 内置预设（含「方寸」）+ 用户创建/修改过的。
     * 用户保存的方案若与预设同 id 则覆盖预设；「方寸」始终以代码定义为准且置于首位。
     */
    public List<ThemeScheme> schemes() {
        synchronized (this) {
            int ver = sp.getInt("__ver", 0);
            if (cachedList != null && ver == cacheVersion) return copyList(cachedList);
            String raw = sp.getString(KEY_SCHEMES, null);
            List<ThemeScheme> user = new ArrayList<>();
            if (raw != null && !raw.isEmpty()) {
                try {
                    user = parseSchemes(raw);
                } catch (Exception ignored) { }
            }
            java.util.LinkedHashMap<String, ThemeScheme> byId = new java.util.LinkedHashMap<>();
            for (ThemeScheme p : presets()) byId.put(p.id, p);
            for (ThemeScheme u : user) {
                if (u == null || u.id == null) continue;
                if (ID_FANG_CUN.equals(u.id)) continue;   // 内置方案始终用最新代码定义
                // 旧版本可能把未修改的预设快照落盘：与当前代码定义一致时直接忽略，
                // 让预设始终跟随最新版本。
                ThemeScheme preset = findPreset(u.id);
                if (preset != null && sameScheme(preset, u)) continue;
                byId.put(u.id, u);
            }
            List<ThemeScheme> all = new ArrayList<>();
            all.add(fangcunDefault());
            for (ThemeScheme s : byId.values()) {
                if (!ID_FANG_CUN.equals(s.id)) all.add(s);
            }
            cachedList = all;
            cacheVersion = ver;
            return copyList(all);
        }
    }

    private static ThemeScheme fangcunDefault() {
        ThemeScheme s = new ThemeScheme();
        s.id = ID_FANG_CUN;
        s.name = "方寸";
        s.primary = 0xFFFF5A36;
        s.secondaryAuto = true;
        s.secondary = 0xFFFE5A35;
        s.bgMode = 0;
        s.bgColor = 0xFF0A0C0F;   // 运行时按模式分别解析，这里给深色占位
        s.bgFrom = 0xFF0A0C0F;
        s.bgTo = 0xFF0A0C0F;
        s.bgAngle = 135;
        s.surface = 0xFF101318;
        s.textPrimary = 0xFFECEDEF;
        s.textSecondary = 0xFF98A0AA;
        s.navAlpha = 88;
        s.glassOn = DEF_GLASS_ON;
        s.barBlur = DEF_GLASS_BLUR;
        s.barFrost = DEF_GLASS_FROST;
        s.barAlpha = DEF_GLASS_ALPHA;
        return s;
    }

    private static List<ThemeScheme> copyList(List<ThemeScheme> in) {        List<ThemeScheme> out = new ArrayList<>(in.size());
        for (ThemeScheme s : in) out.add(s.copy());
        return out;
    }

    /**
     * 解析落盘方案并补默认值。
     *
     * <p>不能直接 {@code gson.fromJson}：Gson 对 JSON 里缺失的 int 给 0、缺失的 boolean 给
     * false。老版本保存的方案没有 glassOn / barBlur / barFrost / barAlpha 字段，
     * 直接反序列化会得到「玻璃关 + 模糊 0 + 白雾 0」—— 毛玻璃整块消失。
     * 这里按「字段是否存在」判断，缺的补 {@link #DEF_GLASS_ON} 等默认值。
     */
    private List<ThemeScheme> parseSchemes(String raw) {
        JsonArray arr = JsonParser.parseString(raw).getAsJsonArray();
        List<ThemeScheme> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            ThemeScheme s = gson.fromJson(o, ThemeScheme.class);
            if (s == null) continue;
            if (!o.has("glassOn")) s.glassOn = DEF_GLASS_ON;
            if (!o.has("barBlur")) s.barBlur = DEF_GLASS_BLUR;
            if (!o.has("barFrost")) s.barFrost = DEF_GLASS_FROST;
            if (!o.has("barAlpha")) s.barAlpha = DEF_GLASS_ALPHA;
            if (!o.has("navAlpha")) s.navAlpha = 88;
            out.add(s);
        }
        return out;
    }

    /** 新增或更新方案（id 相同则覆盖） */
    public synchronized void save(ThemeScheme scheme) {
        if (scheme == null || scheme.id == null) return;
        List<ThemeScheme> all = new ArrayList<>(schemes());
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (scheme.id.equals(all.get(i).id)) { idx = i; break; }
        }
        if (idx >= 0) all.set(idx, scheme); else all.add(scheme);
        persist(all);
    }

    public synchronized void delete(String id) {
        if (id == null || ID_FANG_CUN.equals(id)) return;   // 内置不可删
        List<ThemeScheme> all = new ArrayList<>(schemes());
        all.removeIf(s -> id.equals(s.id));
        persist(all);
        // 被删方案若正被某模式绑定 → 回退到方寸
        if (id.equals(lightId())) setLightId(ID_FANG_CUN);
        if (id.equals(darkId())) setDarkId(ID_FANG_CUN);
    }

    private void persist(List<ThemeScheme> all) {
        // 只落盘「用户自定义」与「被改过的预设」：
        //  - 内置方寸始终以代码为准，绝不落盘；
        //  - 未修改的预设也不落盘，否则应用升级改了预设配色，老用户会被旧快照锁死。
        List<ThemeScheme> toSave = new ArrayList<>();
        for (ThemeScheme s : all) {
            if (ID_FANG_CUN.equals(s.id)) continue;
            ThemeScheme preset = findPreset(s.id);
            if (preset == null || !sameScheme(preset, s)) toSave.add(s);
        }
        sp.edit().putString(KEY_SCHEMES, gson.toJson(toSave))
                .putInt("__ver", sp.getInt("__ver", 0) + 1)
                .apply();
        cachedList = null;
        cachedLight = cachedDark = null;
    }

    @Nullable
    private static ThemeScheme findPreset(String id) {
        if (id == null) return null;
        for (ThemeScheme p : presets()) if (id.equals(p.id)) return p;
        return null;
    }

    /** 方案是否与预设定义一致（用于判断是否需要落盘覆盖预设） */
    private boolean sameScheme(ThemeScheme a, ThemeScheme b) {
        return gson.toJson(a).equals(gson.toJson(b));
    }

    // ---------- 模式绑定 ----------

    public String lightId() {
        return sp.getString(KEY_LIGHT, ID_FANG_CUN);
    }

    public void setLightId(String id) {
        sp.edit().putString(KEY_LIGHT, id == null ? ID_FANG_CUN : id).apply();
        cachedLight = null;
    }

    public String darkId() {
        return sp.getString(KEY_DARK, ID_FANG_CUN);
    }

    public void setDarkId(String id) {
        sp.edit().putString(KEY_DARK, id == null ? ID_FANG_CUN : id).apply();
        cachedDark = null;
    }

    // ==================== 运行时解析 ====================

    /** 当前应生效的方案：按 AppCompatDelegate 的夜模式选绑定 */
    public ThemeScheme current(boolean night) {
        synchronized (this) {
            ThemeScheme cached = night ? cachedDark : cachedLight;
            if (cached != null) return cached;
            String id = night ? darkId() : lightId();
            ThemeScheme found = findById(id);
            if (found == null) found = fangcunDefault();
            if (night) cachedDark = found; else cachedLight = found;
            return found;
        }
    }

    @Nullable
    public ThemeScheme findById(String id) {
        for (ThemeScheme s : schemes()) {
            if (id != null && id.equals(s.id)) return s;
        }
        return null;
    }

    /** 解析后的「实际使用色板」：背景已按明暗模式套用对应令牌 */
    public Resolved resolve(boolean night) {
        ThemeScheme s = current(night);
        Resolved r = new Resolved();
        r.scheme = s;
        // 背景
        if (s.bgMode == 1) {
            r.bgStart = s.bgFrom;
            r.bgEnd = s.bgTo;
        } else {
            r.bgStart = r.bgEnd = s.bgColor;
        }
        r.surface = s.surface;
        r.textPrimary = s.textPrimary;
        r.textSecondary = s.textSecondary;
        r.primary = s.primary;
        r.secondary = s.secondaryAuto ? autoSecondary(s.primary) : s.secondary;
        r.onPrimary = isLight(r.primary) ? 0xFF1B2026 : 0xFFFFFFFF;
        r.navAlpha = clamp100(s.navAlpha) / 100f;
        r.glassOn = s.glassOn;
        r.barBlur = clamp100(s.barBlur);
        r.barFrost = clamp100(s.barFrost);
        r.barAlpha = clamp100(s.barAlpha);
        r.angle = ((s.bgAngle % 360) + 360) % 360;
        return r;
    }

    public static class Resolved {
        public ThemeScheme scheme;
        public int bgStart, bgEnd;
        public int surface;
        public int textPrimary, textSecondary;
        public int primary, secondary, onPrimary;
        public float navAlpha;
        public boolean glassOn;
        public int barBlur, barFrost, barAlpha;
        public int angle;
    }

    private static int clamp100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // ==================== 实时应用 ====================

        /**
     * 把方案应用到窗口与内容容器（不重建 Activity，立即生效）。
     * 页面内的列表/控件颜色在 Fragment 侧自行重绘（监听器由 Activity 分发）。
     */
    public void applyRuntime(Context ctx, View contentRoot) {
        boolean night = com.lateshow.app.util.ThemeManager.actualNight(ctx);
        Resolved r = resolve(night);

        // 1. 内容容器背景：纯色或渐变
        if (contentRoot != null) {
            contentRoot.setBackground(backgroundDrawable(r));
        }

        // 2. 窗口底色 / 状态栏 / 导航栏（与内容背景一致，避免切换瞬间露边）
        if (ctx instanceof android.app.Activity) {
            android.view.Window w = ((android.app.Activity) ctx).getWindow();
            if (w != null) {
                w.setBackgroundDrawable(backgroundDrawable(r));
                w.setStatusBarColor(r.bgStart);
                w.setNavigationBarColor(withAlpha(r.bgStart, r.navAlpha));
            }
        }
    }

    /**
     * 主题引擎未开启时，按「当前模式」回退到内置「方寸」配色。
     * 用于渲染页头/卡片等仍走资源令牌的静态元素：开启 = 用户方案色，关闭 = 内置色。
     */
    @ColorInt
    public static int accentOf(@NonNull Context ctx) {
        // 引擎未初始化（极端情况下适配器先于 Activity 创建）：回退资源令牌，不抛异常
        if (sInstance == null) return ContextCompat.getColor(ctx, R.color.acc);
        return get().resolve(com.lateshow.app.util.ThemeManager.actualNight(ctx)).primary;
    }

    /**
     * 强调文字色（替代写死在 XML 里的 @color/accText）。
     *
     * <p>内置 accText 的取向是：浅底用压暗的同色系、深底用提亮的同色系。这里按当前方案
     * 背景亮度动态算出同方向的变体，保证主题切换后强调文字既随色相走、又始终可读。
     * 引擎关闭时原样返回 @color/accText。
     */
    @ColorInt
    public static int accentTextColor(@NonNull Context ctx) {
        if (!isEnabled(ctx)) return ContextCompat.getColor(ctx, R.color.accText);
        int accent = accentOf(ctx);
        int bg = get().resolve(com.lateshow.app.util.ThemeManager.actualNight(ctx)).bgStart;
        float lum = (0.299f * ((bg >> 16) & 0xFF) + 0.587f * ((bg >> 8) & 0xFF)
                + 0.114f * (bg & 0xFF)) / 255f;
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(accent, hsv);
        if (lum > 0.5f) {   // 浅底：压暗 + 轻微加饱和
            hsv[2] = Math.max(0.22f, hsv[2] * 0.62f);
            hsv[1] = Math.min(1f, hsv[1] * 0.95f + 0.05f);
        } else {            // 深底：提亮 + 降饱和
            hsv[2] = Math.min(1f, hsv[2] * 1.35f + 0.22f);
            hsv[1] = Math.min(1f, hsv[1] * 0.7f);
        }
        return android.graphics.Color.HSVToColor(hsv);
    }

    public android.graphics.drawable.Drawable backgroundDrawable(Resolved r) {
        android.graphics.drawable.GradientDrawable d;
        if (r.bgStart == r.bgEnd) {
            d = new android.graphics.drawable.GradientDrawable();
            d.setColor(r.bgStart);
        } else {
            d = new android.graphics.drawable.GradientDrawable(
                    gradientOrientation(r.angle), new int[]{r.bgStart, r.bgEnd});
        }
        return d;
    }

    private static android.graphics.drawable.GradientDrawable.Orientation gradientOrientation(int deg) {
        // GradientDrawable 只支持 8 个固定方向，取最近的一个（角度误差 ≤22.5°）
        android.graphics.drawable.GradientDrawable.Orientation[] table = {
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,        // 0
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR,             // 45
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,        // 90
                android.graphics.drawable.GradientDrawable.Orientation.BR_TL,             // 135
                android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,        // 180
                android.graphics.drawable.GradientDrawable.Orientation.TR_BL,             // 225
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,        // 270
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,             // 315
        };
        int idx = ((deg + 22) / 45) % 8;
        return table[idx];
    }

    /** 按 alpha 百分比着色（含原色自带 alpha 的保留） */
    public static int withAlpha(@ColorInt int color, float alpha) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 给定底色返回对比文字色：亮底深字 / 暗底浅字。
     *
     * <p>阈值与 {@link #isLight(int)} 保持一致（亮度 ≥160 视为亮底），
     * 与 {@link Resolved#onPrimary} 同源，避免各调用点各写一套阈值。
     */
    @ColorInt
    public static int inkOn(@ColorInt int bg) {
        return isLight(bg) ? 0xFF1B2026 : 0xFFFFFFFF;
    }

    /**
     * 强调底上的文字色（替代写死在 XML 里的 @color/accInk / @color/btnPlayInk）。
     *
     * <p>选中胶囊 / 分段控件 / 主按钮的底色会随方案主色变，但文字色若固定，
     * 遇到深色方案（深蓝、墨绿、黑）时深字会糊在底上读不出来。
     * 引擎开启时按当前方案主色亮度自动选深/浅；关闭时原样返回 builtIn，
     * 保证内置「方寸」观感完全不变。
     */
    @ColorInt
    public static int accentInkColor(@NonNull Context ctx, @ColorInt int builtIn) {
        if (!isEnabled(ctx)) return builtIn;
        if (sInstance == null) return builtIn;
        return get().resolve(com.lateshow.app.util.ThemeManager.actualNight(ctx)).onPrimary;
    }

    // ==================== 主题感知的通用底色 ====================
    // 仓库里很多 drawable（bg_btn_main / bg_seg_on / dot_acc）把 @color/acc 写死在 XML 里，
    // 运行时改不了。这里按同一形状用代码画一份，颜色取当前方案主色；
    // 引擎关闭时原样返回原 drawable，保证内置观感完全不变。

    /** 主题引擎是否已开启（静态工具用；PrefStore 未初始化时返回 false） */
    public static boolean isEnabled(@NonNull Context ctx) {
        try {
            return com.lateshow.app.store.PrefStore.get().themeEngineOn();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static float dp(@NonNull Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /** 主按钮底（替代 bg_btn_main）：10dp 圆角 + 方案主色 */
    @NonNull
    public static android.graphics.drawable.Drawable accentButtonBg(@NonNull Context ctx) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.bg_btn_main);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0xFFFF5A36);
        }
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, 10));
        gd.setColor(accentOf(ctx));
        return gd;
    }

    /** 分段控件选中底（替代 bg_seg_on）：胶囊 + 方案主色 */
    @NonNull
    public static android.graphics.drawable.Drawable accentPillBg(@NonNull Context ctx) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.bg_seg_on);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0xFFFF5A36);
        }
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, 999));
        gd.setColor(accentOf(ctx));
        return gd;
    }

    /**
     * 强调底色块：圆角方形 + 方案主色（主题管理页 / 编辑器共用）。
     *
     * <p>用于滑杆轨道、色块选中环等需要「跟随用户配色」的小面积强调底。
     * 引擎关闭时退回内置橙色，保证内置观感不变。
     */
    @NonNull
    public static android.graphics.drawable.Drawable accentBlockBg(@NonNull Context ctx, float radiusDp) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, radiusDp));
        gd.setColor(isEnabled(ctx) ? accentOf(ctx) : 0xFFFF5A36);
        return gd;
    }

    /** 状态圆点（替代 dot_acc）：圆形 + 方案主色 */
    @NonNull
    public static android.graphics.drawable.Drawable accentDotBg(@NonNull Context ctx) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.dot_acc);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0xFFFF5A36);
        }
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(accentOf(ctx));
        int sz = Math.round(dp(ctx, 6));
        gd.setSize(sz, sz);
        return gd;
    }

    /**
     * 底部导航选中光斑（替代 bg_nav_glow）：径向渐变，主色 13% → 全透明。
     * 引擎关闭时原样返回 bg_nav_glow。
     */
    @NonNull
    public static android.graphics.drawable.Drawable accentGlowBg(@NonNull Context ctx) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.bg_nav_glow);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0);
        }
        int accent = accentOf(ctx);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColors(new int[]{withAlpha(accent, 0.13f), withAlpha(accent, 0f)});
        gd.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(dp(ctx, 19));
        int sz = Math.round(dp(ctx, 38));
        gd.setSize(sz, sz);
        return gd;
    }

    /**
     * 同步进度条（替代 pbar_sync）：3dp 圆角轨道 + 方案主色进度。
     * 引擎关闭时原样返回 pbar_sync。
     */
    @NonNull
    public static android.graphics.drawable.Drawable accentProgressBg(@NonNull Context ctx) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.pbar_sync);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0);
        }
        android.graphics.drawable.GradientDrawable track =
                new android.graphics.drawable.GradientDrawable();
        track.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        track.setCornerRadius(dp(ctx, 2));
        track.setColor(ContextCompat.getColor(ctx, R.color.lineStrong));
        android.graphics.drawable.GradientDrawable prog =
                new android.graphics.drawable.GradientDrawable();
        prog.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        prog.setCornerRadius(dp(ctx, 2));
        prog.setColor(accentOf(ctx));
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{
                                track,
                                new android.graphics.drawable.ClipDrawable(
                                        prog, android.view.Gravity.START,
                                        android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    /**
     * 选集/线路选中底（替代 bg_episode_current）：8dp 圆角 + 主色 13% 填充 + 1dp 主色描边。
     * 引擎关闭时原样返回 bg_episode_current。
     */
    @NonNull
    public static android.graphics.drawable.Drawable accentEpisodeBg(@NonNull Context ctx) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.bg_episode_current);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0);
        }
        int accent = accentOf(ctx);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, 8));
        gd.setColor(withAlpha(accent, 0.13f));
        gd.setStroke(Math.round(dp(ctx, 1)), accent);
        return gd;
    }

    /**
     * 源站圆环（替代 bg_ring_on）：圆形实面 + 1.5dp 描边 + 方案主色。
     *
     * <p>保留原 drawable 的圆形涟漪按压反馈；引擎关闭时原样返回 bg_ring_on。
     *
     * @param faceColor 圆面底色（暗场用 ink800，保证图标可读）
     * @param strokeDp  描边宽度（dp）
     */
    @NonNull
    public static android.graphics.drawable.Drawable accentRingBg(@NonNull Context ctx,
                                                                  @ColorInt int faceColor,
                                                                  float strokeDp) {
        if (!isEnabled(ctx)) {
            android.graphics.drawable.Drawable d =
                    ContextCompat.getDrawable(ctx, R.drawable.bg_ring_on);
            return d != null ? d : new android.graphics.drawable.ColorDrawable(0xFFFF5A36);
        }
        int accent = accentOf(ctx);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(faceColor);
        gd.setStroke(Math.round(dp(ctx, strokeDp)), accent);
        // 涟漪：圆形掩膜 + 主色 25% 按压色，与原 bg_ring_on 的橙色涟漪等价
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(accent, 0.25f)),
                gd, mask);
    }

    // ==================== 调色板工具（编辑器用） ====================

    /** 常用色板（预设推荐 + 基础色） */
    public static List<Integer> palette() {
        List<Integer> p = new ArrayList<>();
        int[] colors = {
                0xFFFF5A36, 0xFFFE5A35, 0xFFE85D4A, 0xFFD64541,
                0xFFFFA37C, 0xFFFFB26B, 0xFFF79256, 0xFFF5A623,
                0xFFF5C243, 0xFFE8D44D, 0xFFCDDC39, 0xFF9CD71B,
                0xFF71C88D, 0xFF4CAF7D, 0xFF49AAA0, 0xFF26A69A,
                0xFF4FD8C4, 0xFF4DB6AC, 0xFF5570EA, 0xFF3F51B5,
                0xFF1565C7, 0xFF4FA9EE, 0xFF29B6F6, 0xFF4DD0E1,
                0xFF9B6BF3, 0xFFB493F5, 0xFF8E6BBF, 0xFFAB47BC,
                0xFFFF6FA5, 0xFFEC407A, 0xFFE91E63, 0xFFD81B60,
                0xFF795548, 0xFF8D6E63, 0xFF607D8B, 0xFF302B4F,
                0xFF1B1412, 0xFF000000,
        };
        for (int c : colors) p.add(c);
        return p;
    }

    /** 供适配器把「资源色」换成当前方案色（无 Activity 时的兜底取色） */
    @ColorInt
    public static int tint(@NonNull Context ctx, int role, boolean night) {
        switch (role) {
            case ROLE_PRIMARY: return get().resolve(night).primary;
            case ROLE_SECONDARY: return get().resolve(night).secondary;
            case ROLE_SURFACE: return get().resolve(night).surface;
            case ROLE_TEXT_PRIMARY: return get().resolve(night).textPrimary;
            case ROLE_TEXT_SECONDARY: return get().resolve(night).textSecondary;
            case ROLE_BG: return get().resolve(night).bgStart;
            default: return 0;
        }
    }

    public static final int ROLE_PRIMARY = 0;
    public static final int ROLE_SECONDARY = 1;
    public static final int ROLE_SURFACE = 2;
    public static final int ROLE_TEXT_PRIMARY = 3;
    public static final int ROLE_TEXT_SECONDARY = 4;
    public static final int ROLE_BG = 5;
}
