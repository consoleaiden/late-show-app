package com.lateshow.app.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;
import androidx.core.graphics.PathParser;
import androidx.core.view.animation.PathInterpolator;

/**
 * 三态主题切换形变图标（明亮 / 夜间 / 随系统）。
 *
 * 与 Web 端 demo（theme-toggle-icon.html）同一套几何、时序与缓动：
 * 48×48 网格、线宽 3、圆头线帽；缓动 cubic-bezier(0.65,0,0.35,1)，无回弹。
 * Web 用 stroke-dashoffset 做线条伸缩，此处等价实现为 PathMeasure 按长度比例裁剪：
 *   dash 偏移 d（可负）→ 可见段 [max(0,-d), min(1, 1-d)]，d=0 全显、d=1.5 全隐、d=-0.6 留外段 40%。
 *
 * 各元素窗口（与 CSS transition 完全一致，正反向对称）：
 *   圆环      dash 380ms · delay 100
 *   8 光线    dash 340ms · delay i×25；光线组旋转 620ms
 *   月牙      dash 420ms · delay 200；旋转 620ms · delay 200
 *   随系统圆底 fill 350ms · scale 450ms · 无延迟
 *   半日弧    dash 380ms · fill 350ms · delay 150
 *   随系统光线 dash 320ms · delay 200+i×40
 *   半月弧    dash 420ms · fill 350ms · delay 280
 */
public class ThemeToggleIcon extends View {

    /** 设计规范色板：暖琥珀=日，冷月光蓝=夜（双主题下保持不变，与 Web 一致） */
    private static final int C_SUN = 0xFFE08A1E;
    private static final int C_MOON = 0xFF8EA6E0;
    private static final int C_HALF_LIGHT = 0xFFF7F2E8;
    private static final int C_HALF_DARK = 0xFF222B40;

    /** 单次切换总时长（最迟元素：半月弧 280+420=700ms） */
    private static final int TOTAL = 700;
    private static final Interpolator EASE = new PathInterpolator(0.65f, 0f, 0.35f, 1f);

    private static Path parse(String d) { return PathParser.createPathFromPathData(d); }

    /** 8 根太阳光线：内端 r15 → 外端 r20.5，每 45°（路径方向内→外） */
    private static final Path[] RAYS = {
            parse("M39,24 L44.5,24"),      parse("M34.6,34.6 L38.5,38.5"),
            parse("M24,39 L24,44.5"),      parse("M13.4,34.6 L9.5,38.5"),
            parse("M9,24 L3.5,24"),        parse("M13.4,13.4 L9.5,9.5"),
            parse("M24,9 L24,3.5"),        parse("M34.6,13.4 L38.5,9.5")};
    /** 太阳圆环 r10 */
    private static final Path CORE =
            parse("M24,14 a10,10 0 1,0 0,20 a10,10 0 1,0 0,-20");
    /** 镂空月牙：外弧 r10（圆心 26.5,24）+ 内弧 r13，只描边 */
    private static final Path CRESCENT =
            parse("M31.5,15.34 A10,10 0 1,0 31.5,32.66 A13,13 0 0,1 31.5,15.34 Z");
    /** 随系统：双色半圆底 + 左半太阳 + 右半月亮 + 3 根左半短光线 */
    private static final Path DISC_L = parse("M24,4 A20,20 0 0,0 24,44 Z");
    private static final Path DISC_R = parse("M24,4 A20,20 0 0,1 24,44 Z");
    private static final Path SUN_HALF = parse("M24,16 A8,8 0 0,0 24,32");
    private static final Path MOON_HALF = parse("M24,16 A8,8 0 0,1 24,32");
    private static final Path[] AUTO_RAYS = {
            parse("M15.2,15.2 L12,12"), parse("M11.5,24 L7,24"), parse("M15.2,32.8 L12,36")};

    /** 单元素动效窗口：from→to，delay 后在 dur 内按 EASE 过渡 */
    private static final class Win {
        final float from, to;
        final int delay, dur;
        Win(float from, float to, int delay, int dur) {
            this.from = from; this.to = to; this.delay = delay; this.dur = dur;
        }
    }

    // —— 当前状态（= Web 里各元素的实时 dash 偏移 / 旋转 / 透明度 / 缩放） ——
    private final float[] rayD = new float[8];      // 光线 dash 偏移
    private final float[] autoRayD = new float[3];  // 随系统光线 dash 偏移
    private float coreD = 1.5f, cresD = 1.5f;       // 圆环 / 月牙 dash 偏移
    private float raySpin, cresSpin;                // 光线组 / 月牙旋转
    private float discA, discS = 0.5f;              // 随系统圆底透明度 / 缩放
    private float sunArcD = 1.5f, sunArcA;          // 半日弧 dash / 填充透明度
    private float moonArcD = 1.5f, moonArcA;        // 半月弧 dash / 填充透明度
    private int targetTheme;                        // 目标主题（光线颜色随目标态瞬时切换，同 Web）

