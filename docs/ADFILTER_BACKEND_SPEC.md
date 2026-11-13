# VodSync 后端升级需求：智能去广告（adfilter）规则下发与命中上报

> 版本：1.1（2026-11-12 追加 Phase 2/3 落地状态）
> 提出方：late-show-app Android 客户端（本次同步提交 `c6b9107`，客户端已实现 Phase 1）
> 接收方：vodsync 后端升级实现（目标版本 **v1.16.0**）
> 状态：**后端 v1.16.0 已实现并上线**（vodsync 提交 `53b399a`），客户端 v1.5.162 完成对接；本文档 §7 为后续待办

---

## 1. 背景与目标

### 1.1 背景

源站片源普遍为 m3u8 HLS 流媒体，广告以「片头 + 随机位置一至多次中插」形式混入播放列表。客户端已实现 **App 端本地过滤**（结构指纹算法：`#EXT-X-DISCONTINUITY` 切 run → host+目录+密钥签名归并 → 保留最长源组），经 4 个真实广告源验证命中率 100%、误杀 0。过滤完全在端上执行（拦截播放列表响应体原位重写，分片仍直连 CDN），后端不参与播放数据面，**零带宽成本**。

### 1.2 本次升级目标

后端只做**控制面**两件事，不做任何播放流量代理：

1. **规则包下发**：算法阈值、高置信 URL 模式、站点级例外可以不依赖 App 发版而更新；
2. **命中上报接收**：聚合各设备的过滤命中数据，为后续「站点级广告指纹库」和误报治理提供数据底座。

### 1.3 非目标（明确不做）

- ❌ 后端 m3u8 改写 / ts 反代 / 播放代理（客户端直连 CDN，浏览器 CORS 问题在原生播放器上不存在）；
- ❌ TS 内容分析、帧率指纹、神经网络识别（Phase 4，另行立项）；
- ❌ 实时推送规则（App 端 TTL 轮询拉取已满足时效要求）。

---

## 2. 接口清单（含请求/响应格式变更）

### 2.0 能力声明变更：`GET /healthz`（已有接口，响应新增字段）

现有响应（节选）：

```json
{ "version": "1.15.0", "capabilities": ["tag_search", "backfill_play_from", "minor_mode"] }
```

变更：`capabilities` 数组**新增一项 `"adfilter"`**。客户端依据既有约定判定能力（清单即契约）：清单非空时，不含 `adfilter` → 客户端完全不调用本节两个新接口（零请求）；清单为空的老版本按版本号推断（≥1.16.0 视为支持）。

### 2.1 `GET /api/v1/adfilter/rules` —— 规则包拉取

**鉴权**：需要登录态（客户端 OkHttp 拦截器自动带 `Authorization: Bearer <token>` 与 `X-API-Key`，同时会带 `X-Minor-Mode` 头——未成年模式开启时；后端按现有统一鉴权中间件处理即可，本接口无需单独适配 minor mode）。

**请求**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `version` | query | int | 否 | 客户端当前持有的规则包版本号，缺省视为 0 |

**响应 200**（`application/json`）：

```json
{
  "changed": true,
  "version": 3,
  "rules": {
    "thresholds": { "relative_max_ratio": 0.5, "absolute_max_sec": 300, "safety_floor": 0.8,
                    "safety_floor_high_confidence": 0.5, "duplicate_max_ratio": 0.8 },
    "url_patterns": ["/ad/", "/ads/", "/adv/", "/advert", "/gg/", "/preroll/", "/midroll/", "ad.m3u8"],
    "site_overrides": [ { "host": "some-ad-heavy-site.com", "mode": "off" } ]
  }
}
```

