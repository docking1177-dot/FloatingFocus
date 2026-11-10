# 五项交互优化成果（2026-11-10）

本轮只优化现有交互，保留计时、预设、额度和C4会话规则。版本仍为0.3.0 / code4；没有合并main或创建Release。

## 实际行为

| 项目 | 修改后的操作与边界 |
|---|---|
| 提醒演示与颜色 | 正计时提醒页及预设草稿复用真实浮窗渲染；顶部预览常驻，选用效果或调整参数自动演示约1.6秒。支持六种预设颜色、A→B、A/B离散交替和事件内固定的随机序列，结束恢复原色，前景随背景保持可读。快速修改以最后一次为准；动画2倍不截断，关闭动画用静态关键状态替代。|
| 声音与震动 | 纯视觉编辑不发声震。明确选用声震效果或试听时才按即时总许可请求，并显示请求/未许可/系统限制反馈。页面退后台释放资源；预览不启动服务、会话或写记录。新颜色属于已有规则快照，普通计时用当前规则，预设与续计沿用各自规则，声音总许可不进预设。|
| 上次模式 | 只记住首页最后手动点选的正/倒计时；活跃会话及页面恢复优先。重新打开不自动开始。组件/磁贴的默认模式保持独立，设置名称已说明作用范围；倒计时秒数及自定义时长仍沿用原规则。|
| 继续计时 | 首页独立“继续计时·N项”进入可靠候选列表，历史页共用。选择后显示来源、检查点、约计、承接累计，再明确继续；选择本身不开始。有活跃会话可浏览但不能覆盖；新段只记新增运行时长，不补停机或重复扣额度，也不打开原App。|
| 时间宽度 | 原先正计时提前预留至少三位小时确实造成多余宽度。现在按当前格式测量：MM:SS不预留小时，达到HH:MM:SS或小时位增长才扩宽，同格式等宽稳定；操作区已有足够宽度时外框可不增宽。普通超时的累计/超时分别按现有格式测量，不改变累计口径；两个读数在不同秒进位时，可各有一次合理扩宽。|
| 浮窗锁定 | 普通展开操作、首页和通知可锁定；锁定继续读数，取消已有触摸/拖动并禁止浮窗操作。首页和通知解锁，无通知权限仍可首页解锁。只在本会话有效，不进入设置、预设或恢复快照；新会话/异常续计默认解锁。C4解锁只恢复操作，不解除口令或改变归零规则。|

## 已确认的取舍

用户已确认：锁定请求触摸穿透，Android12+必要时临时降低原生窗口不透明度至公开系统阈值，保存设置不变、解锁恢复；首页记忆与组件/磁贴默认模式独立。生产浮窗透明度统一在原生窗口只应用一次，预览保留原绘制路径。系统和下层App仍可限制穿透，不能承诺所有厂商或所有App都接收触摸。[Android窗口触摸规则](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE)。

其余为本轮普通UI选择：独立续计弹层、预设色按钮、1.6秒演示、锁图标与明确操作文案。没有新增权限、应用依赖、服务或后台保活。

## 主要文件

