# 首页统一计时页 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in this session; one independent whole-change review at the end.

**Goal:** 合并计时和App，保留预设，安全迁移旧导航。

**Architecture:** MainActivity保留页外当前选择，将QuickLaunchCard内嵌计时面板；HomeNavigation统一解析新/旧ID及顺序，不改启动业务。

**Tech Stack:** Kotlin / Jetpack Compose / 既有SharedPreferences与Python设备回归。

**Spec:** docs/superpowers/specs/2026-11-02-unified-timer-design.md

## Global Constraints

- 保留AGENTS原空行、asset及视频相关用户内容，不新增权限/依赖/服务。
- 默认值、预设快照、C4和活跃会话守卫不变；不合并、不发版、不改版本。

## Task 1：导航迁移

Files: HomeNavigation.kt、HomeNavigationStore.kt、HomeNavigationTest.kt。

- [x] 为两栏默认/排序、六种旧排列去重和apps恢复ID写断言。
- [x] `./scripts/check.ps1 -Mode Unit -Offline`，确认旧实现失败。
- [x] 删除APPS枚举，解析有效旧排列后映射去重；Store持久化归一顺序；selected兼容apps。
- [x] 重跑Unit确认通过。

## Task 2：连续计时面板

Files: MainActivity.kt、QuickLaunchCard.kt；接口仍为原seconds/mode/enabled及原启动回调。

- [x] 保留一套参数，将App网格内嵌原计时HomeSection，删除独立App分支、重复标题和摘要。
- [x] 保留手动设置、管理/额度入口、单页纵向滚动和共同活跃状态。
- [x] 更新既有设备回归到两栏，补两入口同步和迁移实际验证。
- [x] Full Debug/Release与脚本测试；专用模拟器执行相关交互并核对截图。

## Task 3：审查及记录

- [x] 独立审查本轮差异，按影响修复/复测。
- [x] 更新AGENTS、首页/快捷App文档、CHANGELOG及统一测试库，保留原证据及构建身份。
- Git收尾只提交本轮内容，同步当前开发分支；实际提交以Git与交付报告为准。

## Review Focus

1. 旧App第一栏映射不能把预设挪到第一栏，恢复apps不能出现空白。
2. 模式/时长在两个启动入口相同，App点击仍是立即启动而非先选中。
3. 启动失败或缺权限不丢当前选择，快速交替点击不替换会话。
4. 预设仍读取保存配置，活跃/C4操作与管理额度入口保持。
5. 只有两栏的真实导航要有断言，不能以测试别名假装第三栏仍存在。
