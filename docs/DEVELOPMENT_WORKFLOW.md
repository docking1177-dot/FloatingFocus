# 开发、同步、更新与验证

## 代码与安装包的关系

Git 记录源码与文档的每次变更；GitHub 是远程备份、协作与自动检查平台；Gradle 把某次源码提交编译成 APK；手机安装 APK。推送源码不会自动更新手机。

日常流程：提出问题 → 建分支 → 修改 → 单测/Lint/构建 → 模拟器或设备回归 → 合并 main → 更新版本 → 打 Tag → 发布 APK。单人项目不需要维护长期 develop 分支。

```powershell
git switch main
git pull --ff-only
git switch -c fix/overlay-bounds
# 修改后执行 README 中的构建检查
git add app docs
git commit -m 'fix: keep overlay within safe bounds'
git push -u origin fix/overlay-bounds
```

到 GitHub 提 PR；检查通过并完成相关验收后合并。另一台电脑使用 git clone，后续用 git pull --ff-only；两台电脑不要同时直接修改同一分支。Bug 在 Issues 里保存复现步骤与版本，变更在 CHANGELOG.md 里保存。

## 版本与发布

当前版本位于 app/build.gradle.kts：versionName 0.1.1、versionCode 2。下一次修复发布例如 0.1.2 / 3；增加功能也可用 0.2.0 / 3。versionCode 每次发布递增；仅修改文件名不会改变 APK 内部版本。

发布时先更新版本和 CHANGELOG，构建并验收，再将最终提交打 Tag（例如 v0.1.1）。GitHub Releases 对应此 Tag，附 APK、校验值、更新说明与已知问题。公开的源码版本应与安装包对应。

当前 .github/workflows/android.yml 在 main 推送、v* Tag、PR 或手动触发时执行构建、单测、Lint，并保存 APK 与报告。2026-10-20 已完成一次[云端成功验证](https://github.com/docking1177-dot/FloatingFocus/actions/runs/39431207135)。它没有配置模拟器或真机测试，不能据此声称功能验收通过。

## 覆盖安装与签名

覆盖更新要求 applicationId 一致（app.floatingfocus）、签名兼容、新版本符合系统版本限制。每次发布递增 versionCode。正常覆盖安装一般保留已有数据和权限，新权限仍可能需要用户授权；安装前结束当前计时，更新后重新开始。

此工作区交付给手机的 0.1.0 使用 .tools/debug.keystore，生成于本机，私钥已被 Git 忽略。后续用同一个本地构建脚本和同一个文件签名，可以继续覆盖测试。不要删除此文件；私钥备份应单独安全保存，不放公开仓库。

Android Studio 的默认 Debug key、另一台电脑生成的 Debug key、GitHub Actions 临时 Debug key都可能不同，不能假设它们可以覆盖当前手机安装包。CI 的 APK 默认用于独立测试。不要把本机 Debug key 上传为公开构建材料。

正式对外持续发行前，应建立专用 release 签名并备份；Debug key 不作为正式发行身份。当前没有配置 release 签名或密钥迁移。切换为不兼容签名时，通常要卸载旧测试包再安装正式包，会清除旧数据；当前版只有少量权限请求偏好，不保存计时历史。

签名与版本规则：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[应用版本管理](https://developer.android.com/studio/publish/versioning)。

## 安装与自动调试

可以继续传 APK 到手机点击安装。更高效的开发方式是通过 USB 或已配对的无线 ADB 调试；连接后电脑可以直接覆盖安装，无需手工传文件。

```powershell
# adb 可使用本地 .tools/android-sdk/platform-tools/adb.exe
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.floatingfocus/.MainActivity
adb logcat -s FloatingFocus AndroidRuntime
```

多台设备时给每条命令增加 `-s 设备序列号`。首次连接需要在手机确认调试授权；无线调试也需先配对。悬浮权限仍通过正常系统设置授予。

## AI 能检查到什么

| 层级 | 能得到的证据 | 需要设备吗 |
|---|---|---|
| 源码检查、编译、Lint、纯 JVM 单测 | 类型/构建问题、部分静态缺陷、计时模型 | 不需要 |
| APK 检查 | 包名、版本、Manifest、签名、完整性、对齐 | 不需要 |
| 模拟器运行 | 界面截图、点击/拖动、权限流程、跨 App、日志与部分自动回归 | 需要配置模拟器及系统镜像 |
| ADB 真机运行 | 截图/录屏、输入、安装启动、日志、服务与窗口状态 | 手机已连接并授权 |
| 人工真机体验 | 震动手感、提醒是否打扰、复杂使用场景 | 需要人实际体验 |

Gradle 构建自身不会显示运行场景。AI 要判断画面，需要额外启动模拟器或连接真机，并实际取得截图/录屏；只有截图也不足以证明拖动、释放和后台可靠性。可以用 Android 仪器测试、Compose UI 测试和 UI Automator逐步建立自动回归。

日常小修改可先由自动检查和模拟器筛查，不必每次都要求用户手工传 APK。涉及悬浮窗口、安全边界、权限、前台服务、熄屏、厂商后台策略或正式发布时，仍应在目标真机回归。模拟器无法完全替代厂商省电策略、实体震动与长期耗电测试。

本机目前没有 Android Emulator/系统镜像，ADB 未枚举到手机。用户已反馈 0.1.0 在手机安装运行成功；这是用户提供的启动反馈，不扩展为完整验收。AI 尚未读取该手机的运行截图或日志。

## 发布到 GitHub

本项目 MIT 与第三方归属已保留。上传源码范围：app/src、构建配置及 wrapper、脚本、docs、licenses、README、CHANGELOG、CONTRIBUTING 和 .github；排除 SDK、缓存、上游快照、local.properties、密钥和 dist。

公开仓库已建立为 https://github.com/docking1177-dot/FloatingFocus，本机 main 已跟踪 origin/main。另一台电脑可直接 git clone。以下命令仅用于以后把独立工程首次关联到空仓库；不要在当前工程重复执行 remote add：

```powershell
git remote add origin https://github.com/你的账号/FloatingFocus.git
git push -u origin main
git push origin v0.1.0
```

如果 origin 已存在，先检查 git remote -v，避免覆盖错误地址。推送需要有效 GitHub 登录。仓库地址本身不包含授权，也不要把访问令牌贴进聊天或 remote URL。
