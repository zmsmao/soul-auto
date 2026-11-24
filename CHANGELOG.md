# Changelog

本文件记录 SoulBot 面向用户的版本变化。未列出的内部重构不代表聊天策略发生变化。

## [2.0.0] - 2026-11-24

### Added

- 增强多轮对话上下文识别，可理解依赖前文的短答和自然话题交接。
- 引入按消息复杂度选择的回复生成深度：SINGLE、REVIEW、ANALYZE_AND_REVIEW。
- 增加低信息消息连续性判断，符合条件时允许等待，不再只根据单条短消息判断是否回复。
- 为个人资料字段增加主动公开、被问时回答和不公开三种可见性。
- 根据未读会话预览识别重要意图信号，并调整待处理会话顺序。
- 引入稳定联系人 thread_id、消息 event_key 和相应去重标识。
- 增加证据型结构化联系人记忆、来源引用、可选提取和人工管理能力。
- 增加聊天回放盲选对比、人工改稿与分场景反馈。
- 统一 Android Material 3 日夜间主题、导航/卡片样式和 SoulBot 启动图标。
- 按 app、automation、accessibility、workflow、conversation、model、policy、data、diagnostics 拆分 Android 职责边界。
- 建立项目开发与维护约定，明确主线、PR、策略保护、数据迁移和发布流程。

### Changed

- Android `versionName` 更新为 `2.0`。
- Android `versionCode` 更新为 `3`。
- 更新项目首页、产品说明、Android 使用指南、架构审计与 UI 文档。

### Validation

- GitHub Actions #178：单元测试、Debug APK 构建、Android Lint 全部通过。
- 验证代码提交：`5c5bda2dfe859a88794575bdd2684c75b77f55f2`。
- 构建产物为 Debug APK，用于安装验证；它不等同于经过正式签名的 Release APK。

### Release status

本条记录表示代码版本已进入 `master`，不代表 GitHub Releases 页面已经创建正式 Release。正式发布时应在 GitHub Release 中关联版本标签并上传经确认的 APK。
