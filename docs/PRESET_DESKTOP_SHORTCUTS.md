# 预设桌面快捷方式

正式能力，Debug/Release 共用。首页「我的预设 → 管理」中每个预设提供「添加到桌面」。本机绑定 App 图标、预设名称；创建不启动、不申请权限。缺少绑定时复用原绑定窗口，取消即停止。启动器须支持并由用户确认，不用旧广播、不添加动态长按菜单。

## 已确认行为

- 固定身份 `ff.preset.<稳定预设ID>`；Intent 只带 action 和 `preset_id`。名称、时长、目标、外观、模式及位置每次重新从 TimerPresetStore 取。删除/清数据后不猜测同名或其他方案。
- 任意活跃会话（暂停、超时、C4 同样 active）不替换、不结束。所有 App 计时启动共用互斥，覆盖服务第一次发布 active 前的窗口。
- 缺悬浮权限可创建；启动时使用 OverlayPermission，请求后回来点「继续启动」，再次读最新预设。修复绑定/提醒策略也通过现有编辑页，返回仍由用户继续。
- 名称跟随预设，改绑后跟随新 App。重复添加复用身份，优先更新并向系统请求；启动器可能已经固定、接受重新固定或自行复制，FF 不保证图标数量。

## 实现与边界

`PresetDesktopShortcuts` 用官方 ShortcutManager；耗时 API 在 IO 线程，按 Mutex 合并并发，两秒短节流不依赖拒绝回调，返回即结束本地忙状态。只在私有 receiver 收到成功 PendingIntent 后显示成功；成功回调重新读当前数据，删除则禁用。无回调不等于失败，取消不会永久显示正在添加。

`PresetShortcutActivity` 是显式受控启动入口，单独 task affinity、从最近任务隐藏；不清 FF 原栈或草稿，不持有计时核心。初次可见消费一次；重建、权限/编辑返回不重放，用户新点击和明确继续分别作为新尝试。拒绝无效 action、空/过长/控制字符 ID，外部任意时长、包名、模式和组件不参与执行。成功 finish；失败保留有用的重试/修复界面。

共享 `PresetLauncher` / `AppTimerLauncher` 与 FloatingService；服务入口以请求 ID 为手动/预设/快捷启动统一占位，启动结果、App 打开确认和失败取消必须匹配本次身份。失败沿用自身启动取消、外观恢复、打开 App 才开始记录的机制。创建、取消、失效或权限失败不产生有效记录。

预设存储遇到结构错误/重复 ID 返回读错，保存和删除不覆盖坏数据。同步失败不回滚已经保存的预设，也不禁用全部入口。名称或绑定变更、回前台、固定成功做有限同步；位置拖动不重复同步图标。限流后下次前台重试，无服务/定时轮询。保留当前禁备份策略，不新增权限、依赖或后台保活。

图标与 `AppIcon.kt` 共用本机 drawable 来源；AdaptiveIcon 保留无蒙版图层供启动器裁切，普通图标等比放入透明位图，失败占位。最终主题处理、裁切和 FF 徽标由启动器决定。读取历史 App/图标不申请查询全部应用权限。

## 检查

`./scripts/check.ps1 -Offline`；Release 加 `-Variant Release`。身份、读取失败、同步计划、创建支持/绑定、启动冲突及无回调节流有 JVM 测试。设备入口 `./scripts/test-device.ps1 -Suite preset_shortcuts -AllowTestDataChanges`；只接受专用模拟器，备份还原 FF 数据，记录实际启动器确认/桌面图标/App 前台/浮窗与会话，不把 API 返回视为实际添加。

模拟器启动器不是 Redmi/其他真机桌面；验收执行与未执行场景以本次 `.tools/device-checks/` 报告为准。固定入口数据由系统管理，不随 FF SharedPreferences 备份还原；全部设备 suite 在修改数据前拒绝已有 FF 固定入口的 AVD，使用空的专用实例，并清理本轮创建的入口。

参考：[固定快捷方式与成功回调](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts#pinned)、[ShortcutManager 支持、限流与更新](https://developer.android.com/reference/android/content/pm/ShortcutManager)。
