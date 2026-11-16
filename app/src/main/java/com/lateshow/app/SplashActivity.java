package com.lateshow.app;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.lateshow.app.util.FangcunMarkDrawable;

/**
 * 方寸开屏（品牌启动动画）：暗场海报编排 —— 描框（0→45%）与绘三角（40→95%）同段驱动
 * 1.4s → 题字「方寸」淡入（0.9s 起）→ 标语「方寸之间，万象银幕」淡入（1.5s 起）
 * → 2.8s 自动进主页；任意点击跳过。退场用纯淡出（brand_fade_out）。
 */
public class SplashActivity extends AppCompatActivity {

    private static final long AUTO_ADVANCE_MS = 2800L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean advancing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动页先于 MainActivity，需自行初始化偏好与主题引擎
        com.lateshow.app.store.PrefStore.init(this);
        com.lateshow.app.theme.ThemeEngine.init(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView mark = findViewById(R.id.splashMark);
        TextView title = findViewById(R.id.splashTitle);
        TextView slogan = findViewById(R.id.splashSlogan);
        View root = findViewById(R.id.splashRoot);

        // 背景 / 题字 / 标语跟随当前方案（引擎关闭时保持品牌暗场 + 宣白字）
        if (com.lateshow.app.theme.ThemeEngine.isEnabled(this)) {
            com.lateshow.app.theme.ThemeEngine.Resolved r =
                    com.lateshow.app.theme.ThemeEngine.get().resolve(
                            com.lateshow.app.util.ThemeManager.actualNight(this));
            android.util.Log.d("LS_SPLASH", "bgStart=" + Integer.toHexString(r.bgStart)
                    + " bgEnd=" + Integer.toHexString(r.bgEnd)
                    + " primary=" + Integer.toHexString(r.primary)
                    + " accent=" + Integer.toHexString(
                            com.lateshow.app.theme.ThemeEngine.accentOf(this)));
            android.graphics.drawable.Drawable bg =
                    com.lateshow.app.theme.ThemeEngine.get().backgroundDrawable(r);
            root.setBackground(bg);
            if (getWindow() != null) {
                getWindow().setBackgroundDrawable(bg);
                getWindow().setStatusBarColor(r.bgStart);
                getWindow().setNavigationBarColor(
                        com.lateshow.app.theme.ThemeEngine.withAlpha(r.bgStart, r.navAlpha));
            }
            title.setTextColor(r.textPrimary);
            slogan.setTextColor(r.textPrimary);
        }

        // 三角跟随当前方案主色（引擎关闭时回退品牌朱砂）；方框恒为宣白，保证暗场可读
        int tri = com.lateshow.app.theme.ThemeEngine.isEnabled(this)
                ? com.lateshow.app.theme.ThemeEngine.accentOf(this)
                : ContextCompat.getColor(this, R.color.brandTri);
        FangcunMarkDrawable d = new FangcunMarkDrawable(
                ContextCompat.getColor(this, R.color.brandPaper), tri);
        mark.setImageDrawable(d);

        ValueAnimator draw = ValueAnimator.ofFloat(0f, 1f);
        draw.setDuration(1400);
        draw.setInterpolator(new AccelerateDecelerateInterpolator());
        draw.addUpdateListener(a -> d.setProgress((float) a.getAnimatedValue()));
        draw.start();

        // 题字 / 标语：淡入 + 轻微上浮（与描线节奏衔接；先摆初位再起动画）
        title.setTranslationY(10f);
        slogan.setTranslationY(8f);
        title.animate().alpha(1f).translationY(0f).setDuration(600)
                .setStartDelay(900).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        slogan.animate().alpha(0.78f).translationY(0f).setDuration(600)
                .setStartDelay(1500).setInterpolator(new DecelerateInterpolator(1.4f)).start();

        handler.postDelayed(this::go, AUTO_ADVANCE_MS);
        root.setOnClickListener(v -> go());
    }

    /** 进主页（幂等）：淡出退场，主页淡入接住 */
    private void go() {
        if (advancing || isFinishing()) return;
        advancing = true;
        Intent it = new Intent(this, MainActivity.class);
        startActivity(it);
        overridePendingTransition(R.anim.brand_fade_in, R.anim.brand_fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
