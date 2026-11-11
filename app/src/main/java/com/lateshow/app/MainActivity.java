package com.lateshow.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.lateshow.app.model.CardItem;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.ui.DetailFragment;
import com.lateshow.app.ui.FavFragment;
import com.lateshow.app.ui.HomeFragment;
import com.lateshow.app.ui.SettingsFragment;

public class MainActivity extends AppCompatActivity {

    private static final int TAB_COUNT = 3;
    private static final int[] TAB_IDS = {R.id.tab_home, R.id.tab_fav, R.id.tab_settings};
    private static final int[] ICON_IDS = {R.id.tab_home_icon, R.id.tab_fav_icon, R.id.tab_settings_icon};
    private static final int[] GLOW_IDS = {R.id.tab_home_glow, R.id.tab_fav_glow, R.id.tab_settings_glow};
    private static final int[] IND_IDS = {R.id.tab_home_ind, R.id.tab_fav_ind, R.id.tab_settings_ind};

    /** Fragment tags：重建（切主题等配置变更）后按 tag 取回，避免重复 add 与跳回首页 */
    private static final String[] TAGS = {"tab_home", "tab_fav", "tab_settings"};
    private static final String KEY_TAB = "cur_tab";

    /** 进程内只自动检查一次更新（主题切换等重建不再弹窗） */
    private static boolean sUpdateChecked = false;

    private HomeFragment homeFragment;
    private FavFragment favFragment;
    private SettingsFragment settingsFragment;
    private Fragment active;

    /**
     * 详情页栈：列表页（一级）→ 播放/详情（二级）→ 推荐跳详情（三级）→ ……
     * 栈顶（peek）是当前显示的那张，返回手势每次只出栈一级。
     * 详情页内跳详情不再「摘掉旧页」，而是把旧页盖住（暂停播放器 + 隐藏视图）留在栈里。
     */
    private final java.util.ArrayDeque<DetailFragment> detailStack = new java.util.ArrayDeque<>();
    private View detailHost;
    private OnBackPressedCallback detailBack;

    private final ImageView[] icons = new ImageView[TAB_COUNT];
    private final View[] glows = new View[TAB_COUNT];
    private final View[] inds = new View[TAB_COUNT];
    private final int[] curTint = new int[TAB_COUNT];
    private int curTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        com.lateshow.app.util.ApiCache.init(this);
        com.lateshow.app.util.OfflineMedia.init(this);
        applyTheme();
        super.onCreate(savedInstanceState);
        // edge-to-edge：内容画到系统栏下面，详情页海报才能真正盖住状态栏
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        setupGlassTabbar();

        int off = getColor(R.color.t3);
        for (int i = 0; i < TAB_COUNT; i++) {
            icons[i] = findViewById(ICON_IDS[i]);
            glows[i] = findViewById(GLOW_IDS[i]);
            inds[i] = findViewById(IND_IDS[i]);
            // 静态矢量图标：src 已在布局里指定，这里只按选中态着色
            com.lateshow.app.util.Icons.tint(icons[i], off);
            curTint[i] = off;
            glows[i].setAlpha(0f);
            inds[i].setAlpha(0f);
            inds[i].setScaleX(0.2f);
        }

        FragmentManager fm = getSupportFragmentManager();
        homeFragment = (HomeFragment) fm.findFragmentByTag(TAGS[0]);
        favFragment = (FavFragment) fm.findFragmentByTag(TAGS[1]);
        settingsFragment = (SettingsFragment) fm.findFragmentByTag(TAGS[2]);
        if (homeFragment == null) homeFragment = new HomeFragment();
        if (favFragment == null) favFragment = new FavFragment();
        if (settingsFragment == null) settingsFragment = new SettingsFragment();

        // 详情页栈必须先于 switchTab 恢复：switchTab 的 hide 循环是「隐藏除栈顶详情页
        // 外的所有 Fragment」，此时栈还是空的就会把详情页一起藏掉 ——
        // 表现为「全屏播放返回 / 旋屏重建后直接回到首页」（真机 2026-11-10）
        java.util.ArrayList<DetailFragment> details = new java.util.ArrayList<>();
        for (Fragment f : fm.getFragments()) {
            if (f instanceof DetailFragment) details.add((DetailFragment) f);
        }
        // 按 FragmentManager 的加入顺序（栈底 → 栈顶）重建栈
        for (DetailFragment d : details) detailStack.push(d);

        findViewById(TAB_IDS[0]).setOnClickListener(v -> switchTab(homeFragment, 0));
        findViewById(TAB_IDS[1]).setOnClickListener(v -> switchTab(favFragment, 1));
        findViewById(TAB_IDS[2]).setOnClickListener(v -> switchTab(settingsFragment, 2));

