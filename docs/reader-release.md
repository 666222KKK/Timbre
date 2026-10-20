# 构建签名 Release APK

在仓库 Settings → Secrets and variables → Actions 配置四项 Repository secrets：

- `TIMBRE_KEYSTORE_BASE64`：签名文件的 Base64 内容。
- `TIMBRE_STORE_PASSWORD`：签名库密码。
- `TIMBRE_KEY_ALIAS`：密钥别名。
- `TIMBRE_KEY_PASSWORD`：密钥密码。

签名文件与密码仅保存在个人安全位置，不能提交仓库。以后升级必须复用同一签名。

配置一次后，在 Actions → Release → Run workflow 输入版本名及递增版本号。流程运行全部单元测试、格式检查、Android lint，构建开启 R8 与资源精简的 freeRelease APK，验证签名，发布到 Releases。也支持先更新 `update.json` 的版本名和递增版本码，再发布对应 `v3.6.2.2` 形式的 Release，自动添加 APK。版本号不得重复；版本码必须高于已安装版本。

应用包名为 `app.timbre.audiobook.reader`，可与原版共存。Release 构建不包含调试工具与测试代码。
