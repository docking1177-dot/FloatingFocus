# Floating Focus 测试用例与执行证据

本库定义“应该测什么”和“实际测过什么”。本轮只整理，未重跑应用构建、单测或设备测试，也未改变原脚本判定。

## 维护源与浏览入口

- `cases.json`：唯一用例维护源。稳定ID不回收；包含前置、步骤、可判定预期、优先级、构建/环境、推荐途径、代码入口及真机分类。场景、模型类、设备套件和检查门禁分粒度，不把数量当覆盖率。
- `runs.jsonl`：从原独立check摘要、设备results、JUnit XML导入的只追加批次。批次公共身份不逐行复制；每批含一个或多个同粒度执行结果。保留原文件位置与SHA256。
- `manual_runs.jsonl`：实际人工/真机记录，只追加。历史用户反馈保持时间、APK等未知字段。`manual-run-template.json`是填写示例，不是已执行记录。
- `target.json`：本轮目标应用构建身份。目标为68517f8提交的应用内容，Debug/Release检查原来记录7361c48+dirty，但指纹与当时68517f8应用一致；两种原身份均保留，不改写原报告。后续仅文档/整理脚本变更不会生成新APK，因此不能直接拿整个新工作区指纹否定旧应用包。
- `issues.json`：问题、原失败/缺口、修复线索及明确复测关系。不能只因后来出现PASS就推测它复测了全部旧失败。
- [证据摘要与缺口](STATUS.md)、[用户真机最小集](REAL_DEVICE.md)：从上述维护源生成。
- `dist/testing/测试用例库.csv`、`测试执行记录.csv`及XLSX均是**导出报告**，不要在Excel修改后又手工维护JSON。XLSX仅两张工作表，可按优先级、环境、构建、结果、身份、复测和用例ID筛选。

## 整理与校验

```powershell
# 只读现有证据，幂等导入；不调用Gradle/ADB，不执行应用测试
python scripts/test_catalog.py refresh
python scripts/test_catalog.py validate
# 只验证本整理器
python -m unittest discover -s scripts/tests -p test_test_catalog.py -v
```

`refresh`跳过latest指针，不重复把交付/复用汇总算作新执行；源文件内容改变会生成新的带哈希批次ID，旧批次不覆盖。同批次既有记录不会改写。旧schema的验收JSON保留在`evidence-index.json`，其列出事项和缺失身份可查；不从缺整体状态的旧对象自动宣称整批通过。原日志、媒体、私有应用数据和APK不复制进库，只有路径/哈希和必要观察；原文件后来不在本机时，仍保留导入事实，不假装原证据还可读取。

Excel导出使用本机已提供的artifact-tool，不向Android项目增加依赖：先通过桌面工具定位bundled Node/node_modules，在被忽略的`.tools/test-library/node_modules`建立junction，然后运行：

```powershell
& '<bundled Node路径>' scripts/export-test-catalog.mjs
```

输出在`dist/testing/outputs/20261030-test-library/FloatingFocus-tests.xlsx`，两表数据来自同一`report-data.json`。无该运行时的环境仍可用标准库刷新CSV及Markdown，不安装额外平台。导出不会写回维护源。

## 判定与身份规则

- 执行结果只有：未测、通过、失败、阻塞、不适用。没有执行的用例在用例表显示未测，**不伪造一条执行记录**。
- 另外保存：当前构建／当前源码(JVM)／历史构建／身份未核验，以及不需要／需要／待影响评估／待核验／已有复测。旧通过不改成当前通过，旧失败关联复测后也不删除。
- 当前APK按完整SHA256及其检查摘要内容指纹核对，不按版本名判断。独立Unit检查有明确源码时可标当前源码(JVM)，APK不适用；JUnit XML自身没有源码/APK绑定，不能按邻近时间或相同计数补造身份。
- Build、Lint、JUnit汇总和设备交互分别记录。整套passed只落到套件行；只有`cases.json.device_details`白名单、且原结果细项为true时，才能记录对应断言用例通过。当前白名单仅组件六个明确细项。更改映射须先核对那一版本脚本和证据。
- 截图生成不等于视觉通过；播放器start不等于可听/连续；vibrate请求不等于触感。模拟器结果绝不填真机栏。
- 原设备runner未保存当次Android build/启动器版本，保留序列号及“脚本约束”说明，不把API36或Launcher3脚本当成采集到的当次版本。目录名本地时间与JUnit无时区时间保持原值，不擅自补时区。check的startedUtc明确为UTC。
- Full失败未细分实际任务时，只记原check批次失败；不拿残留JUnit计数判单测通过，也不猜assemble/Lint谁失败。JUnit跳过记阻塞，不自动当作不适用。
- C4听感只适用于含三段本地素材的Debug；当前Release无录音，对这些听感项标不适用不等于通过，视觉/口令/计时仍适用。
- 汇总是执行条目数量，包含多版本、重复执行与不同粒度。不会输出虚构覆盖率或把执行条数当通过功能数。

## 新增功能、缺陷和执行结果

1. 新功能：在cases.json新增稳定ID及可独立判定的预期，分开模型、集成和真机不可替代的观察；填真实入口，不能因有入口就填通过。
2. 缺陷：issues.json记录类型（应用/测试脚本/证据缺口）、影响用例、失败记录ID和依据。没有结构化失败身份时只列缺口，不编造失败APK。
3. 完成测试：保留原独立check/results/JUnit，再运行refresh。新目标APK先从真实检查摘要更新target.json的完整身份与音频条件，目标ID必须变化；不要为本资料改动重新构建应用。
4. 人工结果：复制manual-run-template.json填写实际数据；未知字段保留null，结果写实际观察与证据。只登记已实际发生的测试；计划可留模板，不计执行。

```powershell
python scripts/test_catalog.py add-run --file '<本次人工记录.json>'
```

5. 修复复测：给新执行的`retest_of`填原记录ID（或在issues.json.retests明确连线），追加新批次，不能用相同ID改旧结果。只有明确关联、同用例/范围/环境/构建类型/设备且当前身份的通过复测才解除原失败；阻塞、失败、身份未知或其他设备的复测不解除。原失败始终保留，后来的无关联PASS不能掩盖它。人工记录先完整校验再追加，缺字段/错误引用/跨账本重名均拒绝且不改变账本。
6. 选复测范围：先看问题关联用例与`impact_tags`；存储/启动互斥/C4来源改动优先P0及公共入口；局部绘图改动优先几何、触摸和相关截图；文档/整理脚本改动不跑APK。身份未知先核验，旧版本先评估影响，不把全部历史未测交给用户。

## 真机操作与反馈

[REAL_DEVICE.md](REAL_DEVICE.md)按连续流程归并13个独立判定项，建议项另列。使用当前Debug，沿用已知Redmi权限与日常省电配置；已有同APK同系统验收不无理由重测。用户只需报用例ID、操作/发生秒数、实际现象和约时间，按需附录屏及声音状态。授权连接ADB后由AI提取可用日志，不要求用户筛日志；本整理器本身不会连接设备。
