# 超时周期提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在现有 FF 会话中提供可选的浮动周期提醒、受控效果轮换及主动选择下一次提醒。

**Architecture:** 纯 Kotlin `OvertimeReminderEngine` 保存单调截止和当前超时段状态，`FloatingService` 负责唯一调度与输出。独立设置存储和页面，真实浮窗只读取短暂 cue，主动延后通过会话/计时段校验提交。

**Tech Stack:** Kotlin、Jetpack Compose、现有 SharedPreferences、Handler、官方声震 API；不增加依赖、权限或服务。

**Spec:** `docs/superpowers/specs/2026-11-04-overtime-reminders-design.md`（用户已确认）

## Global Constraints

- 总开关默认关闭，基准 300 秒、可选 60—10800 秒，自动间隔 ±20%，用户明确选择的一轮不浮动。
- 三种视觉默认允许，声音/震动默认关闭；首轮只视觉；超时达 3 倍基准后标准，主动延后 3 次后明显；始终取上限。
- 周期强度独立，不读取普通外观强度或 C4 固定零动画强度。
- 同一个 `Countdown` / `FloatingService`；不改累计、额度、历史、预设或 C4 时间轴。
- 完整 C4 不变；音轨忙时跳过短提示音，结束/换段/异常取消，不补发。
- 保留当前用户改动，不合并、不发布、不递增 0.3.0 / code4。

## Review Focus

- 旧段滚轮确认、双击确认及结束与回调竞态，不能改新会话或产生第二次声震。
- 设置关闭/收紧允许效果时即刻停止本效果；明确单次截止不被间隔编辑提前。
- 锁屏/调度延迟只输出一轮，不能积压连响；回调依赖单调时钟，不依赖重绘。
- C4 从完整形态收起时不能重播旧 cue；完整装置布局、口令、音轨和退出限制保持。
- 文字效果和小时进位不改变窗口帧或按钮位置；禁用动画也保留有界静态反馈。

---

### Task 1: 配置与纯调度策略

**Files:** 新建 `app/src/main/java/app/floatingfocus/OvertimeReminders.kt`、`OvertimeReminderSettingsStore.kt`；新建 `app/src/test/java/app/floatingfocus/OvertimeReminderTest.kt`。

**Interfaces:**
- `OvertimeReminderSettings(enabled, intervalSeconds, effects, limit, interaction)` 与 `OvertimeReminderCodec.encode/decode`。
- `OvertimeContext(key: OvertimeKey?, inOvertime: Boolean, paused: Boolean, overtimeMillis: Long, elapsedMillis: Long, plannedMillis: Long, c4: Boolean, c4Expanded: Boolean, c4AudioBusy: Boolean)`。
- `OvertimeReminderEngine.update(now: Long, context: OvertimeContext, settings: OvertimeReminderSettings): OvertimeCue?`，`snooze(now, context, settings, seconds, operationId): Boolean`，`clear()`，只读 `state`。
- `state.nextAt` 为 SessionClock 域的绝对截止；`cue` 包含 ID、允许效果、有效级别、超时/累计/计划/延后次数快照。

- [x] 写失败测试：缺失/非法配置默认关闭且声音震动不被意外开启；300秒范围240000—360000ms；反复update不重新抽截止；迟到只发一轮。
- [x] 写失败测试：首轮视觉、最高两效果、近期降低优先级但可重复；分级与上限；声震独立；C4完整形态与音轨忙过滤。
- [x] 写失败测试：精确180秒单次延后、重复operationId和旧key拒绝、只确认增次数、取消不调用；基准编辑不动明确截止；关闭/换段/正计时取消。
- [x] 运行 `./scripts/check.ps1 -Mode Unit -Offline`，确认新行为缺失而失败；实现最小策略和独立设置存储，再运行同命令至通过。

### Task 2: 服务调度、反馈和稳定渲染

**Files:** 新建 `OvertimeReminderFeedback.kt` / `OvertimeVisualFeedback.kt`；修改 `FloatingService.kt`、`ReminderVibration.kt`、`C4Runtime.kt` / `C4Audio.kt` 的只读音频忙状态、`FocusOverlay.kt`、共用及两源码集 `OvertimeHeader.kt`。

