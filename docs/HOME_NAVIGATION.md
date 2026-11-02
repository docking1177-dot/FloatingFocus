# 首页统一计时与预设

首页只有「计时／预设」两个栏目。点击与内容区左右滑动共用原生HorizontalPager状态，切换顺序遵循保存值。

- 计时：一块连续面板，先选择正/倒计时、常用或自定义时长，再选择启动方式。主按钮仅开始计时，App图标立即以同一套当前参数计时并打开App，正计时同样有效。
- App四列网格嵌入参数下方，以轻分隔线区分，保留名称、管理和末尾添加。共同说明只出现一次，不重复参数摘要；App多时随整页纵向浏览，不嵌套滚动。管理中的每日额度仍可达。
- 预设：继续使用保存的计时、外观、App及C4配置；保存当前、管理与新建保持原路径。

「手动设置」仍在计时页管理四档及启动默认值。自定义时长独立记住最近选择，首次1分钟。与自定义行相同的已选时长不重复展示；启动默认值不同于该行时，保留必要的当前时长摘要。

## 顺序迁移与状态

默认顺序计时/预设；全新首页打开保存顺序的首项。长按标签左右拖动、松开保存，取消不写入；读屏保留前移/后移。返回设置、后台或旋转时按栏目ID恢复，排序不改变当前栏目，滚动按ID保存；模式/时长始终位于页面外共享，不因切栏重置。

`home_navigation.order`独立于计时默认值、App顺序和预设。有效旧三栏排列先将apps映射timer，再保留第一次出现去重，如apps,presets,timer→timer,presets，presets,apps,timer→presets,timer；新两栏直接恢复。畸形/缺项恢复timer,presets，归一结果写回。旧恢复选择apps进入timer，不能出现空白页。旧App页滚动不强行套给内容已改变的新面板，原计时及预设滚动仍按ID恢复。

## 会话与权限

共同活跃状态和结束/加时操作仍位于分页上方。两种启动入口共用当前选择和已有启动门禁，活跃会话不被替换；启动中禁点击、滑动与排序，排序/分页手势互斥。缺悬浮权限的提示靠近开始操作，授权后仍需主动启动。提醒节点冲突阻止两个入口；失败仍由已有启动器回退，不产生有效会话。预设校验、C4归零前口令及全阶段禁暂停/加时保持原服务守卫。

Debug/Release共用页面；Debug预设样式只改变预设内部渲染。没有新增服务、权限、监测或平行参数系统。

源码：`HomeNavigation.kt`/`HomeNavigationStore.kt`负责迁移及持久化，`HomeEntryPager.kt`/`HomeEntryTabs.kt`负责原分页与排序，`MainActivity.kt`组合页面，`QuickLaunchCard.kt`复用App管理与启动。

验证入口：HomeNavigationTest、`home_unified`、`home`、`home_swipe`、`startup`和`preferences`设备套件。本轮身份/实际结果见[验收记录](validation/20261102-unified-timer.json)，不能将历史三栏通过视为本轮两栏通过。

## 分栏初版验证（2026-11-01，历史）

- Debug完整检查173项、Release完整检查165项，失败/错误为0，Lint错误/警告为0。原摘要为`.tools/checks/check-full-debug-20261101-101634-760.json`和`check-full-release-20261101-102215-219.json`。
- 脚本回归36项通过，日志`.tools/checks/script-tests-20261101-home-tabs-final.log`。
- `home_reorder`专项完成已选App双向排序两轮：`.tools/device-checks/20261101-102026/results.json`。它只验证手势，不替代整套首页。
- 完整`home`通过：`.tools/device-checks/20261101-102300/results.json`。包含三栏互斥、模式/时长跨栏、真实长按排序/取消、冷启动首栏、返回/实际横竖屏、2倍字号标签触摸范围与滚动恢复、活跃会话守卫、预设及快捷App实际启动、管理和权限拦截；原始数据已还原。
- 已核对本批实际预设/App及两倍字号计时截图。切栏陈旧绘制和反向排序错误的失败/复测记录保留在测试库；真机触摸手感未执行。

切栏残影的本地现象与[AndroidX图层失效修复](https://android.googlesource.com/platform/frameworks/support/+/fac106bd0c14de492d2301ee746aef98a8329c01%5E1..fac106bd0c14de492d2301ee746aef98a8329c01/)吻合：控件树已变而滚动内容仍绘制旧图。当前以栏目ID隔离布局生命周期，状态holder留在外部；实际截图与滚动恢复已复测。排序对象在DOWN时锁定，避免长按识别时手指已移动而选错邻项。

未解决的既有P2：2倍系统字号下四个等宽时长按钮的部分末字裁切，记录为`FF-DURATION-FONT-001`及`FF-ISSUE-DURATION-FONT`，不把标签尺寸断言当作所有文字可读性通过。本轮未重设计时长按钮。
