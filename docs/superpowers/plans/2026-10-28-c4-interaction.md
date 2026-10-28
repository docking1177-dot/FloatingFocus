# C4 Interaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution or superpowers:subagent-driven-development if selected. Steps use checkbox syntax for tracking.

**Goal:** 稳定 C4 两种形态并支持双向切换、清晰键帽与拆除语音。

**Architecture:** 复用现有服务、窗口、计时与 MediaPlayer。渲染参数、形态状态和成功语音收尾分别负责显示、交互和资源生命周期。

**Tech Stack:** Kotlin / Compose / WindowManager / MediaPlayer / JVM JUnit / 既有 Python 设备回归。

**Spec:** ../specs/2026-10-28-c4-interaction-design.md；用户已确认整组重要行为。

## Global Constraints

- feat/c4-countdown；不合并 main、不发布、不改版本。
- 不添加权限、服务、依赖或预设字段；本机 Debug 音频不提交。
- 44.439 秒、4.92 秒、7355608、220ms 成功显示保持；正常结束限制不因收起取消。

## Review Focus

- 最后 20% 重复展开收起不得继承或累乘倍率。
- 部分输入后收起、旋转、再展开不能丢输入或重播。
- 缝隙、滑动、多指不得触发收起或意外按键。
- 旧语音完成/异步准备回调不得停止新会话。
- 成功后的撤权、划掉任务、播放失败仍须清理，记录不得继续累计。

## Task 1：稳定参数与形态切换

**Files:** ReminderPresentation.kt、FocusOverlay.kt、C4Device.kt、FloatingService.kt、OverlayWindow.kt、OverlayTouchLayout.kt；C4TimelineTest.kt / scripts/device_c4.py。

**Interfaces:** ReminderPresenter.forC4(frame: C4Frame, settings: ReminderSettings): ReminderPresentation；FocusOverlay 增加 c4Expanded 与 onC4Toggle；C4DevicePanel 增加 onToggle。计时输入仍由原 C4Runtime 持有。

- [ ] 新增纯参数测试：两种普通动态效果同时开启，C4 在不同剩余时间仍等于 baseSize / 基础颜色 / 原 opacity，且普通超时仍按原规则变化。运行 Unit 确认新断言失败。
- [ ] 实现纯映射与默认收起的会话显示状态；切换只修改显示状态，不调用 Runtime 初始化、开始或 seek。
- [ ] 常态复用原形状布局，仅显示时间，不出现普通控制；完整形态实体非键区无波纹轻触切换。拖动由原宿主取消点击。
- [ ] 更新设备断言：最后 20% 多次切换 frame 尺寸稳定；输入三位后收起再展开继续成功，检查窗口 ID / 记录 / 播放起点不重置。
- [ ] Unit 与设备相关路径通过后继续。

## Task 2：键帽与手势几何

**Files:** C4Geometry.kt、C4Device.kt、OverlayTouchLayout.kt；C4TimelineTest.kt / scripts/device_c4.py。

**Interfaces:** C4Geometry 提供统一 keyboardPanel 区域，绘制与宿主共用；键触摸槽和可见键帽分别定义，不把视觉尺寸当触摸尺寸。

- [ ] 新增几何断言：44dp 目标、不重叠、键帽小于槽、横竖间隙增加、正常横屏底行处于可用区域。先确认失败。
- [ ] 缩小键帽、保留数字字号并增大视觉间距；不增加整个装置尺寸。
- [ ] 整个 keyboardPanel 吞掉缝隙点击，禁止拖窗/收起，保留极短视口内部滚动。实体拖动/透明区域沿用原路径。
- [ ] 设备真实点击键帽/缝隙、滑动及旋转，确认无误切换；分别检查透明像素与下层点击。

## Task 3：成功语音收尾与交付

**Files:** C4Audio.kt、C4Runtime.kt、FloatingService.kt、ReminderEffectsScreen.kt；scripts/source_state.py、scripts/tests/test_c4_assets.py、scripts/device_c4.py；AGENTS.md、docs/C4_COUNTDOWN.md、CHANGELOG.md。

**Interfaces:** C4Audio.playDefused(onFinished: () -> Unit)；C4Runtime.releaseForDefuse(onFinished: () -> Unit): C4Audio? 转交现有音频所有权。服务保留 successAudio 与收尾代次，旧代回调不可结束新会话。

- [ ] 扩展设备测试预期：成功一次、无爆炸、220ms 后无窗但声音收尾仍在；记录值立即固定；新计时停止旧语音，旧完成回调不关闭新浮窗。先在现有 APK 验证相关新断言失败。
- [ ] 将成功文件复制到忽略目录；build_inputs 增加该文件哈希，素材测试确认 Release 隔离不依赖录音存在。
- [ ] 复用播放器，成功先停原片再播 defused；关闭音效/缺文件/失败即时完成收尾。播放器完成不改变记录。自然到期、错误或异常不进入成功播放。
- [ ] 将原 finishSession 的结算、移除浮窗和结束服务拆成必要步骤；220ms 后对外发布空闲，新会话启动关闭旧播放器并使旧收尾回调失效。所有系统清理路径可取消收尾。
- [ ] 更新首次启用说明，去除旧自动展开/不能收起描述；更新指南、文档及待发布日志。
- [ ] 执行 check.ps1 -Offline、Release Full、Python 脚本测试；设备 c4 / smoke / edges / inline。检查新缺文件、静音及收尾期系统清理路径；截图与听感分开报告。
- [ ] 审查最终差异及素材清单；按实际检查绑定源码/APK 证据，提交并推送当前分支，不创建 Release。
