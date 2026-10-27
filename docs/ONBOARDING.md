# 首次使用教程

四页全屏教程使用现有 Compose Foundation `HorizontalPager`，滑动、按钮、顶部圆点和返回键共用一个可恢复的 `PagerState`。前三页可跳到第四页；第四页可授权或「暂不授权」进入首页。首屏返回退出而不标记完成；后续页面返回上一页。设置 → 使用教程可重看；首屏返回回到设置，完成后回到已有主页，不改原配置、预设和记录。

## 状态与权限

`OnboardingStore` 复用 SharedPreferences / StateFlow，独立保存 `onboarding/completed`，不关联版本号，不缓存权限。显式选择「开始使用」或「暂不授权」才标记完成，系统授权本身不自动结束教程。未完成时重新启动仍显示教程；系统重建通过 Compose 保存当前页。

旧版本没有教程标记时，若已有正式设置、预设、快捷 App、常用时长、C4 设置、首页权限询问标记或计时记录，首次迁移为已完成，避免更新强制打断老用户。仅有空目录或空偏好不视为使用过。已有明确的完成/未完成标记始终优先，不因升级重新判定；清除数据/重装后重新引导。数据丢失或旧版本从未留下任何可识别使用数据的安装无法可靠区分于首次启动，会显示教程。

`OverlayPermission.kt` 为首页与教程共用授权入口和回前台检测：`Settings.canDrawOverlays` 为唯一真实授权状态，`ACTION_MANAGE_OVERLAY_PERMISSION` 打开系统设置，厂商未提供应用详情入口时尝试系统列表，均失败则说明手动路径，不崩溃或假定授权。`ON_RESUME` 重新检测，所以拒绝、返回、进程重建、撤销授权均按系统实时状态显示。权限示意图不使用可操作 Switch、不改变权限，并明确标注示意。未授权也可浏览主页，正式开始计时仍走已有权限拦截。服务及预设的最终权限检查保持原样。

教程不会申请通知、使用情况或其他权限。通知仍由首页现有用户点击入口按需申请；没有新增 Manifest 权限或 Activity。

## 外观与演示边界

沿用 FF 绿色主题、字体和圆角。`FocusTheme` 增加可选深色参数，仅教程按系统切换，未改变其他页面原有主题。安全区域避开状态/导航栏；窄屏内容可纵向滚动，横屏并排展示视觉与说明，主要操作固定在底部。字体放大时保持内容可滚动，不截断正文。

`OnboardingArt.kt` 使用原生 Compose/Canvas，复用 `ReminderPresenter` 和 `OverlayGeometry` 的颜色、大小、透明度及形状计算；手机界面、预设均是被标注的非交互示意，不读取或写入真实用户配置，不启动服务/计时，不发声或震动。只有当前页且 Activity 恢复时才运行动画，后台与离页停止；原生动画通过 derivedStateOf 降低演示重组至约每秒 12 次，尊重系统动画关闭设置。插画按稳定画布测量后整体适配窄屏/横屏，不挤压内部内容。没有第三方动画依赖、游戏/应用素材或联网资源。

## 验证

- `./scripts/check.ps1 -Offline` / `-Variant Release`：实际编译、既有单测及 Lint。
- `python -m unittest discover -s scripts/tests -v`：开发工具回归。
- `./scripts/test-device.ps1 -Suite onboarding -AllowTestDataChanges`：专用 AVD 上模拟首次使用、滑动/按钮/返回/跳过、拒绝后返回、暂不授权与启动拦截、真实系统开关授权、授权页后台进程重建、完成持久化、设置重看不改数据、撤销授权、旧数据迁移、深色和横屏截图。

测试由既有 runner 备份/恢复测试 app 的 SharedPrefs 与 files；内部首次安装场景只清理已备份的 app 私有路径，不卸载或清除系统权限。还原临时夜间/旋转设置。真机厂商权限页面和显示布局仍需单独检查，模拟器通过不冒充真机验收。

官方 API 依据：[Compose Pager](https://developer.android.com/develop/ui/compose/layouts/pager)、[Android Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION)。实际结果以本轮交付及 `.tools/checks/`、`.tools/device-checks/` 为准。
