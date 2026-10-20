# Floating Focus 开源实现评估

核查日期：2026-10-20。四个仓库均通过 Git 实际拉取，使用 `--depth 1` 固定当前 HEAD；以下是所取版本的源码事实，不代表对其全部历史、所有设备或后续提交的保证。

## 结论

选择 **方案 B：新建单模块 Android 工程，迁移项目 A 的成熟模块**。A 是主要代码来源，但不是原仓库整体 Fork。其 Kotlin/Compose 悬浮视图生命周期、窗口参数、原始触摸坐标拖动以及前台服务通知模式可直接改造。整个应用 Fork 会同时带入多计时器、Room、内购、网络及配色设置，不符合本次范围。

保留窗口机制和 Compose 生命周期所有者；修改拖动边界、触摸阈值和资源释放；新增主界面、单调时间模型、集中提醒规则和四阶段胶囊。没有因为代码旧而换架构，也没有引入 DI、数据库或多模块。

## 版本与维护快照

| 项目 | 检查的 Commit | HEAD 提交日期 | 源码规模 |
|---|---|---|---|
| [A: FloatingCountdownTimer](https://github.com/tberghuis/FloatingCountdownTimer) | `785dbb3dd2362635ca61fcc8fb62de9f66fc8825` | 2026-08-03 | 94 个 Kotlin/Java 文件，6,383 个非空行 |
| [B: floatTime](https://github.com/UokyI/floatTime) | `648e9c4c151c0c814770252e01cf50878c38adc9` | 2026-10-20 | 13 个 Java 文件，2,053 个非空行 |
| [C: Stop-me](https://github.com/afnan-nex/Stop-me) | `eaf5109902548a9b0d668687a349342ef987b3ce` | 2026-08-29 | 70 个 Kotlin/Java 文件，4,970 个非空行 |
| [D: touch-grass](https://github.com/Snownamida/touch-grass) | `53f009865e26df8fd36a3ab2b391b656660b6a5c` | 2026-07-11 | 仅参考公开设计 |

统计范围为 `app/src/main` 内 `.kt` / `.java`，不包含资源、测试和构建脚本。提交日期来自 `git log -1`；浅克隆不足以推断长期维护频率。B 的 HEAD 提交说明涉及 F-Droid 元数据和 wrapper 修复。

## 功能比较

| 功能 | 项目 A | 项目 B | 采用方案 |
|---|---|---|---|
| 悬浮窗 | `TimerViewHolder.kt`：ComposeView、TYPE_APPLICATION_OVERLAY、NOT_FOCUSABLE | `FloatingService.java`：XML + 自绘 LiquidFloatingView，含旧 TYPE_PHONE 分支 | 迁移 A 的 ComposeView 和窗口参数，单窗口 |
| 拖动交互 | `OverlayController.kt`：rawX/rawY 位移、GestureDetector 单/双击、ScreenEz 边界、拖到垃圾桶 | raw 坐标、10px 阈值、长按、双击、贴边动画；使用 LAYOUT_NO_LIMITS | 改造 A 的位移算法，用系统 touchSlop 区分点击；系统 Insets 限制边界，移除垃圾桶/双击重置 |
| 倒计时 | `countdown/Countdown.kt`：CountDownTimer + StateFlow；按秒保存暂停值，源码有精度 TODO | CountDownTimer 回调更新剩余毫秒，带任务历史 | 两者都不是简单每秒减一；本版取消暂停，以 elapsedRealtime 起点计算剩余值和阶段，便于跨休眠校准和测试 |
| 后台服务 | 非粘性 Service，specialUse；绑定、多窗口、通知结束/重置 | START_STICKY，onCreate 创建浮窗，未看到恢复倒计时的持久化状态 | 保留 A 的非粘性及 specialUse 模式；只从可见 Activity 的用户操作启动 |
| 权限申请 | 悬浮窗授权引导、通知权限；Manifest 具备 specialUse | canDrawOverlays、设置页、POST_NOTIFICATIONS、specialUse | 使用系统 API；返回主界面重新检查；拒绝通知可继续使用；运行时监听悬浮权限撤销 |
| 动画 | 圆形进度等，未实现本任务 50%/80% 连续颜色分级 | 液体动画、贴边、“storm”等 | 新增灰→橙→红；仅最后 20% 轻缓透明度呼吸，熄屏停止动画 |
| 代码复杂度 | 功能完整，但 Room、Ktor、Billing、导航、DataStore、颜色库和 ScreenEasy 超出 MVP | 较小，但 Java、自绘动画、历史、配置和设置弹窗仍需删改 | 单模块，AndroidX + Kotlin；主要复用 A，B 不作为业务代码基底 |

## 实际读取的关键文件

A 根路径 `app/src/main/java/xyz/tberghuis/floatingtimer/`：

- `service/TimerViewHolder.kt`：窗口参数、LifecycleRegistry、SavedStateRegistry、ViewModelStore、ComposeView 树所有者。
- `service/OverlayController.kt`：增加/删除 bubble、raw 坐标拖动、单击/双击、屏幕边界、解锁刷新。
- `service/FtWindowManager.kt`、`service/FloatingService.kt`：窗口操作、前台服务时机、通知、onDestroy 和 configuration 回调。
- `service/countdown/Countdown.kt`：暂停/恢复/完成及 CountDownTimer 的状态协作。
- Manifest、构建脚本、版本目录、settings、`LICENSE.txt`。

B 根路径 `app/src/main/java/com/floattime/app/`：

- `FloatingService.java`：onCreate/onStartCommand/onDestroy、窗口创建、拖动、倒计时、震动和通知。
- `MainActivity.java` 中权限检查、设置跳转和启动服务路径。
- Manifest、app/build.gradle、Gradle wrapper 属性、`LICENSE`。

C：读取 `service/overlay/OverlayManager.kt`、`feature/overlay/WarningOverlayContent.kt`、构建脚本和版本目录。其最后 30 秒警告可切换 Pill/Minimal/Bold，使用 Compose 状态更新已存在视图；未在这些文件发现本任务的渐进颜色/呼吸机制。Hilt、Room、WorkManager、无障碍、应用追踪和强制阻止超出范围，不引入。

D：拉取后只阅读 README 的公开设计、构建说明和许可证文件清单，不迁移源码。借鉴常驻药丸与分级提醒的产品思路；不采用无障碍识别、统计和全屏拦截。

## 构建、依赖与技术债

- A：compileSdk 37 / targetSdk 36 / minSdk 29，AGP 9.3.1、Kotlin 2.4.10、Gradle 9.6.1；存在本地 `flatDir` 颜色组件。依赖版本新并不等于已验证稳定。本环境没有完整构建原版 A，不能宣称其原版可构建。
- B：compile/target 34、min 24，Gradle 8.2，Java 17，AppCompat 1.6.1、Material Views 1.11.0。对现代版本的 source-level 适配不等于 Android 15/16 真机认证；本环境没有完整构建原版 B。
- C：compile/target 36，而版本目录 AGP 8.7.3；该组合需要额外兼容性核查。未构建原版 C。D 未构建。
- A 的触摸 MOVE 不使用系统 touchSlop；LifecycleOwner 销毁时没有清空 ViewModelStore；service.onDestroy 只注销 OverlayController 的 receiver，未在该路径显式遍历移除窗口。迁移时补齐这些释放路径。
- A 在 foreground 启动异常路径尝试依靠添加视图后再次启动；本版固定从可见 Activity 按钮启动，失败即结束，不吞掉后等待系统超时。
- B 使用 FLAG_LAYOUT_NO_LIMITS，可能越过屏幕安全边界；START_STICKY 重建未恢复计时，不能直接用于需要明确状态的单次会话。
- 本工程选用 AGP 8.10.1、Gradle 8.11.1、Kotlin 2.1.20、Compose BOM 2025.05.01、compile/target 36，最低 Android 10。AGP 8.10 支持 API 36、要求 Gradle 8.11.1 和 JDK 17；本机使用兼容的 JDK 21。参见 [AGP 官方兼容表](https://developer.android.com/build/releases/agp-8-10-0-release-notes)。

完整工程的实际构建/测试结论单独记录于 [TEST_RESULTS.md](TEST_RESULTS.md)，不把源码评估等同运行验证。

## Android 14—16 的选择

连续展示、由用户主动开启且可随时结束的计时浮窗，使用 `specialUse` 并声明解释用途的 property。不是 dataSync、mediaPlayback，也不使用有三分钟限制的 shortService；上架 Google Play 时 specialUse 用途仍需平台审核。参见 [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)。

Android 15 收紧“有悬浮权限即可从后台启动 FGS”的豁免：必须已有可见浮窗。本版在可见 Activity 内主动启动，不依赖该后台豁免。[Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15)。

Android 16 的 JobScheduler 配额变更不直接涉及本版，因为没有后台 Job/WorkManager；compile/target 36 并处理主界面安全 Insets。仍需 Android 14、15、16 真机验证。[Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-all)。

## 许可证结论

- A：仓库含完整 MIT，Copyright (c) 2022 Thomas Berghuis。允许修改、分发和商业使用，须保留版权及许可。已保留原文件，修改文件带来源头。
- B：完整 MIT，Copyright (c) 2026 Uoky。没有复制其业务代码；复制的 Gradle wrapper 是 Gradle 的 Apache-2.0 构建工具，另行归属。
- C：README 声称 MIT，但此 commit 根目录缺少其链接的 LICENSE 文件。授权文本不完整，本版不复制 C 的代码。
- D：此 commit 未发现明确的 LICENSE，不能把公开可读等同复用授权；只参考产品思路。
- 最终依赖及许可证另见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。没有将未迁移的原项目依赖一并打包。
