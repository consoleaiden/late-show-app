package com.lateshow.app.util;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.lateshow.app.api.VodSyncApi;

/**
 * 网络回调的生命周期守卫与请求代次（审计 A-10 / A-11 / A-21）。
 *
 * <p>{@link VodSyncApi} 的回调由静态主线程 Handler 投递，本身不绑定任何组件。
 * 用这里的两层包装把「页面已销毁」「结果已过期」两类脏回调挡在 UI 之外：
 * <ul>
 *   <li>{@link #alive(LifecycleOwner, VodSyncApi.ApiCb)}：组件未 STARTED 时丢弃；</li>
 *   <li>{@link #alive(Fragment, VodSyncApi.ApiCb)}：额外要求 view 仍在（Fragment 已 detach 视图时丢弃）；</li>
 *   <li>{@link #generation(IntSupplier, int, VodSyncApi.ApiCb)}：回调到达时代次已变则丢弃。</li>
 * </ul>
 * 两者可组合使用：{@code generation(() -> seq, mySeq, alive(this, cb))}。
 */
public final class Callbacks {

    public interface IntSupplier {
        int get();
    }

    private Callbacks() { }

    public static <T> VodSyncApi.ApiCb<T> alive(LifecycleOwner owner, VodSyncApi.ApiCb<T> cb) {
        return new VodSyncApi.ApiCb<T>() {
            @Override public void onOk(T result) {
                if (!isAlive(owner)) return;
                cb.onOk(result);
            }
            @Override public void onErr(String message) {
                if (!isAlive(owner)) return;
                cb.onErr(message);
            }
        };
    }

    public static <T> VodSyncApi.ApiCb<T> alive(Fragment f, VodSyncApi.ApiCb<T> cb) {
        return new VodSyncApi.ApiCb<T>() {
            @Override public void onOk(T result) {
                if (f == null || !f.isAdded() || f.getView() == null) return;
                cb.onOk(result);
            }
            @Override public void onErr(String message) {
                if (f == null || !f.isAdded()) return;
                cb.onErr(message);
            }
        };
    }

    public static <T> VodSyncApi.ApiCb<T> generation(IntSupplier current, int expected,
                                                     VodSyncApi.ApiCb<T> cb) {
        return new VodSyncApi.ApiCb<T>() {
            @Override public void onOk(T result) {
                if (current.get() != expected) return;
                cb.onOk(result);
            }
            @Override public void onErr(String message) {
                if (current.get() != expected) return;
                cb.onErr(message);
            }
        };
    }

    private static boolean isAlive(LifecycleOwner owner) {
        if (owner == null) return false;
        try {
            return owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        } catch (Exception e) {
            return false;
        }
    }
}
