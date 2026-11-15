// ============================================================
// Timbre 推荐脚本模板（独立文件，不注册源）
// ------------------------------------------------------------
// 与主源脚本的关系：
//   - 同一个 .jdr 包、同一份 manifest.json
//   - 不调用 registerSource()，所以不会出现在搜索源列表里
//   - 只把函数挂到 globalThis，供 yourrank.json 的 entry 调用
//
// yourrank.json（推荐位独立配置）：
// {
//   "script": "yourrank.js",              // 本文件；槽位可单独覆盖
//   "recommenders": [
//     {
//       "id": "hot",                       // 传给脚本的 groupId
//       "name": "热门榜单",                 // 界面上的 chip
//       "entry": "__rec_hot",              // 本文件导出的全局函数名
//       "description": "站内播放量最高",     // chip 下方的说明
//       "cacheMinutes": 180                // 0/不写 = 不缓存
//     }
//   ]
// }
//
// manifest.json 里对应源只需一行：
//   "recommenderConfig": "yourrank.json"
//
// 返回值与 search 阶段完全一致：
//   [{ id, bookTitle, bookImage?, bookAnchor?, bookDesc?, count?, ...自定义 }]
// 点击后走**主源**的 chapters/audio（宿主会路由到主源），所以自定义字段
// （如 albumId）会被透传给 chapters，跟搜索结果的透传规则一样。
//
// params = { groupId, page, limit }
// ============================================================
;(function () {
  'use strict';

  var HOST = 'https://api.example.com';

  globalThis.__rec_hot = async function (params) {
    var p = params || {};
    var page = Number(p.page || 1);
    var limit = Number(p.limit || 30);

    var resp = await http.get(HOST + '/rank', {
      params: { section: p.groupId || 'hot', page: page, limit: limit },
      headers: { 'User-Agent': 'MySource/1.0' },
      timeoutMs: 15000,
    });
    if (resp.status !== 200) throw new Error('榜单请求失败: HTTP ' + resp.status);

    var data = JSON.parse(resp.body);
    return (data.list || []).map(function (item) {
      return {
        id: String(item.id),
        bookTitle: item.title,
        bookImage: item.cover || '',
        bookAnchor: item.author || '',
        bookDesc: item.intro || '',
        count: item.tracks || 0,
        // 透传给 chapters 的字段
        albumId: String(item.album_id || item.id),
      };
    });
  };

  // 多个推荐位可以共用一个脚本：再挂一个函数，换不同接口即可。
  // globalThis.__rec_new = async function (params) { ... };
})();
