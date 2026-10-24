# 时长记录与正计时

首页可选择倒计时或正计时。倒计时归零时仍只触发一次结束提醒，随后显示「超时 +mm:ss」并继续累计，直到手动结束。正计时从 00:00 开始，不设截止时间，不产生归零震动或按进度变色/放大；颜色、形状、基础大小、不透明度照常生效。预设保存计时模式，旧预设默认倒计时。

## 记录口径

- 从 FF 成功打开目标 App 后开始记录，保存 App 名、包名、开始时间、计划时长、累计时长、超时时长和结束方式。普通手动计时也会记录，标为「手动计时」。启动失败不生成目标 App 记录。
- 记录的是 FF 会话时长，不是目标 App 的真实前台使用时间。切换 App、返回 Home 或锁屏不会主动暂停；此前 Deferred 的 UsageStats 监测仍关闭，不使用无障碍、不申请使用情况访问或存储权限。
- 例如计划 5 分钟，在 3 分 20 秒手动结束或 FF 收到划掉任务回调时结算，记录约 3 分 20 秒；运行到 6 分钟则累计 6 分钟、超时 1 分钟。仅退出目标 App 不会结算。

## 持久化与异常退出

`Countdown` 以 elapsedRealtime 作为计时基准，`ReminderSnapshot` 同时传递已用及超时时长，正式服务和实验室仍复用同一渲染。正计时没有平行服务。

`TimerRecordStore` 在内部文件目录 `files/timer_records/` 为每次会话保存一个 AtomicFile JSON。独立串行 IO 执行器约每秒写入当前小记录，不重写全量历史；积压的中间检查点合并。正常结束保留服务直到最后记录写入完成，重复结束不会生成重复记录。读写失败在记录页显示错误。

收到 `onTaskRemoved` 时以当前单调时钟时长结算，并停止服务/浮窗。`onDestroy` 只作为正常服务销毁的补充处理。强制停止、崩溃、系统杀进程或断电可能没有任何回调：进程下次启动时把未完成记录标为「异常中断，约计」，截止最后成功持久化的检查点；不追加停机到下次启动的时间，不自动恢复计时。每秒检查点不是强杀误差的硬性上限：锁屏休眠、系统调度、存储故障或 IO 延迟可能扩大未保存间隔。不新增唤醒锁或保活方案。

官方依据：[前台服务被用户停止时没有回调](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)、[Service.onTaskRemoved](https://developer.android.com/reference/android/app/Service#onTaskRemoved(android.content.Intent))、[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)。

## 查看与导出

首页「时长记录」查看本地会话，运行中的记录也会更新。选择「导出 CSV」后通过 Android 系统文件选择器指定位置。导出含 UTF-8 BOM，时间使用 UTC ISO 8601，时长以整秒导出，保留模式与结束方式；运行中的记录导出到当时检查点，状态仍为计时中。App 名中的逗号、引号、换行正确转义，并防止表格公式注入。取消文件选择不导出，不申请额外权限。

记录和预设分开存储；编辑、删除预设不会改变既有记录。清除应用数据或卸载会删除本地记录，请先导出需要保留的数据。

## 验收（2026-10-24）

Debug 62 项、Release 56 项 JVM 测试通过；两种构建及 Lint 通过（Debug 仅 1 项既有 Gradle 更新警告）。Android 16 后台模拟器完成正计时、App 启动、真实倒计时归零后超时、划掉任务结算、强制停止后的检查点恢复、正计时预设重启保留和系统文件选择器 CSV 导出。CSV 已重新读取并解析；强杀恢复没有累计停机时间或重复记录。详见 [结构化记录](validation/time-records/results.json)。

模拟器观察不能代表 REDMI K80 真机的任务清理策略或真实触感。5 分钟计划在 3 分 20 秒中断的精确计算由 JVM 测试覆盖，强杀设备回归使用较短会话验证持久化与恢复。

## 到时加时

倒计时到时后，浮窗和首页提供「加时」，用户可输入 1—180 分钟。加时从确认时开始新一段倒计时，颜色/尺寸进度从这一段起点重新计算。累计会话、目标 App、记录 ID 和开始时间保留；计划时长增加输入值，此前已发生的超时单独累计，不因加时被抹掉。每段到时各提醒一次；取消不改变计时，已过期的加时页面和重复提交不影响另一段或另一场计时。正计时没有加时入口，原预设保持不变。

`AddTimeActivity` 负责输入；显示时暂时隐藏现有浮窗，退出后通过原 `OverlayWindow` 恢复，避免浮窗遮挡输入框。不改变悬浮窗键盘/触摸 flags，不新增权限。`Countdown.extendAt` 在同一模型中维护每段截止和累计值，`TimerRecord.savedOvertimeMillis` 持久化实际超时合计；旧记录缺少该字段时沿用原计算。加时后的记录同样支持异常中断恢复和 CSV 导出。后台打开输入页只发生于用户点击浮窗，使用现有悬浮窗授权允许的 Activity 启动条件（[Android 官方说明](https://developer.android.com/guide/components/activities/secure-bal)）。

## 预设绑定与加时验收（2026-10-24）

Debug 67 项、Release 61 项 JVM 测试全部通过，两种构建及 Lint 通过（Debug 保留 1 项既有 Gradle 更新警告）。Android 16 后台模拟器实际验证旧预设绑定、重启后一键启动、新建与编辑目标 App，以及跨应用浮窗加时的取消、无效输入、确认、再次归零、单条记录累计与重启保留。两轮真实归零日志各触发一次提醒。Debug 签名与此前交付包一致。详见[结构化验收记录](validation/preset-extension/results.json)。本轮没有 REDMI K80 真机验证。
