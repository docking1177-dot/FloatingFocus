# 0.1.1 拖动修复验证

日期：2026-10-20。版本：0.1.1 / versionCode 2。

## 修复范围

原先触摸监听只绑定标题 TextView。现在由承载整个 ComposeView 的 OverlayTouchLayout 接收手势，覆盖标题、展开后的按钮、到期提示及窗口内留白；窗口尺寸改变后无需重新定义拖动热区。

移动超过系统 touchSlop 时向子控件发送 ACTION_CANCEL，再由窗口处理剩余拖动；普通轻触继续交给原控件。拖动中回到起点不会恢复点击，多指加入则取消本次交互。窗口位置仍按当前实际宽高约束在可见范围内。

## 已执行

| 检查 | 结果 |
|---|---|
| assembleDebug / testDebugUnitTest / lintDebug | BUILD SUCCESSFUL in 28s |
| JUnit | 9 项通过，0 失败、0 错误、0 跳过 |
| Lint | 0 错误、8 个依赖更新警告、1 个 KTX 建议 |
| APK 签名 | v2 验证通过；证书与已交付 0.1.0 相同 |
| APK 包信息 | app.floatingfocus，0.1.1 / 2，minSdk 29，targetSdk 36 |
| ZIP CRC / 16 KB ZIP 对齐 | 通过 |
| APK 内许可文件 | 与当前 licenses 源文件逐字节一致 |

新增 5 项 JVM 测试覆盖轻触抖动、拖动首次取消子控件、拖回原点、多指/系统取消状态复位、末次位移及结束后的状态。它们验证手势状态模型，不模拟 Android/Compose 实际触摸分发，不代表上述按钮和文字区域已在设备上通过验收。

容器只能通过代码注入回调创建，不用于 XML 布局；因此仅在该类上注明并抑制 ViewConstructor 布局工具提示。没有全局关闭 Lint，也没有引入新依赖。

证据：[构建日志](validation/0.1.1/build.log)、[拖动测试](validation/0.1.1/TEST-app.floatingfocus.OverlayDragGestureTest.xml)、[计时测试](validation/0.1.1/TEST-app.floatingfocus.CountdownTest.xml)、[Lint](validation/0.1.1/lint.txt)、[签名](validation/0.1.1/signature.txt)、[包信息](validation/0.1.1/badging.txt)、[对齐](validation/0.1.1/alignment.txt)、[APK 校验](validation/0.1.1/apk.json)。本机路径及主机名已脱敏；构建中的 analytics.settings 写入提示不影响结果。

## 交付与待验收

- 文件：`dist/FloatingFocus-0.1.1-debug.apk`，24,574,643 字节。
- SHA-256：`f194c4ca25f224b4f482a6695818475da9a683c6cba3a507bd57401b6f1997d7`。
- 签名证书 SHA-256：`2119d9676b8cbf5a1efee1f01b74d0846cd5573d4f18f6faf64a73146158d11a`。
- 相同包名、相同签名、versionCode 从 1 增至 2，可用于覆盖此前本机交付的 0.1.0；先结束当前计时，再安装并重新开始。

本次 ADB 未发现已连接设备；没有运行 Android 模拟器或真机测试，也未取得运行画面。请按[真机清单的 0.1.1 回归项](DEVICE_TEST_CHECKLIST.md#011-拖动回归待执行)，重点检查从「结束计时」「收起」「给眼睛一个休息的机会」「知道了」起拖，以及轻触是否仍执行原操作。

GitHub CI APK 使用临时 Debug 签名，不应代替上述本地 APK 覆盖手机现有版本。
