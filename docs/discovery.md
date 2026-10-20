# 发现好书

在书架底部点击「发现好书」，选择已启用的书源，再选择分类或榜单。
点击书籍可查看章节、加入书架，再点击章节进入播放器。

- 没有书源时，点击「书源管理」导入并启用 `.jdr` 书源，然后返回并刷新。
- 旧书源无需修改：使用玄幻、修仙、都市等分类关键词搜索，页面会明确提示这不是排行榜。
- 扩展书源搜索支持分页时可「加载更多」；重复页自动去重并停止加载。
- 旧源只要返回非空页，就可以继续请求下一页，不依赖源站是否遵守每页 30 本。
- 选择书籍时恢复章节请求所需的附加字段，避免浏览超过缓存容量后无法打开较早结果。
- 旧服务端接口没有分页能力，因此只展示其返回的一页结果。
- 原生发现书源可以返回自己的分类、榜单和分页结果；不内置虚构排行榜或书单。
- 切换书源或分类会清空旧结果。失败可重试，加载下一页失败会保留已有书籍。

## 为 .jdr 书源添加原生发现

在现有 `registerSource({...})` 对象内添加两个可选函数：

```js
async discoverCategories() {
  return [
    { id: 'fantasy', title: '玄幻' },
    { id: 'hot', title: '热榜' },
  ];
},
async discover(params) {
  // 调用你自己的源站分类或榜单接口。
  // params: { categoryId, page: 1起, limit: 30 }
  const resp = await http.get(HOST + '/browse', {
    params: { category: params.categoryId, page: params.page, limit: params.limit },
    timeoutMs: 15000,
  });
  if (resp.status !== 200) throw new Error('发现加载失败');
  const data = JSON.parse(resp.body);
  return {
    books: data.items.map(item => ({
      id: String(item.id),
      bookTitle: item.title,
      bookImage: item.cover || '',
      bookAnchor: item.author || '',
      bookDesc: item.intro || '',
      albumId: String(item.albumId || item.id),
    })),
    hasMore: data.hasMore === true,
  };
},
```

上例的 `HOST`、路径和响应字段仅用于说明协议，必须替换为源站实际接口。
分类 `id` 和 `title` 必填，重复 `id` 保留第一项。
`books` 使用与 `search` 相同的字段契约，自定义字段同样透传到 `chapters`。
`hasMore` 必须是布尔值，省略时视为 `false`。
未实现 `discoverCategories` 或返回 `null` 时使用分类搜索兼容模式；返回 `[]` 表示该源暂无分类。
已声明原生发现但接口失败时显示重试，不会悄悄切换成搜索结果。

## 验证

```text
./gradlew :core:extension-engine:test
./gradlew :core:online:testDebugUnitTest :core:extension:testDebugUnitTest
./gradlew :app:testFreeDebugUnitTest
./gradlew :app:assembleFreeDebug
```

回归测试覆盖可选阶段兼容、分类和分页协议校验、发现结果的自定义字段透传、旧源搜索页码，以及分页去重和重复页终止。
导航注册和序列化由现有自动枚举 Destination 的测试覆盖。
手动检查：无书源、导入返回刷新、切换书源/分类、首屏与下一页重试、加入书架和章节播放。

`Discovery tests and APK` 工作流会在发现分支推送后先运行回归测试、全量单元测试和 Android lint，全部通过后才打包并上传 APK。
测试版使用独立包名 `app.timbre.audiobook.discovery`，可与原版同时安装，书架和书源数据独立。
