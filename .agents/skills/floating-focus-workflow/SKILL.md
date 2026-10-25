---
name: floating-focus-workflow
description: 在 Floating Focus 项目修改、验证和交付改动时，选择现有检查、模拟器回归及发布入口，核对源码与产物证据。
---

先读根目录 `AGENTS.md`，按本次任务定位代码与相关文档。不要重新实现已有脚本。

- JVM 逻辑：`./scripts/check.ps1 -Mode Unit -Offline`；应用交付：`./scripts/check.ps1 -Offline`。Release 检查加 `-Variant Release`。只改文档无需构建。
- 开发脚本：`python -m unittest discover -s scripts/tests -v`，再执行实际变更路径。`.tools/checks/` 中每次运行保留独立 JSON；复用前核对 source、tasks、variant 和 APK SHA256，不能只看 latest 的 passed。
- 窗口/服务/触摸：按 `docs/CHECKS.md` 启动专用模拟器，运行 `scripts/test-device.ps1` 的相关 suite。它会备份并恢复测试数据；截图不是自动视觉验收，模拟器不证明真机震感与厂商后台策略。
- 发布仅在用户授权发布时按 `docs/RELEASE_AUTOMATION.md` 执行。复用相同提交的 main CI；缺失/运行中/失败时停在门禁，不反复触发新构建。普通修复不递增版本、不发 Release。
- 收尾报告本次修改、实际检查、提交与推送目标和验证边界。产品约束维护在 AGENTS，流程细节维护在 docs，本文件只提供入口。
