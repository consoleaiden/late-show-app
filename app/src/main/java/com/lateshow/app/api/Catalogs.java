package com.lateshow.app.api;

import com.lateshow.app.store.PrefStore;

/** 数据源入口：按偏好返回当前模式对应的 Catalog 实现 */
public final class Catalogs {

    private static final SyncCatalog SYNC = new SyncCatalog();
    private static final StaticCatalog STATIC = new StaticCatalog();

    /** 切换模式后置位：首页在下一次回到前台时重新加载 */
    private static volatile boolean sDirty = false;

    private Catalogs() {}

    public static int mode() {
        return PrefStore.get().dataMode();
    }

    public static boolean isStatic() {
        return mode() == Catalog.MODE_STATIC;
    }

    public static Catalog get() {
        return isStatic() ? STATIC : SYNC;
    }

    /** 缓存 key 前缀：两种模式的数据绝不能互相命中 */
    public static String cacheTag() {
        return isStatic() ? "static" : "sync";
    }

    public static void markDirty() {
        sDirty = true;
    }

    /** 读取并清除脏标记（true 表示模式刚切换过，调用方应重新加载） */
    public static boolean consumeDirty() {
        boolean d = sDirty;
        sDirty = false;
        return d;
    }
}
