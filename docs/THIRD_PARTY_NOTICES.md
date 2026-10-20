# 第三方代码与依赖

本工程自身新增代码使用根目录 MIT License。第三方内容保留各自许可证；MIT 主工程不会把依赖重新许可为 MIT。

| 内容 | 来源 / 版本 | 许可 | 分发方式 |
|---|---|---|---|
| 悬浮 Compose 宿主、拖动和服务模式的适配代码 | Thomas Berghuis / FloatingCountdownTimer，commit `785dbb3dd2362635ca61fcc8fb62de9f66fc8825` | MIT，Copyright (c) 2022 Thomas Berghuis | 许可原文在 `licenses/FloatingCountdownTimer-MIT.txt`，衍生源码有说明 |
| Compose / Material 3 | AndroidX，BOM 2025.05.01 | Apache-2.0 | 实际选中的 AndroidX 模块通过 Gradle 引入 |
| Activity Compose | AndroidX 1.10.1 | Apache-2.0 | 运行时依赖 |
| Lifecycle | AndroidX 2.9.0 | Apache-2.0 | 运行时依赖 |
| SavedState | AndroidX 1.3.0 | Apache-2.0 | 运行时依赖 |
| Core KTX | AndroidX 1.16.0 | Apache-2.0 | 运行时依赖 |
| Kotlin stdlib、JetBrains annotations | Kotlin 插件 2.1.20 及解析的传递依赖 | Apache-2.0 | 运行时依赖 |
| kotlinx.coroutines | AndroidX 传递依赖 | Apache-2.0 | 运行时依赖 |
| kotlinx.serialization 1.7.3 | AndroidX 传递依赖 | Apache-2.0，已核对解析版本的 Maven POM | 运行时依赖 |
| JSpecify 1.0.0 | AndroidX 传递依赖 | Apache-2.0，已核对解析版本的 Maven POM | 运行时依赖 |
| Guava ListenableFuture | AndroidX concurrent-futures 传递依赖 | Apache-2.0 | 运行时依赖 |
| Gradle wrapper | 从 floatTime commit `648e9c4c151c0c814770252e01cf50878c38adc9` 复制，分发版本固定 8.11.1 | Apache-2.0 | wrapper 脚本保留原版权头；仅用于构建 |
| JUnit 4.13.2 | JUnit | EPL-1.0 | 仅测试，不进入 APK |
| Hamcrest | JUnit 的测试传递依赖 | BSD-3-Clause | 仅测试，不进入 APK |

Apache-2.0 全文保留在 `licenses/Apache-2.0.txt`。`licenses/` 通过 Android assets 打进 APK，可在应用「开源许可」中查看。未设置删除第三方许可证的 packaging excludes。

本次 `debugRuntimeClasspath` 实际解析的 88 个组件坐标见 [runtime-dependencies.txt](validation/runtime-dependencies.txt)。其中包含 BOM 和多平台元数据组件，不代表 APK 内有 88 个独立二进制库。

本应用没有使用原项目的名称、图标或商标来暗示官方关系。A、B 原版中的 Room、Billing、Ktor、颜色组件、ScreenEasy 等未被引入，本表不替这些未使用组件作许可证结论。没有复制 Stop-me / touch-grass 的代码。

许可证来源：

- [FloatingCountdownTimer MIT](https://github.com/tberghuis/FloatingCountdownTimer/blob/785dbb3dd2362635ca61fcc8fb62de9f66fc8825/LICENSE.txt)
- [AndroidX 源码许可证](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
- [Kotlin 许可证](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)
- [kotlinx.coroutines 许可证](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)
- [JetBrains annotations 许可证](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt)
- [Gradle 许可证](https://github.com/gradle/gradle/blob/master/LICENSE)
- [JUnit 4 许可证](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt)
- [Apache-2.0 原文](https://www.apache.org/licenses/LICENSE-2.0.txt)

AGP、Gradle 分发包、JDK、Android SDK 仅存在开发缓存中，不随项目源代码包或 APK 分发。
