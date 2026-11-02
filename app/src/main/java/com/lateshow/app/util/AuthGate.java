package com.lateshow.app.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lateshow.app.MainActivity;
import com.lateshow.app.R;
import com.lateshow.app.api.VodSyncApi;
import com.lateshow.app.model.Models;
import com.lateshow.app.store.PrefStore;

/**
 * 访问控制网关：服务端 v1.5.0+ 除 /healthz 与 /api/v1/auth/* 外都需要 API Token。
 * 负责首次引导、401 引导重填、以及「填完立即验证」。
 */
public final class AuthGate {

    /** 防止并发 401 同时弹出多个输入框 */
    private static boolean sPrompting = false;

    private AuthGate() { }

    /** 启动时探测：服务端开了鉴权且本地没 Token → 引导填写（不阻塞进入 App；直连模式无鉴权，跳过） */
    public static void checkStartup(final Activity act) {
        if (act == null || act.isFinishing()) return;
        if (com.lateshow.app.api.Catalogs.isStatic()) return;
        VodSyncApi.get().authState(new VodSyncApi.ApiCb<Models.AuthState>() {
            @Override public void onOk(Models.AuthState s) {
                if (s == null || act.isFinishing()) return;
                if (s.enabled && PrefStore.get().apiToken().isEmpty()) promptToken(act, null);
            }
            @Override public void onErr(String m) {
                // 连接失败由各页面自行提示，这里不打扰
            }
        });
    }

    /** 任意接口返回 401 时调用：是 token 问题就引导填写，填完自动重试 */
    public static boolean handleApiError(final Activity act, String msg, final Runnable retry) {
        if (!VodSyncApi.isTokenError(msg)) return false;
        promptToken(act, retry);
        return true;
    }

    /** 弹出 Token 填写框（预填旧值，方便对比） */
    public static void promptToken(final Activity act, final Runnable onRetry) {
        if (act == null || act.isFinishing() || sPrompting) return;
        sPrompting = true;

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 20);
        box.setPadding(pad, dp(act, 4), pad, 0);

        TextView tip = new TextView(act);
        tip.setText("该服务器已开启访问控制，需要有效的 API Token 才能读取数据。\n\n"
                + "获取方式：浏览器打开 " + PrefStore.get().baseUrl()
                + " → 用管理密码登录 → 右上角「⚙ 设置」→ 复制 API Token，粘贴到下面。\n\n"
                + "注意：面板里点过「重新生成」或重新开启访问控制后，旧 Token 会立即失效，需要重新复制。");
        tip.setTextSize(12f);
        tip.setTextColor(act.getColor(R.color.t2));
        tip.setLineSpacing(dp(act, 2), 1.45f);
        box.addView(tip);

        final EditText input = new EditText(act);
        input.setHint("vs_xxxxxxxxxxxxxxxx");
        input.setSingleLine(true);
        input.setText(PrefStore.get().apiToken());
        input.setSelection(input.getText().length());
        input.setTextSize(13f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 14);
        box.addView(input, lp);

        final AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("需要 API Token")
                .setView(box)
                .setPositiveButton("保存并验证", null)
                .setNeutralButton("去设置页", (dd, w) -> openSettings(act))
                .setNegativeButton("稍后", null)
                .create();
        d.setOnDismissListener(di -> sPrompting = false);
        d.show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                toast(act, "请先粘贴 API Token");
                return;
            }
            PrefStore.get().setApiToken(t);
            d.dismiss();
            verify(act, onRetry);
        });
    }

    /** 用需要鉴权的接口验证 Token 是否可用；成功后执行 onDone */
    public static void verify(final Activity act, final Runnable onDone) {
        VodSyncApi.get().get("/api/v1/stats", null, Models.StatsResp.class,
                new VodSyncApi.ApiCb<Models.StatsResp>() {
                    @Override public void onOk(Models.StatsResp r) {
                        toast(act, "已连接 · Token 有效");
                        if (onDone != null) onDone.run();
                    }
                    @Override public void onErr(String m) {
                        if (VodSyncApi.isTokenError(m)) {
                            toast(act, "Token 无效，请重新复制");
                            promptToken(act, onDone);
                        } else {
                            toast(act, "连接失败：" + m);
                        }
                    }
                });
    }

    private static void openSettings(Activity act) {
        if (act instanceof MainActivity) ((MainActivity) act).openSettings();
    }

    private static void toast(Context ctx, String msg) {
        if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