**Consumes:** Task1 的 context/engine/cue/store。
**Produces:** `FloatingService.requestOvertimeSnooze(context, expectedSessionId, expectedStart, seconds, operationId)`、`SessionState` 的提醒摘要；浮窗 `onSnooze` 回调，真实计时段与全部旧操作含义不变。

- [x] 为有效控制请求/模式/段/重复确认和强度、视觉占位规则补纯逻辑失败测试，运行 Unit 确认失败。
- [x] 接入同一服务的单个到期回调与设置收集；归零后启动，结束/加时/清理取消，时钟/随机只由策略持有。
- [x] 短音使用克制的系统媒体提示并处理音频焦点、静音/勿扰；两短震复用现有兼容分支；分别拥有可取消资源，不修改归零震动。
- [x] 提亮与内描边只改绘制；状态效果使用稳定时间行占位；完整 C4 提前返回原组件，展开清除旧视觉 cue，收起不能重播。
- [x] 运行 Unit；相关服务/触摸必须由 Task4 真正设备回归验证，不能用本步编译替代。

### Task 3: 设置页面与主动选择

**Files:** 新建 `OvertimeReminderScreen.kt`；修改 `SettingsActivity.kt`、`MainActivity.kt`、`FocusOverlay.kt`。

**Consumes:** Task1 设置、Task2 服务的同一延后入口和摘要。
**Produces:** 设置首页「超时提醒」；两个交互方式、独立声震选择；首页 `DurationDialog` 与普通超时浮窗内 `DurationWheels`。

- [x] 设置页显示实际浮动范围、上限与分级说明，复用现有主题/滚轮/返回规则，不进预设编辑。
- [x] 普通轻量模式不增加强制展开；较强模式保留稳定结束/稍后提醒入口。C4 只在首页提供延后，不修改装置。
- [x] 明确轮次确认只改排期不加时，计划显示含已确认加时，实际读 Countdown 累计；回显/取消/旧段确认遵循 Task1/2 验证。
- [x] 运行 `./scripts/check.ps1 -Offline` 与 `-Variant Release -Offline`，要求构建、全部适用 JVM 与 Lint 通过。

### Task 4: 设备回归、文档与交付

**Files:** 新建 `scripts/device_overtime_reminders.py` 并接入原 runner；维护 `docs/testing/`、`docs/OVERTIME_REMINDERS.md`、`docs/CHECKS.md`、`AGENTS.md`、`CHANGELOG.md`、本轮 validation 报告。

**Consumes:** 前三任务的真实构建，原 check/results/JUnit 身份。
**Produces:** 同源模拟器结果和截图/连续帧证据、待验真机最小集、一份逻辑提交及授权范围内开发分支同步。

- [x] 为 runner 整理/接口变更补必要脚本检查；`python -m unittest discover -s scripts/tests -v`。
- [x] 专用 API36 模拟器备份/还原测试数据；实际滚轮设置、首次归零、周期触发、延后确认/取消、结束无残留；检查会话累计/计划/额度未被延后更改。
- [x] 覆盖 C4归零前无事件、尾音无叠加、两形态/拖动/旧cue不重播，声震独立与上限；采集稳定窗口帧和小时占位，不以截图宣称没有瞬态抖动。
- [x] 深浅主题/大字体核图，复用必要 edges/inline/overlay_stability 等已固化套件；只对相关范围记录当前通过。
- [x] 全分支独立审查，重要发现以失败测试复现后修复；最终检查绑定源码和 APK SHA，更新测试库，不改历史结果。
- [x] 更新产品原则和非专业成果说明，明确真机短音/振感/厂商后台仍待验；仅暂存本任务文件，原用户改动保留，独立提交并按已授权目标同步，不合并或发版。

## 执行方式与自查

沿用用户已授权「按设计实施」及本会话持续实施的选择，本会话原生完成，再独立审查，不重复请求同一实施授权。四任务共享同一 context/key，服务和 UI 不复制调度。所有行为均来自已确认设计；未引入新的产品取舍。

## 执行结果与范围（2026-11-04）

四任务已完成，见 `docs/validation/20261104-overtime-reminders.md`。勾选表示完成实现与对应检查；C4声震是模拟器请求/选择验证，不是实际试听触感。周期开启的STATUS坐标与真实分钟进位通过；小时组合仅既有周期关闭回归，不冒充所有新布局组合。禁用动画、声震瞬间收紧及物理手机仍列缺口。授权范围内保留开发分支，不合并/发版。
