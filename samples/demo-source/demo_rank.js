// 最小推荐脚本示例：不注册源，只向 globalThis 挂两个推荐函数。
// 配置见同目录 demo_rank.json，manifest 里只需 "recommenderConfig": "demo_rank.json"。
// 返回字段与 search 阶段一致；点击后走主源（demo.js）的 chapters/audio。
;(function () {
  'use strict';

  var HOST = 'https://api.example.com';

  // groupId = "hot"
  globalThis.__rec_demo_hot = async function (params) {
    var p = params || {};
    var resp = await http.get(HOST + '/rank', {
      params: { section: 'hot', page: Number(p.page || 1), limit: Number(p.limit || 30) },
      headers: { Accept: 'application/json' },
      timeoutMs: 15000,
    });
    if (resp.status !== 200) throw new Error('热门榜单请求失败: HTTP ' + resp.status);
    return toBooks(JSON.parse(resp.body));
  };

  // groupId = "newest"：同一个脚本里可以挂多个推荐位
  globalThis.__rec_demo_newest = async function (params) {
    var p = params || {};
    var resp = await http.get(HOST + '/rank', {
      params: { section: 'newest', page: Number(p.page || 1), limit: Number(p.limit || 30) },
      headers: { Accept: 'application/json' },
      timeoutMs: 15000,
    });
    if (resp.status !== 200) throw new Error('最新上架请求失败: HTTP ' + resp.status);
    return toBooks(JSON.parse(resp.body));
  };

  function toBooks(data) {
    var list = (data && data.list) || [];
    var result = [];
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      if (!item.id || !item.title) continue;
      result.push({
        id: String(item.id),
        bookTitle: item.title,
        bookImage: item.cover || '',
        bookAnchor: item.author || '',
        bookDesc: item.intro || '',
        count: item.tracks || 0,
        // 透传给 chapters 阶段
        albumId: String(item.album_id || item.id),
      });
    }
    return result;
  }
})();
