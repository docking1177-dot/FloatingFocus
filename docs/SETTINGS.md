# 正式设置与到期提醒

首页右上角齿轮进入「设置」。修改自动保存，并即时更新正在运行的正式浮窗；不重启或重置倒计时。

页面上方固定显示实时预览，下方设置独立滚动。预览复用 `ReminderPresenter` 与 `FocusOverlay`，无需悬浮权限；可拖动「预览进度」查看 15 分钟示例的渐变、节点变化和到期外观。初始进度为 0，便于查看基础颜色；后段颜色由所选变色策略决定。预览进度仅用页面的 rememberSaveable 保存，不写入配置或正式会话，不调用服务和震动。预览不支持拖动、展开或结束真实计时。容器随可用高度适配；先测量完整内容，超出预览区域时整体缩小，避免横屏下挤掉时间文字，因此预览不承诺与跨应用浮窗像素等大。

## 配置与效果

- 沿用 `ReminderSettings` 与 `ReminderPresenter`。`ReminderSettingsStore` 使用项目已有的 SharedPreferences 方式，文件名 `reminder_settings`；同进程只有一个 StateFlow 数据源，UI 与服务共同订阅。
- 变色/变尺寸方式默认连续变化，可选节点突变；强度默认 1，范围 0—1；基础大小默认 1，范围 0.75—1.5；不透明度默认 0.94，范围 0.30—1.00。默认效果仍为变色。
- 正式设置提供变色、变尺寸两种现有策略。方式参数仅作用于对应策略；交互确认保留为实验策略。基础颜色有石墨、蓝、绿、紫、棕五种预设，默认石墨。变色策略从所选颜色向橙、红变化，强度决定混合程度；变尺寸策略一直使用所选颜色。没有自定义任意 RGB 输入。
- 三种形状以并排图形缩略图选择：横向胶囊、纵向胶囊、圆形。旧 `ROUNDED_RECTANGLE` 配置键保留并映射为横向胶囊；圆形键不变，新增 `VERTICAL_CAPSULE`。预览与真正跨应用浮窗共用渲染和几何计算。
- `OverlayGeometry` 按稳定的基础内容测量，再一次性应用提醒大小；不再让 TextView 自动缩字结果反过来决定 WRAP_CONTENT 浮窗尺寸。倒计时数字使用等宽字体；形状、标题及操作按钮随同一比例缩放，屏幕放不下时整体适配。展开、到期时容器可增大以容纳操作。
- 不透明度、呼吸和手动动画统一作用于整个浮窗，包括背景、文字和图标。已移除 BackgroundColorSpan 和按钮文字背景，不存在独立的不透明文字矩形。保留轻微文字阴影；低不透明度会同时减弱文字，复杂背景下用户可提高不透明度。预设实色对白字对比度测试只验证实色，不能保证透明叠加后仍达到同一对比度。
- Android 13+ 使用官方 `AttachedSurfaceControl.setTouchableRegion` 让触摸区域按形状匹配，透明角落可穿透。Android 10—12 的公开 API 不提供此方法：形状外拒绝点击/拖动，但底层系统窗口仍按矩形接收输入，不能保证角落透传。未使用非 SDK 反射或新增权限。
- 原实验参数只存在内存中，没有旧持久化配置需要迁移。旧快捷启动等 SharedPreferences 不变；缺省、损坏或未知配置键按字段恢复默认值/合法范围。实验室不保存一份平行配置，读取正式外观参数、保留自己的实验策略和时间；重置模拟不重置正式设置。

## 震动排查与修正

源码检查确认：原服务归零会调用 `ReminderVibration.once`，Manifest 已声明 VIBRATE，API 29/31 的振动器获取分支存在。没有“后台就跳过”的条件。原正式路径没有传入提醒强度，且只提交 180ms 的 NOTIFICATION 震动，没有调用结果诊断。

2026-10-23 的旧 APK 对照：Android 16 模拟器在 Home 后正常归零，系统记录 `usage=NOTIFICATION`、`finished`、180ms。因此手机反馈的无震动不能直接归因为归零事件未触发；设备设置、通知类别策略和短脉冲触感仍可能有关，需要真机核对。