- 客户端 `version` 已是最新：返回 `{ "changed": false, "version": <当前版本> }`，**省略 `rules` 字段**（客户端已做容错，但省略可省流量）；
- 客户端版本号高于服务端（如服务端刚回滚）：返回 `changed=false` 且 `version` 为服务端当前版本，客户端会持用本地较新包直到下次比较（可接受，见 §5 回滚）；
- `rules` 内所有字段均可缺省：缺省字段 = 客户端使用内置默认值。

**规则包字段语义与约束**（客户端装载时会钳制，服务端也应校验拒绝越界包）：

| 字段 | 类型 | 默认 | 客户端钳制区间 | 语义 |
|---|---|---|---|---|
| `thresholds.relative_max_ratio` | double | 0.5 | [0.05, 0.95] | 广告源组相对时长上限：组时长 < 比例 × 最长源组时长 才判广告 |
| `thresholds.absolute_max_sec` | long | 300 | [10, 1800] | 广告源组绝对时长上限（秒），防多段拼片正片被误杀 |
| `thresholds.safety_floor` | double | 0.8 | [0.5, 1.0] | 安全钳：过滤后总时长 < 原始 × 该值 → 客户端整体放弃本次过滤 |
| `thresholds.safety_floor_high_confidence` | double | 0.5 | [0.4, 1.0] | **v1.5.169 新增**。命中「同一批分片被贴两遍」判据时的安全钳下限（短剧单集只有 1~2 分钟，广告占四成是常态，0.8 会把判对的结果全挡回去） |
| `thresholds.duplicate_max_ratio` | double | 0.8 | [0.3, 0.9] | **v1.5.169 新增**。「重复插入」判据的相对上限：被判组时长必须 < 该比例 × 最长组；广告比正片还长的结构一律放行 |
| `url_patterns` | string[] | 内置 8 项 | ≤64 条，非空 | 高置信 URL 子串（小写匹配），命中且组时长 ≤ absolute_max_sec 才删；**禁止放入 `/shop/` `/sale/` `/brand/` 这类语义词**（会对正常影视目录误杀） |
| `site_overrides[].host` | string | — | ≤256 条 | 站点域名（客户端按小写精确匹配播放地址 host） |
| `site_overrides[].mode` | string | — | 当前仅 `"off"` | `off` = 该站点整体不过滤（用于误报站点止血） |

**错误**：401（token 失效，客户端走既有 TOKEN_ERR 流程）；其余任何非 200 客户端均静默忽略并继续用本地规则，因此服务端错误语义无需客户端适配。

### 2.2 `POST /api/v1/adfilter/report` —— 过滤命中上报

**鉴权**：同 §2.1（Bearer token）。

**请求**（JSON body，批量）：

