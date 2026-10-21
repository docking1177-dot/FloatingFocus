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
```

脚本成功返回退出码 0，失败返回 1；无效参数由 PowerShell 返回非零。成功输出测试数和 Lint 摘要，失败显示错误及日志末尾。完整日志和 `latest.json` 位于 `.tools/checks/`，被 Git 忽略。

`Unit` 不打包 APK、不运行 Lint；`Full` 包含二者；`Emulator` 使用 `Full` 的结果进行安装启动，默认设备为 `emulator-5554`。`-Serial emulator-5556` 可指定另一个已经启动的模拟器；`-BootTimeoutSeconds 240` 可调整等待启动时间。脚本只接受模拟器序列号，不会自动选择连接的手机。

Emulator 模式不会自动授予悬浮权限、点击开始或完成拖动回归，也不会在结束后自动关闭模拟器。显示窗口及停止方式见 [EMULATOR.md](EMULATOR.md)。安装启动通过不等于触摸、权限和前台服务场景通过。

本地构建继续调用现有 `build-local.ps1`，保留同一份测试签名。日常检查不复制 dist、不修改版本、不提交 Git、不发布 Release。

文档改动无需运行 Gradle；开发脚本检查语法并验证实际变更路径。应用改动按范围选择检查，完整交付使用 Full；涉及交互时再执行相关模拟器或真机回归。已有 GitHub Actions 继续在远端执行构建、单测和 Lint。

2026-10-21 本机验证：Full 与 Emulator 模式通过，9 项单测、Lint 0 错误 / 8 警告 / 1 提示；Emulator 模式实际完成安装及 Activity 启动。另用隔离的无效 JDK 配置确认失败返回 1。原始结果保存在 `.tools/checks/`，不作为源码提交。
