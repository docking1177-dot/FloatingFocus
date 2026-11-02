# 系统控制与超时体验实施计划

> **For agentic workers:** 使用superpowers:executing-plans在本会话执行；完成后做一次独立全分支审查。

**Goal:** 原生磁贴、可滑动首页、普通累计超时布局比较与持续C4超时共用现有会话。

**Architecture:** Countdown提供时长，服务为唯一控制所有者；纯模型给出状态/操作能力，UI和系统入口消费同一规则。Debug候选使用源码集隔离。

**Tech Stack:** Kotlin、现有Compose foundation/material3、Android TileService/StatusBarManager，无新依赖。

**Spec:** docs/superpowers/specs/2026-11-01-controls-overtime-design.md

## 全局约束

保留用户修改、当前预设和所有设置；仅新开发分支。时间归零/声音/记录仍复用原链路；禁止C4超时加时的校验必须在服务，不能只隐藏控件。Debug选择不进入预设，不触发真实会话。重要行为已由用户确认，不再重复请求确认。

## Task 1：C4持续超时与统一控制能力

- [x] 在C4TimelineTest先增加断言：归零/尾音为EXPLODING，尾音后为OVERTIME；归零前口令有效，截止时后无解除。
- [x] 运行Unit离线检查，保存原OFF行为导致的失败。
- [x] C4Frame增加只读超时及口令锁定区分，C4Runtime增加stageActive()，全局声音仍即时控制。
- [x] SessionState增加c4Active，SessionControls负责canEnd/canPause/canExtend；服务对请求按当前运行时再校验，WidgetModel/首页/通知同步。
- [x] C4DevicePanel在归零后将#键显示为结束，FocusOverlay常态显示专属超时读数；不受普通实验偏好影响。
- [x] JVM覆盖两个归零边界、旧控制请求、C4结束而不能加时/暂停；最终设备覆盖完整和常态、尾音中结束与尾音后保留。

**Interfaces:** C4Frame.active表示专属主题，requiresCode表示仅口令阶段；SessionControls基于c4Active禁止暂停/加时，基于c4Locked限制结束。后续Tile仅消费该规则。

## Task 2：普通超时累计数据与Debug布局

- [x] 先写OvertimeReadoutTest：15分钟+5分钟显示累计20分钟，多次extendAt只读elapsedAt不重复加超时，正计时不生成超时读数，长时长有小时。
- [x] ReminderPresentation追加可选只读读数，默认调用保持兼容；正式基线OvertimeTimeHeader只绘制，不管理计时。
- [x] Debug/Release同名OvertimeHeader；Debug三候选及独立偏好，开发者模式增加可操作选择和无副作用预览（折叠/展开、形状、长时长）。
- [x] 所有候选共用FocusOverlay原按钮/拖动/锚点；Release不包含实验偏好、选择器或其他候选。
- [x] 设备在同一数据下截图三候选，检查真实浮窗/设置预览、三形状与长时长，不更改预设或正式记录。

**Interfaces:** OvertimeReadout由snapshot.elapsedMillis和overtimeMillis提供；C4路径提前分支，不读取OvertimeHeader。

## Task 3：首页原生横向分页

- [x] HomeEntryPager封装原生Pager与标签同步，状态按HomeEntry.id保存；栏目顺序改变保留当前ID。
- [x] MainActivity原when内容保持不变，仅改宿主；计时选择及活跃会话操作仍在页外共享。
- [x] HomeEntryTabs报告排序状态；启动中/排序中禁分页，分页时禁排序；纵向滚动与内部控件不误切。
- [x] 设备验证双向滑动、点击同步、排序后方向、每页滚动、模式/时长共享、旋转/返回、活跃会话和启动忙状态。

**Interfaces:** 分页内容只得到HomeEntry，不处理计时启动；现有HomeNavigationStore继续只保存顺序。

## Task 4：原生控制中心磁贴

- [x] 先写TimerTileModelTest：空闲/运行/暂停/超时/C4/忙状态，当前会话/段校验，锁屏延迟不操作新会话。
- [x] FocusTileService只在onStartListening收集session/defaults；onStopListening/onDestroy取消，没有独立轮询或保活。
- [x] 空闲通过WidgetStartActivity启动；活动控制沿用带requestId和timerStart的现有服务入口；C4和超时打开MainActivity。
- [x] Android14+使用PendingIntent折叠控制中心，锁屏unlockAndRun后重验；连续点击防重入。
- [x] Manifest声明系统BIND_QUICK_SETTINGS_TILE服务和受保护设置别名；新增24dp白色透明图标；设置提供官方添加确认及旧系统指引。
- [x] 设备使用真实SystemUI添加、切换状态、点击开始/暂停/继续和C4查看；移除本轮添加的磁贴，恢复原配置。

## Task 5：集成验证与交付

- [x] Debug/Release Full，脚本单测及相关设备套件；修复失败并保留原始结果。
- [x] 独立审查整分支，修复重要问题并按影响复测；未执行真机项明确保留。
- [x] 更新AGENTS、功能说明、CHANGELOG、统一用例库与构建身份；仅追加执行证据。
- Git收尾：只提交本轮内容并同步开发分支；用户原AGENTS空行和asset不混入，不合并/发版。实际提交与同步结果以Git记录及交付报告为准。

## Review Focus

1. C4视觉常驻不等于口令锁常驻；所有入口必须区分结束与暂停/加时。
2. 加时历史中的elapsedBefore已含超时，显示不得二次相加。
3. 排序改变pager索引但不能改变当前栏目ID或页面草稿。
4. 磁贴解锁/连续点击后的请求不得影响后来会话；冷启动须在可见Activity启动FGS。
5. 实验组件不能进入Release或污染C4显示；原位置锚点与窗口手势保持。