- 提醒：`StopwatchReminderScreen.kt`、`StopwatchColorEffects.kt`、`StopwatchColorVisual.kt`、`ReminderEffectTimeline.kt`、`StopwatchPreview.kt`、`StopwatchReminders.kt`、`OvertimeVisualFeedback.kt`、`OvertimeReminderFeedback.kt`、`SettingsActivity.kt`。
- 首页与续计：`MainActivity.kt`、`QuickDurations.kt`、`TimerStartupDefaults.kt`、`TimerPreferencesScreens.kt`、`StopwatchRecoveryControls.kt`、`StopwatchRecoveryPresentation.kt`、`HistoryScreen.kt`。
- 测宽与锁定：`TimeSlot.kt`、`StopwatchTimeHeader.kt`、`OvertimeTimeHeader.kt`、`FocusOverlay.kt`、`OverlayWindow.kt`、`OverlayLockPolicy.kt`、`FloatingService.kt`、`ic_lock.xml`；前述Kotlin均在`app/src/main/java/app/floatingfocus/`。
- 新增六个JUnit类并扩展预设快照测试。复用原设备runner，新增`interactions`、`interaction_c4`和`interaction_theme`；调整续计入口及格式切换断言。通知持续计秒无法静止时，用SDK自带UIAutomator读取新层级，实际点击SystemUI控件；不发送解锁服务Intent替代交互。助手只存在测试脚本/缓存，不进入APK。[官方Configurator源码](https://android.googlesource.com/platform/frameworks/base/+/11d444f/cmds/uiautomator/library/core-src/com/android/uiautomator/core/Configurator.java)。

## 验证与身份

应用完整检查实际执行于11月6日：Debug315项、Release307项，失败/错误/跳过均0；两种构建Lint错误/警告均0。之后未修改应用输入，11月10日继续完善并执行设备回归。219项应用输入逐项核对一致，不能把脚本/文档变更写成重新构建APK。原始提交/dirty/指纹保留，不回填为后来的提交。

| 实际检查 | 原始证据 | 范围 |
|---|---|---|
| Debug完整检查 | `.tools/checks/check-full-debug-20261106-101957-759.json` | 构建、315单测、Lint |
| Release完整检查 | `.tools/checks/check-full-release-20261106-102123-434.json` | 构建、307单测、Lint；无实验Activity及本地录音 |
| 五项交互专项 | `.tools/device-checks/20261106-102232/results.json` | 真实模式重启、续计选择、跨UID图库菜单穿透、锁定/解锁、颜色帧恢复、1x/2x/关闭动画、快速覆盖 |
| C4锁定专项 | `.tools/device-checks/20261110-211039/results.json` | 输入保留、真实通知解锁、锁定跨零单次爆炸、归零后正常结束、续计不继承锁定 |
| 小时显示 | `.tools/device-checks/20261110-211812/results.json` | 1小时/100小时进位、三形状展开/旋转、1000小时原生组件、长小时大字体不溢出 |
| 展开稳定性 | `.tools/device-checks/20261110-213812/results.json` | 普通累计主显、完整C4、C4专属阶段前的连续帧/锚点/边缘/旋转，以及两个超时读数分别跨小时 |
| 主题预览 | `.tools/device-checks/20261110-215554/results.json` | 深色及2倍字体的真实颜色帧、预览隔离；截图另行查看 |
| 脚本回归 | `.tools/checks/interaction-script-tests-delivery-20261110.log` | 43项现有Python脚本测试；实际UIAutomator路径另由C4专项执行 |

跨小时使用隔离的可靠检查点夹具，让正式计时继续越过边界，并非实际等待1000小时；超时模拟只在已有Debug实验室。不修改用户记录。设备测试仅专用AOSP模拟器，每批备份/还原应用数据；报告不将日志播放请求当作听感或震感。

早期失败保留：默认alpha未在原生dump显示、通知计秒无法idle、冷启动通知排序变化、通知栏关闭后一次点击未送达C4、补充真实输入窗口证据后复测、远端日期参数引号、首个几何tick及两个读数分别进位等回归脚本假设均有独立失败记录；没有覆盖原失败，也不把失败批次拆成未经证实的逐项通过。旧Redmi/HyperOS整窗闪动仍待同APK真机复测。

## 产物与可视化

- 日常APK仍是`app/build/outputs/apk/debug/app-debug.apk`；本轮交付副本`dist/FloatingFocus-0.3.0-interaction-debug.apk`。
- Debug SHA256：`f85138900b50203ab83c21feb1622f57145daa6b99e67c2cb833a6a38b620386`；沿用原测试签名SHA256`2119d9676b8cbf5a1efee1f01b74d0846cd5573d4f18f6faf64a73146158d11a`。
- Release检查包SHA256：`569366982c12383d777d82bb6d1a7bc76f3f8ac9c283458eebca94339891142a`，未签名，不作为手机安装包。
- Debug包含3段授权未明的本地C4录音，仅本机使用，不上传GitHub；Release不含录音，无声不能误判为故障。
- 本地实际截图浏览：`dist/reports/interaction-evidence.html`。小时前/后指同APK的时间格式边界，不是混用新旧APK。录屏和原坐标数据留在上述设备证据目录，静态截图不能单独证明无抖动。

## 真机最小集与未完成项

1. `FF-PHONE-LOCK-001`：Redmi上先验证普通正计时锁定后能操作覆盖下的App、不能拖动浮窗、首页解锁恢复；再C4输入7后锁定、通知解锁、继续355608。解锁不应解除C4，归零后可正常结束。通知不可用时必须能首页解锁。AOSP跨UID实际穿透不能替代HyperOS策略。
2. `FF-PHONE-PREVIEW-001`：正计时提醒页选择颜色并观察完整恢复；声震总许可关闭应只给未请求反馈，愿意使用时再分别开启并明确试听，实际听感/震感与静音/勿扰限制需要真机确认。测试后恢复日常设置，不为纯视觉功能强制授权。

长时长自然使用时可补看横竖屏/通知/组件，不要求为了本轮等一小时或重跑所有桌面、权限、后台清理用例。用户反馈用例ID、入口、操作、发生时间与现象，必要时短录屏；获ADB授权后由AI读取日志，不要求手动整理。统一测试库保留原结果与身份，未测真机不写通过。

原构建/各设备批次均保持原始身份。部分同APK设备批次因回归脚本/文档导致全仓库指纹不同，在测试库仍保守列为历史；本报告以APK完整SHA及219项应用输入另行核对，不篡改原记录或自动扩大通过范围。
