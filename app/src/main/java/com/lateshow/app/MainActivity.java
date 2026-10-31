package com.lateshow.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
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
    private static final int[] LABEL_IDS = {R.id.tab_home_label, R.id.tab_fav_label, R.id.tab_settings_label};
    private static final int[] GLOW_IDS = {R.id.tab_home_glow, R.id.tab_fav_glow, R.id.tab_settings_glow};
    private static final int[] IND_IDS = {R.id.tab_home_ind, R.id.tab_fav_ind, R.id.tab_settings_ind};

    private HomeFragment homeFragment;
    private FavFragment favFragment;
    private SettingsFragment settingsFragment;
    private Fragment active;

    private final ImageView[] icons = new ImageView[TAB_COUNT];
    private final TextView[] labels = new TextView[TAB_COUNT];
    private final View[] glows = new View[TAB_COUNT];
    private final View[] inds = new View[TAB_COUNT];
    private final int[] curTint = new int[TAB_COUNT];
    private int curTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int off = getColor(R.color.t3);
        int labelOff = getColor(R.color.t3);
        for (int i = 0; i < TAB_COUNT; i++) {
            icons[i] = findViewById(ICON_IDS[i]);
            labels[i] = findViewById(LABEL_IDS[i]);
            glows[i] = findViewById(GLOW_IDS[i]);
            inds[i] = findViewById(IND_IDS[i]);
            icons[i].setColorFilter(off);
            curTint[i] = off;
            labels[i].setTextColor(labelOff);
            labels[i].setAlpha(0f);
            labels[i].setTranslationY(dp(5));
            glows[i].setAlpha(0f);
            inds[i].setAlpha(0f);
            inds[i].setScaleX(0.2f);
        }

        homeFragment = new HomeFragment();
        favFragment = new FavFragment();
        settingsFragment = new SettingsFragment();

        findViewById(TAB_IDS[0]).setOnClickListener(v -> switchTab(homeFragment, 0));
        findViewById(TAB_IDS[1]).setOnClickListener(v -> switchTab(favFragment, 1));
        findViewById(TAB_IDS[2]).setOnClickListener(v -> switchTab(settingsFragment, 2));

        switchTab(homeFragment, 0);
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
        else tx.add(R.id.container, target);
        tx.commit();
        active = target;

        boolean animate = curTab != -1;
        curTab = tab;
        renderTabs(tab, animate);
    }

    /** 渲染导航选中态：颜色渐变 + 图标弹跳 + 标签上浮 + 光斑/指示条展开 */
    private void renderTabs(int tab, boolean animate) {
        int acc = getColor(R.color.acc);
        int off = getColor(R.color.t3);
        for (int i = 0; i < TAB_COUNT; i++) {
            final boolean sel = i == tab;
            final ImageView icon = icons[i];
            final TextView label = labels[i];
            View glow = glows[i];
            View ind = inds[i];
            int target = sel ? acc : off;

            if (animate) animateTint(icon, curTint[i], target);
            else icon.setColorFilter(target);
            curTint[i] = target;
            label.setTextColor(target);

            if (animate) {
                label.animate().alpha(sel ? 1f : 0f).translationY(sel ? 0 : dp(5))
                        .setDuration(sel ? 220 : 140).start();
                glow.animate().alpha(sel ? 1f : 0f).setDuration(sel ? 280 : 160).start();
                ind.animate().alpha(sel ? 1f : 0f).scaleX(sel ? 1f : 0.2f)
                        .setDuration(sel ? 240 : 120).start();
            } else {
                label.setAlpha(sel ? 1f : 0f);
                label.setTranslationY(sel ? 0 : dp(5));
                glow.setAlpha(sel ? 1f : 0f);
                ind.setAlpha(sel ? 1f : 0f);
                ind.setScaleX(sel ? 1f : 0.2f);
            }

            if (sel && animate) {
                bounce(icon);
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
