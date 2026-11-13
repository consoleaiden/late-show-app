package com.lateshow.app.util;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.PlaybackParameters;

import com.lateshow.app.R;
import com.lateshow.app.store.PrefStore;

/**
 * 播放器悬浮工具：倍速菜单 + 播放设置聚合面板，内嵌（DetailFragment）与全屏（PlayerActivity）共用。
 *
 * <p>聚合面板把分散在播放器各处的功能收进「设置」按钮：倍速、广告提报、
 * 跳过片头/片尾、复制播放地址、下载。面板为黑色 70% 不透明度，关闭方式：
 * 再点一次设置按钮、点击面板外区域、5 秒无操作，或任一一次性功能执行完。
 */
public final class PlayerPanel {

    /** 聚合面板无操作自动关闭时长（用户要求 5 秒） */
    private static final long PANEL_IDLE_MS = 5000L;

    /** 当前打开的聚合面板（设置按钮二态切换、无操作定时关闭都用它） */
    private static PopupWindow panel;

    private PlayerPanel() { }

    /** 播放地址按需现取：换集/换线路后地址会变，attach 时机上不能定死 */
    public interface UrlProvider { String get(); }

    /** 绑定播放设置按钮（倍速已并入面板，倍速 chip 已删除） */
    public static void attach(final View btnGear, final ExoPlayer player, final UrlProvider urls) {
        btnGear.setOnClickListener(v -> {
            if (panel != null && panel.isShowing()) {
                panel.dismiss();            // 再点一次设置按钮 = 关闭
            } else {
                showPanel(v, player, urls);
            }
        });
    }

    /** 强制收面板：换集 / 退出内嵌 / 进全屏前调用，避免悬浮窗残留在新界面上 */
    public static void dismissPanel() {
        if (panel != null) panel.dismiss();
    }

