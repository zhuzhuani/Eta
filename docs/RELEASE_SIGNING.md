# 固定签名与覆盖更新

本分支是独立安装的 Eta 定时任务版，不替换官方 Eta 或旧 Etax。

- 安装包名固定为 `io.github.zhuzhuaini.eta`（按用户指定拼写）。
- 源码 namespace 仍为 `io.github.mangi.eta`，不是安装包名；不应批量替换 Kotlin 类名、Xposed 入口和 Root helper 类名。
- Release 使用一次性生成、之后持续复用的 RSA-4096 签名，有效期 10000 天。CI 不生成新密钥，也不允许缺少配置时退回临时签名。
- 公共证书 SHA-256：`ce2bcd760a9ffc5633f22b6de63b6c1c03beae48fa0540da56222d1d955fe1b2`。
- 工作流将产物证书指纹与 [固定指纹](../.github/release-signing.sha256) 对照，并核对实际 APK 包名；不匹配时拒绝上传。

## GitHub Actions 配置

私钥仅通过下列 Actions Secrets 注入受信任的发布构建，不进入 Git、PR 检查或 APK artifact：

| Secret | 用途 |
| --- | --- |
| `ETA_RELEASE_KEYSTORE_BASE64` | 固定 keystore 的 Base64 编码 |
| `ETA_RELEASE_STORE_PASSWORD` | keystore 密码 |
| `ETA_RELEASE_KEY_ALIAS` | 固定私钥别名 |
| `ETA_RELEASE_KEY_PASSWORD` | 私钥密码 |

现有 `ETAX_*` Secrets 属于旧 Etax，不修改或复用。请将新 keystore、别名和两个密码保存于私密备份；GitHub 不能直接导出已有 Secret 的明文。丢失密钥后重新生成一套并不能继续覆盖安装旧签名版本。仓库只保存公开指纹，不能依靠指纹恢复私钥。

签名构建仅允许本仓库的 `main`、`feature/scheduled-tasks` 和 `v*` 标签。它执行仓库里的工作流与 Gradle 脚本，所以**拥有相关仓库/分支写权限的人必须是可信的签名维护者**；`contents: read` 不会隔离脚本对 Secrets 的访问。若以后开放多人协作，应先配置受保护分支或 GitHub Environment 审批，而不是直接把未审查的外部代码放入签名构建。

## 以后如何覆盖更新

1. 推送 `feature/scheduled-tasks`，等待 **Eta Build** 的校验和构建均成功。
2. 下载不含 `-debug` 的 Release APK。Debug 是另一套调试签名，不用于本 Release 的覆盖更新。
3. 保持包名与上述签名不变，`versionCode` 不低于手机已安装版本，并保留数据库升级迁移。
4. 满足以上条件时可直接安装更新并保留此应用的数据，无需每次卸载。

本版包名与官方 `io.github.mangi.eta`、旧版 `io.github.mangi.etax` 不同，因此可共存，首次安装是全新应用，不会自动继承另外两者的设置、模型配置、权限或聊天记录。Root、无障碍、通知读取和 LSPosed 授权需为新包单独配置；是否同时开启两个版本对同一系统进程的 Hook，仍应按设备实际兼容性验证。
