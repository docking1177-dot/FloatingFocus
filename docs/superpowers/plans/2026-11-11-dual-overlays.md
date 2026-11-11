# 双浮窗实施计划

> For agentic workers: use superpowers:executing-plans in this conversation, task by task.

**Goal:** 两路独立浮窗、独立背景/文字透明度及安全横屏边界。
**Architecture:** 一个前台服务内复用现有会话实现，聚合容量和公开状态；额度按包名合并运行覆盖。
**Tech Stack:** Kotlin / Compose / WindowManager / 现有 JVM 与 ADB 回归。
**Spec:** ../specs/2026-11-11-dual-overlays-design.md

## 约束与审查重点
不改权限、依赖、版本、C4时间轴；保留用户修改，不发布。重点防止旧请求结束错窗、失败取消其他窗、重复扣额、锁定alpha重复应用、旋转覆盖拖动位置。

## 任务
- [x] 1. 为容量/最早会话控制、重叠额度、透明度迁移与归一化添加有区分力的失败测试，运行确认失败。
- [x] 2. FloatingService 内封装现有单路运行资源，添加两路宿主与 ID 路由；修改共用启动器、首页控制、组件入口校验。两路独立记录/配置，失败只回退拥有的请求。
- [x] 3. DailyAppQuota 将同时活跃同包会话的运行覆盖去重，保留原账本/水位兼容及失败重试。
- [x] 4. ReminderSettings/Codec/Store/Presentation/FocusOverlay/设置预览增加前景透明度，保持旧值兼容和C4固定主题。
- [x] 5. 实测横屏底边/旋转/导航模式；有证据才修安全边界，未复现保持待真机确认。
- [x] 6. Unit/Debug/Release 检查及相关模拟器两窗、透明度和窗口回归；更新指南、测试库与成果说明，提交并按既有授权安全同步开发分支，不合并或发版。

实测发现全屏隐藏状态栏的可见性不匹配，已由原生父框/窗口66px差值复现并修复；审查两项发现及补充底边钳制回归已修正。225项应用输入保持，文档/测试整理不重建APK。真机仍待验，不记通过。
