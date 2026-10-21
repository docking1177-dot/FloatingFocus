# 本地 Android 模拟器

模拟器与系统镜像位于 `.tools/android-sdk`，AVD 位于 `.tools/avd`，均被 Git 忽略。默认设备为 `FloatingFocus_API_36`（Android 16 / API 36，AOSP x86_64），2.5 GiB 内存、2 个 CPU 核心；不需要 Google 账号或 Play 商店。

## 创建（本机已安装后无需重复）

PowerShell，在工程根目录执行；JAVA_HOME 指向现有 JDK 17/21：

```powershell
$env:ANDROID_HOME = Join-Path $PWD '.tools/android-sdk'
$env:ANDROID_USER_HOME = Join-Path $PWD '.tools/android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $PWD '.tools/avd'
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root=$env:ANDROID_HOME 'emulator' 'system-images;android-36;default;x86_64'
'no' | & "$env:ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat" create avd -n FloatingFocus_API_36 -k 'system-images;android-36;default;x86_64' -d pixel_5
& "$env:ANDROID_HOME/emulator/emulator.exe" -accel-check
```

下载使用现有网络配置；代理参数如有需要，仅给该次 sdkmanager 命令设置。Windows 使用 WHPX 加速，不需要关闭已有 Hyper-V 或安全功能。

## 启动与安装

```powershell
# 后台运行，AI 可通过 ADB 获取截图和输入
.\scripts\start-emulator.ps1
# 需要在桌面查看时，先停止后台实例，再运行
# .\scripts\start-emulator.ps1 -ShowWindow
$adb = Join-Path $PWD '.tools/android-sdk/platform-tools/adb.exe'
& $adb devices -l
& $adb -s emulator-5554 shell getprop sys.boot_completed
# 上一条输出 1 后再安装；明确指定设备，避免影响已连接的手机
& $adb -s emulator-5554 install -r dist/FloatingFocus-0.1.1-debug.apk
& $adb -s emulator-5554 shell am start -n app.floatingfocus/.MainActivity
```

正常使用时通过应用入口授予悬浮权限。自动化回归可在这个专用测试模拟器上执行 `adb -s emulator-5554 shell appops set app.floatingfocus SYSTEM_ALERT_WINDOW allow`，再重新打开应用；这只用于布置测试环境，不替代权限引导流程的测试。

停止释放内存：

```powershell
& $adb -s emulator-5554 emu kill
```

模拟器用于安装启动、截图、点击/拖动、跨 App 与日志检查；真实震动体验、厂商后台管理、耗电与正式发布仍需目标真机验证。日常改动使用增量构建与 ADB 覆盖安装；不必每次复制 APK 到 dist、增加版本或发布 Release。
