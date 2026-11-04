# 随亮屏计时 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 用户主动启用后，默认屏幕亮起累计正计时、息屏暂停，同会话且历史不包含息屏时间。

**Architecture:** 复用Countdown/SessionClock/FloatingService，增加screenDriven来源和纯ScreenOnTracker。新记录附可校验活动区间，旧记录仍按连续跨度统计；系统显示监听只存在于活跃新模式。

**Tech Stack:** 现有Kotlin/Compose、AtomicFile/JSONObject、公开DisplayManager与屏幕广播；无新依赖/权限/服务。

**Spec:** `docs/superpowers/specs/2026-11-04-screen-on-timer-design.md`，用户已确认整组设计。

## Global Constraints

- 默认关闭，仅正计时，isInteractive且默认屏幕STATE_ON计入，锁屏不要求解锁，AOD不计。
- 一次开启同一会话，结束/关闭/进程死亡后不自动开启。保留当前会话不替换。
- 不绑定App、不扣额度、不读取全局C4作运行配置，不影响普通暂停、旧历史和预设。
- 屏幕是新模式唯一暂停所有者，全部控制入口一致；不轮询、无唤醒锁或重启监听。
- 版本0.3.0/code4不变；保留用户修改，开发分支收尾，不合并或发版。

## Review Focus

- 亮/灭广播与显示事件乱序或重复：读实际状态、幂等切换，不重新开会话。
- 初始化注册/取样、结束与回调交错：同主线程、结束立即关闭订阅，不重复结算。
- 息屏只停UI但记录仍涨：实际亮屏区间、冻结时钟、持久化字段共同断言。
- 旧组件暂停/继续请求及C4全局开启：服务拒绝新模式手动暂停，C4不创建且普通控制不变。
- 新记录损坏/跨午夜/强杀：严格校验活动区间，保留最后检查点，统计不补空档/停机。

### Task 1: 活动区间与记录口径

**Files:** create `ScreenOnTimer.kt`, test `ScreenOnTimerTest.kt`；modify `TimerRecord.kt` / `TimerRecordStore.kt` / `HistoryStatistics.kt` / `HistoryScreen.kt`；test `ScreenOnHistoryTest.kt`。

**Interfaces:** `ActiveInterval(startOffsetMillis:Long,endOffsetMillis:Long)`；`ScreenTimeSnapshot(spanMillis:Long,intervals:List<ActiveInterval>)` with `activeMillis`；`ScreenOnTracker(startedAt:Long)` with `update(now:Long,lit:Boolean):Boolean`, `snapshot(now:Long):ScreenTimeSnapshot`, `finish(now:Long):ScreenTimeSnapshot`；`ScreenOnPolicy.counts(interactive:Boolean,displayOn:Boolean):Boolean`。
`TimerRecord.screenTime:ScreenTimeSnapshot?`为可选追加字段，`endedWallMillis`用新跨度；CSV追加口径/跨度/区间三列，旧前十列保持。

- [x] 先写多次亮灭、重复、初始息屏、结束不恢复、非法区间、跨小时/日、CSV/旧记录的行为测试，Unit观察失败。
- [x] 实现纯区间累计、记录兼容与只读统计；新记录累计只读interval sum，不能降级成连续记录。
- [x] 存储编码/解码在原文件链路追加新字段；无新依赖，JSON往返由真实设备原子文件验证。
- [x] Unit至全部通过，记录RED/GREEN证据。

### Task 2: 正式会话服务与控制一致性

**Files:** modify `FloatingService.kt` / `SessionControls.kt` / `TimerWidgetModel.kt` / `TimerTileModel.kt`；create `ScreenStateObserver.kt`；test `ScreenOnControlsTest.kt`。

**Interfaces:** `SessionState.screenDriven:Boolean=false`；`FloatingService.startScreenTimer(context):Boolean`经原startAdmission调用同ACTION_START，私有extra；`ScreenStateObserver(context,handler,onChanged:(Boolean)->Unit)` registers+samples, `close()`；内部`applyScreenState(lit)`只改同一SessionClock和Task1 tracker。

- [x] 先测试新模式禁手动pause/resume、仍能end、旧请求/其他会话隔离、tile/widget只查看；观察Unit失败。
- [x] 用户开启固定STOPWATCH/null目标/C4=false，原准入和权限不变；时钟初始冻结，注册读屏幕后恢复或等待。
- [x] 变化先按单调时间切分区间，更新原时钟及SessionState/通知；重复值不刷新/重复写，息屏不安排tick。
- [x] 清理订阅先于结算，保留tracker直到最后检查点完成，所有结束路径幂等；通知标明亮屏/息屏并保留结束。
- [x] Unit全部通过。

### Task 3: 设置入口与正式显示

**Files:** create `ScreenOnTimerScreen.kt`；modify `SettingsActivity.kt` / `MainActivity.kt` / 原服务浮窗参数；Manifest只更新specialUse用途描述。

- [x] 设置页读取正式session/pending，主开关表示当前会话状态，开启失败/已有会话/权限提示可见；授权返回再次点开启，不自动延迟启动。
- [x] 首页/浮窗状态可辨别亮屏模式，沿用外观和结束，息屏不能手动恢复；回前台和页面重建不创建会话。
- [x] 两主题/大字体沿用FocusTheme/Spacing；不增加预设字段或首页模式选项。
- [x] Debug Full及Release Full：编译、全部适用单测、Lint；保存源码/APK绑定摘要。

### Task 4: 设备、文档与交付

**Files:** create `scripts/device_screen_on_timer.py` and register suite `screen_on`；update `docs/testing/` / `AGENTS.md` / `docs/TIME_RECORDS.md` / `CHANGELOG.md`；create `docs/SCREEN_ON_TIMER.md` and task validation report。

- [x] 专用模拟器真实UI开启、重复亮灭/锁屏、后台、同会话、暂停区间落盘；正常结束后亮屏不重启。
- [x] 冲突、权限撤销、强停重开、首页/组件/磁贴能力校验；新JSON实际往返，CSV导出沿现有文件选择链路核对。
- [x] 原普通记录/正计时/C4相关回归按影响选择；脚本测试验证相应整理器，避免无理由重复全量。
- [x] 原始失败不覆盖，统一测试库追加细粒度实测；手机AOD、后台策略等独立未测。
- [x] 新鲜独立整分支审查，重要问题以失败测试复现修复，完成最终证据/哈希核验和用户改动保留检查。
- [x] 一次任务逻辑提交及已授权开发分支同步，排除私有视频历史/音频，不合并/发布。

## 执行选择

沿用本会话原生执行并独立审查，用户已明确“按设计实施”，不再重复请求执行方式/计划许可。原工作区复用及feature branch符合桌面偏好。每项测试/结果写入本计划独立ledger，最终一次提交符合仓库逻辑提交要求。

## 本轮结果

实现及相关检查完成，260/252 JVM、Lint0，实际screen_on与原history回归通过；脚本检查与交付身份见validation报告。旧STOP跨会话补最小修复。真机AOD/厂商策略未执行，旧PendingIntent实际投递未执行，不把模型证明泛化。仅开发分支收尾，未合并/发版。
