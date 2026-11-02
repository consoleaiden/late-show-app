package com.lateshow.app.util;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.PlaybackParameters;

import com.lateshow.app.R;
import com.lateshow.app.store.PrefStore;

/** 播放器悬浮工具：倍速菜单 + 播放设置弹窗（跳过片头/片尾），内嵌与全屏共用 */
public final class PlayerPanel {

    private PlayerPanel() { }

    /** 绑定倍速与设置按钮：chip 乘以 anchor 弹出菜单 */
    public static void attach(final View btnSpeed, final View btnGear, final ExoPlayer player) {
        refreshSpeedLabel(btnSpeed);
        btnSpeed.setOnClickListener(v -> showSpeedMenu(v, player));
        btnGear.setOnClickListener(v -> showSettingsDialog(v.getContext()));
    }

    /** 把当前保存的倍速应用到播放器（创建/换集后调用） */
    public static void applySavedSpeed(ExoPlayer player) {
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(PrefStore.get().playSpeed()));
        }
    }

    /** 倍速弹出菜单：档位选中即生效并持久化 */
    public static void showSpeedMenu(final View anchor, final ExoPlayer player) {
        final Context ctx = anchor.getContext();
        PopupMenu pm = new PopupMenu(ctx, anchor, Gravity.END);
        for (float s : PrefStore.SPEEDS) {
            pm.getMenu().add(speedLabel(s));
        }
        pm.setOnMenuItemClickListener(item -> {
            float s = parseSpeed(String.valueOf(item.getTitle()));
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(s));
            PrefStore.get().setPlaySpeed(s);
            refreshSpeedLabel(anchor);
            Toast.makeText(ctx, "倍速 " + speedLabel(s), Toast.LENGTH_SHORT).show();
            return true;
        });
        pm.show();
    }

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

        new AlertDialog.Builder(ctx)
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

    public static void refreshSpeedLabel(View chip) {
        if (chip instanceof TextView) {
            ((TextView) chip).setText(speedLabel(PrefStore.get().playSpeed()));
        }
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
