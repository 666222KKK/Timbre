<div align="center">

<img src="artwork/public/timbre/timbre-icon-512.png" width="92" alt="提莫听书 Timbre 应用图标" />

# 提莫听书

**Timbre｜免费开源的听书 App · Android / iOS 双端**

它不催你开会员，只记得你听到第几秒。

[![version](https://img.shields.io/github/v/release/cq10086123/Timbre?style=flat-square&label=version&color=1d3a7a)](https://github.com/cq10086123/Timbre/releases/latest) [![license](https://img.shields.io/badge/license-GPL--3.0-1d3a7a?style=flat-square)](LICENSE.md) [![android](https://img.shields.io/badge/Android-9%2B-1d3a7a?style=flat-square)](docs/download.md) [![downloads](https://img.shields.io/github/downloads/cq10086123/Timbre/total?style=flat-square&label=downloads&color=1d3a7a)](https://github.com/cq10086123/Timbre/releases) [![stars](https://img.shields.io/github/stars/cq10086123/Timbre?style=flat-square&label=stars&color=1d3a7a)](https://github.com/cq10086123/Timbre/stargazers)

<sub>本地书 · WebDAV / NAS · 在线书源 · .jdr 接口源 · 发现页　|　Android 9+ / iOS · GPL-3.0 · 简体中文 · 免费无内购</sub>

</div>

---

## 提莫听书是什么

提莫听书（Timbre）是一款为中文听书场景做的听书 App / 有声书播放器，覆盖 Android 与 iOS 双端，自由开源、免费、无内购。它按「一口气听几小时」设计：上千集、单本上千小时的长音频，扫描、切章、进度都不掉链子；息屏、锁屏、切后台照样连续播放，不会被系统掐断。手机上下载好的 MP3、M4B、FLAC 直接导入，NAS 上的有声书通过 WebDAV 挂进书架，也可以导入 `.jdr` 接口源在线搜书——三条路的书共用一个书架、一套进度。不知道听什么的时候，往左滑一下书架就是发现页。

书库、播放进度、书签和缓存全部留在本机。没有账号，没有广告，没有统计与追踪 SDK。

| 书从哪来 | 怎么用 | 什么时候用得上 |
| --- | --- | --- |
| 本地音频文件 | 把音频文件夹交给它，扫描完就能开播 | 已经下载好的有声书、自己整理的长音频 |
| WebDAV / NAS | 填一次服务器地址，浏览目录直接导入 | 群晖、飞牛 fnOS、Alist 上的家庭书库 |
| 在线书源 / `.jdr` | 导入接口源包，搜索即听 | 想听的书不在自己设备上 |

[接口源直链](https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/samples/Timbre.jdr) · [功能说明](docs/features.md) · [常见问题](docs/faq.md) · [下载安装](docs/download.md) · [接口制作](samples/README.md)

## 长音频稳定性与息屏播放

这是它和一般播放器差别最大的地方。多数播放器是照着三分钟一首歌设计的：书一多，导入就抢存储带宽；切集时反复重建播放列表，进度跟着跳；屏幕一灭，后台服务被系统收走，回头还得自己找位置。提莫听书把这几件事当主线处理。

- **息屏不断播**：以媒体播放前台服务运行，持有唤醒锁，并可申请忽略电池优化；锁屏、切后台、连蓝牙耳机或 Android Auto 都继续播，通知栏与耳机按键直接控制，不必点亮屏幕。
- **播放永远优先**：导入扫描、封面校验、在线缓存、后续章节预取共用一条 IO 通道，播放器一开始缓冲，这些后台任务立刻让路，声音起来后自动接着做。所以边导入边听，开播速度和没在导入时一样。
- **长书不卡、进度不跳**：章节自然排序、导入并发可控、播放列表只组装一次；上千集的书切集不卡顿、不丢位置，进度按秒落盘，进程被回收后仍回到原处。
- **网络不稳也不打断**：WebDAV 与在线书源边缓存边播，后续章节提前预取；失败会写明原因并重试，不会把错误页当成一集存下来。

## 为长音频做的播放细节

**断点续播精确到秒**：每本书、每一集的位置都记得；暂停时自动回退几秒，接上刚才那半句话。倍速 0.5–2.0x、跳过静音、音量增益、章节导航、书签都在播放页里，蓝牙耳机、Android Auto 和桌面小部件可以直接切集。

**跳过片头片尾**：每本书单独设置。片头每集只跳一次，不会和暂停回退打架；暂停在片尾不会被强行切走；最后一集照常播完。这些细节是听了几百集之后才补上的。

**睡眠定时器**：支持固定时长、播完本集停止、渐弱淡出、摇一摇续命，也能设在某个时间段开始播放时自动开启。自定义时长可以直接输入分钟数，下次打开还记得上次填的值。

**书架顺手**：自然排序让「第 2 集」排在「第 10 集」前面；解析完第一集就能开播，网格 / 列表切换、全局搜索和「未开始 / 在听 / 听完」标记都在书架页。

**封面自动就位**：音频文件夹里的图片优先于内嵌封面，WebDAV 上的 `cover.jpg`、`folder.png` 这类名字会被优先认出来；一张图都没有时按书名生成一张。手动指定的封面不会被自动覆盖。

## 发现页：不知道听什么的时候

书架向左滑就是发现页。它按你导入的接口源聚合推荐位——榜单、分类、完结、真人等，不同源有不同栏目，直接点进去就能开听，不用先想好书名再搜。

发现页是可选功能：设置里关掉之后，书架只保留书库一页，左滑不会再切过去，底部提示也会一并隐藏。

## 无障碍：为看不见屏幕的人做的

听书是少数「眼睛可以完全离开屏幕」的娱乐方式，但播放器本身往往默认你能看见。v3.7 起针对 TalkBack 用户补了一轮：

- **状态会念出来**：播放 / 暂停、切换章节、加载中都会主动播报，不用反复摸屏幕确认现在在干什么。
- **滑块有名字也有值**：进度条念「已播 1 分 23 秒，共 45 分钟」而不是百分比；倍速、跳过片头片尾、音量增益、预加载数量都会读出当前值。
- **进度条能按 30 秒跳**：TalkBack 的操作菜单里直接提供「快进 30 秒」「快退 30 秒」，不必用滑块一点点拖。
- **输入框自动聚焦**：自定义睡眠时长打开就能直接打字，不用先找输入框。

这些改动只在系统无障碍服务开启时生效，视觉界面没有任何变化。

## 本地有声书：格式与导入

支持 Android 原生能解的大部分格式：MP3、M4A / M4B、AAC、FLAC、OGG / OPUS、WAV、MKA / MKV、MP4、WebM、3GP，完整清单见 [SupportedAudioFormats.kt](core/data/api/src/main/kotlin/voice/core/data/SupportedAudioFormats.kt)。一集一个文件、按文件名排好是最省心的方式；导入时把整个文件夹选进来即可。

删除书籍默认只从书架移除，不会动你的音频文件——只有明确勾选「同时删除我的文件」才会真的删掉。

## WebDAV / NAS：把家里的书库带出门

支持 HTTP / HTTPS 与 Basic、Digest 认证，可以先「测试连接」再导入；浏览目录后，既能「此文件夹作为一本书」，也能「子文件夹各作为一本书」整库导入。播放边缓存边播，后续章节自动预取，连上 WLAN 自动刷新；密码失效会在服务器列表里直接标出来，不用猜原因。

v3.6.3 起，书架、每本书听到第几章、章节内的位置，以及倍速、跳过静音、跳过片头片尾这些设置，都能备份到你自己的 WebDAV 目录，换机或重装后一键拉回。备份文件只落在你自己的服务器上。自签证书可勾选信任。

## 在线书源与 .jdr 接口源

书不在设备上时，导入 `.jdr` 接口源包即可扩展：搜索、取章节、拿播放直链全部由播放器内置的 QuickJS 沙箱在手机上完成，请求从你自己的 IP 直连源站，不经任何中间服务器。沙箱备了 MD5 / SHA / AES / ChaCha20 / SM4 加密桥与 HTTP 桥，签名类、加密类接口也写得出来；一个包能装多个源，按源启停、随时更新卸载。

**导入现成的接口源合集**：在 App 内选「从链接导入」，粘贴这条直链：

```
https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/samples/Timbre.jdr
```

部分源有请求频率限制，别反复切换。在线章节的时长若源站没给，播放后会自动探测修正；长按书架上的书还能追更章节、缓存全书离线听。

想自己做一个源：[接口源开发套件](samples/README.md)里有空白模板、接口契约说明和打包脚本，按说明填好搜索、章节、播放直链三个函数就能打出一个 `.jdr` 包。做源过程中遇到接口格式的问题，可以到 [Issue](https://github.com/cq10086123/Timbre/issues/new?template=4-question.yml) 里问。

## 隐私：不联网也能听完一本书

无账号、无广告、无统计、无行为追踪 SDK。应用自己只发起两类请求：每天最多一次检查版本、读取维护者公告；其余联网都来自你主动配置的 WebDAV 或在线书源。检查失败会静默放弃，不影响使用，也从不自动下载或安装任何东西。详见[隐私政策](PRIVACY.md)。

提供简体中文与英文界面，支持浅色 / 深色 / 跟随系统三种主题。

## 下载与安装

Android 端到 [GitHub Releases](https://github.com/cq10086123/Timbre/releases/latest) 下载最新签名 APK，Android 9（API 28）及以上直接安装，不需要应用商店。新旧版本同一签名，覆盖安装会保留书库、进度与设置；应用启动后会静默检查更新，只提示、不自动下载。

[下载 Android 最新版 APK](https://github.com/cq10086123/Timbre/releases/latest) · [全部版本](https://github.com/cq10086123/Timbre/releases) · [安装说明](docs/download.md)

## 常见问题

**提莫听书支持哪些音频格式？** MP3、M4B / M4A、FLAC、OGG、OPUS、WAV 等常见格式基本都行，[完整列表](docs/faq.md#支持哪些音频格式)在这里（Android 端）。

**息屏之后会断播吗？** 不会。播放跑在媒体播放前台服务上并持有唤醒锁，锁屏、切到后台、连蓝牙耳机都继续播；如果系统仍在杀后台，可在设置里允许忽略电池优化。

**上千集的书会卡吗？** 章节按自然排序，导入、封面校验和缓存会在播放器缓冲时让路，播放列表只组装一次。边导入边听也能顺滑切集，进度不会跳。

**能听 NAS 上的有声书吗？** 能。填好 WebDAV 地址、账号密码即可，群晖、飞牛 fnOS、Alist 都可用；播放边缓存边播，不要求一直连着家里的网络。

**发现页会自己联网拉推荐吗？** 只用你已导入的接口源，不会额外请求别的地方；不想要可以在设置里关掉。

**看不见屏幕能用吗？** 开着 TalkBack 就能完整操作播放：播放暂停、切章、拖动进度、快进快退 30 秒都有语音反馈，具体见上面的[无障碍](#无障碍为看不见屏幕的人做的)一节。

**要登录或者开会员吗？** 不用。完全免费开源，没有账号、没有会员、没有内购。

**和 Voice 是什么关系？** 提莫听书基于 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，在中文化之外加了 WebDAV 远程书库、`.jdr` 接口源、发现页、无障碍支持、跳过片头片尾、自动封面、应用内更新等。详见[关于页](docs/about.md)。

更多问题看 [docs/faq.md](docs/faq.md)，或直接提 [Issue](https://github.com/cq10086123/Timbre/issues)（附上设备、系统版本、App 版本和复现步骤，会快很多）。

## 从源码构建

```bash
./gradlew :app:assembleFreeDebug
```

Debug 包可直接安装（Android 端）。Release 签名、包名覆盖与提交规范见 [CONTRIBUTING.md](CONTRIBUTING.md)。free 分支不含 Firebase 与任何统计依赖，无云端服务。

## 关于与许可

提莫听书基于 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，播放内核、书库模型与整体架构都建立在 Voice 之上。感谢原作者 [Paul Woitaschek](https://github.com/PaulWoitaschek) 与所有上游贡献者。项目由个人在业余时间维护，欢迎 Issue 与 PR。

[GNU GPLv3](LICENSE.md) © Voice 原作者及提莫听书贡献者

## 免责声明

**提莫听书是纯粹的播放器客户端**，自身不提供、不存储、不分发、不托管任何音频、文字、影视内容，也不运营任何内容服务、不设立内容索引、不提供下载站点。

软件能播放的内容全部来自以下三种途径，均由使用者自行提供或选择：

1. **本地文件**——你自己设备上已有的音频文件；
2. **WebDAV / NAS**——你自己搭建或有权访问的服务器；
3. **`.jdr` 接口源**——由第三方或你本人编写、导入的接口配置。

项目维护者不参与上述内容的制作、审核、上传与分发，对接口源指向的内容不知情、不控制、不背书，也未从中获取任何收益。

**关于项目提供的示例接口源**：仓库与文档中附带的 `.jdr` 文件是接口格式示例，用于演示「如何请求某个站点、如何解析返回结果」，不含任何音频文件，也不是内容库。实际播放时，音频流由对应站点直接送达你的设备，本项目不存储、不转码、不代理、不分发。项目与示例源均为公益性质：不收费、无广告、无会员、无任何商业收益。若权利人认为某个示例源指向的内容侵犯其权益，可通过 [Issue](https://github.com/cq10086123/Timbre/issues) 联系我们，我们会立即停止提供该源。

使用本软件即表示你理解并同意：

- 你对所访问、播放的内容拥有合法权利，或已取得权利人许可；
- 通过示例接口源访问内容前，已自行确认该内容的权利状态；
- 你的使用行为符合所在国家或地区的法律法规；
- 不将本软件用于侵犯著作权、传播违法信息或其他违法用途。

本项目仅供学习、研究与个人使用，**不得用于任何商业用途**。软件按 GPL-3.0 许可「按原样」提供，不附带任何明示或暗示的担保；因使用或无法使用本软件而产生的任何直接或间接损失及法律责任，由使用者自行承担。

若权利人认为**本项目本身**（不含第三方提供的内容）侵犯其合法权益，请通过 [Issue](https://github.com/cq10086123/Timbre/issues) 与我们联系，我们会及时处理。

---

Timbre (提莫听书) is a free, open-source audiobook player for Android and iOS: local MP3 / M4B / FLAC files, WebDAV / NAS libraries and custom `.jdr` interface sources in one shelf, with a swipe-left Discover page and full TalkBack support. Built for long listens — thousands of chapters, uninterrupted playback with the screen off, second-accurate resume. No accounts, no ads, no tracking. [Releases](https://github.com/cq10086123/Timbre/releases/latest).

<!-- filler-pr-95 -->

<!-- filler-pr-96 -->

<!-- filler-pr-97 -->

<!-- filler-pr-98 -->

<!-- filler-pr-99 -->

<!-- filler-pr-100 -->
