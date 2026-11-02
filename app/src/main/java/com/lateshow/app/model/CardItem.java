package com.lateshow.app.model;

/** 首页/收藏/历史统一卡片模型（设计规范 §6.3） */
public class CardItem {
    public static final int KIND_HOME = 0;
    public static final int KIND_FAV = 1;
    public static final int KIND_HIST = 2;

    public int kind;
    public String slug;        // 源站 slug
    public String sourceName;  // 源站展示名
    public long vodId;
    public String title;
    public String pic;
    public String remark;      // vodRemarks 角标
    public String timeStr;     // 添加/收藏时间（底部遮罩）
    public String topTimeStr;  // 历史卡片顶部时间
    public long playedSec;
    public long totalSec;
    public boolean fav;
    /** 本地是否已缓存完整播放数据（离线可播） */
    public boolean cached;
}
