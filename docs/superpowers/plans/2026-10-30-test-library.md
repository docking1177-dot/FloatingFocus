# Test library and execution evidence plan

> **For agentic workers:** Use superpowers:executing-plans inline. Do not run Android tests or change application behavior for this documentation task.

**Goal:** 建立可持续维护的用例库、只追加的执行记录及两表Excel导出，合并用户真机最小验收集。

**Architecture:** docs/testing/cases.json 是用例维护源；runs.jsonl 保存从原结构化报告导入的批次与原粒度结果，manual_runs.jsonl 保存人工记录。target.json 明确本轮被测APK，不把文档变更后的仓库指纹误当成新应用构建。标准库整理脚本只读现有报告，Excel是派生报告，不双向维护。

**Tech Stack:** Python标准库、原JSON/JUnit XML证据、artifact-tool生成两工作表XLSX。

**Spec:** 用户2026-10-30测试管理需求及 docs/testing/README.md 中的记录规则。

此处是实施步骤，不是测试执行结果；具体结果与未知身份统一见测试库原记录和STATUS，不从计划勾选推断功能通过。

## Constraints

- 不修改app、原检查器或设备回归判定，不执行Android测试，不新增服务/依赖/平台。
- 不把脚本存在、截图生成、播放/震动请求或历史结果推为当前真机通过。
- 保留原日期/未知时区/未知身份、失败与复测；重复导入不得覆盖历史。
- 用例、执行、证据粒度、构建适用性及复测理由分别存储。

## Steps

- [x] 定义目标构建、有限功能场景、模型/套件边界及真机分组。
- [x] 为整理器编写汇总不拆分、身份缺失、时区未知、失败保留与幂等导入测试。
- [x] 导入检查、设备、JUnit和旧报告索引，保留人工历史反馈的身份缺口。
- [x] 生成两张表、真实统计和真机操作清单，核对导出行数及视觉。
- [ ] 仅运行整理脚本检查，独立审查证据规则，提交同步开发分支。