```json
{
  "items": [
    {
      "url": "https://2609.lbsl2026.com/20261110/XdRvPic9/3375kb/hls/index.m3u8",
      "rule_version": 3,
      "removed_runs": 2,
      "removed_ms": 51600,
      "total_ms": 13379900,
      "at": 1762926745
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | string | 被过滤的播放列表地址（客户端实际拉取的 m3u8 URL，可能为二级变体） |
| `rule_version` | int | 上报时客户端生效的规则包版本（0 = 仅内置规则） |
| `removed_runs` | int | 删除的广告 run 数 |
| `removed_ms` | long | 删除的广告总时长（毫秒） |
| `total_ms` | long | 过滤前列表总时长（毫秒） |
| `at` | long | 过滤发生时刻，epoch 秒 |

**用户主动举报样本（v1.5.169 起，复用本接口，无需新增端点）**

播放页地址条的「有广告」按钮、设置页的反馈行，都会往同一个报告通道投递一条
`removed_runs = 0, removed_ms = 0, total_ms = 0` 的记录。

这不是脏数据，而是**样本举报标记**：自动统计只在真正删掉广告时才入库
（客户端 `record()` 仅在过滤结果为非 null 时调用，至少 1 个 run），
所以 `removed_runs == 0` 的记录**必然来自用户手动举报**。

服务端建议：

- 保持现有校验不变（`removed_ms ≤ total_ms` 对 0/0 天然成立，不会丢弃）；
- 运营视图里把 `removed_runs = 0` 的记录单独列出（url + host + at 即可），
  作为「规则漏判样本」的输入源，配合 `/adfilter/admin/stats` 的 host 分布决定是否调参；
- 若要长期做样本库（存列表原文、供离线回归），再单开 `POST /adfilter/sample`
  存 `{url, playlist, note}`；当前这版客户端只用 report 通道，不依赖该端点。

**客户端发送行为**（服务端容量规划的依据）：本地攒批，队列 ≥5 条或规则拉取成功时触发；单批 ≤50 条；上报失败本地保留重试、超上限滚动丢弃最旧 → **服务端必须幂等**：按 `(token, url, at)` 去重，重复上报不产生重复记录。

**响应 200**：任意 JSON 即可，建议 `{ "accepted": 2 }`（accepted = 去重后实际入库条数）；客户端不解析内容。

**服务端必须做的防护**（公网可写端点）：

1. 鉴权必选，匿名请求 401；
2. 限流：建议 60 请求/小时/用户（App 正常频率 ≈ 每会话 1–2 次）；
3. 校验：`items` 数组 ≤50、单条所有字段类型正确、`removed_ms ≤ total_ms`、`at` 与服务端时间偏差 > 7 天的丢弃；
4. 字段长度：`url` ≤2048，超长丢弃该条；
5. 未知字段忽略（向前兼容）。

**数据落地建议**：新表 `adfilter_reports(id, user_id, url, rule_version, removed_runs, removed_ms, total_ms, at, created_at)` + `(user_id, url, at)` 唯一索引。这张表是 Phase 2「按 (源站, vod_id) 聚合出已知广告区间、随规则包下发指纹」的数据源，请保留原始明细不聚合覆盖。

---

## 3. 新增字段与数据结构汇总

| 位置 | 变更 | 类型 | 说明 |
|---|---|---|---|
| `/healthz` → `capabilities[]` | 新增枚举值 | string | `"adfilter"` |
| `GET /api/v1/adfilter/rules` | 新增接口 | — | §2.1 |
| `POST /api/v1/adfilter/report` | 新增接口 | — | §2.2 |
| 服务端存储 | 新增规则包存储 | — | 当前版本号（单调递增 int）+ 规则 JSON 全文 + 历史 ≥5 个版本（供回滚）；建议表 `adfilter_rule_packs(version PK, rules_json, channel, created_at, enabled)` |
| 服务端存储 | 新增上报明细表 | — | §2.2 数据落地建议 |

规则包管理建议提供管理端入口（管理面板或 CLI 均可）：查看当前版本、编辑规则 JSON、发布新版本（version+1）、回滚到历史版本。

---

## 4. 兼容性要求

1. **向后兼容（硬性）**：所有变更均为新增，不得修改/删除任何既有接口与字段。未升级的老客户端（≤v1.5.156）不认识 `adfilter` 能力、不调用新接口，行为与现状完全一致。
2. **客户端对服务端的容错范围**（服务端可依赖的行为边界）：
   - `/healthz` 缺 `capabilities` 字段 → 按版本号推断；
   - rules 接口任何非 200 / 超时（客户端侧无专门超时，走 OkHttp 默认）→ 静默忽略，本地规则继续；
   - `rules` JSON 非法或字段越界 → 客户端**整体丢弃回退内置规则**（不会部分采纳），因此服务端发布前自校验很重要——客户端不会替服务端兜底错误数据；
   - report 接口失败 → 客户端保留队列重试，最多 50 条，最旧滚动丢弃。
3. **数据隔离**：上报接口需按现有用户体系隔离（token → user_id），不得跨用户可见。
4. **隐私**：`url` 为用户实际播放地址，仅用于该用户自己的过滤数据聚合，不得进入任何对外接口。

## 5. 灰度与回滚策略

1. **灰度（建议，客户端 Phase 2 支持后启用）**：规则包存储增加 `channel` 字段（`stable` / `beta`）；rules 接口支持可选 query 参数 `channel`（缺省 `stable`）。客户端将在后续版本加「参与规则内测」开关传入该参数——**本次后端实现需预留该参数**（收到未知 query 参数不得 422，FastAPI 下请用 `Optional` 接收）。
2. **回滚**：
   - 服务端一键回滚 = 把 `enabled` 指回历史版本并保持 `version` 单调（新版本号 + 旧规则内容）；客户端 `version` 高于服务端时按 `changed=false` 处理，持续用本地包直到下次发布，无不良影响；
   - 止血链：单站点误杀 → `site_overrides` 加 `{host, mode:"off"}` 发新版（分钟级生效，App 30min TTL + 每次起播触发拉取）；整体误杀 → 回滚版本。
   - 客户端侧固有兜底（服务端可依赖）：过滤后时长 < 原始 80% 自动放弃；解析失败原样放行。规则包再差也不会导致播放失败，最多退化为「不过滤」。
3. **监控指标**：rules 接口 QPS（预期 ≈ 日活 × 2）、report 接口 QPS 与单批条数分布、`removed_ms/total_ms` 分布（突增提示规则过激）、同一 url 在多设备上的 removed_runs 分歧（提示源站动态化）。

---

## 6. 联调验收标准

以下用例全部通过视为验收完成。`$BASE` = 后端地址，`$TOKEN` = 测试用户 token。

### 6.1 能力声明

```bash
curl -s $BASE/healthz | python -m json.tool
# 通过条件：capabilities 含 "adfilter"；且 version ≥ 1.16.0
```

### 6.2 规则拉取（四态）

```bash
# ① 首次（version=0）：changed=true 且 rules 完整
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/adfilter/rules?version=0"
# ② 已最新：changed=false 且无 rules 字段
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/adfilter/rules?version=<当前>"
# ③ 未携带 token：401
# ④ 未知 query 参数不报错：加 &channel=beta 仍返回 200
```

### 6.3 规则包校验（服务端发布前自测）

- JSON 可解析、字段名与 §2.1 完全一致（snake_case）；
- 越界值（如 `relative_max_ratio: 0.99`）被服务端拒绝发布；
- `version` 相比上一版严格 +1。

### 6.4 上报（五态）

```bash
# ① 正常批量（2 条）
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"items":[{"url":"https://e.com/a/index.m3u8","rule_version":3,"removed_runs":2,"removed_ms":51600,"total_ms":13379900,"at":1762926745},{"url":"https://e.com/b/index.m3u8","rule_version":3,"removed_runs":1,"removed_ms":17600,"total_ms":4020900,"at":1762926800}]}' \
  "$BASE/api/v1/adfilter/report"
