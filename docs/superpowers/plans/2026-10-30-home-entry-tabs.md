# Home entry tabs implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline; finish with an independent whole-branch review.

**Goal:** 首页用计时／预设／App分栏，支持本机保存长按拖动顺序。
**Architecture:** 稳定入口ID与偏好、原生Compose标签手势、MainActivity条件组合既有三个组件。会话状态共同展示，计时与启动逻辑不复制。
**Tech Stack:** Kotlin / Compose / SharedPreferences；既有Python ADB runner。
**Spec:** docs/superpowers/specs/2026-10-30-home-entry-tabs-design.md（用户明确选择方案一）。

## Constraints / review focus

- 不增加依赖、权限或后台任务，不更改版本、不合并或发布。
- 只保存完成的排序；取消、重建和稳定ID不能丢入口或意外切栏。
- 换栏不能取消启动请求、复位计时选项、影响活跃会话或预设草稿。
- 既有回归明确进入栏目，不通过隐藏自动导航掩盖错误。
- 深浅主题、大字体和小屏保留触摸区域，状态与结束操作始终可达。

## Tasks

- [x] 1. HomeNavigationTest先RED；实现HomeEntry/HomeEntries顺序模型与HomeNavigationStore，运行Unit检查。
- [x] 2. HomeEntryTabs原生长按拖动、取消及读屏动作；MainActivity保留共同计时选择、栏目滚动及活跃操作。
- [x] 3. 调整既有设备入口，增加首页分栏结构化回归；更新AGENTS、CHECKS和测试库，完成Debug/Release检查及相关专用模拟器回归。
- [x] 4. 独立审查与必要复测完成，具备提交/同步条件；实际提交与推送结果以Git记录和交付报告为准。

## Verification

`./scripts/check.ps1 -Mode Unit -Offline`（先确认新模型缺失失败）；`./scripts/check.ps1 -Offline`；`./scripts/check.ps1 -Variant Release -Offline`；`python -m unittest discover -s scripts/tests -v`；`./scripts/test-device.ps1 -Suite home -AllowTestDataChanges`。

实际结果与2026-11-01的原始证据路径见`docs/HOME_NAVIGATION.md`。切栏图层残影采用稳定key隔离；反向排序将身份锚定到DOWN，先失败后通过`home_reorder`两轮及完整`home`复测。2倍字号的既有时长文字裁切单独登记P2，不扩大本轮应用修改。
