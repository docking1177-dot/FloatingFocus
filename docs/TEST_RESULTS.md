# 构建与验证记录

日期：2026-10-20。以下结果针对本次交付的 Floating Focus 0.1.0 Debug；构建通过不代表真机功能已经验收。

## 环境与构建

- Windows，JDK 21.0.10（现有 PyCharm JBR），源码/字节码目标 Java 17。
- 本地 Android SDK Platform 36、Build Tools 35.0.0；AGP 8.10.1、Gradle 8.11.1、Kotlin 2.1.20。
- `assembleDebug testDebugUnitTest lintDebug`：**BUILD SUCCESSFUL**，最终一次构建 40 秒，51 个任务中 27 个执行、24 个为最新。
- 最终构建使用 `--offline` 和本机临时 Maven 缓存 init 脚本。早期网络下载失败已通过临时转发官方仓库解决；工程自身仍配置官方 Google/Maven Central 仓库。
- 构建日志有 Android 工具无法写入用户目录 analytics.settings 的非致命提示；没有修改用户目录权限或系统网络设置。
- 原始记录：[build-final.log](validation/build-final.log)。

## 自动化检查

### GitHub 云端验证

2026-10-20，公开仓库提交 `5f57264ecf70ccd88bcc930237597bccbac7d22c` 在 Ubuntu 24.04 / JDK 17 上完成 `assembleDebug testDebugUnitTest lintDebug`，**BUILD SUCCESSFUL in 2m 45s**。见 [成功的 Actions 运行](https://github.com/docking1177-dot/FloatingFocus/actions/runs/39431207135)。运行页已保存 Debug APK 和检查报告，保留期为 14 天；该 APK 使用 CI 临时测试签名。

首次云端尝试曾因 `sdkmanager: command not found` 失败，补齐 SDK 初始化并固定 runner/Action 版本后重新运行通过。没有将首次失败描述为成功。此验证不包含模拟器、真机或 UI 仪器测试。

### 本地验证

| 检查 | 实际结果 | 范围 |
|---|---|---|
| Kotlin 编译与 Debug APK 打包 | 通过 | 当前工程 |
| JUnit | 4 测试，0 失败、0 错误、0 跳过 | 单调时间模型、阶段边界、跳过刷新、时长校验、显示取整 |
| Android Lint | 0 错误、8 警告、1 提示 | 8 项是固定 Gradle/AndroidX 版本有新版本；1 项是 ViewModel KTX 建议 |
| APK 签名 | `apksigner verify` 通过，v2，RSA 2048 | 测试签名，不是商店发布签名 |
| APK ZIP 完整性 | CRC 校验通过 | 所有 ZIP 条目 |
| ZIP 16 KB 对齐 | `zipalign -c -P 16 4`，退出码 0 | 包结构静态检查 |
| ELF 16 KB 对齐 | 四个 ABI 的 native 库 PT_LOAD 均为 16384 | AndroidX graphics-path 库；不等于 16 KB 真机运行测试 |
| 许可文件 | APK 内 3 份许可/声明与工程源文件逐字节一致 | MIT、Apache-2.0、第三方声明 |
| Manifest | minSdk 29、targetSdk 36、应用包名及权限核对 | 未申请 INTERNET、无障碍、使用情况访问或精确闹钟权限 |

保持已完成构建验证的版本组合，没有为消除“有更新版本”警告而整体升级依赖。ViewModelStore 仅用于 Compose 宿主生命周期，无需为建议单独添加直接 KTX 依赖。未全局禁用 Lint。

原始记录：[JUnit XML](validation/CountdownTest.xml)、[Lint 文本](validation/lint-results-debug.txt)、[Lint XML](validation/lint-results-debug.xml)、[签名](validation/apk-signature.txt)、[包信息](validation/apk-badging.txt)、[完整性和 ELF 对齐](validation/apk-integrity.txt)、[ZIP 对齐](validation/apk-alignment.txt)、[运行时依赖坐标](validation/runtime-dependencies.txt)。

## 交付 APK

- 路径：`dist/FloatingFocus-0.1.0-debug.apk`。
- 大小：24,803,875 字节（约 23.7 MiB）。
- applicationId：`app.floatingfocus`。
- versionName / versionCode：`0.1.0` / `1`。
- SHA-256：`df2aec66aadf98875b0eb34e27d0681495cf7cb416ecc176f1899227c8d3f31c`。
- 签名证书 SHA-256：`2119d9676b8cbf5a1efee1f01b74d0846cd5573d4f18f6faf64a73146158d11a`。

`dist/SHA256SUMS.txt` 同时记录 APK 和源码压缩包的校验值。源码包不包含测试签名私钥，重新构建可能使用不同签名。

## 未验证及已知边界

用户后续反馈：`FloatingFocus-0.1.0-debug.apk` 已传到 Android 手机并运行成功。设备型号、系统版本和具体操作覆盖尚未提供；该记录仅代表用户报告安装与启动成功，不代表以下场景全部通过。

AI 侧 `adb devices -l` 未发现设备，见 [设备枚举](validation/adb-devices.txt)。AI 没有执行安装启动或 Android 仪器测试，也没有读取真机画面。因此以下均为**待真机验收**：

- Android 14/15/16 的授权、前台服务、跨 App 显示、拖动与点击区分、旋转、系统栏/刘海边界。
- 实际视觉颜色和呼吸效果、一次震动、通知结束、确认后的窗口/服务释放。
- 通知拒绝、撤销悬浮权限、系统动画关闭、强制停止、不同厂商后台策略。
- 连续开始/结束、内存、耗电、温度、卡顿和 16 KB 页大小设备运行。

完成这些项目的操作和预期见 [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md)。单元测试模拟跨越时间点，仅验证计算模型，不能替代熄屏真机测试。

当前不持有 WakeLock、不申请精确闹钟。深度休眠可延迟执行到期震动，唤醒后会按 elapsedRealtime 校准；进程被系统终止或重启后不恢复计时。该版本适用于用户主动开启、使用其他 App 时可见的提醒场景。
