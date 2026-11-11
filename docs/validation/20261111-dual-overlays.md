# 2026-11-11 双浮窗、横屏底边与独立透明度验收

## 面向使用者的变化

- 同时最多两路独立浮窗，同App/同预设可启动两次；第三路拒绝。首页逐路控制，组件/磁贴选择最早一路；结束后接管幸存一路。
- 普通窗的外观实时跟随当前外观设置，预设窗用自己的保存外观，不再临时修改全局；同时最多一路C4，原时间轴/口令/固定外观不改。
- 背景和数字/图标不透明度在设置中分别调，旧值兼容迁入两项。
- 全屏App隐藏状态栏时，横屏拖动不再多留一条状态栏高度；可见导航和缺口安全区仍保留。

## 根因与边界

修改前在FF界面未复现，换到全屏Gallery后复现：父框bottom1080，浮窗bottom1014，多留66px。应用用getInsetsIgnoringVisibility减掉隐藏状态栏，系统实际拟合却按可见栏。改getInsets并在系统栏变化时重测后，三形状bottom均为1080。原失败在20261111-194552，前后截图来自真实模拟器。
两窗复用一个服务内的TimerSession；各自窗口、时钟、提醒、记录及ID/段控制。原单路额度游标改成多游标，同包覆盖取并集；跨日先结算所有包再换日。记录各自保存，合计可能超过额度扣时。检查点排队按记录ID，避免第二路被饿死。
新窗临时错开基于实际高度，防越界后仍有区别，不写回预设位置；正计时续计保留原活跃任务门禁。

## 已执行

|范围|实际结果|证据|
|---|---|---|
|Debug构建/单测/Lint|330测试通过，Lint 0错误/警告/提示|.tools/checks/check-full-debug-20261111-194644-069.json|
|Release构建/单测/Lint|322测试通过，Lint 0错误/警告/提示；无本机录音|.tools/checks/check-full-release-20261111-194847-880.json|
|landscape_bottom|原结果passed，应用数据还原=True|.tools/device-checks/20261111-194827/results.json|
|dual_overlays|原结果passed，应用数据还原=True|.tools/device-checks/20261111-194947/results.json|
|opacity_channels|原结果passed，应用数据还原=True|.tools/device-checks/20261111-195331/results.json|
|dual_widgets|原结果passed，应用数据还原=True|.tools/device-checks/20261111-200615/results.json|
|overlay_rotation|原结果passed，应用数据还原=True|.tools/device-checks/20261111-200727/results.json|

脚本单测43项通过（.tools/checks/dual-script-tests-final-20261111.log；后续如复跑保留独立日志）。UIAutomator在两路秒表下无法空闲的批次195435保留为失败；widgets复用既有live-ui工具，不读取旧树。完整旧widgets套件195909末尾未取得启动器缩放手柄，保持失败；限定dual_widgets只验证本轮两窗控制，不冒充完整套件通过。193231桌面确认按钮大小写误匹配的失败也保留。中间APK结果不冒充最终构建。

## 产物身份

Debug SHA256：`ecd25461af812e696f5c477e10e35b798e5512a0ff4318f6dfd0a15512966b67`；签名SHA256：`2119d9676b8cbf5a1efee1f01b74d0846cd5573d4f18f6faf64a73146158d11a`。
安装路径：`app/build/outputs/apk/debug/app-debug.apk`；本机交付副本：`dist/FloatingFocus-0.3.0-dual-overlays-debug.apk`。含3段未确认公开授权的本机C4音频，仅本机Debug，不上传GitHub。Release未签名，不作为可安装交付包。
225项应用输入见同目录JSON；最终检查时本地基线befc272且dirty=true，保留原身份，不写成之后的提交。后续只改测试脚本/资料时无需再构建；同APK不同全仓库指纹按原规则保守分类，报告给出应用输入一致性。

## 可视化与真机待验

[真实截图对照](../../dist/reports/dual-overlays-evidence.html)。两窗图为拖动后呈现，初始位置错开另由坐标和持久化断言验证。截图不代替动态抖动或触摸穿透验证。
本次未连接Redmi真机。本轮最小集为FF-PHONE-DUAL-001、FF-PHONE-OPACITY-001；FF-PHONE-LANDSCAPE-001按用户决定暂缓，标记待处理，不要求本轮执行。通用修正已完成且保留，不能据此关闭Redmi问题。原最小集调整后，见测试库导出的REAL_DEVICE。厂商桌面确认、两锁定窗重叠后的实际下层触摸、复杂底图可读性、后台清理，以及声震听感不由模拟器通过代替。
发现异常请报测试ID、操作顺序、当时有几窗/形状/模式、约时间；按需附录屏。授权ADB后由AI提取日志，无需人工筛日志。

## 修改入口

FloatingService、DualOverlayPolicy、AppTimerLauncher/PresetLauncher、MainActivity、TimerWidgets/WidgetStartActivity、TimerRecordStore、DailyAppQuota/QuotaLedgerCodec；透明度为ReminderSettingsCodec/ReminderPresentation、OverlayOpacity、FocusOverlay、SettingsActivity；边界和临时位置为OverlayWindow/OverlayPosition。无新增Manifest权限、依赖或服务。

API依据：[WindowInsets](https://developer.android.com/reference/android/view/WindowInsets)、[LayoutParams拟合](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)。
