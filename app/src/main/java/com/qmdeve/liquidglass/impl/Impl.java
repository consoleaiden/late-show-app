/*
 * MIT License
 *
 * Copyright (c) 2025-2026 Donny Yang
 *
 * 源码来自 https://github.com/QmDeve/AndroidLiquidGlassView（MIT License）。
 */

package com.qmdeve.liquidglass.impl;

import android.graphics.Canvas;

public interface Impl {
    void onSizeChanged(int w, int h);
    void onPreDraw();
    void draw(Canvas c);
    default void dispose() {}
}