# 期望：200 {"accepted": 2}；重复发送同样请求 → accepted: 0（幂等）
# ② 匿名：401
# ③ items 超过 50 条：整体 4xx 拒绝（客户端会滚动重试较小批次，不会死循环）
# ④ removed_ms > total_ms：该条丢弃（accepted 计数不含它）或 4xx，二选一但必须稳定
# ⑤ at 偏差 > 7 天：该条丢弃
```

### 6.5 端到端（真实播放验证，需与客户端 v1.5.157+ 联调）

1. App 起播 → 服务端日志看到一次 `GET /adfilter/rules`（30min TTL 内不重复）；
2. 播放带广告源站（可用 `https://2609.lbsl2026.com/20261110/XdRvPic9/index.m3u8` 验证：应剔片头 25.8s + 中插 25.8s）→ 连续播放 5 个带广告视频后，`adfilter_reports` 表出现对应明细且无重复；
3. 服务端把某广告站 `mode:"off"` 发布新版 → App 下次拉取后该站恢复原始播放（含广告）；
4. 服务端回滚规则版本 → App 播放行为随回滚版本变化，无报错、无重复拉取风暴；
5. 全程老客户端（不升级）回归一遍：行为与升级前一致，服务端无来自它的 rules/report 请求。

---

## 附：客户端侧实现锚点（供后端 AI 交叉核对契约）

