# Floating Focus：AI 项目指南

## 项目与入口
- Android 原生 Kotlin / Jetpack Compose 悬浮渐进提醒计时器，包名 `app.floatingfocus`。
- 保持轻量：功能变更以当前需求为界，不顺带扩展权限、依赖或后台保活机制。
- 主界面在 `MainActivity.kt`；会话和前台服务在 `FloatingService.kt`；计时模型在 `Countdown.kt`。
- 浮窗宿主与边界在 `OverlayWindow.kt`，整个窗口的手势分发在 `OverlayTouchLayout.kt`，手势状态在 `OverlayDragGesture.kt`，内容在 `FocusOverlay.kt`。
- 上述源码均位于 `app/src/main/java/app/floatingfocus/`；JVM 测试在 `app/src/test/`。

## 工作与检查
- 开始修改前检查 Git 状态，保留用户现有改动；先定位相关代码，不要求每次通读全部文档。
- Windows 统一检查入口：`./scripts/check.ps1`，默认完整构建、单测和 Lint。
- 只跑 JVM 单测：`./scripts/check.ps1 -Mode Unit`。
- 完整检查后启动专用模拟器、安装并启动应用：`./scripts/check.ps1 -Mode Emulator`。
- 本机离线复用已有缓存：为以上命令添加 `-Offline`；有本地 Maven init 脚本时自动使用。
- 完整日志与最新摘要在 `.tools/checks/`，成功时只需阅读摘要，失败再查看相关日志。
- 文档修改不用重建 APK；开发脚本修改验证相应脚本即可。应用改动运行相关检查，已有通过结果可复用，避免无理由重复全量检查。
- 触摸、权限、服务或窗口边界修改，需要实际模拟器/设备回归。Emulator 模式仅验证安装启动，不自动证明交互通过。
- 报告区分源码检查、JVM 单测、模拟器和真机结果；未执行的项目不能写成通过。

## 环境、签名与产物
- 本机 SDK 在 `.tools/android-sdk`，AVD 在 `.tools/avd`，Gradle 缓存在 `.gradle-home`；详细设置按需看 `docs/EMULATOR.md`。
- 使用现有 JDK 17/21 和 `scripts/build-local.ps1`；不改系统代理配置，不为普通构建重装整个工具链。
- `.tools/debug.keystore` 对应此前交付到手机的测试签名，须保留。密钥、凭据、SDK、AVD、缓存和 `local.properties` 不提交、不打印。
- 日常调试直接使用 `app/build/outputs/apk/debug/app-debug.apk`；只有交付时整理 `dist`、校验值与版本说明。
- 新版本覆盖安装前确认包名、签名兼容；公开交付时递增 versionCode。CI 临时 Debug 签名不能假定可覆盖手机现有包。

## Git 与发布
- 一个完整改动形成一次逻辑清晰的提交。使用原生 Git 同步，先核对当前分支及远端。
- 凭据交给 Git Credential Manager；不把令牌写进脚本、remote URL、日志或仓库文件。
- 不覆盖用户改动，不强推或重写既有远端历史。遇到远端前进先检查差异再同步。
- 修改、提交、推送与正式发版分开；普通修复调试无需每次增加版本、打标签、压缩源码或创建 Release。
- 当前任务授权范围内完成必要验证与同步；合并或发布按用户当次指令执行。

## 按需参考
- 构建环境与功能：`README.md`。
- 开发、同步、签名和发布：`docs/DEVELOPMENT_WORKFLOW.md`。
- 统一检查参数：`docs/CHECKS.md`。
- 模拟器启动：`docs/EMULATOR.md`；已有交互验证：`docs/EMULATOR_VALIDATION.md`。
- 真机验收：`docs/DEVICE_TEST_CHECKLIST.md`；变更历史：`CHANGELOG.md`。
