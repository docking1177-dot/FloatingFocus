# C4 常态图案 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 常态 C4 复用普通浮窗形状选择，胶囊与圆形使用不同 C4 图案，竖胶囊装饰相对横胶囊旋转90°。

**Architecture:** 继续由 ReminderPresenter/OverlayGeometry 决定外观与测量，C4CompactArt 仅绘制内部装饰。只传入现有形状枚举，不把普通大小、颜色、透明度传进 C4 主题。预设仍通过已有外观应用流程提供保存的形状，不增加配置字段或入口。

**Tech Stack:** Kotlin、Jetpack Compose 原生绘制、现有 JVM/ADB 检查脚本。

**Spec:** 本轮用户明确要求及确认“沿用普通浮窗的形状选择”；产品说明随实现更新于 docs/C4_COUNTDOWN.md。

## Global Constraints

- 不改完整装置、时间轴、音效、口令、拖动和退出规则。
- 保持C4固定大小倍率、配色及0.7不透明度，不新增权限、依赖或设置。
- 胶囊图案采用绑带、液晶面板、线路与现有LED；圆形采用环状面板、线路与LED。数字区域留空且保持可读。
- 使用当前功能分支，不自动合并、发版或递增版本。

## Review Focus

- 形状改变须同时影响绘制、窗口轮廓和触摸，不能仅换图案。
- 竖胶囊装饰转向但文字正向，圆形不误走胶囊分支。
- 普通大小/颜色/透明度不重新泄漏进C4。
- 展开后完整装置尺寸与输入保持不变，收起保留输入。
- 数字不被装饰遮挡，透明角落不成为额外触摸区域。

## Task 1: 常态形状与绘制

**Files:** ReminderPresentation.kt、FloatingService.kt、FocusOverlay.kt、C4CompactArt.kt、C4TimelineTest.kt。

- [ ] 修改既有主题测试，验证三种形状及固定色/透明度/倍率；先运行 Unit 观察缺失接口导致失败。
- [ ] forC4仅接收额外shape，服务传入settings.shape；常态绘制区分圆形/胶囊，竖向使用90°坐标映射。
- [ ] Debug Full通过后，复用c4_compact验证三形状截图、尺寸稳定、拖动、展开/收起、输入及解除。

## Task 2: 文档与收尾

**Files:** scripts/device_c4.py、docs/C4_COUNTDOWN.md、docs/CHECKS.md、AGENTS.md、CHANGELOG.md。

- [ ] 更新旧回归中“形状也完全独立”的断言，保留大小/颜色/透明度隔离验证。
- [ ] 明确本轮已确认的例外仅为常态轮廓，补充两套图案与文字正向规则。
- [ ] 执行脚本测试、Release Full与独立审查；核对同源码/APK的设备证据。
- [ ] 仅提交本轮改动并同步当前分支，保留用户asset；不合并或发布。