- 算法与规则装载：`app/src/main/java/com/lateshow/app/util/HlsAdFilter.java`、`AdFilterRules.java`
- 拉取/上报调度：`util/AdFilter.java`（TTL 30min、批量 ≥5、队列上限 50、幂等键 url+at）
- 传输层：`api/VodSyncApi.java` 统一拦截器（Bearer + X-API-Key + 可选 X-Minor-Mode）
- 能力判定：`api/ServerCaps.java`（清单优先，无清单时版本 ≥1.16.0 兜底）
- 客户端钳制区间：§2.1 表格——服务端发布的值落在区间内才不会被客户端二次修正，发布前请按区间校验

---

## 7. Phase 2 / Phase 3 落地状态（2026-11-12 追加）

### 7.1 Phase 2 联调结论：契约完全对齐

后端 v1.16.0（`53b399a`）已按期实现，逐项核对结果：

| 契约项 | 后端实现 | 客户端 |
|---|---|---|
| `/healthz` 声明 `adfilter` | ✅ 静态项，`ADFILTER_ENABLED=false` 时摘除 | ✅ 清单优先，无清单按 ≥1.16.0 推断 |
| `GET /adfilter/rules` 四态 | ✅ 四态齐全，读库失败降级 version=0，从不 5xx | ✅ 只认 `changed`/`version`/`rules` |
| `channel` 参数 | ✅ 缺省 stable，未知值按 stable | ✅ 内测开关传 beta |
| `POST /adfilter/report` | ✅ items≤50 整批 400；限流 60/h；`accepted/dropped/duplicates` 三分 | ✅ 攒批 ≥5、上限 50、失败保留队列 |
| 单条校验 | ✅ url≤2048、字段非负、`removed_ms≤total_ms`、at 偏差 ≤7 天 | ✅ 上报字段与 §2.2 完全一致 |
| 幂等 | ✅ `(user_id, url_hash, at)` 唯一索引 | ✅ 失败重试不产生重复 |
| 鉴权 | ✅ read Token 可用（`_READ_SCOPE_WRITES`） | ✅ 复用既有拦截器 |
| 规则包校验 | ✅ 服务端硬校验，越界 400 | ✅ 越界包整体丢弃回退内置 |

一处**实现优于文档**的地方（客户端无需适配）：后端用 `sha256(url)` 做去重键而不是 url 本体——url 最长 2048 字符进 PG btree 唯一索引会超限，这个坑文档里没写到，后端处理正确。

### 7.2 客户端 v1.5.162 补的内容

**可观测性**（上一版排查被「`swapped=true` 但看不出删了多少」卡住）：

- 过滤日志从 `swapped=true` 升级为 `swapped=true hit=2runs/51s of 14157s`；
- 删除区间落日志：`spans=[0-25,3087-3112]`（秒，原时间轴）；
- master 播放列表的跳过原因由 `bare-uri-without-extinf`（看着像解析失败）改为 `master-playlist`（事实正确，master 本来就不该过滤）；
- 命中统计不再只存在于上报队列：服务端连不上时日志里仍然有据可查。

**Phase 2 收尾**：

- 设置页新增规则状态行：`规则：服务端规则 v3（内测通道） · 待上报 2 条`，点击立即刷新（走 `AdFilter.refreshNow()` 清 TTL 防抖）；
- 「参与规则内测」开关：拉规则时带 `channel=beta`，切换后立刻重拉；
- 上报失败退避 10 分钟（原来失败后每次起播都去撞同一个失败接口），成功时记录 `accepted/dropped/dup` 便于区分「数据脏」与「重复重试」。

