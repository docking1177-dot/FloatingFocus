# 开发、同步、更新与验证

## 代码与安装包的关系

Git 记录源码与文档的每次变更；GitHub 是远程备份、协作与自动检查平台；Gradle 把某次源码提交编译成 APK；手机安装 APK。推送源码不会自动更新手机。

### 日常小修复与发版分开

Git 仓库只初始化一次。每个完整的小修复提交一次 commit，不是重新创建仓库；分支与 PR 用于保存和审查改动。日常调试使用 Gradle 增量构建，再通过 ADB 覆盖安装到模拟器；不必每次增加版本号、复制 dist、压缩源码或发布 Release。修改文档和开发脚本无需重建应用。

适合本项目的轻量流程：修改 → 相关单测与模拟器验证 → commit / push → 自动检查。涉及悬浮触摸的修复还要在目标手机回归；达到可交付状态时再更新版本、整理说明并发布。

AI 应主动判断完整改动的检查与提交时机，沿用已有授权同步当前开发分支，不需要每次等待「推送」口令。构建 APK、提交、推送、合并 main 和创建 Release 是不同结果，交付时分别报告。GitHub 默认展示 main 的最近提交；只推送开发分支，不会更新 main 的提交时间。当前 CI 在 main、PR 或手动触发时运行，版本 Tag 复用同 SHA 的 main 检查，单独推送开发分支仍以本地检查结果为依据。

同一问题调试过程中可以反复使用同一版本号。0.1.0 已交付给手机后，将完成的修复作为 0.1.1 交付是合理的；无需每改一行就创建新版本。

统一检查使用 `./scripts/check.ps1`；`-Mode Unit` 只跑单测，`-Mode Emulator` 完整检查后安装启动应用，本机缓存可添加 `-Offline`。详见 [CHECKS.md](CHECKS.md)。模拟器启动与 ADB 操作见 [EMULATOR.md](EMULATOR.md)。

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

当前版本位于 app/build.gradle.kts：versionName 0.2.0、versionCode 3。下一次修复发布例如 0.2.1 / 4；新功能里程碑可用 0.3.0 / 4。versionCode 每次发布递增；仅修改文件名不会改变 APK 内部版本。

发布时先更新版本和 CHANGELOG，构建并验收，再将最终提交打 Tag（例如 v0.1.1）。GitHub Releases 对应此 Tag，附 APK、校验值、更新说明与已知问题。公开的源码版本应与安装包对应。

GitHub Release 是版本下载页面，Android release 是应用构建类型，两者不同。测试用 debug APK 也可以附在 GitHub Pre-release 中，并明确其测试性质。面向长期使用者的发行包应使用固定、妥善备份的 release 签名；不要在不同机器上临时生成签名来做覆盖更新。

dist 是本地交付目录，不需要保留每次调试构建。此前保留 0.1.0 和 0.1.1 是用于版本对照；确认新版本后可将旧产物归档或删除。Git 保存源码历史，不能代替保留原签名的已发布安装包；Android 通常也不允许直接将更低 versionCode 的旧包覆盖安装。

当前 .github/workflows/android.yml 在 main 推送、PR 或手动触发时执行构建、单测、Lint，并保存源码身份、APK 校验值与报告。版本 Tag 不再重复构建；发布脚本核对同 SHA 的 main 成功结果，详见 [发布自动化](RELEASE_AUTOMATION.md)。2026-10-20 已完成一次[云端成功验证](https://github.com/docking1177-dot/FloatingFocus/actions/runs/39431207135)。CI 没有配置模拟器或真机测试，不能据此声称功能验收通过。

## 覆盖安装与签名

覆盖更新要求 applicationId 一致（app.floatingfocus）、签名兼容、新版本符合系统版本限制。每次发布递增 versionCode。正常覆盖安装一般保留已有数据和权限，新权限仍可能需要用户授权；安装前结束当前计时，更新后重新开始。

此工作区交付给手机的 0.1.0 使用 .tools/debug.keystore，生成于本机，私钥已被 Git 忽略。后续用同一个本地构建脚本和同一个文件签名，可以继续覆盖测试。不要删除此文件；私钥备份应单独安全保存，不放公开仓库。

Android Studio 的默认 Debug key、另一台电脑生成的 Debug key、GitHub Actions 临时 Debug key都可能不同，不能假设它们可以覆盖当前手机安装包。CI 的 APK 默认用于独立测试。不要把本机 Debug key 上传为公开构建材料。

正式对外持续发行前，应建立专用 release 签名并备份；Debug key 不作为正式发行身份。当前没有配置 release 签名或密钥迁移；0.2.0 以固定本地 Debug 签名的 GitHub Pre-release 交付。切换为不兼容签名时，通常要卸载旧测试包再安装正式包，会清除旧数据；当前版保存预设、常用 App、外观设置和会话记录，卸载前应导出所需 CSV。

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

本机已配置 Android 16 专用模拟器，可通过统一检查入口启动；历史验证见 `docs/EMULATOR_VALIDATION.md`，每次运行仍需确认设备在线。2026-10-23 用户反馈新版在 REDMI K80 / Android 16 上振动成功；这是用户真机反馈，不扩展为全部场景通过。AI 未读取该手机的振动系统日志。

## 发布到 GitHub

本项目 MIT 与第三方归属已保留。上传源码范围：app/src、构建配置及 wrapper、脚本、docs、licenses、README、CHANGELOG、CONTRIBUTING 和 .github；排除 SDK、缓存、上游快照、local.properties、密钥和 dist。

公开仓库为 https://github.com/docking1177-dot/FloatingFocus，origin 已配置。2026-10-21 本机已复用 Git Credential Manager 的 docking1177-dot 账号，原生 `git push --dry-run origin HEAD` 验证通过；凭据由系统凭据管理器保存。仓库本地配置固定账号和已验证的 OpenSSL / HTTP 1.1 连接选项，没有修改全局 Git 配置。

日常同步当前开发分支：

```powershell
git status --short
git diff --stat
# 检查并提交本次相关文件后
git push -u origin HEAD
```

另一台电脑先 clone，再单独登录该电脑的 Git 凭据管理器。需要重新登录时，可运行 `git credential-manager github login --username docking1177-dot --device` 并按终端提示在 GitHub 完成确认。不要运行会打印密码的 `git credential fill`，也不要把访问令牌贴进聊天、脚本或 remote URL。