    /** 把当前保存的倍速应用到播放器（创建/换集后调用） */
    public static void applySavedSpeed(ExoPlayer player) {
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(PrefStore.get().playSpeed()));
        }
    }

    // ==================== 播放设置聚合面板 ====================

    /**
     * 弹出聚合面板：黑色 70% 不透明度圆角层，锚在设置按钮下方（右对齐）。
     * 点面板外区域关闭 = focusable 弹窗 + outsideTouchable；5 秒无操作由
     * touchInterceptor 逐次触摸续期实现（选倍速弹出二级菜单时菜单事件进不来，
     * 所以打开菜单/选中档位两个时机手动续期）。
     */
    private static void showPanel(final View anchor, final ExoPlayer player, final UrlProvider urls) {
        final Context ctx = anchor.getContext();
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_panel_black70);
        box.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));

        final Handler idle = new Handler(Looper.getMainLooper());
        final Runnable autoClose = () -> {
            PopupWindow p = panel;
            if (p != null) p.dismiss();
        };
        final Runnable bumpIdle = () -> {
            idle.removeCallbacks(autoClose);
            idle.postDelayed(autoClose, PANEL_IDLE_MS);
        };

        // 倍速：面板保持打开，选中后刷新行文案并续期计时（便于连续试不同档位）
        final TextView rowSpeed = newRow(ctx, "倍速 · " + speedLabel(PrefStore.get().playSpeed()));
        rowSpeed.setOnClickListener(v -> {
            bumpIdle.run();
            buildSpeedMenu(ctx, rowSpeed, player, () -> {
                rowSpeed.setText("倍速 · " + speedLabel(PrefStore.get().playSpeed()));
                bumpIdle.run();
            }).show();
        });
        box.addView(rowSpeed, matchWrap());

        // 广告提报：一次性功能，执行后面板即收
        box.addView(newRow(ctx, "广告提报", v -> {
            closePanel();
            StreamBar.reportAdUrl(ctx, urls.get());
        }), matchWrap());

        box.addView(newRow(ctx, "播放设置", v -> {
            closePanel();
            showSettingsDialog(ctx);
        }), matchWrap());

        box.addView(newRow(ctx, "复制地址", v -> {
            closePanel();
            StreamBar.copyUrl(ctx, urls.get());
        }), matchWrap());

        box.addView(newRow(ctx, "下载", v -> {
            closePanel();
            StreamBar.downloadUrl(ctx, urls.get());
        }), matchWrap());

        final PopupWindow pw = new PopupWindow(box,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, true);
        // 点面板外区域自动关闭：透明背景 + outsideTouchable（focusable 弹窗默认拦截外部点击）
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 面板内任何触摸都重置 5 秒无操作计时
        pw.setTouchInterceptor((v, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE
                    || a == MotionEvent.ACTION_UP) {
                bumpIdle.run();
            }
            return false;
        });
        pw.setOnDismissListener(() -> {
            idle.removeCallbacks(autoClose);
            if (panel == pw) panel = null;
        });
        panel = pw;
        bumpIdle.run();
        pw.showAsDropDown(anchor, 0, dp(ctx, 6), Gravity.END);
    }

    private static void closePanel() {
        PopupWindow p = panel;
        if (p != null) p.dismiss();
    }

    private static TextView newRow(Context ctx, String text, View.OnClickListener l) {
        TextView tv = newRow(ctx, text);
        tv.setOnClickListener(l);
        return tv;
    }

    private static TextView newRow(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(0xFFFFFFFF);
        tv.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
        // 主题自带的按压涟漪（代码里取 attr 要手动解析一次）
        TypedValue out = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            tv.setBackgroundResource(out.resourceId);
        }
        return tv;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ==================== 倍速 ====================

    /** 倍速弹出菜单（聚合面板倍速行调用）：档位选中即生效并持久化 */
    private static PopupMenu buildSpeedMenu(final Context ctx, final View anchor,
                                            final ExoPlayer player, final Runnable onPick) {
        PopupMenu pm = new PopupMenu(ctx, anchor, Gravity.END);
        for (float s : PrefStore.SPEEDS) {
            pm.getMenu().add(speedLabel(s));
        }
        pm.setOnMenuItemClickListener(item -> {
            float s = parseSpeed(String.valueOf(item.getTitle()));
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(s));
            PrefStore.get().setPlaySpeed(s);
            if (onPick != null) onPick.run();
            Toast.makeText(ctx, "倍速 " + speedLabel(s), Toast.LENGTH_SHORT).show();
            return true;
        });
        return pm;
    }

    // ==================== 播放设置（跳过片头/片尾） ====================

    /** 播放设置弹窗：跳过片头 / 片尾秒数（改动即时保存） */
    public static void showSettingsDialog(final Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, dp(ctx, 8), pad, 0);

        final EditText inIntro = new EditText(ctx);
        inIntro.setInputType(InputType.TYPE_CLASS_NUMBER);
        inIntro.setHint("跳过片头（秒）");
        inIntro.setText(String.valueOf(PrefStore.get().skipIntroSec()));
        box.addView(inIntro);

        final EditText inEnding = new EditText(ctx);
        inEnding.setInputType(InputType.TYPE_CLASS_NUMBER);
        inEnding.setHint("跳过片尾（秒）");
        inEnding.setText(String.valueOf(PrefStore.get().skipEndingSec()));
        box.addView(inEnding);

        TextView note = new TextView(ctx);
        note.setText("片头：对新开始播放的集生效（已有进度的集仍续播）；片尾：立即生效。填 0 = 不跳过，上限 600 秒。");
        note.setTextSize(11f);
        note.setPadding(0, dp(ctx, 6), 0, 0);
        box.addView(note);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("播放设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    PrefStore.get().setSkipIntroSec(parseIntSafe(inIntro.getText().toString()));
                    PrefStore.get().setSkipEndingSec(parseIntSafe(inEnding.getText().toString()));
                    Toast.makeText(ctx, "播放设置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public static String speedLabel(float s) {
        float r = Math.round(s * 100f) / 100f;
        if (r * 10f == Math.round(r * 10f)) {
            return String.format(java.util.Locale.US, "%.1fx", r); // 0.5x / 1.0x / 1.5x / 2.0x
        }
        return String.format(java.util.Locale.US, "%.2fx", r);     // 0.75x / 1.25x
    }

    private static float parseSpeed(String text) {
        try {
            return Float.parseFloat(text.replace("x", "").trim());
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
