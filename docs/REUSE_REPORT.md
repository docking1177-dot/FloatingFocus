# 开源代码复用与改动记录

## 主来源

FloatingCountdownTimer，作者 Thomas Berghuis，MIT。

- 仓库：https://github.com/tberghuis/FloatingCountdownTimer
- 固定版本：`785dbb3dd2362635ca61fcc8fb62de9f66fc8825`
- 原始快照：本地 `.upstream/FloatingCountdownTimer`（开发参考，不作为发布包组成部分）。
- 实施策略：方案 B，迁移可独立维护的模块。不是原版应用的完整 Fork，也不声称是原作者官方版本。

## 文件对应

| 当前文件 | 原文件 | 复用方式及必要变更 |
|---|---|---|
| `app/src/main/java/app/floatingfocus/OverlayWindow.kt` | `service/TimerViewHolder.kt`、`service/OverlayController.kt` | 改造复用 LifecycleRegistry/SavedStateRegistry/ViewModelStore 的 ComposeView 宿主、窗口参数、原始触摸坐标位移算法。单窗口替换 bubble 集合；去除垃圾桶/双击/ScreenEasy；使用系统 Insets、touchSlop；显式清理 composition 和 ViewModelStore |
| `app/src/main/java/app/floatingfocus/FloatingService.kt` | `service/FloatingService.kt` | 适配复用 specialUse 分版本调用、PendingIntent 通知操作、START_NOT_STICKY 和配置变化回调模式。移除绑定和多计时器依赖，增加撤权监听、单会话更新、一次震动和完整停止路径 |
| `licenses/FloatingCountdownTimer-MIT.txt` | `LICENSE.txt` | 原文完整复制，不修改作者声明 |
| `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` | floatTime commit `648e9c4c151c0c814770252e01cf50878c38adc9` 同路径 | wrapper 文件原样复制；属于 Gradle 构建工具，Apache-2.0。属性文件新建并固定 Gradle 8.11.1 |

没有原样复制 A 的完整业务源文件。上表两个 Kotlin 文件为经过删减、整合和适配的衍生实现，不能将其标成“完全原创”。原版 CountDownTimer 本身不是简单回调减一；替换它是为了去除秒级暂停状态与 Bubble/报警/数据库耦合，并为本任务提供可独立测试的实际经过时间和提醒阶段。

## 新增模块

- `Countdown.kt`：纯 Kotlin 单调时间模型、显示取整、集中提醒阈值。
- `FocusOverlay.kt`：圆角胶囊、展开操作、四阶段颜色和最后阶段呼吸。
- `MainActivity.kt` / `FocusTheme.kt`：Material 3 主界面，预设/自定义时长，权限指引。
- `CountdownTest.kt`：阶段边界、跳过刷新/休眠、时长校验与显示测试。
- 新 applicationId `app.floatingfocus`、名称 Floating Focus、自制矢量图标。
- 构建配置、构建脚本、安装/验证文档。

## 未引入内容

不引入 A 的 Room、Billing、Ktor、DataStore、复杂导航、颜色库、ScreenEasy、秒表、多计时器或品牌资源。不复制 B 的 Java 业务代码，不复制 C、D 的源码。

## 发布归属

源码头及 `licenses/` 保留 Thomas Berghuis 的 MIT 版权许可；应用分发应同时带上第三方声明。构建工具与 AndroidX/Kotlin 的许可独立列出，不将上游所有依赖笼统称为 MIT。见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
