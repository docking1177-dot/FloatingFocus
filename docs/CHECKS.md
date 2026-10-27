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

```powershell
./scripts/test-device.ps1 -Suite smoke -AllowTestDataChanges
./scripts/test-device.ps1 -Suite edges -AllowTestDataChanges
./scripts/test-device.ps1 -Suite inline -AllowTestDataChanges
./scripts/test-device.ps1 -Suite settings -AllowTestDataChanges
./scripts/test-device.ps1 -Suite home -AllowTestDataChanges
./scripts/test-device.ps1 -Suite preferences -AllowTestDataChanges
./scripts/test-device.ps1 -Suite c4 -AllowTestDataChanges
# 发布前全部执行
./scripts/test-device.ps1 -Suite all -AllowTestDataChanges
```

先启动且等待专用 `FloatingFocus_API_36` 模拟器就绪，结束当前计时。脚本只接受 emulator 序列号并核对 AVD；要求镜像自带 Gallery。首次安装或覆盖安装本地 Debug APK，使用 run-as 备份/还原 app 的 SharedPrefs 和 files；创建自己的预设和快捷 App，不依赖旧临时脚本/数据。失败也尝试还原，备份留在 `.tools/device-checks/<时间>/`，还原失败写入结果并返回失败；只在专用测试模拟器授予悬浮/通知权限，权限和安装版本保留。不要在有重要数据或正在计时的设备执行。

smoke 检查预设启动 Gallery、手动结束、记录；edges 检查三种形状四个边缘展开/收起；inline 等待真实 1 分钟到期，检查取消/确认加时、同窗口/前台/记录、基础尺寸恢复、动态增长及第二次归零。部分浮窗坐标按实际 frame 推导，系统 dumpsys/UI 层级变化仍可能需要维护。截图只供视觉复核，不自动断言像素/震动手感。每组记录成功/失败、源码、APK 哈希和截图。覆盖安装会比对安装前后存储内容；同版本重装通过不冒充跨版本升级通过。

脚本自身回归：`python -m unittest discover -s scripts/tests -v`。无外部测试依赖，覆盖任务参数组合、源码指纹变化、发布证据拒绝和 CI 精确复用。发布步骤见 [RELEASE_AUTOMATION.md](RELEASE_AUTOMATION.md)。

`settings` 回归设置列表/返回、四种开关组合与持久化、两个预设各自位置、重启恢复、横竖屏边界、展开不改位置及手动计时隔离；测试使用自己的预设数据，完成后由同一 runner 还原。`all` 包含这组。

`home` 检查三卡分区、四列快捷网格与启动、末尾添加 App 保留旧选择、末尾新建预设与启动、自定义默认 1 分钟及手动设置入口、说明折叠、缺失悬浮权限时就近提示与阻止启动；保存截图供视觉复核，授权测试后恢复允许状态。`all` 也包含此组。

`preferences` 通过真实分秒滚轮验证回显、确认/取消、常用时长与预设的秒精度持久化/实际启动，拖动策略节点并设置不同类型，检查三种启动入口对冲突的阻止。`inline` 等待真实到期，验证默认两分钟、滚轮滑动不移动窗口、取消/确认、同一会话和第二次到期。共用备份还原与源码/APK 身份检查。

UIAutomator 每次先删除旧快照并确认新文件生成，短暂失败最多重试三次，每次均清理旧文件，始终不沿用旧层级；重试次数写入 hierarchyRetries。ActivityManager 的 `am start -W` 若仅返回 timeout，必须同时验证实际前台 MainActivity 和新生成的首页层级才继续交互；完整启动输出保存在 `activity-launch.txt`，次数记为 `activityWaitTimeouts`，不把后续交互通过当作启动耗时合格。

`c4` 验证全局开关默认值及重启保存、预设启动目标 App 后 60/40/30/10 秒自然归零、40 秒切换几何连续、暂停/恢复/取消、关音效、关功能和正计时排除。保存逐节点 LED/音频请求日志；SoundPool 返回成功不等于真机可听或硬件音画零延迟，亦不证明 CS2 精确还原。详见 [C4_COUNTDOWN.md](C4_COUNTDOWN.md)。`all` 包含本组，执行时间增加约三分钟。

文档改动无需运行 Gradle；开发脚本检查语法并验证实际变更路径。应用改动按范围选择检查，完整交付使用 Full；涉及交互时再执行相关模拟器或真机回归。已有 GitHub Actions 继续在远端执行构建、单测和 Lint。

2026-10-21 本机验证：Full 与 Emulator 模式通过，9 项单测、Lint 0 错误 / 8 警告 / 1 提示；Emulator 模式实际完成安装及 Activity 启动。另用隔离的无效 JDK 配置确认失败返回 1。原始结果保存在 `.tools/checks/`，不作为源码提交。
