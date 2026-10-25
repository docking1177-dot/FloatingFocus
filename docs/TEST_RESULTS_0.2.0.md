# 0.2.0 公开测试版验证

日期：2026-10-25。versionName 0.2.0，versionCode 3，包名 app.floatingfocus，最低 Android 10 / API 29，targetSdk 36。

## 本轮发布检查

- Debug 构建、75 项 JVM 测试、Lint 通过；0 错误，保留 1 项既有 Gradle 更新警告。
- Release 构建、69 项 JVM 测试、Lint 通过；0 错误、0 警告。公开下载的是下述固定签名 Debug 包。
- APK 版本／包名与预期一致，apksigner 校验通过，签名与之前交付的 0.1.0／0.1.1 Debug 包一致。zipalign 4-byte／16KiB 检查通过。
- Android 16 / API 36 后台模拟器从 0.1.1 / 2 覆盖更新到 0.2.0 / 3；预设、外观、常用 App 配置字节哈希一致，已有 14 条记录保留。
- 新版实际完成绑定预设一键启动 Gallery、悬浮显示、手动结束、记录新增与历史页面打开；没有 AndroidRuntime FATAL EXCEPTION。

## 功能回归证据

发布前版本号为 0.1.1，功能源码与本版一致。本轮只更改版本元数据与发布文档，复用此前实际回归，不将它们写成本轮真机结果：

- [正计时、超时、记录、CSV、进程退出恢复](validation/time-records/results.json)。
- [预设绑定与同一会话加时](validation/preset-extension/results.json)。
- [三形状贴边 24 次展开／收起、浮窗内加时与尺寸恢复](validation/overlay-fixes/results.json)。
- [双列预设、色阶与两次提亮、系统动画开关](validation/color-grid/results.json)。
- 本轮结构化结果：[release-0.2.0/results.json](validation/release-0.2.0/results.json)。

## 交付与限制

交付 `FloatingFocus-0.2.0-debug.apk`，使用固定工作区测试签名，包含 Debug 提醒实验室，以 GitHub Pre-release 发布。Release 构建未配置正式签名，不作为可安装的正式发行包上传。

APK SHA-256：`67ccb3183f8e9efcbf18c8c77b0427992f970d08a7605b8df30ff3124c8d3637`。

未完成 REDMI K80 的最新完整真机回归。此前用户确认振动成功，不扩展为全部模式通过。记录属于 FF 会话；强杀只保证最后持久化检查点，不能精确捕获强杀时刻；深度休眠可能延迟到期提醒。未新增前台 App 监测、无障碍或后台保活。

云端检查在 main 和 v0.2.0 标签推送后运行；实际结果见对应 [GitHub Actions](https://github.com/docking1177-dot/FloatingFocus/actions/workflows/android.yml)。CI 使用临时 Debug 签名，其 artifact 不能假定可覆盖本机签名的旧安装包。
