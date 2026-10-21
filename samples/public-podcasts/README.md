# 公开播客接口源

适用于 Timbre 3.6.2.4；在「设置 → 接口源 → 导入 .jdr 文件」中导入 `public-podcasts-1.0.0.jdr`，启用「公开播客」。无需更新 APK。

## 使用

- 搜索节目名称或关键词，默认查询香港地区的公开播客目录。
- 可用 `tw:科技`、`hk:故事`、`us:science` 指定目录地区。
- 在「发现」选择「公开播客」，浏览香港、台湾、国际热门和教育、社会与文化、科技、喜剧、新闻、虚构故事、真实犯罪、健康与健身分类。
- 搜索框可直接粘贴公开 RSS/Atom 地址，也支持 `rss:https://example.com/podcast.xml`。节目网页链接与 RSS 订阅地址用途不同。
- 点击节目加载公开单集；章节保留 RSS 顺序，通常最新单集在前。添加书架后可使用播放器现有的进度保存功能。

搜索单个关键词最多返回 200 个公开 RSS 节目，分类榜单最多 100 个节目；可以换关键词或地区继续查询。榜单中没有公开 RSS 的节目会被过滤。

目录使用 Apple 的公开播客搜索与榜单，音频直接来自节目发布方 RSS 的公开 enclosure 地址。无须 Apple、Podchaser 或 Spotify 账号，不从平台播放器提取音频。只有发布方提供公开 RSS 音频的单集才可播放。

不同节目托管站的网络可达性不同，RSS 也可能只保留最近一部分单集。出现 HTTP 错误、RSS 不可达、没有公开音频或单集被移除时，接口源会提示具体原因。

## 验证

自动测试：`node scripts/test_public_podcasts.mjs`。

覆盖沙箱接口、搜索分页、分类顺序、RSS/Atom 解析、CDATA/实体、相对音频地址、时长、视频过滤、重复单集、冷启动解析、无效 XML 和网络错误。

实测通过搜索、榜单、RSS 章节、冷启动目录查询和音频范围读取。音频测试仅读取 64 字节，没有下载完整音频。未进行 Android 真机播放实测。

打包：`python scripts/pack_jdr.py samples/public-podcasts public-podcasts-1.0.0.jdr`。

参考：[Apple Search API](https://performance-partners.apple.com/resources/documentation/itunes-store-web-service-search-api/)、[播客 RSS 规范](https://podcasters.apple.com/support/823-podcast-requirements)。
