# 指定 App 自动计时 — Deferred

2026-10-22：因开发优先级调整暂缓，保留代码供后续恢复。本轮停止继续研究、优化和设备兼容扩展。

## 当前关闭方式与恢复步骤

- `FeatureFlags.APP_USAGE_AUTO_TIMER = false` 统一关闭 Debug/Release 入口、配置 Activity、服务监测启动与轮询启动。旧内部启动请求不能开启监测，也不会打断已有手动计时。
- 当前 Manifest 不声明 `PACKAGE_USAGE_STATS`，不引导使用情况授权；没有无障碍服务或替代监听路径。覆盖安装新 APK 会终止旧进程；本功能原本不保存启用状态、不自动重启。已安装的旧 APK 需更新，才会获得此关闭行为。
- 保留 `AutoTimerEngine`、`PausableCountdown`、增量事件游标、`UsageAppMonitor`、配置/日志和 14 项 JVM 测试。可启动 App 列表提取到 `LaunchableApps.kt`，供新的“一键打开 App + 手动计时”复用。
- 完成度：主体功能、模型测试、Android 16 模拟器常见路径已完成（下方为历史记录）。仍存在 UsageEvents 延迟/缺失、分屏焦点不确定、厂商后台回收及耗电未验收等限制。
- 恢复开发需用户重新确认优先级；恢复权限声明与用途说明，打开统一开关，再运行单测/Lint、授权/撤权、锁屏、快速切换、进程终止回归，补 Android 12—16 与目标真机验收。不要仅打开开关便宣称可正式发布。

当前产品使用主界面「打开 App，开始专注」：用户点击后启动普通计时并打开所选 App；离开目标后继续计时，完全不读取使用事件。

## 历史使用设计（当前不可访问）

1. 在页面打开系统「使用情况访问」和「悬浮窗」设置并授权。通知建议开启，便于看到运行状态和停止按钮。
2. 从当前用户的可启动 App 中选择目标；支持按名称、包名搜索。排除本应用和桌面，不申请 `QUERY_ALL_PACKAGES`。
3. 设置 1—180 分钟，以及 500/1000/2000/5000ms 查询间隔（默认 1000ms），点击「开启 App 监测」。设置在本机保存；启用状态不保存。
4. 进入目标 App 开始/恢复；离开、回到 Home 或锁屏暂停并移除浮窗。多个目标共用一轮计时额度，目标之间切换不会重新开始。
5. 通知、主界面或配置页均可停止监测；浮窗的结束/到期确认也停止整轮监测。与手动倒计时互斥，请先停止当前模式再切换。

修改时长、选择或间隔需停止后重新开启；重新开启是一轮新计时。正常离开配置页不停止已开启的监测。

## 实现与系统边界

- 沿用 `FloatingService` 的 `specialUse` 前台服务和停止通知，用户在可见 Activity 主动开启时立即进入前台。目标 App 事件只驱动同一服务内的模型与窗口，不从后台再次启动前台服务；不依赖 Android 15 的悬浮权限后台启动豁免。
- `PausableCountdown` 只保存已消耗时间，每段运行复用原有 `Countdown` 和 `elapsedRealtime`。`AutoTimerEngine` 接收事件，`UsageAppMonitor` 在 IO 线程读取事件，在主线程串行应用状态。复用 `ReminderPresenter`、`FocusOverlay` 和 `OverlayWindow`，暂停时释放窗口，恢复时重建同一宿主类型。
- `UsageStatsManager.queryEvents(begin, end)` 增量读取，默认每次查询约一个间隔加 2 秒重叠；重叠去重。只消费 Activity 恢复/暂停/停止及锁屏事件，不读取整日历史或定期扫描累计使用统计。系统时间跳变或调度中断超过 30 秒会丢弃旧前台推断并暂停，不把失去观测的时间算作使用。
- 同一批快速切换按事件时间依次处理，仅最终状态决定窗口显示，避免瞬间闪窗。稍迟事件可在 2 秒重叠中补收；比已经应用的事件更旧的乱序事件丢弃。系统/OEM 更长延迟或丢失事件无法精确修复，显示是近似检测，查询间隔不是延迟保证。
- 锁屏/息屏广播立即暂停并清空前台推断，锁定时不查询历史。解锁仅读取最近 2 秒新事件；没有可靠恢复事件时保持暂停，需退出再进入目标或重新开启监测。
- Android 10+ 分屏可有多个 RESUMED Activity。UsageEvents 不提供其他 App 的全局顶层焦点回调，因此多个恢复的包并存时保守暂停，即使都已选中。只有事件明确剩下单个目标才继续；不宣称能准确识别所有厂商的分屏/PiP 焦点变化。
- 两项特殊权限撤销时停止监测并清理窗口；AppOps 监听加每轮查询前检查。系统无法提供事件时暂停，查询异常则停止并显示说明。
- `START_NOT_STICKY`；无开机接收器、自动重启、精确闹钟、WakeLock 或电池白名单引导。系统杀进程/强停/重启后不会假装继续计时，本轮进度不恢复，用户必须重新开启。厂商仍可终止前台服务。
- `specialUse` 声明符合运行时类型要求，不等于已经通过 Google Play 对该用途的审核。

