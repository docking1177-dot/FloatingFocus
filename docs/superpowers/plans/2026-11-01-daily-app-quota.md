# Daily App Quota Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task-by-task; one independent review after implementation.

**Goal:** 同包名 FF 会话共享持久化每日额度，预设按最新剩余值启动。

**Architecture:** 独立纯记账模型和 AtomicFile 串行存储；FloatingService 提供原会话运行状态，共用预设启动器解析额度。现有 Compose 管理及草稿编辑接入，不创建第二套计时。

**Tech Stack:** Kotlin/JVM、现有 Compose、java.time、Android AtomicFile；无新增依赖。

**Spec:** ../specs/2026-11-01-daily-app-quota-design.md

## Global Constraints

- 固定时区、日界线延后生效；已有历史不变，额度不监测真实 App 前台。
- 所有绑定会话计运行时间；暂停不计，超时继续计；固定预设保持原语义。
- 旧预设固定时长；共用启动校验；不替换活跃会话或绕过 C4。
- 保留 AGENTS.md 原有删空行及 asset/；不合并、不发版、不增加权限/依赖。

## Review Focus

- tick/暂停/结束重复提交不能双扣；进程重启不能补计停机。
- 跨日及日界线编辑不能重复赠额；设备时区不改变固定记账区。
- 写失败保留待重试消耗，读失败不能变成空账；取消配置不写入。
- 额度实际剩余值参与提醒节点校验，三个预设入口一致。
- 启动失败不扣额，旧请求不操作后来的会话，C4 锁定不受影响。

## Task 1：账本及预设模型

- [x] 先写 DailyAppQuotaTest / PresetQuotaTest，运行 Unit 观察缺实现失败。
- [x] DailyAppQuota.kt：周期、固定时区、配置、运行区间、水位、异常时钟。
- [x] AppQuotaStore.kt / AppQuotaCodec.kt：原子持久化、串行 IO、失败/重试。
- [x] TimerPreset / Store：来源字段，旧数据固定，合法性验证。
- [x] Unit 检查通过。

## Task 2：启动、会话和界面

- [x] PresetLauncher / AppTimerLauncher：实时解析额度、实际提醒校验、目标打开后记账。
- [x] FloatingService：复用运行、暂停、结束、C4 结算生命周期，水位去重。
- [x] App 管理额度编辑与重置时刻；设置独立预设草稿、保存摘要；三种 Debug 布局与 Release 共用摘要。
- [x] 组件和桌面入口共用 ID 路径，不另存剩余值。

## Task 3：验收与收尾

- [x] 新增 quota 设备套件及统一测试库用例；脚本回归。
- [x] Debug/Release 完整检查，专用模拟器 quota、相关入口回归；保留独立证据。
- [x] 独立代码审查，修复实际问题并针对性复测。
- [x] 更新配置/迁移/验证说明，隔离用户差异后提交并同步开发分支。
