# 统一检查入口

在项目根目录使用 PowerShell：

```powershell
# 默认完整检查：生成 Debug APK、JVM 单测、Lint
.\scripts\check.ps1
# 只运行 JVM 单测
.\scripts\check.ps1 -Mode Unit
# 完整检查，启动专用模拟器，安装并启动应用
.\scripts\check.ps1 -Mode Emulator
# 本机复用已缓存的依赖；有临时 Maven init 脚本时自动使用
.\scripts\check.ps1 -Offline
# 离线单测；保持 Gradle task 与 --offline 为独立参数
.\scripts\check.ps1 -Mode Unit -Offline
# Release 构建、Release 单测、Release Lint（不生成正式签名）
.\scripts\check.ps1 -Variant Release -Offline
```

脚本成功返回退出码 0，失败返回 1；无效参数由 PowerShell 返回非零。成功输出测试数和 Lint 摘要，失败显示错误及日志末尾。每次保留独立日志和同名 JSON，`latest.json` 只是最近一次结果的便捷副本，均在 `.tools/checks/`，被 Git 忽略。

JSON 记录 Git commit、源码内容 SHA256 指纹、dirty、未跟踪源码、任务列表、variant、离线模式、起止时间、本地构建输入哈希及 Full 的 APK 路径/校验值。指纹包含全部已跟踪文件（含删除）和未忽略的新文件；未跟踪的用户 `asset/` 只记录排除数量，不列出私人素材文件名、不参与源码指纹，已跟踪的 asset 仍参与。SDK/缓存等忽略项不属于源码；local.properties 与本地 Maven bridge 只记录哈希，不泄露内容。检查期间源码或构建输入改变会判为失败。提交后 SHA 已变，正式发布需在最终提交重新生成检查证据。

`python scripts/source_state.py` 可单独查看当前身份。结果只证明列出的任务，不把 Unit、安装启动或 CI Debug 误作完整发布/交互验收。

`Unit` 不打包 APK、不运行 Lint；`Full` 包含二者；`Emulator` 使用 `Full` 的结果进行安装启动，默认设备为 `emulator-5554`。`-Serial emulator-5556` 可指定另一个已经启动的模拟器；`-BootTimeoutSeconds 240` 可调整等待启动时间。脚本只接受模拟器序列号，不会自动选择连接的手机。

Emulator 模式不会自动授予悬浮权限、点击开始或完成拖动回归，也不会在结束后自动关闭模拟器。显示窗口及停止方式见 [EMULATOR.md](EMULATOR.md)。安装启动通过不等于触摸、权限和前台服务场景通过。

本地构建继续调用现有 `build-local.ps1`，保留同一份测试签名。日常检查不复制 dist、不修改版本、不提交 Git、不发布 Release。

## 可重复的设备回归

C4 布局局部回归：`./scripts/test-device.ps1 -Suite c4_keypad -AllowTestDataChanges`。组合普通基础大小0.75/1.0/1.5、不同颜色/透明度/形状，验证C4两种形态尺寸不随之变化，窗口透明度固定0.7；增加系统大字体，记录0—7位输入的实际 Compose 字形溢出结果与截图。点击十二键、边缘与缝隙，验证按键拖动取消、收起保留输入及口令解除，最后还原字体设置。它不代替完整 `c4` 声音/时间轴检查。

服务清理检查按包名/组件匹配当前 `* ServiceRecord` 条目。`dumpsys activity services` 即使带包名筛选，也可能附带系统全局的历史 `Last ANR service`；这类诊断不能判成FF仍在运行，脚本单测覆盖两者区别。

预设桌面入口：`./scripts/test-device.ps1 -Suite preset_shortcuts -AllowTestDataChanges`。实际确认/取消、桌面图标、冷热启动、更新/删除/读错、权限返回和重建有独立断言，记录 App 前台/浮窗/会话。失败还原 FF 数据；固定入口不属于 FF 文件备份，因此所有 suite 都在修改前拒绝已有 FF 固定入口的 AVD，桌面 suite 清理自己的入口。真机启动器与未执行的边界单独报告，见 [PRESET_DESKTOP_SHORTCUTS.md](PRESET_DESKTOP_SHORTCUTS.md)。

