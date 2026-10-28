# Preset desktop shortcuts implementation plan

> **For agentic workers:** Use superpowers:executing-plans in this session; one final independent review. Steps tracked in the ignored ledger.

**Goal:** 从用户确认添加的桌面入口启动最新预设与绑定 App。
**Architecture:** 官方 pinned shortcut + ID-only entry，复用预设存储、现有校验及正式启动器；一次性同步图标，无后台轮询。
**Tech Stack:** Kotlin / Compose / Android API 29–36 / existing coroutines / JUnit.
**Spec:** docs/superpowers/specs/2026-10-28-preset-shortcuts-design.md

## Global constraints
- 已确认整组推荐行为；不新建计时系统、权限、服务或依赖，不碰布局实验偏好，不发版。
- 保留当前分支基线 fa0255e 与用户 asset。一个完整逻辑提交，完成检查后推送功能分支。

## Task 1: identity, store and pinned shortcut management
- [ ] 写 JVM RED：同名不同 ID、无效 ID、严格查找/读取失败、活跃冲突、绑定/权限/策略和一次消费。
- [ ] 实现 PresetShortcutPolicy.kt，TimerPresetStore 的可区分读错快照/写入保护，PresetDesktopShortcuts.kt 的支持检查/固定身份/本机图标/回调和有限同步。
- [ ] TimerPresetsCard.kt 管理增加添加操作；复用绑定对话框，不改变布局数据和启动回调。
- [ ] 跑 Unit，新增正式设备 suite 的创建/取消/桌面显示断言。

## Task 2: ID-only launch and validation
- [ ] PresetShortcutActivity.kt：显式入口、独立 affinity、消费保存、授权与修复返回继续，成功 finish；私有成功 receiver 入 manifest。
- [ ] PresetLauncher.kt 复用共享校验，首页和桌面都读取最新预设；AppTimerLauncher 保护并发启动，原有取消与外观恢复保持。
- [ ] 设备 suite 验证冷热/重复/重建、更新/删除/读错、目标不可用、权限、当前会话及 C4，检查实际 App/窗口/记录而非仅返回值；备份还原原 FF 数据，桌面副作用仅专用 AVD。
- [ ] 更新 AGENTS/PRESETS/CHECKS/CHANGELOG；Full Debug/Release、脚本测试、设备回归、最终独立审查；绑定源码/APK，提交推送，不合并。

## Review focus
- 读取失败不能批量禁用；拒绝 pin 无回调不永久 busy；等待中编辑/删除由成功回调重读。
- 重建/权限返回不重播；新点击仍是新请求；服务未 active 的并发窗口不互相取消。
- 清理本入口 task 不清理 FF 原草稿；外部 extras 不成为执行依据；Release 不依赖实验组件。
