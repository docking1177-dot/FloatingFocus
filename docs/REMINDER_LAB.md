# 提醒实验室（Debug）

主界面底部「提醒实验室 · Debug」进入。使用现有 debug/release 源码集，没有新增 App、Flavor 或 Build Type，也不需要新增依赖或权限。

源码集隔离采用 Android 官方的 [构建源码集机制](https://developer.android.com/build/build-variants)；时长选项使用 Compose [FlowRow](https://developer.android.com/develop/ui/compose/layouts/flow) 保持单颗标签完整、空间不足时整颗换行。

## 使用

- 选择变色、变尺寸或交互确认，页面内预览立即更新。已打开的跨应用实验悬浮窗同步更新。
- 总时长为 1—10800 秒，剩余可输入 -10800 至总时长之间的整数秒；负数代表超时。应用输入、拖动进度或点击 0/50/80/100/120% 可以冻结在指定节点。
- 「播放模拟」以 1× 速度运行，使用与正式计时相同的 `Countdown` 和 `elapsedRealtime`，支持跨零进入超时；调整时间会暂停播放。
- 变色、变尺寸分别可选连续变化或节点突变，节点沿用已用 50%、80%、100%。变色连续模式保留原有灰→橙→红进度区间；变尺寸在前 50% 保持基础大小，之后连续增长至 1.5 倍。节点模式在边界跳变。
- 强度影响颜色混合、尺寸增幅、呼吸幅度和手动振动幅度；基础大小 0.75—1.5，不透明度 0.30—1.00。交互确认不通过强度关闭，在非安静阶段显示一次，确认后同阶段不重复，下阶段重新提示。
- 「触发提醒动画」播放两轮透明度动画，遵循系统动画开关；「触发一次振动」发送一次 180ms 振动。模拟跨零不会自动振动，真机用于判断实际触感。
- 「显示跨应用悬浮窗」复用正式悬浮宿主。首次需悬浮权限；从实验室页面主动启动。按 Home 切到其他 App 后仍可查看、拖动；通知可返回调参或关闭窗口。
- 实验窗口与正式窗口共用完全相同的 `FocusOverlay`，相同时间/参数下标题、尺寸、间距和按钮文案一致：展开为「结束计时 / 收起」，到期为「知道了」。窗口内部不显示实验标记；实验身份仅在调试页和通知说明。操作只关闭实验服务，模拟状态继续与正式会话隔离。
- 2026-10-22 已在 Android 16 模拟器对照收起、展开、到期三种窗口尺寸，并验证关闭实验窗口不停止另一轮正式计时；记录见 [多目标与布局验证](QUICK_LAUNCH.md#2026-10-22-多目标与布局比较)。
- 「重置全部测试参数」关闭实验悬浮窗、停止播放并恢复默认值。退出实验室关闭实验窗口；系统配置变化保留内存中的参数，进程重启恢复默认值。

## 最小结构

```text
正式 Countdown → SessionState → ReminderSnapshot ─┐
                                                ├→ ReminderPresenter → FocusOverlay → OverlayWindow
Debug LabSimulation → 同一 Countdown → 快照 ─────┘
```

- `main/ReminderPresentation.kt`：纯 Kotlin 快照、策略参数与外观计算，不访问服务、存储或 Android API。
- `main/FocusOverlay.kt`：只消费外观和动作回调。尺寸通过实际文字与内边距布局改变，悬浮宿主继续按测量尺寸处理拖动、边界。
- `main/ReminderVibration.kt`：正式服务与手动实验复用振动实现，正式计时保持到零一次振动。
- `debug/LabSimulation.kt`：不可变模拟值、独立时钟与阶段确认。`ReminderLabState` 只保存在进程内存中，不读写正式服务状态或持久化数据。
- `debug/ReminderLabActivity.kt` / `ReminderLabService.kt`：Compose 调参面板与实验窗口生命周期。独立通知 ID / Channel；关闭实验不调用正式服务。实验服务只负责托管同一个浮窗组件，不另写计时或拖动实现。
- `debug/DeveloperEntry.kt` 提供入口；`release/DeveloperEntry.kt` 为空。实验页面和服务只在 Debug Manifest 注册且不导出，Release 不编译实验类。

## 检查

`scripts/check.ps1 -Offline` 运行策略、边界、模拟时钟与已有计时/拖动单测并构建和 Lint。`src/testDebug` 中的模拟测试只参与 Debug 测试。

涉及浮窗修改还需模拟器回归：四档标签不折行；三方案切换、关键节点与超时；连续/突变；参数和重置；跨 App 拖动与按钮点击；正式计时与实验同时运行时互不结束；关闭及权限撤销清理；系统字体放大。Emulator 检查模式仅安装启动，不能代替这些交互验证。

公开发版前额外构建 Release，检查合并 Manifest 和 APK 中不存在实验 Activity/Service/模拟类。实验室不改变包名、签名或当前版本号；按项目原有流程交付与发布。

## 本次验证（2026-10-21）

- Debug：统一检查入口 `-Mode Emulator -Offline` 通过，23 项 JVM 测试、Lint 0 错误（8 条既有依赖更新警告、1 条既有提示），安装启动成功。
- Release：`assembleRelease`、`testReleaseUnitTest` 通过，17 项测试；检查合并 Manifest 和未混淆 APK 的 DEX，实验页面、服务、状态和模拟类均未打包。
- Android 16 / API 36 模拟器：正式 30 分钟计时与实验窗口同时运行；Home 后两窗可见，实验窗口独立拖动；尺寸方案在 0/50/80/100/120% 更新实际窗口尺寸，结束态文字和关闭按钮区域可拖动；确认操作缩回提示，关闭或重置实验后正式计时服务仍在运行。
- 实测 50% 节点变色为橙色、切回连续模式为灰色；大小、强度与不透明度调节、手动动画、一次振动请求及重置恢复默认值。模拟器振动服务记录 180ms 请求，不能代表真机触感。
- 输入总时长 10 秒、剩余 -3 秒显示超时 00:03；从剩余 1 秒播放会跨零进入超时，暂停后进度保持不变。播放时 UIAutomator 无法等待界面静止，跨零使用截图检查，暂停后再读取控件验证。
- 系统字体 200% 时四个标签均为同样的单行高度，「30 分」整颗换行。测试后字体恢复 100%。撤销悬浮权限会清理实验窗口/服务，未授权时显示操作提示；测试后恢复原有授权。
- 结构化结果与 APK 校验值：[results.json](validation/reminder-lab/results.json)。本机截图与补充日志位于 `.tools/lab-validation/`，不进入源码仓库。

尚未执行真机触感、厂商后台管理及多 Android 版本兼容性验证。本次不发版、不变更版本号，日常测试使用 `app/build/outputs/apk/debug/app-debug.apk`。