```powershell
./scripts/test-device.ps1 -Suite smoke -AllowTestDataChanges
./scripts/test-device.ps1 -Suite edges -AllowTestDataChanges
./scripts/test-device.ps1 -Suite inline -AllowTestDataChanges
./scripts/test-device.ps1 -Suite settings -AllowTestDataChanges
./scripts/test-device.ps1 -Suite home -AllowTestDataChanges
./scripts/test-device.ps1 -Suite preferences -AllowTestDataChanges
./scripts/test-device.ps1 -Suite c4 -AllowTestDataChanges
./scripts/test-device.ps1 -Suite onboarding -AllowTestDataChanges
./scripts/test-device.ps1 -Suite developer -AllowTestDataChanges
./scripts/test-device.ps1 -Suite history -AllowTestDataChanges
./scripts/test-device.ps1 -Suite preset_styles -AllowTestDataChanges
# 发布前全部执行
./scripts/test-device.ps1 -Suite all -AllowTestDataChanges
```

先启动且等待专用 `FloatingFocus_API_36` 模拟器就绪，结束当前计时。脚本只接受 emulator 序列号并核对 AVD；要求镜像自带 Gallery。首次安装或覆盖安装本地 Debug APK，使用 run-as 备份/还原 app 的 SharedPrefs 和 files；创建自己的预设和快捷 App，不依赖旧临时脚本/数据。失败也尝试还原，备份留在 `.tools/device-checks/<时间>/`，还原失败写入结果并返回失败；只在专用测试模拟器授予悬浮/通知权限，权限和安装版本保留。不要在有重要数据或正在计时的设备执行。

smoke 检查预设启动 Gallery、手动结束、记录；edges 检查三种形状四个边缘通过标题和可见背景分别展开/收起；inline 等待真实 1 分钟到期，检查取消/确认加时、同窗口/前台/记录、基础尺寸恢复、动态增长及第二次归零。部分浮窗坐标按实际 frame 推导，系统 dumpsys/UI 层级变化仍可能需要维护。截图只供视觉复核，不自动断言像素/震动手感。每组记录成功/失败、源码、APK 哈希和截图。覆盖安装会比对安装前后存储内容；同版本重装通过不冒充跨版本升级通过。

展开位置抖动还须检查中间帧：专用模拟器上把普通浮窗拖到屏幕中部，用 `adb shell screenrecord` 录制数次展开/收起并逐帧复核。新内容必须直接出现在按新尺寸计算的最终位置，不得先在旧左上角绘制、下一帧再补移；最终 frame 正确不能单独证明无抖动。录屏和帧图保存在对应 `.tools/device-checks/` 证据目录，不提交视频。

脚本自身回归：`python -m unittest discover -s scripts/tests -v`。无外部测试依赖，覆盖任务参数组合、源码指纹变化、发布证据拒绝和 CI 精确复用。发布步骤见 [RELEASE_AUTOMATION.md](RELEASE_AUTOMATION.md)。

`settings` 回归设置列表/返回、四种开关组合与持久化、两个预设各自位置、重启恢复、横竖屏边界、展开不改位置及手动计时隔离；测试使用自己的预设数据，完成后由同一 runner 还原。`all` 包含这组。

`home` 检查三卡分区、四列快捷网格与启动、末尾添加 App 保留旧选择、末尾新建预设与启动、自定义默认 1 分钟及手动设置入口、说明折叠、缺失悬浮权限时就近提示与阻止启动；保存截图供视觉复核，授权测试后恢复允许状态。`all` 也包含此组。

`preferences` 通过真实分秒滚轮验证回显、确认/取消、常用时长与预设的秒精度持久化/实际启动，拖动策略节点并设置不同类型，检查三种启动入口对冲突的阻止。`inline` 等待真实到期，验证默认两分钟、滚轮滑动不移动窗口、取消/确认、同一会话和第二次到期。共用备份还原与源码/APK 身份检查。

`startup` 检查默认15分钟迁移、独立分秒默认值、保存/取消、正倒计时切换、重启/前台/旋转、活跃服务的新首页，以及四档编辑/恢复默认互不影响；通过实际会话记录核对125秒倒计时和正计时，不以选项截图代替运行结果。`c4_compact` 单独验证常态截图、拖动、双向切换、输入保留和口令结束，并保留完整装置截图供同条件前后对照。`preset_shortcuts` 增加取消后未确认指引、重复添加固定ID及实际桌面图标点击；不支持的启动器与厂商权限仍须真机单列验证。以上 suite 使用原 `test-device.ps1 -Suite <名称> -Serial <专用模拟器> -AllowTestDataChanges`，`all` 包含 `startup`，完整C4行为仍由 `c4` 覆盖。

