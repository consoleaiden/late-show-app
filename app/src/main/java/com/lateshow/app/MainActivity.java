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
    /** 三个底部图标的 Lottie 资产名 */
    private static final String[] NAV_ICONS = {"ic_home", "ic_heart", "ic_settings"};
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

    /** 详情页（Fragment）：全屏浮层，与列表在同一窗口，才能做出跟手一镜到底 */
    private DetailFragment detailFragment;
    private View detailHost;
    private OnBackPressedCallback detailBack;

    private final com.airbnb.lottie.LottieAnimationView[] icons = new com.airbnb.lottie.LottieAnimationView[TAB_COUNT];
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

        int off = getColor(R.color.t3);
        for (int i = 0; i < TAB_COUNT; i++) {
            icons[i] = findViewById(ICON_IDS[i]);
            glows[i] = findViewById(GLOW_IDS[i]);
            inds[i] = findViewById(IND_IDS[i]);
            // Lottie 图标：装载并停在起始帧，颜色由选中态统一着色
            com.lateshow.app.util.LottieIcon.bind(icons[i], NAV_ICONS[i]);
            com.lateshow.app.util.LottieIcon.tint(icons[i], off);
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

        findViewById(TAB_IDS[0]).setOnClickListener(v -> switchTab(homeFragment, 0));
        findViewById(TAB_IDS[1]).setOnClickListener(v -> switchTab(favFragment, 1));
        findViewById(TAB_IDS[2]).setOnClickListener(v -> switchTab(settingsFragment, 2));

        // 配置变更（切明暗主题）重建后回到原来的 Tab，而不是强制跳回首页
        int startTab = savedInstanceState != null ? savedInstanceState.getInt(KEY_TAB, 0) : 0;
        if (startTab < 0 || startTab >= TAB_COUNT) startTab = 0;
        switchTab(fragmentAt(startTab), startTab);

        // 详情页浮层（配置变更/切主题后由 FragmentManager 自动恢复）
        detailHost = findViewById(R.id.detailHost);
        detailFragment = (DetailFragment) fm.findFragmentByTag(DetailFragment.TAG);
        // 「详情页内跳详情」会短暂同时存在两张详情页（新的叠在旧的上面，展开后再摘旧的）。
        // 如果正好在这个窗口里重建（切主题/旋屏），把底下那张清理掉，只保留最上面一张
        java.util.ArrayList<DetailFragment> details = new java.util.ArrayList<>();
        for (Fragment f : fm.getFragments()) {
            if (f instanceof DetailFragment) details.add((DetailFragment) f);
        }
        for (int i = 0; i < details.size() - 1; i++) {
            fm.beginTransaction().remove(details.get(i)).commitNowAllowingStateLoss();
        }
        if (!details.isEmpty()) detailFragment = details.get(details.size() - 1);
        detailBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                if (detailFragment != null) detailFragment.requestClose();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, detailBack);
        if (detailFragment != null) {
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
     * 内容区补系统栏内边距：详情页浮层是它的兄弟节点，不受影响（海报要顶到状态栏）。
     * 用监听而不是 fitsSystemWindows，是因为键盘弹起时还要让出 IME 高度。
     */
    private void applySystemBarInsets() {
        final View content = findViewById(R.id.mainContent);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
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
        final DetailFragment prev = detailFragment;
        // 已在详情页（点相关推荐/相同演员）：旧页先留着当背景——新页要从被点的那张卡片形变展开，
        // 如果先把旧页摘掉，形变起点背后会直接露出首页列表，看起来像闪了一下
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
        detailFragment = f;
        detailBack.setEnabled(true);
        // 详情页信息带是深色 → 状态栏图标固定白色
        setLightStatusBarIcons(false);

        if (prev != null) {
            // 新页展开到位后再摘旧页：这一刻两张页面的形变已经接上，摘掉不会被看见
            f.setOnEnterEnd(() -> {
                if (!prev.isAdded()) return;
                getSupportFragmentManager().beginTransaction()
                        .remove(prev).commitAllowingStateLoss();
            });
        }
    }

    /**
     * 详情页收起动画结束后回调：隐藏浮层、恢复系统栏图标配色。
     *
     * @param self 正在收起的详情页（由它自己负责摘除，这里只清理「详情页内跳详情」留下的旧页）
     */
    public void onDetailClosed(DetailFragment self) {
        detailFragment = null;
        FragmentManager fm = getSupportFragmentManager();
        java.util.ArrayList<DetailFragment> left = new java.util.ArrayList<>();
        for (Fragment f : fm.getFragments()) {
            if (f instanceof DetailFragment && f != self) left.add((DetailFragment) f);
        }
        for (DetailFragment f : left) {
            fm.beginTransaction().remove(f).commitAllowingStateLoss();
        }
        if (detailHost != null) detailHost.setVisibility(View.GONE);
        if (detailBack != null) detailBack.setEnabled(false);
        setLightStatusBarIcons(!com.lateshow.app.util.ThemeManager.actualNight(this));
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
            if (info == null || isFinishing()) return;
            String notes = info.notes == null ? "" : info.notes.trim();
            if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
            new MaterialAlertDialogBuilder(this)
                    .setTitle("发现新版本 v" + info.version)
                    .setMessage("当前 v" + com.lateshow.app.util.UpdateChecker.currentVersion() + "\n\n" + notes)
                    .setPositiveButton("立即更新", (d, w) -> com.lateshow.app.util.UpdateChecker.downloadAndInstall(this, info))
                    .setNegativeButton("稍后", null)
                    .show();
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
        for (Fragment f : fm.getFragments()) if (f != detailFragment) tx.hide(f);
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

    /** 渲染导航选中态：颜色渐变 + 图标播放一次 + 光斑/指示条展开（无文字标签） */
    private void renderTabs(int tab, boolean animate) {
        int acc = getColor(R.color.acc);
        int off = getColor(R.color.t3);
        for (int i = 0; i < TAB_COUNT; i++) {
            final boolean sel = i == tab;
            final com.airbnb.lottie.LottieAnimationView icon = icons[i];
            View glow = glows[i];
            View ind = inds[i];
            int target = sel ? acc : off;

            if (animate) animateTint(icon, curTint[i], target);
            else com.lateshow.app.util.LottieIcon.tint(icon, target);
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
                // 点选：图标从头播放一次（描边心形 → 实心），播完静止
                icon.setSpeed(1f);
                icon.setProgress(0f);
                icon.playAnimation();
                bounce(icon);
            } else if (!sel) {
                icon.animate().cancel();
                icon.setScaleX(1f);
                icon.setScaleY(1f);
                icon.pauseAnimation();
                icon.setProgress(0f);   // 取消选中回到起始帧（心形=描边态）
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

    /** 图标着色渐变（Lottie 值回调，填充/描边同一颜色，不经过 ColorFilter） */
    private void animateTint(final com.airbnb.lottie.LottieAnimationView icon, int from, int to) {
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        va.setDuration(260);
        va.addUpdateListener(a -> com.lateshow.app.util.LottieIcon.tint(icon, (Integer) a.getAnimatedValue()));
        va.start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public void goHome() {
        switchTab(homeFragment, 0);
    }

    /** 设置页切换数据源后调用：首页清空筛选并重新加载 */
    public void notifyDataSourceChanged() {
        if (homeFragment != null) homeFragment.onDataSourceChanged();
    }
}