    private Win[] wins;
    private ValueAnimator animator;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint butt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathMeasure pm = new PathMeasure();
    private final Path seg = new Path();

    public ThemeToggleIcon(Context c) { super(c); init(); }
    public ThemeToggleIcon(Context c, @Nullable AttributeSet a) { super(c, a); init(); }
    public ThemeToggleIcon(Context c, @Nullable AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        butt.setStyle(Paint.Style.STROKE);
        butt.setStrokeWidth(3f);
        butt.setStrokeCap(Paint.Cap.BUTT);
        fill.setStyle(Paint.Style.FILL);
    }

    /** 目标态各元素取值（与 Web 的 CSS 状态表一一对应） */
    private static final class Targets {
        final float coreD, cresD, raySpin, cresSpin, discA, discS, sunArcD, sunArcA, moonArcD, moonArcA;
        final float[] rayD, autoRayD;
        Targets(int theme) {
            boolean sun = theme == 1;   // PrefStore.THEME_LIGHT
            boolean moon = theme == 0;  // PrefStore.THEME_DARK
            boolean auto = theme == 2;  // PrefStore.THEME_AUTO
            coreD = sun ? 0f : 1.5f;
            cresD = moon ? 0f : 1.5f;
            raySpin = moon ? 60f : 0f;
            cresSpin = moon ? -45f : 0f;
            discA = auto ? 1f : 0f;
            discS = auto ? 1f : 0.5f;
            sunArcD = auto ? 0f : 1.5f;
            sunArcA = auto ? 1f : 0f;
            moonArcD = auto ? 0f : 1.5f;
            moonArcA = auto ? 1f : 0f;
            rayD = new float[8];
            for (int i = 0; i < 8; i++) rayD[i] = sun ? 0f : moon ? -0.6f : 1.5f;
            autoRayD = new float[3];
            for (int i = 0; i < 3; i++) autoRayD[i] = auto ? 0f : 1.5f;
        }
    }

    /** 设置主题态。animate=false 直接定格（进入页面时用）；true 按窗口表播放形变 */
    public void setMode(int theme, boolean animate) {
        targetTheme = theme;
        Targets tg = new Targets(theme);
        if (animator != null) { animator.cancel(); animator = null; }
        if (!animate) {
            coreD = tg.coreD; cresD = tg.cresD; raySpin = tg.raySpin; cresSpin = tg.cresSpin;
            discA = tg.discA; discS = tg.discS;
            sunArcD = tg.sunArcD; sunArcA = tg.sunArcA; moonArcD = tg.moonArcD; moonArcA = tg.moonArcA;
            System.arraycopy(tg.rayD, 0, rayD, 0, 8);
            System.arraycopy(tg.autoRayD, 0, autoRayD, 0, 3);
            invalidate();
            return;
        }
        // 快照当前值作为各窗口起点 → 逐窗口过渡（快速连点时从中间态平滑续播，与 CSS transition 行为一致）
        float fCore = coreD, fCres = cresD, fRaySpin = raySpin, fCresSpin = cresSpin;
        float fDiscA = discA, fDiscS = discS;
        float fSunArcD = sunArcD, fSunArcA = sunArcA, fMoonArcD = moonArcD, fMoonArcA = moonArcA;
        wins = new Win[]{
                new Win(fCore, tg.coreD, 100, 380),
                new Win(fCres, tg.cresD, 200, 420),
                new Win(fRaySpin, tg.raySpin, 0, 620),
                new Win(fCresSpin, tg.cresSpin, 200, 620),
                new Win(fDiscA, tg.discA, 0, 350),
                new Win(fDiscS, tg.discS, 0, 450),
                new Win(fSunArcD, tg.sunArcD, 150, 380),
                new Win(fSunArcA, tg.sunArcA, 150, 350),
                new Win(fMoonArcD, tg.moonArcD, 280, 420),
                new Win(fMoonArcA, tg.moonArcA, 280, 350),
                new Win(rayD[0], tg.rayD[0], 0, 340),   new Win(rayD[1], tg.rayD[1], 25, 340),
                new Win(rayD[2], tg.rayD[2], 50, 340),  new Win(rayD[3], tg.rayD[3], 75, 340),
                new Win(rayD[4], tg.rayD[4], 100, 340), new Win(rayD[5], tg.rayD[5], 125, 340),
                new Win(rayD[6], tg.rayD[6], 150, 340), new Win(rayD[7], tg.rayD[7], 175, 340),
                new Win(autoRayD[0], tg.autoRayD[0], 200, 320),
                new Win(autoRayD[1], tg.autoRayD[1], 240, 320),
                new Win(autoRayD[2], tg.autoRayD[2], 280, 320)};
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(TOTAL);
        va.setInterpolator(null); // 线性主时钟，缓动在各窗口内独立施加（同 CSS）
        va.addUpdateListener(a -> {
            float t = TOTAL * (float) a.getAnimatedValue();
            int w = 0;
            coreD = eval(wins[w++], t);
            cresD = eval(wins[w++], t);
            raySpin = eval(wins[w++], t);
            cresSpin = eval(wins[w++], t);
            discA = eval(wins[w++], t);
            discS = eval(wins[w++], t);
            sunArcD = eval(wins[w++], t);
            sunArcA = eval(wins[w++], t);
            moonArcD = eval(wins[w++], t);
            moonArcA = eval(wins[w++], t);
            for (int i = 0; i < 8; i++) rayD[i] = eval(wins[w++], t);
            for (int i = 0; i < 3; i++) autoRayD[i] = eval(wins[w++], t);
            invalidate();
        });
        va.start();
        animator = va;
    }