`theme` 验证系统浅/深主题、参数页存续切换、原生选时、提示对话框、历史图表/真实读取错误及大字体；读取实际帧缓冲区核对背景主题，截图另行视觉复核。开启C4音效时运行独立正计时至少46秒，检查同一会话、禁用态及无C4事件/音频请求；倒计时正向验证复用 `c4_compact`。`all` 包含 `theme`。脚本只使用已有标准库和ADB，不新增图像测试依赖。

UIAutomator 每次先删除旧快照并确认新文件生成，短暂失败最多重试三次，每次均清理旧文件，始终不沿用旧层级；重试次数写入 hierarchyRetries。ActivityManager 的 `am start -W` 若仅返回 timeout，必须同时验证实际前台 MainActivity 和新生成的首页层级才继续交互；完整启动输出保存在 `activity-launch.txt`，次数记为 `activityWaitTimeouts`，不把后续交互通过当作启动耗时合格。

`c4` 验证首次开启说明、44.439 秒进入稳定常态、双向切换保留输入、4.92 秒开放且早按不缓存，实体键盘/错误/退格/清空/解除、键区不拖动与实体拖动、首页及通知结束入口限制、开关仅下会话、短起点、单次爆炸与尾音后恢复超时。覆盖关音效、锁屏/唤醒不重播、横竖屏、强停/撤权、原三形状与正计时排除；Android16 以不透明度 0.7 在同一窗口透明角落真实点击另一 UID 的图库菜单，并核对两个包的 UID 与浮窗未收到该点击。系统设置在此镜像会隐藏浮窗，不能用其隐藏状态证明穿透。保存截图与 MediaPlayer 位置/节点日志，不等于真机可听、无缝衔接或 CS2 灯光精确还原；高不透明度及旧系统穿透未测。详见 [C4_COUNTDOWN.md](C4_COUNTDOWN.md)。`all` 包含本组，执行耗时取决于模拟器；C4 断言使用真实计时，较长交互拆成独立会话；真实录音只用本机 Debug 资源，CI/Release 不含录音，声音场景需本地文件。

文档改动无需运行 Gradle；开发脚本检查语法并验证实际变更路径。应用改动按范围选择检查，完整交付使用 Full；涉及交互时再执行相关模拟器或真机回归。已有 GitHub Actions 继续在远端执行构建、单测和 Lint。

`onboarding` 复用同一备份/还原机制检查首次引导、滑动/返回/跳过、真实系统悬浮授权与拒绝、后台进程重建、暂不授权和启动拦截、重看不改功能数据、撤销权限、旧数据迁移、深色和横屏。临时夜间/旋转配置会恢复；仅在专用模拟器清理已备份的 app 私有数据模拟首次启动，不使用 `pm clear` 或卸载。详情见 [ONBOARDING.md](ONBOARDING.md)。

`developer` 检查调试入口只在设置、旧方案/确认/退出按钮移除、浮窗按钮同一行、正式组合效果在实验窗口生效、关闭及返回清理、不写计时记录。`home` 同时检查说明与许可位于设置；两组均备份还原数据。仅 Debug 执行 developer，Release 另检查构建、空 DeveloperEntry 和合并 Manifest 中无实验组件。

`history` 检查今日/周/月、全部/手动/历史 App 筛选、同名包区分、搜索、柱点击/空白取消和时段列表；通过真实系统文件选择器保存后解析 CSV，确认筛选不改变全部导出范围和原记录。还检查读取错误/重试不结算活跃会话、空记录、旋转保持筛选，保存深色/大字体截图供复核。夹具和导出文件只用于专用模拟器，结束由同一 runner 还原原始数据与临时显示设置；`all` 包含本组。

2026-10-21 本机验证：Full 与 Emulator 模式通过，9 项单测、Lint 0 错误 / 8 警告 / 1 提示；Emulator 模式实际完成安装及 Activity 启动。另用隔离的无效 JDK 配置确认失败返回 1。原始结果保存在 `.tools/checks/`，不作为源码提交。

`preset_styles` 仅 Debug：通过设置里的开发者模式切换三候选并返回真实首页，检查独立偏好持久化、同数据/顺序、不可操作预览及无副作用、四列快捷App、空/单/奇数/较多预设、小屏2倍字号、真实权限/启动失败/双击/活跃会话禁用。保留同一批数据三种截图，大字体截图另列；临时显示设置与应用数据均还原。Release构建后检查DEX无PresetStyleStore/PresetStylePicker/候选渲染，不把仅隐藏入口当隔离。
