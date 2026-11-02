package com.lateshow.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.lateshow.app.store.PrefStore;
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
        setContentView(R.layout.activity_main);

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

        if (!sUpdateChecked) {
            sUpdateChecked = true;
            maybeCheckUpdate();
        }

        // 服务端开启访问控制且本地没有 API Token 时，引导填写（不阻塞进入 App）
        com.lateshow.app.util.AuthGate.checkStartup(this);
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
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("发现新版本 v" + info.version)
                    .setMessage("当前 v" + com.lateshow.app.util.UpdateChecker.currentVersion() + "\n\n" + notes)
                    .setPositiveButton("立即更新", (d, w) -> com.lateshow.app.util.UpdateChecker.downloadAndInstall(this, info))
                    .setNegativeButton("稍后", null)
                    .show();
        });
    }

    public void applyTheme() {
        int t = PrefStore.get().theme();
        AppCompatDelegate.setDefaultNightMode(
                t == PrefStore.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : t == PrefStore.THEME_AUTO ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES);
    }

    private void switchTab(Fragment target, int tab) {
        if (curTab == tab && active != null) return;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        for (Fragment f : fm.getFragments()) tx.hide(f);
        if (target.isAdded()) tx.show(target);
        else tx.add(R.id.container, target, TAGS[tab]);
        tx.commit();
        active = target;

        boolean animate = curTab != -1;
        curTab = tab;
        renderTabs(tab, animate);
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

    /** 图标着色渐变 */
    private void animateTint(final ImageView icon, int from, int to) {
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        va.setDuration(260);
        va.addUpdateListener(a -> icon.setColorFilter((Integer) a.getAnimatedValue()));
        va.start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public void goHome() {
        switchTab(homeFragment, 0);
    }
}
