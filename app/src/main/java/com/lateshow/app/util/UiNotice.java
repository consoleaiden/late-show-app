package com.lateshow.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.lateshow.app.LateShowApp;

/**
 * 应用级气泡提示：非 UI 层（图片测速 / 广告过滤）也能发短提示。
 *
 * <p>两点必须守住：
 * <ul>
 *   <li>Toast 用 Application 上下文，不持有 Activity / Fragment，避免泄漏；</li>
 *   <li>Toast 只能在主线程 show，调用方可能在 OkHttp / ExoPlayer 线程，统一切回。</li>
 * </ul>
 * <p>Application 未就绪（单元测试等）时静默忽略，提示永远不能影响主流程。
 */
public final class UiNotice {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiNotice() { }

    /** 任意线程可调用；消息为空或上下文未就绪时什么都不做。 */
    public static void show(String msg) {
        if (msg == null || msg.isEmpty()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showNow(msg);
        } else {
            MAIN.post(() -> showNow(msg));
        }
    }

    private static void showNow(String msg) {
        Context ctx = LateShowApp.instance();
        if (ctx == null) return;
        Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
