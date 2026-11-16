# 自定义主题系统（ThemeEngine）

用户可为首屏、详情页、设置页等所有强调色部件配置配色方案，并为**亮色 / 暗色分别绑定**不同方案。
引擎关闭时，全 App 完全回退到内置「方寸」配色（朱砂 `#FE5A35` / 暖墨 `#1B1412` / 宣白 `#F2ECE6`），观感与旧版一致。

## 1. 实现文件

| 文件 | 职责 |
| --- | --- |
| `theme/ThemeEngine.java` | 方案数据模型、持久化、解析（`resolve`）、运行时取色 API |
| `theme/ThemeGalleryActivity.java` | 方案画廊：预览、绑定亮/暗、新建、恢复内置 |
| `theme/ThemeEditorActivity.java` | 方案编辑器：主色/辅助色/背景/玻璃参数 |
| `util/ThemeManager.java` | 日夜模式应用（`apply` / `expectNight` / `actualNight`） |
| `util/FangcunMarkDrawable.java` | 开屏 / 字标的一次性「手绘」Drawable，颜色构造传入 |
| `util/FangcunLoadingDrawable.java` | 品牌 loading（Canvas 三角），`setAccentColor` 运行时改色 |
| `util/RingBreathDrawable.java` | 源站呼吸环，`setAccentColor` 运行时改色 |
| `store/PrefStore.java` | `themeEngineOn` / `theme`（0 暗 1 亮 2 跟随）/ 方案 JSON |
| `LateShowApp.java` | 进程入口：`ThemeManager.apply` → `PrefStore.init` → `ThemeEngine.init`，保证开屏即取色 |

持久化在 `lateshow_theme.xml`：`theme_schemes`（JSON 数组）、`theme_light_id`、`theme_dark_id`。

## 2. 运行时取色 API（`ThemeEngine` 静态方法）

所有 API 都遵循同一条约定：**引擎开启 → 取当前绑定方案的颜色；引擎关闭 → 原样返回内置资源/令牌**。
新增强调色部件一律走这里，禁止写死 `@color/acc`。

| API | 用途 |
| --- | --- |
| `accentOf(ctx)` | 方案主色（选中态 / 按钮 / 图标） |
| `accentTextColor(ctx)` | 强调文字色（浅底压暗、深底提亮的同色系变体） |
| `accentInkColor(ctx, builtIn)` | **强调底上的文字色**：按主色亮度自动选深/浅（`Resolved.onPrimary`） |
| `inkOn(bg)` | 给定任意底色返回对比文字色（亮底 `#1B2026` / 暗底 `#FFFFFF`） |
| `accentButtonBg(ctx)` | 主按钮底（替代 `bg_btn_main`） |
| `accentPillBg(ctx)` | 选中胶囊底（替代 `bg_seg_on`） |
| `accentDotBg(ctx)` | 源站/代理圆点（替代 `dot_acc`） |
| `accentRingBg(ctx, ...)` | 源站圆环（替代 `bg_ring_on`） |
| `accentEpisodeBg(ctx)` | 选集/线路选中底（替代 `bg_episode_current`） |
| `accentProgressBg(ctx)` | 进度条（替代 `pbar_sync`） |
| `accentGlowBg(ctx)` | 底栏光斑（替代 `bg_nav_glow`） |

`Resolved` 字段：`bgStart/bgEnd`（背景）、`surface`、`textPrimary/textSecondary`、`primary/secondary/onPrimary`、
`navAlpha`、`glassOn/barBlur/barFrost/barAlpha`、`angle`。

## 3. 各页面接入清单

| 页面 | 跟随主题的部件 |
| --- | --- |
| 开屏 `SplashActivity` | 品牌三角（`FangcunMarkDrawable`），方框恒为宣白 |
| 首页 `HomeFragment` | 分类芯片选中底/字、源站呼吸环、源站菜单圆点、顶栏/弹层毛玻璃（模糊/白雾/底色/开关） |
| 详情页 `DetailFragment` | 页面底色、主按钮底/字、选集选中底、线路/明星 Tab、源站胶囊圆点、品牌 loading、简介展开文字色、收藏色 |
| 收藏页 `FavFragment` | 分段胶囊选中底/字（`switchSegSilent`）、卡片角标与进度 |
| 设置页 `SettingsFragment` | 分段控件、保存/检查更新按钮、打开同步控制台/新增代理文字、代理圆点、页脚字标三角 |
| 同步控制台 `SyncConsoleActivity` | 主按钮、添加自定义源站文字、选中计数、进度 |
| 主题画廊/编辑器 | 选中卡片底/标题字、亮暗标签、分段控件 |

