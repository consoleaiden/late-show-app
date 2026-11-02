# 离线缓存设计（首页 / 分类 / 收藏 / 历史）

实现文件：

- `app/src/main/java/com/lateshow/app/util/ApiCache.java`：接口响应缓存（内存 + 文件，带 TTL）
- `app/src/main/java/com/lateshow/app/util/OfflineMedia.java`：收藏/历史的完整播放数据存储
- `app/src/main/java/com/lateshow/app/store/PrefStore.java`：收藏/历史条目（新增 `content` / `epCount` / `cached` 字段）
- `app/src/main/java/com/lateshow/app/ui/HomeFragment.java`：缓存优先加载、失败回落
- `app/src/main/java/com/lateshow/app/ui/PlayActivity.java`：详情缓存写入 + 离线播放
- `app/src/main/java/com/lateshow/app/ui/FavFragment.java`：离线可播数量展示

## 1. 缓存的数据范围

| 数据 | 是否缓存 | 说明 |
| --- | --- | --- |
| 首页影片列表第 1 页（含 `pagecount` / `total`） | 是，TTL 10 分钟 | key = `videos\|源站\|分类\|关键词\|页码`，不同源站/分类/搜索词各自一份 |
| 首页列表第 2 页及以后 | 否 | 体积不可控，翻页仍走网络；离线时只展示已缓存的第一页 |
| 分类列表 | 是，TTL 6 小时 | key = `cats\|源站`，分类变动慢，命中即不发请求 |
| 源站列表 `/api/v1/sources` | 是，TTL 10 分钟 | 供源站切换菜单与「已收录 N 部 · M 源」统计离线展示 |
| 收藏条目元信息 | 永久（SharedPreferences） | 标题/封面/备注/分类/简介/分集数/收藏时间 |
| 历史条目元信息 | 永久（最近 200 条） | 上面这些 + 线路/集号/集名/已播秒数/总时长/最近播放时间 |
| 收藏/历史的**完整播放数据** | 永久（到期由容量 LRU 淘汰） | 整份 `VideoDetail`：`vod_play_list` 全部线路、每集 `name` + `url`（即分集数与全部播放地址）、`vod_content`、`vod_pic`、`type_name` |
| 接口缓存上限 | 60 个文件 | 超出按最后修改时间淘汰最旧 |

不缓存：搜索的后续页、设置页同步日志、鉴权接口响应、视频流本身（播放永远直连源站 CDN）。

## 2. 存储结构

```
/files/api-cache/<key>.json            接口响应缓存
    {"t":1699..., "d": <原始响应 JSON>}
/files/offline-media/<slug>_<vodId>.json   一部剧一个文件，整份 VideoDetail
```

- 都用应用私有目录（`getFilesDir()`），不需要存储权限，卸载即清理，也不会被系统当临时文件清掉。
- 接口缓存写盘是异步单线程串行，读优先内存 LRU，未命中才读文件；UI 线程不做批量 IO。
- 离线播放数据保留 12 部在内存 LRU，详情页反复进出不重复解析。

## 3. 更新策略

**接口缓存（stale-while-revalidate 的简化版）**

1. 请求前先查缓存：命中且未过期 → 直接渲染，**不发网络请求**（这就是「避免重复请求」的实现）。
2. 未命中或已过期 → 发请求；成功后覆盖缓存（同一 key 覆盖写，不做追加）。
3. 请求失败 → 读「忽略 TTL」的旧缓存兜底，列表页提示「网络不可用，显示本地缓存内容」，
   源站统计显示「● 缓存数据 · N 分钟前」，顶部圆环由 `healthz` 探测结果决定灰/橙。
4. 设置页可手动「清除首页/分类/源站缓存」。

**离线播放数据**

| 触发时机 | 动作 |
| --- | --- |
| 详情接口成功，且该剧**已收藏或已有历史** | 覆盖写入最新线路与每集地址 |
| 点击播放（会产生历史） | 覆盖写入，保证历史里的剧离线可播 |
| 点击收藏 | 立即写入，并把 `FavItem` / `HistItem` 的 `content` / `epCount` / `cached` 补齐 |
| 取消收藏且**无**播放历史 | 删除该剧的离线文件，`cached` 置 false |
| 写入后超 80 部或 40MB | 按文件最后修改时间淘汰最旧 |

播放进度不写离线文件：每 5 秒只会更新 SharedPreferences 里的历史条目，避免频繁 IO。

## 4. 离线时的读取逻辑

1. 收藏页 / 历史页本身只读本地存储，断网照常展示；副标题显示「N 部可离线播放」。
2. 点开某部剧 → `PlayActivity.loadDetail()` 请求详情：
   - 成功：正常渲染，并按上面的规则刷新离线数据。
   - 失败：先调 `OfflineMedia.loadDetail(slug, vodId)`
     - 有缓存 → 用缓存的 `VideoDetail` 渲染（线路、选集、简介齐全），元信息尾部标注「离线缓存」，
       弹「离线模式：使用本地缓存的播放数据」，**可直接点播放**；
     - 无缓存 → 再走 API Token 引导 / 报错提示「详情加载失败」。
3. 首页断网：列表、分类、源站菜单全部用缓存渲染，点进未缓存的剧会提示详情加载失败。

## 5. 已知限制

- 离线播放依赖源站 CDN 直链仍然有效；若源站换了地址或链接带时效签名，离线缓存会失效（重新联网打开一次该剧即可刷新）。
- 封面图由 Glide 自己的磁盘缓存负责，离线时若从未加载过该封面会显示占位色块，不影响播放。
- 升级到本版本前已有的收藏/历史没有播放数据，需要联网打开一次才会生成离线缓存。
