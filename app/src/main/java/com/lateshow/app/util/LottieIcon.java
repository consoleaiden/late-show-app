package com.lateshow.app.util;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.value.LottieValueCallback;

/**
 * Lottie 图标装载与控制。
 *
 * 资产约定：assets/lottie/<name>.json（深色配色）、<name>_light.json（浅色配色）。
 * 控制能力：装载/播放/暂停/setProgress/setSpeed/反向播放 + 运行时单色 tint。
 */
public final class LottieIcon {

    private LottieIcon() {}

    public static boolean isNight(Context c) {
        int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m != Configuration.UI_MODE_NIGHT_NO; // 项目默认深色
    }

    /** 按当前主题加载组合（带缓存） */
    public static LottieComposition comp(Context c, String name) {
        Context app = c.getApplicationContext();
        String asset = "lottie/" + name + (isNight(c) ? ".json" : "_light.json");
        LottieComposition r = LottieCompositionFactory.fromAssetSync(app, asset).getValue();
        if (r == null) r = LottieCompositionFactory.fromAssetSync(app, "lottie/" + name + ".json").getValue();
        return r;
    }

    /** 装载并停在 0 帧（静止态） */
    public static void bind(View v, String name) {
        if (!(v instanceof LottieAnimationView)) return;
        LottieAnimationView lav = (LottieAnimationView) v;
        LottieComposition cp = comp(v.getContext(), name);
        if (cp == null) return;
        lav.setComposition(cp);
        lav.setSpeed(1f);
        lav.setProgress(0f);
    }

    /** 从头播放一次（播完停在末帧） */
    public static void play(View v, String name) {
        bind(v, name);
        if (v instanceof LottieAnimationView) ((LottieAnimationView) v).playAnimation();
    }

    /** 反向播放一次（当前进度 → 0） */
    public static void playReverse(LottieAnimationView lav) {
        lav.setSpeed(-1f);
        lav.playAnimation();
    }

    /** 递归装载视图树内 tag 以 ic_ 开头的 LottieAnimationView，并各播放一次 */
    public static void bindTree(View root) {
        if (root == null) return;
        if (root instanceof LottieAnimationView) {
            Object tag = root.getTag();
            if (tag instanceof String && ((String) tag).startsWith("ic_")) {
                play(root, (String) tag);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) bindTree(g.getChildAt(i));
        }
    }

    /** 运行时单色着色（覆盖 JSON 内置配色） */
    public static void tint(View v, int color) {
        if (!(v instanceof LottieAnimationView)) return;
        ((LottieAnimationView) v).addValueCallback(new KeyPath("**"), LottieProperty.COLOR,
                new LottieValueCallback<>(color));
    }

    /** 注入 LottieDrawable 到普通 ImageView（FloatingActionButton 等场景），返回引用便于后续控制 */
    public static LottieDrawable into(ImageView iv, String name, boolean play) {
        LottieComposition cp = comp(iv.getContext(), name);
        if (cp == null) return null;
        LottieDrawable d = new LottieDrawable();
        d.setComposition(cp);
        d.setProgress(0f);
        if (play) d.playAnimation();
        iv.setImageDrawable(d);
        return d;
    }
}