官方依据：[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[后台启动前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[specialUse 类型](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)、[多窗口与 multi-resume](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-resume)。

## 调试与测试

配置页「查看调试日志」可看最近事件，内存上限 150 条，页面显示最近 30 条，可清空。不写入持久文件、不上传使用记录；进程结束即清空。Debug 额外输出 Logcat tag `AutoTrigger`：

- `MONITOR_START/STOP`：监测生命周期、间隔和所选数量。
- `TARGET_RESUME package=... delayMs=...`：目标 App 进入及事件时间到实际检测的延迟。
- `STATE RUNNING/PAUSED remainingMs=...`、`SCREEN`、`QUERY_RESET/UNAVAILABLE`、`MONITOR_ERROR`、`TIMER_FINISHED`：计时与边界变化。

JVM 测试覆盖暂停/恢复、Home、快速切换、分屏歧义、同包 Activity 切换、锁屏、到期、乱序、进程重建、增量去重与时钟跳变。实际设备还需回归权限引导、切换延迟、息屏、撤权、停止与后台管理；本机使用统一检查入口，具体结果随验证记录更新。

### 2026-10-21 本机验证

- Debug 构建与 37 项 JVM 测试通过；Release 构建与 31 项 JVM 测试通过（不含 6 项 Debug 实验室测试）。新增自动计时模型测试 14 项。
- Debug Lint 无错误，1 项现有 AGP 版本提示；Release Lint 无问题。合并后的两份 Manifest 均确认没有无障碍服务/权限及 `QUERY_ALL_PACKAGES`；Release 仅含现有 `FloatingService`，没有实验室组件。
- Android 16 / API 36 AOSP 模拟器，通过系统页面实际授予使用情况访问；选择 Gallery、15 分钟、500ms 间隔并从页面主动启用。
- 实测目标进入显示浮窗、Home 隐藏并暂停、返回恢复同一额度、连续快速切换、锁屏暂停和解锁恢复。一次 Home 离开/返回的日志剩余时间均为 `878094ms`，暂停期间未扣减；本次观察到的目标进入检测延迟为 80—693ms，仅为此环境样本，并非性能承诺。
- 分别用 AppOps 撤销使用情况访问和悬浮权限，确认停止日志、服务列表为空、无浮窗。测试后恢复模拟器原有权限状态。
- 监测运行且目标位于前台时，对本应用进程发送 SIGKILL：服务与窗口消失；冷启动后页面显示「未开启」，未自动重启监测。该测试不等同于所有厂商的后台回收策略。
- 页面停止监测、切回手动计时、手动计时回到 Home 后继续显示、结束后清理窗口均通过。未见本应用崩溃日志。
- 最后一次构建后重新覆盖安装；配置 1 分钟且尚未进入目标时，主界面正确显示 `01:00`，主界面的停止监测也通过。Debug APK 签名与此前交付的 `FloatingFocus-0.1.1-debug.apk` 一致，包名不变。
- 本地原始事件和截图在 `.tools/auto-trigger-validation/`，构建日志在 `.tools/checks/`（均不提交）。

尚未实测：Android 12—15 设备、厂商真机后台策略、真实分屏/PiP 的厂商差异、长时间耗电；分屏歧义、到期以及系统时间跳变目前有 JVM 模型测试，不能当作相应设备场景已经验收。
