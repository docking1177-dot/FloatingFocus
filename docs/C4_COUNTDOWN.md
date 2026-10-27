# C4 倒计时（开发分支）

## 行为与边界

设置 → 提醒 → 末段特效：C4 默认关闭，音效默认开启（关闭 C4 时隐藏音效项）。两项独立保存在 `c4_settings`，不进入预设快照；修改全局开关即时影响正在进行的倒计时。正式版可用，无新权限、服务、依赖或自动监测。

只替换倒计时最后 40 秒的视觉和音效；60 秒计时在剩余 40 秒进入，40/30/10 秒从对应剩余时间进入。保留原形状、位置、基础尺寸、动态尺寸及整体透明度；期间暂停原有背景闪烁，避免两套动画叠加。归零恢复原外观，继续原超时计时及单次震动；原来没有结束提示音，因此不存在另一段结束音同时播放。

启用 C4 后，倒计时展开浮窗提供暂停/继续；暂停冻结倒计时、LED 和声光调度，停止当前短音。恢复不会重播已经消费的滴声，从下一节点继续。关闭 C4 后已暂停的计时仍可继续。提前结束、划掉 FF 或服务销毁会释放播放器，不播放爆炸。加时沿用同会话的新计时段，重置该段的 C4 消费状态。正计时排除。

`Countdown` 算法保持不变，`SessionClock` 把暂停区间从单调采样时钟扣除。历史记录仍是既有 FF 会话墙钟时长（含暂停停留），不是目标 App 的前台使用时长；不会把暂停功能误当作新增 App 监测。休眠可能延后回调，不保证精确闹钟或系统杀进程后的播放。

## 还原依据及明确偏差

- [Valve 2023-10-17 官方更新](https://store.steampowered.com/news/posts/?appids=730&enddate=1697746392&feed=steam_community_announcements)确认 CS2 最后 10 秒使用新声音。它没有提供逐次滴声/LED 的精确时间轴。
- [用户提供的官方更新汇总](https://store.steampowered.com/news/posts/?appids=730&enddate=1699055103&feed=steam_community_announcements)包含上述更新。
- [CS2 schema 镜像](https://github.com/SteamTracking/GameTracking-CS2/blob/master/DumpSource2/schemas/client/C_PlantedC4.h)可见 NextGlow、NextBeep 和 TenSecWarning 字段，但不包含实现，不能据此推导间隔或证明原版声光完全同步。
- [2017 旧版参考代码](https://git.botox.bz/BotoX/hl2_src-leak-2017/src/branch/botox/src/game/client/cstrike/c_plantedc4.cpp)不属于当前 CS2。本项目没有复制该文件的实现或素材。

**当前未获得可核对的当前 CS2 40 秒录制及逐帧/逐音时间轴，因此这是可配置的近似特效，不宣称原版精确还原。** 默认连续加速采用需求给出的旧版参考公式 `max(150, 100 + 0.9 × remainingMillis / 40)`（毫秒），不是三个固定慢/中/快阶段。最后 10 秒切换本项目原创双脉冲高音，同时延长 LED 脉冲至 120ms；这部分音色、频率、脉冲宽度、爆炸音均未与 CS2 实测对齐。示意装置是原创简化矢量图，不是游戏模型/贴图。

后续拿到合法可用的录制后，应标注游戏构建/录制条件，提取每次声音及 LED 时间点，与本实现日志比较后替换配置；不能把现有近似值反标为测量结果。

## 配置、音频与职责

- `C4SettingsStore.kt`：全局开关持久化；`ReminderEffectsScreen.kt`：提醒设置入口。
- `C4Timeline.kt`：纯时间轴、每段去重状态、暂停时钟。`assets/c4_profile.json`：间隔/最后阶段/容许迟到参数；`measuredRemainingMillis` 可填严格递减的实测剩余毫秒值，优先替代公式。校验非法配置后记录警告并回退近似值。
- `C4Runtime.kt`：现有 `FloatingService` 拥有的控制器，按单调时钟算下一事件，一次消费当前事件，不追补积压声音。音频加载未就绪不提前消费当前节点；超过迟到容限则跳过。LED 和 SoundPool 请求在同一次回调开始，LED 持续对应短音时长；不承诺硬件输出延迟为零。
- `C4Audio.kt`：统一可替换声音表，SoundPool 单声道播放流、预加载、音频焦点与清理。使用媒体音量；静音、振动模式、勿扰、媒体音量为零或焦点丢失时不播放，不写系统音量，不绕过系统限制。参考 [SoundPool](https://developer.android.com/reference/android/media/SoundPool) / [AudioFocusRequest](https://developer.android.com/reference/android/media/AudioFocusRequest)。
- `C4Device.kt` / `FocusOverlay.kt`：在同一个现有浮窗中绘制，渲染不推进时间轴；折叠/展开仍使用原交互和位置锚点。

三个 `res/raw/c4_*.wav` 是 `scripts/generate-c4-audio.py` 确定性合成的原创 PCM 短音：80ms 普通音、120ms 最后阶段音、900ms 爆炸示意音，适用项目 MIT 许可。无需下载资源，可用 `python scripts/generate-c4-audio.py` 重建。替换音频须先确认授权，保持短音不超过对应脉冲及最短间隔；不要公开打包未经授权的 Valve 原始素材。

## 验证入口

- `./scripts/check.ps1 -Offline`、Release 对应检查：编译、JVM 时间轴/暂停/去重/短预设兼容及 Lint。
- `python -m unittest discover -s scripts/tests -v`：含音频格式/时长与时间轴配置一致性。
- 专用模拟器 `./scripts/test-device.ps1 -Suite c4 -AllowTestDataChanges`：真实设置默认值/持久化，预设启动 Gallery 后台运行，60/40/30/10 秒自然归零，40 秒边界同窗口/同几何，暂停/恢复/取消、静音效、关 C4、正计时；自动备份还原数据，保存日志/截图和源码/APK 身份。

模拟器日志 `played=true` 只证明 SoundPool 接受请求，不能证明真机扬声器可听、硬件延迟或主观原版还原度。真机需另验音量/静音/勿扰/来电焦点、厂商后台限制及听感。实际执行结果见开发交付报告，不把这些入口存在写成已通过。