    /** 窗口内求值：局部进度 → EASE 缓动 → 线性插值 */
    private static float eval(Win w, float t) {
        float local = (t - w.delay) / w.dur;
        if (local <= 0f) return w.from;
        if (local >= 1f) return w.to;
        return w.from + (w.to - w.from) * EASE.getInterpolation(local);
    }

    /** 等价 Web stroke-dashoffset：dash 偏移 d → 描画可见段 [max(0,-d), min(1,1-d)] */
    private void drawTrim(Canvas c, Path path, float d, Paint p) {
        float from = Math.max(0f, -d), to = Math.min(1f, 1f - d);
        if (to - from <= 0.002f) return;
        pm.setPath(path, false);
        float len = pm.length();
        if (len <= 0f) return;
        seg.rewind();
        pm.getSegment(len * from, len * to, seg, true);
        c.drawPath(seg, p);
    }

    private static void alpha(Paint p, int color, float a) {
        p.setColor(color);
        p.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, a))));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getPaddingTop() == 0 && getPaddingBottom() == 0 ? getHeight()
                : getHeight() - getPaddingTop() - getPaddingBottom();
        float s = Math.min(w, h) / 48f;
        if (s <= 0f) return;
        c.save();
        c.translate(getPaddingLeft() + (w - Math.min(w, h)) / 2f,
                getPaddingTop() + (h - Math.min(w, h)) / 2f);
        c.scale(s, s);

        // 1) 随系统双色圆底（缩放 + 淡入）
        if (discA > 0.01f) {
            c.save();
            c.scale(discS, discS, 24, 24);
            alpha(fill, C_HALF_LIGHT, discA); c.drawPath(DISC_L, fill);
            alpha(fill, C_HALF_DARK, discA);  c.drawPath(DISC_R, fill);
            c.restore();
        }
        // 2) 半日弧（填充 + butt 描边伸缩，delay 150）
        if (sunArcA > 0.01f) {
            alpha(fill, C_SUN, sunArcA);  c.drawPath(SUN_HALF, fill);
            alpha(butt, C_SUN, sunArcA);  drawTrim(c, SUN_HALF, sunArcD, butt);
        }
        // 3) 半月弧（delay 280）
        if (moonArcA > 0.01f) {
            alpha(fill, C_MOON, moonArcA); c.drawPath(MOON_HALF, fill);
            alpha(butt, C_MOON, moonArcA); drawTrim(c, MOON_HALF, moonArcD, butt);
        }
        // 4) 随系统 3 根短光线（琥珀，delay 200+i×40）
        alpha(stroke, C_SUN, 1f);
        for (int i = 0; i < 3; i++) drawTrim(c, AUTO_RAYS[i], autoRayD[i], stroke);
        // 5) 太阳光线组：颜色随目标态瞬时切换（同 Web 的 class 翻转），组旋转 620ms
        alpha(stroke, targetTheme == 0 ? C_MOON : C_SUN, 1f);
        c.save();
        c.rotate(raySpin, 24, 24);
        for (int i = 0; i < 8; i++) drawTrim(c, RAYS[i], rayD[i], stroke);
        c.restore();
        // 6) 太阳圆环
        alpha(stroke, C_SUN, 1f);
        drawTrim(c, CORE, coreD, stroke);
        // 7) 月牙（旋转 + 描画，delay 200）
        alpha(stroke, C_MOON, 1f);
        c.save();
        c.rotate(cresSpin, 24, 24);
        drawTrim(c, CRESCENT, cresD, stroke);
        c.restore();
        c.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
