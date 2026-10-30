# 夜场 LATE SHOW — 影视聚合 App

原生 Android（Java）影视聚合客户端，对接 VodSync 后端。

## 功能

- **首页**：源站切换、实时搜索、分类筛选、3 列海报墙无限加载
- **收藏 / 播放历史**：本地持久化、断点续播进度、双遮罩历史卡片
- **设置**：播放源配置、同步控制台（全站同步 / 暂停 / 自动同步开关 / 日志）、三档主题切换
- **播放详情**：线路 + 选集网格、ExoPlayer HLS 播放、进度记忆

## 构建

GitHub Actions 自动构建：push 到 `main` 即触发，APK 在 Run 的 Artifacts 中下载（`late-show-apk`）。

本地构建：`gradle assembleDebug`

## 配置

默认后端：`http://mosang.asia:8181`，可在 App 设置页「播放源」中修改。