**Phase 3 客户端侧（不依赖后端）**：

- 过滤时记录删除区间，按播放地址缓存 32 条（SharedPreferences 持久化，跨进程存活）；
- 续播定位：历史里存的是**原始时间轴**，起播前换算到过滤后时间轴（`mapStartMs`）；
- 进度回写：统一换回原始时间轴再存（`unmapPosition`）——否则开一次去广告就会把记录往后推一个广告时长；
- 起播地址是 master、过滤命中的是它下面的变体列表，两者路径不同，缓存查找按目录前缀回退命中。

### 7.3 后端还需要做什么

按「必须 / 建议 / 待验证」三档，前两档都不需要客户端发版：

**P0 —— 无代码改动，运营动作（已具备）**

1. 首次上线确认 `/healthz` 已声明 `adfilter`，`adfilter_rule_packs` 已有播种的 v1（`ensure_seed` 启动时写入）；
2. 误杀止血流程已闭环：`site_overrides` 加 `{host, mode:"off"}` 发新版 → 客户端 30 分钟 TTL 内生效，也可让用户点设置页状态行立刻拉取；
3. 整体误杀：面板一键回滚（发新版本号 + 旧内容，客户端只比较版本大小，无需特殊分支）。

**P0（v1.5.169 追加）—— 两行阈值必须登记，否则新参数被静默丢弃**

`normalize()` 的 `_THRESHOLDS` 是白名单，表里没有的键**不报错、直接丢掉**。客户端 v1.5.169 起会读两个新参数，服务端不登记的话，面板上填了也不会下发（看着像客户端没生效，实际是服务端自己吞了）：

```python
_THRESHOLDS = {
    "relative_max_ratio": (0.05, 0.95, False),
    "absolute_max_sec": (10, 1800, True),
    "safety_floor": (0.5, 1.0, False),
    "safety_floor_high_confidence": (0.4, 1.0, False),   # 新增
    "duplicate_max_ratio": (0.3, 0.9, False),            # 新增
}
```

不登记也不会出事：客户端两个字段都有内置默认值（0.5 / 0.8），内置规则照常工作。只是失去了运营侧动态调参的能力。

**P1 —— 建议补的聚合视图（纯后端，约半天）**

`GET /adfilter/admin/stats` 目前只给全局 `removed_ratio`。误杀通常发生在**单个站点**上，全局比例会被其它正常站点稀释，看不出异常。建议增加按 host 的分布：

```json
{ "available": true, "total": 128, "recent": 42,
  "hosts": [ { "host": "2609.lbsl2026.com", "reports": 96, "distinct_urls": 61,
               "avg_removed_ratio": 0.018, "max_removed_ratio": 0.041,
               "avg_removed_runs": 2.0, "last_at": 1762926745 } ] }
```

判定口径建议：某 host 的 `avg_removed_ratio > 0.15`，或 `avg_removed_runs > 4`，基本可以判定规则在该站过激，直接加 `mode:"off"` 止血。这个视图不需要客户端配合，却能把「等用户报障」变成「面板上主动发现」。

**P2 —— 指纹库：先离线验证，暂不下发**

原始需求里 Phase 3 是「按 (源站, vod_id) 聚合已知广告区间、随规则包下发」。现在**不建议**直接做下发，原因：

1. 上报量还不够。指纹的价值来自「同一 URL 在多台设备上删除区间一致」，样本少于 ~5 条时无法区分「源站动态插入」和「算法误判」；
2. 源站播放列表是**动态再生的**（实测同一 URL 连拉 5 次，ETag 每次都变，广告段位置也变），按 URL 下发指纹的命中率会随时间衰减；
3. 客户端消费指纹需要新增一条「不看 DISCONTINUITY 结构、直接按区间删」的算法分支，风险显著高于现在的结构指纹，收益却未验证。

