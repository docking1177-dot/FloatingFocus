# Debug Preset Styles Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for native execution. Track actual execution in the ignored ledger to preserve source-bound check evidence.

**Goal:** 开发者模式切换三种真实首页预设布局，正式版保留当前布局。
**Architecture:** main保留TimerPresetsCard业务；debug/release同签名PresetItems隔离渲染。Debug独立SharedPreferences/StateFlow，实验室只渲染禁用预览。
**Tech Stack:** Kotlin / Compose / Material3 / SharedPreferences / 既有ADB回归。
**Spec:** ../specs/2026-10-28-preset-styles-design.md（用户完整需求与默认方案；继续任务已授权）。

## Constraints and Review Focus

- main已授权合并；新工作在codex/preset-card-styles，不自动合并/发版/递增版本。
- 同ID/顺序/启动回调，快捷App四列完全不动；实验组件仅debug，无新增依赖或权限。
- 预览不得执行任何真实操作；切换不写预设/外观/记录、不启动服务。
- 大字体长名称必须增高；正计时和图标缺失能辨认；运行中不能绕过业务禁用。

## Task 1: Shared operations, isolated layouts and selection

Files: main/TimerPresetsCard.kt；新增debug/PresetItems.kt、PresetStyleStore.kt、PresetStylePicker.kt；release/PresetItems.kt；debug/ReminderLabActivity.kt。
Interfaces: @Composable PresetItems(presets:List<TimerPreset>,enabled:Boolean,onCreate:()->Unit,onLaunch:(TimerPreset)->Unit)；Debug PresetComparisonItems(style,...同参数)；PresetStylePicker()。

- [ ] 在统一device runner新增preset_styles路径，先运行验证开发者模式缺选择入口的失败。
- [ ] 原网格逐字提取至Release；main只替换items块。Debug实现三种纯展示及独立偏好，选择与首页共享flow，预览回调为空且enabled=false。
- [ ] 运行Debug/Release Full、实际设备suite，修复布局或状态问题。

## Task 2: Evidence and delivery

Files: scripts/device_preset_styles.py、device_regression.py、test-device.ps1；docs/PRESETS.md、CHECKS.md；AGENTS.md、CHANGELOG.md。

- [ ] 设备验证三样式同数据/顺序/启动以及空、单、奇数、较多、长名/缺图标/正计时，真实权限/失败/禁用、预览无副作用、小屏大字体。
- [ ] Debug/Release Full；Python脚本回归；检查Release DEX无候选/存储/选择器且Manifest无新增组件。
- [ ] 一次独立最终审查，必要修复后按现有流程绑定源码/APK，提交并同步当前开发分支。保留三张真实截图，报告未验证项目。