修正仍集中在原 `ReminderVibration`：计时归零使用 ALARM usage，Android 13+ 使用 VibrationAttributes，旧系统使用 AudioAttributes；不设置绕过系统策略的 flags。静音模式显式跳过，勿扰与系统振动强度继续由系统裁决。强度 0 不调用振动器，正强度使用 200—500ms 单次脉冲并调节振幅；无幅度控制硬件使用 DEFAULT_AMPLITUDE。默认强度 1 对应 500ms。

`CompletionReminder` 只在活动、未暂停、实际剩余时间等于零时消费一次结束事件。消费后即使强度为零、系统拒绝或用户继续停留在零也不重试；取消不会调用结束提醒，新会话重置门闩。以剩余毫秒判断，避免浮点进度舍入造成提前触发。

Logcat 标签 `FloatingFocus` 区分完成事件、已提交、强度零、静音、无振动器及 API 异常。`submitted` 仅代表请求已提交，不保证系统批准或真机产生触感；通知仍为静默的前台服务通知，不重复发出震动。

官方依据：[Vibrator](https://developer.android.com/reference/android/os/Vibrator)、[VibrationAttributes](https://developer.android.com/reference/android/os/VibrationAttributes)、[触摸区域 API](https://developer.android.com/reference/android/view/AttachedSurfaceControl#setTouchableRegion(android.graphics.Region))。

## 验收重点

检查设置实时生效和重启保留，三种形状在展开/归零/大尺寸下的文本、按钮与边界；检查实验室没有五项重复控制且重置不更改正式配置。前台和其他 App 上方各完成一次计时，检查系统振动记录；取消、零强度、持续零点不得产生额外请求。真机还需检查触感、静音/勿扰和厂商系统行为。

真机反馈（2026-10-23）：用户报告 REDMI K80 / Android 16 的系统闹钟振动正常，旧版 Floating Focus 归零和实验室手动触发均无触感，相关通知渠道开关已开启。当前没有该手机的系统振动拒绝日志，不能把模拟器执行成功视为 K80 修复完成。新版实验室手动按钮会显示提交或跳过原因，需覆盖安装后对照验证。

后续真机反馈（2026-10-23）：用户覆盖安装新版后确认「振动成功」。这确认该设备已恢复振动；用户未细分前后台、静音/勿扰等场景，因此不将这条反馈扩展为完整设备验收，也不据此断言旧版失效的唯一系统原因。

## 本轮验证（2026-10-23）

Debug 50 项、Release 44 项 JVM 测试通过；Debug Lint 0 错误、1 项既有 Gradle 版本警告，Release Lint 无问题。Release DEX 包含 SettingsActivity，不含实验室 Activity、Service、State 或 LabSimulation。

Android 16 模拟器实际检查了配置保存/进程重启、运行中颜色与形状切换、圆形透明角落与拖动、最大尺寸的归零标题和关闭按钮、实验室重置隔离、前后台归零与手动震动系统记录、零强度不提交及零点调高强度不补震。详见 [结构化验收记录](validation/settings/results.json)。暂停/非活动/重置触发约束另由 JVM 测试覆盖，未将其列为真机交互结果。

首页精简及设置内预览的后续验证见 [预览验收记录](validation/home-preview/results.json)：包括滚动时可见、实时外观变化、最大参数与 100% 进度、无服务启动、启停计时、配置保留及横屏/放大字体截图。预览相关真机交互尚未验证。

## 尺寸与预设的后续修正

连续变尺寸沿用原规则：前 50% 保持基础大小，后半程逐渐放大；节点模式在 50%、80%、100% 切换，最终为基础大小的 1.5 倍。变色方案不按时间放大。尺寸计算的修正不改变这些时间节点、强度和倍率。

设置页也用于编辑预设：通过预设 ID 打开时读写独立草稿，保存才更新该预设；取消不修改正式设置或既有方案。详见 [PRESETS.md](PRESETS.md)。

2026-10-23—24 后续验收：Debug 56 项、Release 50 项 JVM 测试通过，最终 Debug/Release 构建及 Lint 通过（Debug 保留 1 项既有 Gradle 更新警告）。Android 16 后台模拟器实际完成预设管理与启动流程、三种形状跨应用拖动/展开/收起/关闭、连续变尺寸和大字体截图检查。详见 [本轮验收记录](validation/presets/results.json)。截图和原始操作文件保存在本机 `.tools/presets-validation/`；这不代表新改动已通过真机验收。
