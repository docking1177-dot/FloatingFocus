# 可重复的发布入口

`scripts/release.ps1` 固化当前固定 Debug 签名的 GitHub Pre-release 流程，不负责商店正式签名，不自动修改版本、提交、合并、打标签或推送。脚本不下载工具：复用 Python、原生 Git/GCM、PATH 中 gh 或 `.tools/gh/gh.exe`、本地 SDK build-tools 35.0.0。

## 准备

1. 用户授权发版后，更新版本、CHANGELOG、`docs/releases/<version>.md`，说明测试性质和未验证的真机场景。提交最终源码。
2. 确认 `.tools/debug.keystore` 是已备份的固定文件。发布脚本要求 APK 签名与既有证书 SHA256 相同，不会生成替代密钥。日常 build-local 首次开发仍可生成 Debug key；缺少原密钥时不能拿新密钥发布覆盖更新。
3. 源码稳定后运行检查，保存各自打印的独立 JSON 路径：

```powershell
./scripts/check.ps1 -Offline
./scripts/check.ps1 -Variant Release -Offline
./scripts/start-emulator.ps1
# 等待 adb -s emulator-5554 shell getprop sys.boot_completed 返回 1
./scripts/test-device.ps1 -Suite all -AllowTestDataChanges
./scripts/release.ps1 -Action prepare -DebugCheck <Debug检查.json> -ReleaseCheck <Release检查.json> -DeviceReport <回归results.json>
```

prepare 只整理 `dist/<version>/`：APK、发布说明、BUILD_INFO、SHA256SUMS。要求干净源码、对应源码的 Full Debug/Release 结果、相同 APK 的三组模拟器回归，并验证包名、版本、固定签名和 APK 对齐。不接受只跑 Unit 的结果。已存在的目录仅在完全匹配时复用，不覆盖旧包；失败留下的部分目录需检查后移开，再重新准备。

## CI 与上传

合并/推送 main 后，CI 只在 main、PR 或显式手动触发时构建。版本标签不再触发第二套同样的检查。发布脚本查找 `android.yml` **相同 SHA、main 分支、push/手动事件**的最新运行，成功才放行；PR 合并测试 SHA 不作替代。若没有结果，先推送/合并正确提交；必要时手动触发一次 `gh workflow run android.yml --ref main`。运行中或失败时脚本说明原因并退出，可稍后重跑，不自动再排一次构建。

确认远端标签已指向同一提交后：

```powershell
./scripts/release.ps1 -Action draft
./scripts/release.ps1 -Action publish
./scripts/release.ps1 -Action verify
```

draft 使用 `--verify-tag` 创建预发布草稿，按文件补传缺失附件，再下载校验全部字节。网络中断后可重跑 draft；已存在的不同内容不会覆盖，需人工检查冲突。publish 先验证草稿附件再公开，并回读验证；已公开相同版本只做核验，不重写附件。普通脚本开发不需要实际执行 draft/publish。

GCM 凭据仅在捕获的进程输出和 gh 子进程环境中传递，不写文件或打印。配置/网络错误不会无限重试。脚本不改变代理或全局 Git 配置。

本流程消除的是同 SHA 的 **main + tag** 重复发布构建；PR 测试合并提交、人工主动重跑仍保留，不能把不同测试范围或不同 SHA 当作可复用结果。

参考：[GitHub CLI release create](https://cli.github.com/manual/gh_release_create)、[Actions 触发事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)。
