# 参与开发

开始前阅读 README、docs/REUSE_REPORT.md 和 docs/THIRD_PARTY_NOTICES.md。

1. 一个功能或问题一个 Issue，注明复现步骤、手机型号、Android 版本与 APK 版本。
2. 从最新 main 创建短分支，例如 feat/vibration-toggle 或 fix/overlay-bounds。
3. 修改范围保持集中；迁移第三方代码时保留来源与许可证。
4. 执行 `./gradlew assembleDebug testDebugUnitTest lintDebug`；Windows 用 `gradlew.bat`。
5. PR 说明行为变化、验证结果和未验证项目。与悬浮窗/权限/后台有关的变更需按 docs/DEVICE_TEST_CHECKLIST.md 补做对应设备测试。

不要提交 SDK、构建缓存、local.properties、账号令牌、私钥或签名密码。日志和截图提交前移除个人信息。APK 通过 GitHub Releases 分发，不存入源码 Git 历史。

分支、版本、签名与部署方式见 [维护指南](docs/DEVELOPMENT_WORKFLOW.md)。
