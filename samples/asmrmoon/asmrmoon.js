;(function () {
  'use strict';
  var HOST = 'https://asmrmoon.com';
  var UA = 'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36';
  var AUDIO = /\.(mp3|m4a|aac|wav|flac|ogg|opus|m3u8|mp4)$/i;
  var lastRequest = 0;

  function path(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || /[\u0000\\]/.test(value)) throw new Error('无效的站点路径');
    var parts = value.split('/').filter(Boolean);
    if (parts.some(function (p) { return p === '.' || p === '..'; })) throw new Error('不允许的站点路径');
    return '/' + parts.join('/');
  }
  function child(parent, name) {
    if (typeof name !== 'string' || !name || name === '.' || name === '..' || /[\/\\\u0000]/.test(name)) throw new Error('站点返回无效文件名');
    return path((parent === '/' ? '' : parent) + '/' + name);
  }
  function nameOf(p) { return p.split('/').filter(Boolean).pop() || 'ASMR MOON'; }
  function id(p, isDir) { return (isDir ? 'd:' : 'f:') + encodeURIComponent(path(p)); }
  function fromId(value) {
    var text = String(value || '');
    if (!/^[df]:/.test(text)) throw new Error('无效的书籍或章节 ID，请重新搜索');
    return { path: path(decodeURIComponent(text.slice(2))), directory: text[0] === 'd' };
  }
  function number(value, fallback, maximum) {
    var n = Number(value);
    return Number.isInteger(n) && n >= 1 ? Math.min(n, maximum) : fallback;
  }
  async function post(action, body) {
    // New hosts provide a non-blocking sleep bridge; never busy-wait in QuickJS.
    var wait = 1200 - (Date.now() - lastRequest);
    if (wait > 0 && typeof http.sleep === 'function') await http.sleep(wait);
    lastRequest = Date.now();
    var response = await http.post(HOST + '/api/fs/' + action, {
      json: body,
      headers: { 'User-Agent': UA, Referer: HOST + '/', Origin: HOST, Accept: 'application/json' },
      timeoutMs: 25000,
    });
    var raw = String(response.body || '').trim();
    if (raw[0] === '<' || /MOONSHIELD|月盾|cf-chl-|captcha/i.test(raw.slice(0, 300))) {
      throw new Error('ASMR MOON 需要网站验证：设置 → 接口源 → ASMR MOON → 网站验证，完成后保存会话再重试');
    }
    if (response.status < 200 || response.status >= 300) throw new Error('ASMR MOON 请求失败：HTTP ' + response.status);
    var json;
    try { json = JSON.parse(raw); } catch (_) { throw new Error('ASMR MOON 没有返回有效 JSON'); }
    if (json.code !== 200) {
      var message = String(json.message || '');
      if (/password|密码|protected|保护/i.test(message)) throw new Error('该目录受保护，需要站点访问权限，本源不会绕过目录限制');
      if (json.code === 401 || json.code === 403) throw new Error('网站会话已失效或需要登录，请在接口源页重新完成网站验证');
      throw new Error('ASMR MOON 接口错误：' + json.code + ' ' + message.slice(0, 100));
    }
    if (!json.data || typeof json.data !== 'object') throw new Error('ASMR MOON 返回了空数据');
    return json.data;
  }
  function content(data) {
    if (data.content === null && Number(data.total) === 0) return [];
    if (!Array.isArray(data.content)) throw new Error('站点目录格式已改变：缺少 data.content');
    return data.content;
  }
  function book(item, parent) {
    var directory = item.is_dir === true;
    if (!directory && !AUDIO.test(item.name || '')) return null;
    var full = child(parent, item.name);
    return { id: id(full, directory), bookTitle: item.name, bookAnchor: nameOf(parent), bookDesc: full };
  }
  function books(items, parent) {
    var seen = Object.create(null);
    return items.map(function (item) { return book(item, item.parent === undefined ? parent : path(item.parent)); })
      .filter(function (b) { if (!b || seen[b.id]) return false; seen[b.id] = true; return true; });
  }
  async function list(p, page, size) {
    return post('list', { path: p, password: '', page: page, per_page: size, refresh: false });
  }
  function compare(a, b) {
    var aa = a.name.toLowerCase().match(/\d+|\D+/g) || [];
    var bb = b.name.toLowerCase().match(/\d+|\D+/g) || [];
    for (var i = 0; i < Math.min(aa.length, bb.length); i++) {
      if (aa[i] === bb[i]) continue;
      if (/^\d+$/.test(aa[i]) && /^\d+$/.test(bb[i])) {
        var delta = Number(aa[i]) - Number(bb[i]);
        if (delta) return delta;
      }
      return aa[i] < bb[i] ? -1 : 1;
    }
    return aa.length - bb.length;
  }

  registerSource({
    id: 'asmrmoon',
    async search(params) {
      var keyword = String(params.keyword || '').trim();
      var page = number(params.page, 1, 10000);
      var size = number(params.limit, 30, 100);
      if (keyword.startsWith('path:')) {
        if (page > 1) return [];
        var p = path(keyword.slice(5).trim());
        var item = await post('get', { path: p, password: '' });
        return books([{ name: nameOf(p), is_dir: item.is_dir }], p.substring(0, p.lastIndexOf('/')) || '/');
      }
      if (!keyword) return [];
      var data = await post('search', { parent: '/', keywords: keyword, page: page, per_page: size, password: '' });
      return books(content(data), '/');
    },
    async discoverCategories() {
      return [
        { id: '/中文音声', title: '中文音声' },
        { id: '/日韩音声', title: '日韩音声' },
        { id: '/English', title: 'English' },
      ];
    },
    async discover(params) {
      var p = path(String(params.categoryId));
      if (!['/中文音声', '/日韩音声', '/English'].includes(p)) throw new Error('未知发现分类');
      var page = number(params.page, 1, 10000);
      var size = number(params.limit, 30, 100);
      var data = await list(p, page, size);
      var items = content(data);
      return { books: books(items, p), hasMore: Number.isFinite(Number(data.total)) ? page * size < Number(data.total) : items.length === size };
    },
    async chapters(params) {
      var target = fromId(params.bookId);
      if (!target.directory) return [{ chapter_id: id(target.path, false), title: nameOf(target.path), order: 1 }];
      var result = [], seen = Object.create(null), requests = 0;
      async function walk(p, prefix, depth) {
        if (depth > 12 || seen[p]) throw new Error('目录嵌套过深或存在重复路径，请搜索更具体的作品目录');
        seen[p] = true;
        var entries = [], previous = '';
        for (var page = 1; ; page++) {
          if (++requests > 200) throw new Error('目录过大，请搜索具体作品，或用 path:/目录/作品 打开较小目录');
          var data = await list(p, page, 100);
          var batch = content(data);
          var fingerprint = JSON.stringify(batch.map(function (x) { return [x.name, x.is_dir]; }));
          if (batch.length && fingerprint === previous) throw new Error('站点分页重复，已停止加载以避免重复章节');
          previous = fingerprint;
          entries = entries.concat(batch);
          if (!batch.length || (Number.isFinite(Number(data.total)) ? entries.length >= Number(data.total) : batch.length < 100)) break;
        }
        entries.sort(compare);
        var visitedNames = Object.create(null);
        for (var item of entries) {
          if (visitedNames[item.name]) continue;
          visitedNames[item.name] = true;
          var full = child(p, item.name);
          var title = prefix + item.name;
          if (item.is_dir === true) await walk(full, title + '/', depth + 1);
          else if (AUDIO.test(item.name)) {
            if (result.length >= 2000) throw new Error('章节超过 2000，请选择更具体的作品目录');
            result.push({ chapter_id: id(full, false), title: title, order: result.length + 1 });
          }
        }
      }
      await walk(target.path, '', 0);
      if (!result.length) throw new Error('此目录没有可播放音频，请选择具体音频作品');
      return result;
    },
    async audio(params) {
      var target = fromId(params.chapterId);
      if (target.directory || !AUDIO.test(target.path)) throw new Error('所选章节不是可播放音频');
      var data = await post('get', { path: target.path, password: '' });
      if (typeof data.raw_url !== 'string' || !/^https:\/\//i.test(data.raw_url)) throw new Error('站点没有返回 HTTPS 音频直链');
      // Resolve afresh on each play: AList signatures expire. The host adds a
      // verified session only for this package and the exact HTTPS origin.
      return { url: data.raw_url, headers: { 'User-Agent': UA, Referer: HOST + '/' } };
    },
  });
})();