        // 配置变更（切明暗主题）重建后回到原来的 Tab，而不是强制跳回首页
        int startTab = savedInstanceState != null ? savedInstanceState.getInt(KEY_TAB, 0) : 0;
        if (startTab < 0 || startTab >= TAB_COUNT) startTab = 0;
        switchTab(fragmentAt(startTab), startTab);

        // 栈重建后只保留栈顶可见，被盖住的低层级页面隐藏
        // （低层级的播放器在旋屏前 onDestroyView 已释放，这里无需再处理）
        if (details.size() > 1) {
            androidx.fragment.app.FragmentTransaction rtx = fm.beginTransaction();
            for (int i = 0; i < details.size() - 1; i++) rtx.hide(details.get(i));
            rtx.commitAllowingStateLoss();
        }
        detailHost = findViewById(R.id.detailHost);
        detailBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                DetailFragment top = detailStack.peek();
                if (top != null) top.requestClose();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, detailBack);
        if (!details.isEmpty()) {
            detailHost.setVisibility(View.VISIBLE);
            detailBack.setEnabled(true);
        }

        if (!sUpdateChecked) {
            sUpdateChecked = true;
            maybeCheckUpdate();
        }

        // 服务端开启访问控制且本地没有 API Token 时，引导填写（不阻塞进入 App）
        com.lateshow.app.util.AuthGate.checkStartup(this);
    }

    /**
     * 液态玻璃底部导航（效果移植自 Kyant0/AndroidLiquidGlass，Apache-2.0）：
     * 折射 container 里的列表内容，Android 13+ 生效，低版本自动退化为半透明圆角条。
     * 参数对着 Kyant0 目录 App 的 bottom-tabs 预设调的，观感可在真机上微调。
     */
    private void setupGlassTabbar() {
        com.lateshow.app.ui.widget.LiquidGlassBar bar = findViewById(R.id.tabbar);
        bar.setBackdropSource(findViewById(R.id.container));
        float d = getResources().getDisplayMetrics().density;
        bar.setGlassCornerRadius(24f * d);
        bar.setRefraction(18f * d, 42f * d);   // 折射带宽 / 折射量（边缘透镜放大）
        bar.setBlurSigma(32f * d);             // 磨砂（4 倍强度；tier1 据此把降采样加深到 1/40）
        bar.setFrost(0.88f);                   // 中心白雾（原 0.22 × 4）
        bar.setHighlight(0.30f);               // 边缘高光
        bar.setDispersion(0.8f);               // 色散（彩虹边）
    }

    /**
     * 内容区补系统栏内边距：只补底部（导航条/键盘）。顶部不补——状态栏透明，
     * 首页海报列表要延伸到屏幕最顶端，避免状态栏区域出现纯色带和海报割裂；
     * 各 Fragment 顶部需要的让位由它们自己对根视图补 insets。
     * 用监听而不是 fitsSystemWindows，是因为键盘弹起时还要让出 IME 高度。
     */
    private void applySystemBarInsets() {
        final View content = findViewById(R.id.mainContent);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(0, 0, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /**
     * 打开影片详情（全屏 Fragment 浮层）。
     *
     * @param source 列表/推荐卡片的封面容器；非空时取其屏幕坐标，详情页据此从卡片位置展开、
     *               收起时再缩回去（一镜到底）。
     */
    public void openDetail(CardItem item, View source) {
        if (isFinishing() || item == null) return;
        final DetailFragment prev = detailStack.peek();
        // 已在详情页（点相关推荐/相同演员）：旧页先留着当背景——新页要从被点的那张卡片形变展开，
        // 如果先把旧页摘掉，形变起点背后会直接露出首页列表，看起来像闪了一下。
        // 旧页不会被销毁：新页展开结束后盖住它（暂停播放器 + 隐藏视图），返回时逐级恢复
        int[] card = null;
        if (source != null && source.getWidth() > 0 && source.getHeight() > 0) {
            int[] loc = new int[2];
            source.getLocationOnScreen(loc);
            card = new int[]{loc[0], loc[1], source.getWidth(), source.getHeight()};
        }
        DetailFragment f = DetailFragment.newInstance(item, card);
        detailHost.setVisibility(View.VISIBLE);
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.detailHost, f, DetailFragment.TAG)
                .commitNow();
        detailStack.push(f);
        detailBack.setEnabled(true);
        // 详情页信息带是深色 → 状态栏图标固定白色
        setLightStatusBarIcons(false);

        if (prev != null) {
            // 新页展开到位后盖住旧页：这一刻两张页面的形变已经接上，盖住不会被看见
            f.setOnEnterEnd(() -> coverDetail(prev));
        }
    }

    /**
     * 把一张详情页盖住（被新页压到下面时调用）：
     * 暂停播放器（避免两层同时出声）+ 隐藏视图（不参与绘制，省 GPU），
     * Fragment 与视图都保留在栈里，返回手势出栈一级时原样恢复。
     */
    private void coverDetail(DetailFragment d) {
        if (d == null || !d.isAdded()) return;
        d.onCovered();
        getSupportFragmentManager().beginTransaction()
                .hide(d).commitAllowingStateLoss();
    }

    /** 详情页开始收起（下拉跟手 / 系统返回）：露出下一级，让收起形变落到上一级的卡片上 */
    public void onDetailCloseStarted(DetailFragment self) {
        DetailFragment under = underOf(self);
        if (under != null && !under.isVisible()) {
            getSupportFragmentManager().beginTransaction()
                    .show(under).commitNowAllowingStateLoss();
        }
    }

    /** 收起被取消（下拉未过阈值回弹）：重新盖住下一级 */
    public void onDetailCloseAborted(DetailFragment self) {
        DetailFragment under = underOf(self);
        if (under != null && !under.isHidden()) coverDetail(under);
    }

    /** self 的下一级页面（栈里紧挨 self 下面的那张）；self 不在栈顶或已是栈底时返回 null */
    private DetailFragment underOf(DetailFragment self) {
        if (detailStack.isEmpty() || detailStack.peek() != self || detailStack.size() < 2) return null;
        java.util.Iterator<DetailFragment> it = detailStack.iterator();   // 栈顶 → 栈底
        it.next();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * 详情页收起动画结束后回调：出栈一级，恢复下一级页面的显示与播放器。
     *
     * @param self 正在收起的详情页（由它自己负责摘除，这里只清理栈里可能残留的更深层页面）
     */
    public void onDetailClosed(DetailFragment self) {
        detailStack.remove(self);
        // 防御性对账：只把「已脱离 FragmentManager」的栈残留丢弃（正常路径不存在）。
        // 注意：绝不能在这里摘除还在 FragmentManager 里的低层级页面——
        // 栈的底层（1~under-1 层）本来就以 hidden 状态留在管理器里等逐级返回，
        // 旧实现的「双保险清理」会每次出栈都把整个底层栈销毁，第 3 次返回就直接见列表了
        detailStack.removeIf(d -> !d.isAdded());
        DetailFragment under = detailStack.peek();
        if (under != null && under.isHidden()) {
            getSupportFragmentManager().beginTransaction()
                    .show(under).commitAllowingStateLoss();
        }
        if (under != null) {
            under.onUncovered();
            setLightStatusBarIcons(false);
        } else {
            if (detailHost != null) detailHost.setVisibility(View.GONE);
            detailBack.setEnabled(false);
            setLightStatusBarIcons(!com.lateshow.app.util.ThemeManager.actualNight(this));
        }
    }

    /** 彻底收起整个详情栈（如详情页点标签跳搜索）：先把低层级全部摘掉，栈顶走收起动画 */
    public void closeAllDetails() {
        if (detailStack.isEmpty()) return;
        DetailFragment top = detailStack.peek();
        for (DetailFragment d : detailStack) {
            if (d != top) getSupportFragmentManager().beginTransaction()
                    .remove(d).commitNowAllowingStateLoss();
        }
        detailStack.clear();
        detailStack.push(top);
        top.requestClose();
    }

    private void setLightStatusBarIcons(boolean light) {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(light);
    }

    /** 跳转到设置页（401 引导用） */
    public void openSettings() {
        switchTab(settingsFragment, 2);
    }

    private Fragment fragmentAt(int tab) {
        return tab == 1 ? favFragment : tab == 2 ? settingsFragment : homeFragment;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_TAB, curTab < 0 ? 0 : curTab);
    }

    /** 启动时静默检查一次新版本（检查失败不打扰用户） */
    private void maybeCheckUpdate() {
        com.lateshow.app.util.UpdateChecker.check((info, error) -> {
            // 审计 A-10：网络回调到达时 Activity 可能已销毁，禁止再弹窗
            if (info == null || isFinishing() || isDestroyed()) return;
            String notes = info.notes == null ? "" : info.notes.trim();
            if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
            try {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("发现新版本 v" + info.version)
                        .setMessage(com.lateshow.app.util.UpdateChecker.brief(info) + "\n\n" + notes)
                        .setPositiveButton("立即更新", (d, w) -> com.lateshow.app.util.UpdateChecker.downloadAndInstall(this, info))
                        .setNegativeButton("稍后", null)
                        .show();
            } catch (Exception ignored) { }
        });
    }

    public void applyTheme() {
        com.lateshow.app.util.ThemeManager.apply(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        healNightMode();
    }

    /** 主题自检：资源解析到的日夜与偏好不符（重启后偶发的混色）时，重新断言并重建 */
    private void healNightMode() {
        boolean mismatch = com.lateshow.app.util.ThemeManager.expectNight(this)
                != com.lateshow.app.util.ThemeManager.actualNight(this);
        if (!mismatch) return;
        long now = System.currentTimeMillis();
        if (now - sLastHealAt < 3000) return;   // 防抖：避免极端情况下循环重建
        sLastHealAt = now;
        com.lateshow.app.util.ThemeManager.apply(this);
        recreate();
    }

    private static long sLastHealAt;

    private void switchTab(Fragment target, int tab) {
        if (curTab == tab && active != null) return;
        FragmentManager fm = getSupportFragmentManager();
        boolean animate = curTab != -1 && active != target;
        FragmentTransaction tx = fm.beginTransaction();
        for (Fragment f : fm.getFragments()) if (f != detailStack.peek()) tx.hide(f);
        if (target.isAdded()) tx.show(target);
        else tx.add(R.id.container, target, TAGS[tab]);
        tx.commit();
        fm.executePendingTransactions();   // 立刻落地，下面才能对已 attach 的视图做动画
        active = target;
        curTab = tab;
        if (animate) crossFadeIn(target.getView());
        renderTabs(tab, animate);
    }

    /** 页面切换：新页从下方 8dp 处淡入（旧页在同一次 commit 里隐藏），避免硬切 */
    private void crossFadeIn(View v) {
        com.lateshow.app.util.UiAnim.fadeUp(v, 8);
    }

    /** 渲染导航选中态：颜色渐变 + 图标弹跳 + 光斑/指示条展开（无文字标签） */
    private void renderTabs(int tab, boolean animate) {
        int acc = getColor(R.color.acc);
        int off = getColor(R.color.t3);
        for (int i = 0; i < TAB_COUNT; i++) {
            final boolean sel = i == tab;
            final ImageView icon = icons[i];
            View glow = glows[i];
            View ind = inds[i];
            int target = sel ? acc : off;

            if (animate) animateTint(icon, curTint[i], target);
            else com.lateshow.app.util.Icons.tint(icon, target);
            curTint[i] = target;

            if (animate) {
                glow.animate().alpha(sel ? 1f : 0f).setDuration(sel ? 280 : 160).start();
                ind.animate().alpha(sel ? 1f : 0f).scaleX(sel ? 1f : 0.2f)
                        .setDuration(sel ? 240 : 120).start();
            } else {
                glow.setAlpha(sel ? 1f : 0f);
                ind.setAlpha(sel ? 1f : 0f);
                ind.setScaleX(sel ? 1f : 0.2f);
            }

            if (sel && animate) {
                bounce(icon);   // 点选：弹跳一次（静态图标无逐帧动画）
            } else if (!sel) {
                icon.animate().cancel();
                icon.setScaleX(1f);
                icon.setScaleY(1f);
            }
        }
    }

    /** 图标弹跳：先收缩再放大回弹 */
    private void bounce(final ImageView icon) {
        icon.animate().cancel();
        icon.setScaleX(0.86f);
        icon.setScaleY(0.86f);
        icon.animate()
                .scaleX(1.16f).scaleY(1.16f)
                .setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> icon.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(new OvershootInterpolator(2.6f))
                        .start())
                .start();
    }

    /** 图标着色渐变（静态矢量：直接对 colorFilter 做 ARGB 插值，无需逐帧运行动画） */
    private void animateTint(final ImageView icon, int from, int to) {
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        va.setDuration(260);
        va.addUpdateListener(a -> com.lateshow.app.util.Icons.tint(icon, (Integer) a.getAnimatedValue()));
        va.start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public void goHome() {
        switchTab(homeFragment, 0);
    }

    /**
     * 详情页点标签的快捷搜索：收起详情浮层 → 回到首页 → 用标签值在「该影片所在源站」检索。
     * 检索、分类、海报墙全部复用首页现有结构（见 HomeFragment#searchFromTag），不另建页面。
     */
    public void searchFromTag(String keyword, String slug, String sourceName) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        if (!detailStack.isEmpty()) closeAllDetails();
        switchTab(homeFragment, 0);
        if (homeFragment != null) homeFragment.searchFromTag(keyword, slug, sourceName);
    }

    /** 设置页切换数据源后调用：首页清空筛选并重新加载 */
    public void notifyDataSourceChanged() {
        if (homeFragment != null) homeFragment.onDataSourceChanged();
    }
}
