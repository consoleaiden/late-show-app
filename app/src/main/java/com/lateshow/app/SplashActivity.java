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
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView mark = findViewById(R.id.splashMark);
        View title = findViewById(R.id.splashTitle);
        View slogan = findViewById(R.id.splashSlogan);
        View root = findViewById(R.id.splashRoot);

        FangcunMarkDrawable d = new FangcunMarkDrawable(
                ContextCompat.getColor(this, R.color.brandPaper),
                ContextCompat.getColor(this, R.color.brandTri));
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
