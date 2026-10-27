# Floating Focus

![Android checks](https://github.com/docking1177-dot/FloatingFocus/actions/workflows/android.yml/badge.svg)

Android 原生悬浮计时器，支持倒计时、超时正计时和独立正计时。可一键打开常用 App 并计时，通过颜色、尺寸和提亮提醒控制使用时长，并保存本地会话记录。

本项目迁移并改造 [FloatingCountdownTimer](https://github.com/tberghuis/FloatingCountdownTimer) 的悬浮窗生命周期、拖动和服务模式，使用独立品牌、图标及 applicationId。详见 [开源调研](docs/OPEN_SOURCE_REVIEW.md)、[复用记录](docs/REUSE_REPORT.md)、[第三方声明](docs/THIRD_PARTY_NOTICES.md)。

当前公开测试版：**0.2.0 / versionCode 3**。从 [GitHub Release](https://github.com/docking1177-dot/FloatingFocus/releases/tag/v0.2.0) 下载 `FloatingFocus-0.2.0-debug.apk`、校验值及发布说明。APK 使用此前本机固定 Debug 签名，包含提醒实验室；同签名旧测试包可覆盖安装。更新前结束计时，需要保留的记录可先导出 CSV。GitHub 自动提供对应标签的源码包，私钥／SDK／缓存不在源码中。

[更新记录](CHANGELOG.md) · [0.2.0 发布检查](docs/TEST_RESULTS_0.2.0.md)。已完成 Android 16 模拟器回归；REDMI K80 最新完整真机验收待补充。模拟器操作见 [EMULATOR.md](docs/EMULATOR.md)。

公开仓库：[docking1177-dot/FloatingFocus](https://github.com/docking1177-dot/FloatingFocus)。后续开发、Git 同步、版本发布、签名与 AI/设备验证方式见 [维护指南](docs/DEVELOPMENT_WORKFLOW.md)。参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)，版本变化见 [CHANGELOG.md](CHANGELOG.md)。[GitHub Actions](https://github.com/docking1177-dot/FloatingFocus/actions/workflows/android.yml) 运行云端构建、单测和 Lint；其临时签名 APK 用于独立测试，正式下载以 Release 附件为准。

## 功能

- 使用与提醒说明、开源许可在设置中查看；Debug 专属「开发者模式」也仅在设置中进入，模拟直接复用正式外观与提醒配置。

- 四页首次使用教程，末页系统悬浮授权，支持暂不授权浏览；设置 → 使用教程可随时重看，更新不重置完成标记。见 [教程设计与边界](docs/ONBOARDING.md)。

- 四个可配置常用时长及 1 秒—180 分钟自定义倒计时；另有独立正计时。
- 归零后继续超时正计时，可在浮窗内加时或结束；每段归零提醒一次。
- 常用 App 快捷启动；预设绑定已添加的 App，保存完整计时配置，双列网格一键启动。
- 横向胶囊、纵向胶囊、圆形；整个浮窗可拖动，展开／收起保持位置锚点。
- 正式设置实时预览颜色、尺寸、透明度及提醒强度；支持连续变化或节点突变，提亮时保持透明度。
- 开发分支可选 [C4 末段特效](docs/C4_COUNTDOWN.md)：最后 40 秒切换原创装置图与音效，支持暂停/继续；当前为可配置近似节奏，未经 CS2 原版实测校准，尚未进入 0.2.0 发布包。
- 本地 FF 会话记录与 CSV 导出；记录累计时长及超时，异常中断仅恢复最后检查点并标记约计。
- 时长记录按今日小时、本周/本月每日展示条形图与累计；可筛选历史 App，跨日分配且保留会话明细，CSV 仍导出全部原始记录。统计沿用 FF 会话口径，含手动计时，见 [统计规则与边界](docs/TIME_RECORDS.md)。
- 基于 `SystemClock.elapsedRealtime()` 计时；熄屏、离开目标 App 后继续，手动结束后清理服务资源。
- 无账号、联网权限、无障碍服务、使用情况访问权限、数据库或开机自启；自动监测保持 Deferred。

## 工程结构

```text
app/src/main/java/app/floatingfocus/
  MainActivity.kt       时长选择与权限引导
  FloatingService.kt    前台服务、通知、单会话状态、权限撤销、震动
  OverlayWindow.kt      从 A 适配的 Compose 宿主、窗口生命周期与拖动
  OverlayTouchLayout.kt 整个窗口的触摸分发与按钮点击取消
  OverlayDragGesture.kt 拖动阈值、位移与取消状态
  FocusOverlay.kt       渐进色彩、提亮提醒与展开／加时面板
  Countdown.kt          单调时间模型、ReminderPolicy 与显示格式
  FocusTheme.kt         Material 3 主题
app/src/test/           JVM 计时与拖动手势状态测试
docs/                  调研、归属、测试结果及真机清单
licenses/              上游 MIT、Apache-2.0
scripts/build-local.ps1 Windows 本地构建辅助脚本
```

`.tools/`、`.gradle-home/`、`.upstream/` 和 `local.properties` 为本机缓存/参考快照，均已 gitignore；不需要提交或分发。

## 开发环境

| 项目                     | 版本                  |
| ---------------------- | ------------------- |
| 最低系统                   | Android 10 / API 29 |
| compileSdk / targetSdk | 36 / 36             |
| AGP / Gradle           | 8.10.1 / 8.11.1     |
| Kotlin                 | 2.1.20              |
| Compose BOM            | 2025.05.01          |
| Java                   | JDK 17 或 21         |
| SDK Build Tools        | 35.0.0              |

Android Studio 中打开本目录，选择 JDK 17/21。用 SDK Manager 安装 `Android SDK Platform 36`、`Build-Tools 35.0.0` 和 Platform-Tools。将 SDK 目录写入本机 `local.properties`：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

## 编译

Windows 工作区的统一检查入口：

```powershell
.\scripts\check.ps1                # 构建、单测、Lint
.\scripts\check.ps1 -Mode Unit     # 仅 JVM 单测
.\scripts\check.ps1 -Mode Emulator # 完整检查后安装启动模拟器应用
```

本机离线复用缓存可添加 `-Offline`。成功仅输出摘要，完整日志在 `.tools/checks/`；参数与验证范围见 [CHECKS.md](docs/CHECKS.md)。AI 项目入口为 [AGENTS.md](AGENTS.md)。以下为手动构建方式。

Windows PowerShell：

```powershell
$env:JAVA_HOME = '你的 JDK 17 或 21 路径'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew assembleDebug testDebugUnitTest lintDebug
```

此工作区已准备本地 SDK，可使用：

```powershell
.\scripts\build-local.ps1
```

脚本将 Gradle/Android 缓存写入工作区，并在设置了 `HTTPS_PROXY` 时用于该次构建；不会改系统网络配置。若没有设置 JAVA_HOME，会尝试本机已发现的 PyCharm JBR；其他电脑应自行设置 JAVA_HOME。首次构建需下载 Maven 依赖。

本机此前遇到 Java 经代理下载依赖中断，开发时使用临时本地 Maven 转发缓存完成下载，项目默认仓库仍为官方仓库。当前工作区可用以下命令复用已缓存依赖（仅限本机，辅助文件不进入源码包）：

```powershell
.\scripts\build-local.ps1 -Tasks @('assembleDebug','testDebugUnitTest','lintDebug','--offline','-I','.tools/maven-bridge.init.gradle')
```

Debug 输出：`app/build/outputs/apk/debug/app-debug.apk`。

本地脚本会生成测试签名 `.tools/debug.keystore`，Android Studio / 普通 Gradle 命令则使用标准 Debug 签名。两种签名不同的 APK 不能直接覆盖安装，需要先卸载旧测试包。这是测试签名，不是商店发布签名；正式发行需另外配置私钥并保管。

## 安装与使用

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以把 APK 传到手机，允许该文件管理器安装应用后安装。

1. 打开 Floating Focus，选择时长。
2. 首次开始会说明悬浮权限用途。到系统设置允许「显示在其他应用上层」，返回后再点击开始。
3. Android 13+ 可点击「开启计时通知」授权。拒绝通知不阻止计时，但通知栏可能不显示通知，仍可从胶囊或主界面结束。
4. 打开视频 App；轻触胶囊标题可展开，从悬浮窗任意区域拖动可调整位置，包括展开后的按钮和到期提示。
5. 到期后显示超时正计时，可在浮窗「加时」，或点击「结束计时」。

应用没有要求用户关闭全部系统省电限制，也不通过无障碍绕过系统限制。

## Android 兼容边界

- Android 14+ 使用 `specialUse` 前台服务和用途 property，Android 15/16 从可见 Activity 的明确点击启动。没有后台自动拉起或开机恢复。
- Google Play 对 specialUse 用途有单独审核；具备 manifest 声明不代表已获得上架审核通过。
- 手动计时（含一键打开 App）熄屏时真实时间继续流逝。未申请精确闹钟、未持有 WakeLock，深度休眠可能延迟执行到期震动；唤醒后会根据单调时钟立即校准，不能作为精确闹钟替代品。
- 系统强制停止、前台任务管理器停止、进程被回收或设备重启，会结束本次会话；不自动恢复。厂商省电策略可能提前停止服务。
- 用户撤销悬浮权限时，应用停止计时并清理资源；部分系统会直接杀掉进程。
- 某些金融/权限/安全页面主动禁止第三方悬浮窗，锁屏界面也可能隐藏它。不会绕过这些限制。
- 最低支持 Android 10。窗口边界按系统栏与刘海 Insets 限制，需在手势导航/三键导航、横竖屏和不同 OEM 上复核。
- 系统关闭动画后停止提亮动画；勿扰或设备设置可能禁止震动。

## 验证

「指定 App 自动计时」已标记 **Deferred**，统一开关关闭所有运行入口；保留已有代码与测试，进度和恢复条件见 [AUTO_TRIGGER.md](docs/AUTO_TRIGGER.md)。当前重点为手动悬浮计时与 [一键启动目标 App](docs/QUICK_LAUNCH.md)。

Debug 构建的主界面底部提供「提醒实验室」：三种提醒策略切换、时间／超时模拟、动画与振动触发及真实跨应用实验悬浮窗；正式参数统一在设置中调整。实验状态与正式计时隔离，Release 不包含实验页面或服务。使用与实现见 [REMINDER_LAB.md](docs/REMINDER_LAB.md)。

当前验证见 [0.2.0 检查记录](docs/TEST_RESULTS_0.2.0.md)，历史证据见 [0.1.1 检查记录](docs/TEST_RESULTS_0.1.1.md) 和 [0.1.0 检查记录](docs/TEST_RESULTS.md)。未连接设备时不会把真机验收写成通过。

快速真机流程：设置自定义 1 分钟，分别在约 30 秒、48 秒、60 秒观察阶段转换、归零和加时；再覆盖跨 App、拖动、横竖屏、熄屏、撤权及通知拒绝路径。完整清单见 [DEVICE\_TEST\_CHECKLIST.md](docs/DEVICE_TEST_CHECKLIST.md)。

## 下一阶段

可按实际反馈增加震动开关、位置记忆、无动画偏好和精确到期提醒的可选授权流程。先完成 Android 14/15/16 多品牌真机验证，再考虑扩展；当前没有提前引入相关框架。