建议顺序：先跑 P1 的聚合视图 + 积累 2~4 周上报 → 离线统计「同 host 的 removed_runs/removed_ratio 方差」→ 方差小（结构稳定）才做指纹下发，届时再定契约。真要做的话，字段建议挂在规则包里、与现有字段并列，客户端按「有就用、没有就走原算法」的方式前向兼容：

```json
{ "thresholds": {}, "url_patterns": [], "site_overrides": [],
  "fingerprints": [
    { "host": "2609.lbsl2026.com", "dir_prefix": "/20261110/",
      "removed_runs": 2, "spans_ms": [[0, 25760], [3087160, 3112920]],
      "samples": 37, "confidence": 0.92 } ] }
```

注意：后端 `normalize()` 目前会**静默丢弃**未知顶层键（这是正确的向前兼容行为），所以新增字段必须同步改 `normalize()` 的已知字段表，否则面板发布后下发的内容里不会有它。

### 7.4 已知限制（客户端侧，接受）

1. **首次观看某片且从中间续播时有一次偏移**：删除区间要等播放列表真正下载完才知道，起播那一刻缓存还是空的，位置按原始轴下发，最多偏移一个广告总时长；第二次起就准确（缓存已建立）。从 0 开始看不受影响。
2. 播放列表 URL 每次变化（源站换域名/换目录）会导致缓存 miss，退化为上面的情况。
3. 上报是采样性质：队列上限 50 条，超出滚动丢弃最旧，服务端统计应看趋势而非绝对值。

### 7.5 v1.5.169 判据增强：「同一批分片被贴两遍」

**触发样本**（`https://vip2.bfbfhao.com/20241007/LrKc9O6T/index.m3u8`，854x1652 竖屏短剧）：

| run | 时长 | 分片目录 | 备注 |
|---|---|---|---|
| A | 25.76s | `/20261009/RCP0FQBQ/9622kb/hls` | 片头广告 |
| B | 10.00s | `/20241007/LrKc9O6T/hls` | 正片 |
| C | 25.76s | `/20261009/RCP0FQBQ/9622kb/hls` | **与 A 逐文件相同** |
| D | 60.20s | `/20241007/LrKc9O6T/hls` | 正片 |

旧规则按「组时长 < 50% × 最长组」判定，广告组 51.5s 对正片组 70.2s = 73%，不满足 → `no-ad-candidate` 整包放行。

**新判据**：同一源组内出现两个**分片序列完全相同**的 run → 该组是「被重复贴进去的素材」。
正片不会自我重复，只有广告会被插多遍。这条不受时长比例约束，但有上限
`duplicate_max_ratio`（默认 0.8，广告比正片还长的结构一律放行）。

命中时安全钳下限从 0.8 放宽到 `safety_floor_high_confidence`（默认 0.5）：短剧单集只有
1~2 分钟，广告占四成是常态，0.8 会把判对的结果全部挡回去。本样本 kept = 70.2/121.7 = 57.7%，通过。

**同日修复**：重建播放列表时，头部声明的 `#EXT-X-KEY` / `#EXT-X-MAP` 只记进了变量、
从未写回 `header` 列表，导致「头部声明 AES-128 + 首个保留 run 沿用同一 KEY」时密钥行整条消失
→ 播放器按明文解析 → 整片花屏。现在重建开头显式补回。

**回归覆盖**：15 个真实/合成样本 × 2 种 baseUrl = 30 组，判定仅新样本 2 组变化，其余 28 组一致；
输出字节唯一差异是补回的头部 KEY 行。边界用例（AES 头部、正片自身重复、广告重复、直播流）全部符合预期。

**试过但撤掉的方案**：「分片目录与播放列表目录一致 = 正片」的锚定判据。实测会反向出错——
短剧源站的广告素材常常和列表同目录，锚定会把广告锁成正片、进而删掉真正片。目录关系不可靠，
重复插入才是结构层面的硬证据。