`MainActivity` 负责分发：主题变化时遍历 Fragment，对实现 `ThemeAware` 的调用 `onThemeChanged()`；
底栏图标由 `Icons.tint` 染色，指示条/光斑用 `accentInkColor` / `accentGlowBg` 重画。

## 4. 约定与踩坑

1. **XML 里的 `@color/acc` 是「引擎关闭时的回退值」**，不是漏改。运行时由代码用同一形状重画覆盖。
   判断标准：该处是否有对应的 `ThemeEngine.accentXxx()` 调用；没有就是漏。

2. **初始渲染就要应用主题**，不能只写在 `onThemeChanged()` 里。
   典型反例：`FavFragment` 的 `switchSeg()` 有「同态直接返回」短路，初始化时不能用它，
   必须调 `switchSegSilent(showHist)`，否则胶囊一直停在 XML 默认的橙色。

3. **强调底上的文字必须跟随主色亮度**。主色可以是任意用户色，固定 `accInk` 会在深主色上糊掉。
   统一用 `accentInkColor(ctx, 内置回退)`；引擎关闭仍返回内置文字色，观感不变。

4. **MotionLayout 会吞掉子树的 `requestLayout()`**（见 `res/xml/motion_detail.xml` 注释）：
   处于转场态且 `layoutDuringTransition="honorRequest"` 时不向父级传递；
   即使真的跑起来，`onMeasure()` 在「测量规格没变」时也不会调 `ConstraintLayout.onMeasure()`。
   因此详情页运行时新增的选集行/推荐卡必须先递归 `forceLayout()` 打脏标记，再从 DecorView 强制遍历
   （`DetailFragment.forceTraversal()` + `markTreeDirty()`）。

5. **主题变更不重建 Activity**：`ThemeEngine.applyRuntime()` 只换窗口/内容底色，
   页面内控件由 `onThemeChanged()` 逐个重绘，切换才顺滑。

## 5. 毛玻璃（底栏 / 顶栏 / 弹层）

实现集中在 `ui/widget/`：
`FrostedGlassBar`（底栏 + 顶栏）、`FrostedGlassPanel`（源站/分类弹层）、`FrostedGlassRenderer`（共用渲染）、
`GlassTheme`（把方案参数映射到控件）。

- **效果**：采样背后内容 → 降采样模糊 → 叠白雾。**不做**边缘折射 / 色散 / 彩虹边（原「液态玻璃」已移除，
  `res/raw/liquid_glass_bar.agsl` 一并删除）。
- **全 API 可用**：模糊走「画进 1/8~1/28 小图再双线性放大」的纯 Canvas 方案，不依赖 `RenderEffect`/
  `RenderNode`（后者在华为等部分 GPU 上会被静默丢弃，玻璃会整块变透明）。
- **可调参数**（`ThemeScheme`，均有默认值）：
  `glassOn`（默认开）、`barBlur`（默认 36）、`barFrost`（默认 40）、`barAlpha`（默认 88）。
  主题编辑器的「毛玻璃」分区可实时调节并保存；底栏/顶栏在 `onThemeChanged()` 里重刷，弹层在每次显示时重刷。
- **默认值兜底**：老版本落盘的方案没有这几个字段，Gson 会把缺失的 int 读成 0、boolean 读成 false
  （等于「玻璃关 + 无模糊」，毛玻璃整块消失）。因此 `ThemeScheme` 字段带初始值，
  且 `ThemeEngine.parseSchemes()` 再按「字段是否存在」补一次默认值。
- **永不全透明**：采样失败或用户关掉毛玻璃时，控件 background 仍有一层 `barAlpha` 的底色。
- **省电**：采样节流 ~11fps，且只在有绘制时进行（不主动 `invalidate` 自我续帧），静止零开销。
