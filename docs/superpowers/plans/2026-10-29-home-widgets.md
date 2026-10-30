# FF Home Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现已确认的2×1、2×2桌面组件，复用唯一会话、默认配置及预设启动。

**Architecture:** 纯展示模型计算Chronometer基准及按钮语义；AppWidgetProvider用系统RemoteViews呈现两个响应式布局。轻量进程内Flow观察已存在会话/配置，仅状态变化刷新。默认启动走可见Activity，预设复用原桌面入口，控制命令绑定会话、换段与明确目标状态。

**Tech Stack:** 原生Kotlin、RemoteViews/XML、现有Compose权限页面、现有JVM/ADB检查。

**Spec:** docs/WIDGETS.md（包含用户已确认行为、实现建议及系统限制）。

## Constraints and review focus

- 不新增依赖、权限、服务、监测、保活、并行计时或版本发布。
- 已有会话与C4限制不可绕过，旧PendingIntent不可影响新会话/新加时段。
- 启动/权限返回/页面重建不能重复消费启动请求，预设只按稳定ID读取最新数据。
- 暂停、归零、超时和进程重新绑定显示正确，历史口径保持。
- 两种尺寸、拖动缩放、深浅主题、大字体及多个实例；真机限制单列。

## Task 1: Model and shared commands

- [ ] 编写展示基准、暂停/继续、陈旧命令与C4守卫测试，先观察失败。
- [ ] 添加TimerWidgetModel，SessionState附带单调采样时刻；服务复用SessionClock处理幂等、绑定会话的控制。
- [ ] 保留既有悬浮操作行为，补齐正计时暂停后的显示。

## Task 2: Providers and UI

- [ ] 两个官方组件入口与响应式RemoteViews布局、主题色及点击反馈，原生Chronometer计秒。
- [ ] 默认启动复用已有配置/权限/策略/服务，预设复用PresetShortcutActivity；进程内观察不增加轮询。
- [ ] 完成Debug Full，实际桌面添加、默认/预设启动、暂停/继续/结束、冲突、C4与缩放回归。

## Task 3: Review and delivery

- [ ] 更新AGENTS/检查文档/CHANGELOG，补充设备回归入口及还原机制。
- [ ] 脚本测试、Release Full与独立审查，核对源码/APK/截图证据。
- [ ] 提交并同步开发分支，不合并或发版，不提交用户asset及本机音频。
