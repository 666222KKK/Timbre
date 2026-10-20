# ASMR MOON 接口源

接口依据为站点 AList 目录结构、此前 Moon Android App 的 `ApiClient` 与网页验证流程，以及公开客户端的同站接口实现。没有绕过验证或受保护目录。

## 安装与使用

1. 安装支持网站验证的新 Timbre Reader 3.6.2.2（可覆盖本分支 3.6.2.1）。
2. 设置 → 接口源 → 导入 `asmrmoon-1.0.0.jdr`。
3. 如果搜索或播放提示月盾：在接口源页的 ASMR MOON 下点“网站验证 / 更新会话”，自行完成验证，点“保存会话”返回，再重新搜索。
4. 发现页支持中文音声、日韩音声、English 三类目录。搜索支持普通关键词；也可用 `path:/English/目录名` 精确打开作品目录。

目录作为书籍，音频文件作为章节，章节会递归读取子目录并按数字顺序排列。封面、说明文档及压缩包不会当成音频。大目录超过 200 次目录请求或 2000 个音频时提示改选更具体作品，避免假装已经加载全部内容。

## 验证会话

沿用 Moon 的人工网页验证、Cookie 与浏览器标识配对方案。会话使用设备密钥加密，保存在不参与备份的私有目录。仅同一个接口包安装实例、同一 HTTPS 域名和端口可用；重新导入或换设备需要重新验证。源脚本不内置任何个人 Cookie。

音频链接每次播放重新获取。带验证会话的媒体先用单字节 Range 请求解析跳转，跨站跳转时移除网站 Cookie；不会主动下载完整音频。清除按钮只撤销此接口源保存的请求会话。

目录若需要密码、登录权限或站点等级，源会明确提示受保护；不会跳过保护、伪造权限或提升等级。

## 接口契约

- `POST /api/fs/search`：`parent, keywords, page, per_page, password`；读取 `data.content[].parent/name/is_dir`。
- `POST /api/fs/list`：`path, page, per_page, password, refresh`；读取 `data.content[].name/is_dir` 与 `data.total`。
- `POST /api/fs/get`：`path, password`；读取 `data.raw_url`，播放时携带 Referer 和浏览器标识。
- `http.sleep(ms)` 为宿主新增的异步限速能力，避免脚本忙等消耗 CPU。

## 测试边界

脚本包含 13 项隔离沙箱模拟测试，覆盖递归、分页、直链刷新、缓存丢失、目录限制及验证错误。另有原生会话隔离、加密持久化、重定向防泄漏和 QuickJS 睡眠桥测试。

当前开发环境的普通 HTTP 请求返回 HTTP 403 月盾验证页，浏览器能打开目录。尚不能据此宣称真实搜索、真实音频播放链路已通过；仍需在手机内完成站点验证后验证实际播放。服务器验证策略变化时可能仍要求重验。

参考：
- https://github.com/cq10086123/Timbre/blob/main/samples/README.md
- https://github.com/TOBugCoding/WeAsmr/blob/main/src/netMusic.cpp
- https://asmrmoon.com/
