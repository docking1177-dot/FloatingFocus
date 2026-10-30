# 桌面计时组件

## 本轮已确认的范围

- 2×1（两列一行）：当前计时、开始、暂停/继续；2×2增加结束及按现有顺序前两个预设。
- 空闲开始使用常用时长页保存的启动默认配置（模式/倒计时秒数），不会自动打开目标App。预设按钮读取最新ID，沿用已有预设启动及绑定App流程。
- 多个组件共用现有唯一会话，不能替换活跃、暂停、超时或C4会话。C4阶段不能暂停或结束，仍按既有口令解除。
- 本轮不做1×1或4×2统计。允许拖动缩放，放大后使用基础大布局，不推断为已开发统计图。

## 实现选择

采用系统AppWidget/RemoteViews，无新依赖、权限或服务。Android12+使用响应式布局，旧系统按尺寸选布局；实际网格占用与圆角由启动器决定。单行、紧凑预设、完整预设三档布局适配可用高度，缩到不足110dp时只保留计时操作。

使用系统Chronometer在桌面计秒，基准来自现有会话单调时钟快照；不创建第二套计时器、不每秒发送完整组件更新。开始/暂停/继续/归零/加时/结束和配置变化更新状态，空闲没有轮询。暂停复用SessionClock，历史仍沿用原FF会话时长口径，不改变原始记录。

默认启动经可见的受控Activity与原悬浮权限、策略、服务校验，并等待Lifecycle.RESUMED后才消费首次点击；缺权限返回后须明确继续并重新读取配置。预设沿用PresetShortcutActivity，参数只含ID。控制动作绑定本次会话与换段标识，采用明确的暂停/继续命令，重复或陈旧动作不切换/结束其他会话。

TimerTaskPolicy仅忽略WidgetStartActivity/PresetShortcutActivity这两类受控中转任务自身结束引发的onTaskRemoved；移除主FF任务或未知任务仍按原规则结算停止。避免中转页finish被误判为用户划掉主界面，不扩大后台运行范围。110dp高的2×2宿主保留紧凑预设布局，空间足够时采用完整布局；低于110dp才退为单行计时。

组件只是会话显示，不拥有后台保活。正常清理会更新组件；进程被无回调强杀后，启动器可能暂时保留旧显示，重新打开FF、点组件或系统重新绑定时校正，不能保证精确捕获强杀时刻。不会为修正显示自动重启计时或补记停机时间。

## 依据与验收边界

- [Android基础组件](https://developer.android.com/develop/ui/views/appwidgets)
- [响应式尺寸](https://developer.android.com/develop/ui/views/appwidgets/layouts)
- [更新开销与触发](https://developer.android.com/develop/ui/views/appwidgets/advanced)
- [RemoteViews Chronometer](https://developer.android.com/reference/android/widget/RemoteViews#setChronometer(int,long,java.lang.String,boolean))

构建、模型/控制单测、真实启动器添加与点击、缩放及多组件同步分别验收；接口返回不代替桌面验证。厂商启动器与真机未验证时单列，不新增厂商权限绕过。

设备入口为 `scripts/test-device.ps1 -Suite widgets -Serial emulator-5556 -AllowTestDataChanges`。从系统组件选择器添加，不用内部广播伪造点击；检查记录和目标App，再核对桌面显示。UIAutomator可能因多个运行中的Chronometer持续更新无法达到空闲，运行控制使用此前空闲状态新读取的组件ID位置，暂停后重新读取冻结数值；不把旧层级当成实时状态。结果、截图、源码指纹、APK校验和数据还原结果在 `.tools/device-checks/`。本轮不把模拟器结果宣称为Redmi/HyperOS实测。

2026-10-30专用Android16 Launcher3已完成真实添加、默认倒计时/正计时、两个预设及目标App、最新预设读取、已有会话不替换、多个组件暂停/继续/结束、权限返回后明确继续、超时、C4禁止暂停/结束、缓存进程冷启动、深浅主题/1.5倍字体和拖动放大检查。数据最终比对还原，测试组件从桌面删除。JVM覆盖单调基准、C4守卫、重复/旧会话/旧加时段动作及受控任务清理。Release编译与静态检查独立执行，不以Debug桌面验证代替Release真机验证。Android29–30、Redmi/HyperOS、TalkBack和无回调强杀后的桌面缓存行为尚未设备验证。
