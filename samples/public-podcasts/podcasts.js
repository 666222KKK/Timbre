;(function () {
  'use strict';
  var UA = 'Timbre-PublicPodcasts/1.0';
  var cache = Object.create(null);
  var categories = [
    { id: 'hk:all', title: '香港热门' },
    { id: 'tw:all', title: '台湾热门' },
    { id: 'us:all', title: '国际热门' },
    { id: 'tw:1304', title: '教育' },
    { id: 'tw:1324', title: '社会与文化' },
    { id: 'tw:1318', title: '科技' },
    { id: 'tw:1303', title: '喜剧' },
    { id: 'tw:1489', title: '新闻' },
    { id: 'tw:1483', title: '虚构故事' },
    { id: 'tw:1488', title: '真实犯罪' },
    { id: 'tw:1512', title: '健康与健身' },
  ];
  function integer(value, fallback, max) {
    var n = Number(value);
    return Number.isInteger(n) && n > 0 ? Math.min(n, max) : fallback;
  }
  function absolute(value) {
    var s = String(value || '').trim();
    if (!/^https?:\/\/[^/?#\s@]+(?:[/?#]|$)/i.test(s) || /[\u0000-\u0020\\]/.test(s)) {
      throw new Error('需要有效的 HTTP/HTTPS 地址');
    }
    return s.replace(/#.*$/, '');
  }
  function resolve(base, value) {
    var v = String(value || '').trim();
    if (/^https?:\/\//i.test(v)) return absolute(v);
    var parts = absolute(base).match(/^(https?:)\/\/([^/?#]+)([^?#]*)/i);
    if (v.startsWith('//')) return absolute(parts[1] + v);
    if (!v || /^[a-z][a-z0-9+.-]*:/i.test(v)) throw new Error('无效的 RSS 媒体地址');
    if (v[0] === '?') return absolute(parts[1] + '//' + parts[2] + parts[3] + v);
    var path = v[0] === '/' ? v : parts[3].replace(/[^/]*$/, '') + v;
    var suffix = path.match(/[?#].*$/);
    path = path.replace(/[?#].*$/, '');
    var stack = [];
    path.split('/').forEach(function (p) {
      if (p === '..') stack.pop();
      else if (p && p !== '.') stack.push(p);
    });
    return absolute(parts[1] + '//' + parts[2] + '/' + stack.join('/') + (suffix ? suffix[0] : ''));
  }
  function remember(key, value, ttl) {
    var keys = Object.keys(cache);
    if (keys.length >= 12) delete cache[keys[0]];
    cache[key] = { value: value, until: Date.now() + ttl };
    return value;
  }
  function cached(key) {
    var item = cache[key];
    return item && item.until > Date.now() ? item.value : null;
  }
  async function get(url, json) {
    var response = await http.get(absolute(url), {
      headers: { 'User-Agent': UA, Accept: json ? 'application/json' : 'application/rss+xml, application/atom+xml, application/xml, text/xml' },
      timeoutMs: 25000,
    });
    if (response.status < 200 || response.status >= 300) throw new Error('播客请求失败：HTTP ' + response.status);
    var raw = String(response.body || '').replace(/^\uFEFF/, '').trim();
    if (raw.length > 12 * 1024 * 1024) throw new Error('RSS 文件过大，请选择较小的订阅地址');
    if (!json) return raw;
    try { return JSON.parse(raw); } catch (_) { throw new Error('播客目录未返回有效 JSON，请稍后重试'); }
  }
  function entities(value) {
    return String(value).replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, function (full, code) {
      var named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
      if (named[code]) return named[code];
      var n = code[1] === 'x' || code[1] === 'X' ? parseInt(code.slice(2), 16) : Number(code.slice(1));
      return n > 0 && n <= 0x10ffff && !(n >= 0xd800 && n <= 0xdfff) ? String.fromCodePoint(n) : full;
    });
  }
  function parseXml(raw) {
    if (/<!DOCTYPE|<!ENTITY/i.test(raw)) throw new Error('RSS 含有不支持的 XML 声明');
    var root = { name: '', attrs: {}, children: [], parts: [] }, stack = [root], nodes = 0;
    var tokens = /<!\[CDATA\[[\s\S]*?\]\]>|<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<(?:[^<>"']|"[^"]*"|'[^']*')*>|[^<]+/g;
    var match, cursor = 0;
    while ((match = tokens.exec(raw))) {
      if (match.index !== cursor) throw new Error('RSS XML 格式损坏');
      cursor = tokens.lastIndex;
      var token = match[0], node = stack[stack.length - 1];
      if (token.startsWith('<!--') || token.startsWith('<?')) continue;
      if (token.startsWith('<![CDATA[')) { node.parts.push(token.slice(9, -3)); continue; }
      if (token.startsWith('</')) {
        if (stack.length <= 1 || token.slice(2, -1).trim() !== node.name) throw new Error('RSS 标签不匹配');
        stack.pop();
      } else if (token[0] === '<') {
        var head = token.match(/^<([a-zA-Z_][\w:.-]*)/);
        if (!head || ++nodes > 120000 || stack.length > 64) throw new Error('RSS 格式无效或结构过大');
        var attrs = Object.create(null), attr, body = token.slice(head[0].length).replace(/\/?\s*>$/, '');
        var pattern = /\s+([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
        var end = 0;
        while ((attr = pattern.exec(body))) {
          if (attr.index !== end || Object.prototype.hasOwnProperty.call(attrs, attr[1])) throw new Error('RSS 属性格式无效');
          attrs[attr[1]] = entities(attr[2] === undefined ? attr[3] : attr[2]);
          end = pattern.lastIndex;
        }
        if (body.slice(end).trim()) throw new Error('RSS 属性格式无效');
        var child = { name: head[1], attrs: attrs, children: [], parts: [] };
        node.children.push(child);
        node.parts.push(child);
        if (!/\/\s*>$/.test(token)) stack.push(child);
      } else node.parts.push(entities(token));
    }
    if (cursor !== raw.length || stack.length !== 1 || root.children.length !== 1) throw new Error('RSS 文件不完整');
    return root.children[0];
  }
  function local(node) { return node.name.split(':').pop(); }
  function children(node, name) { return node.children.filter(function (n) { return local(n) === name; }); }
  function text(node) { return node ? node.parts.map(function (p) { return typeof p === 'string' ? p : text(p); }).join('') : ''; }
  function field(node, name) { return text(children(node, name)[0]).trim(); }
  function plain(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
  function clean(value) { return entities(String(value || '').replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim(); }
  function seconds(value) {
    var parts = String(value || '').trim().split(':');
    if (parts.length > 3 || parts.some(function (p) { return !/^\d+(?:\.\d+)?$/.test(p); })) return 0;
    return Math.floor(parts.reduce(function (n, p) { return n * 60 + Number(p); }, 0));
  }
  function feedBook(feed, url) {
    return { id: 'rss:' + encodeURIComponent(url), bookTitle: feed.title || 'RSS 播客', bookImage: feed.image, bookAnchor: feed.author, bookDesc: feed.description, feedUrl: url };
  }
  function parseFeed(raw, url) {
    var document = parseXml(raw), atom = local(document) === 'feed';
    var channel = atom ? document : children(document, 'channel')[0];
    if ((!atom && local(document) !== 'rss') || !channel) throw new Error('该地址不是 RSS/Atom 播客订阅，请输入节目 RSS 地址');
    var entries = children(channel, atom ? 'entry' : 'item'), episodes = [], seen = Object.create(null);
    for (var item of entries) {
      var enclosure = children(item, 'enclosure').find(function (n) { return !n.attrs.type || /^audio\//i.test(n.attrs.type); });
      if (!enclosure) enclosure = children(item, 'link').find(function (n) { return n.attrs.rel === 'enclosure' && (!n.attrs.type || /^audio\//i.test(n.attrs.type)); });
      if (!enclosure) continue;
      var media;
      try { media = resolve(url, enclosure.attrs.url || enclosure.attrs.href); } catch (_) { continue; }
      var guid = field(item, atom ? 'id' : 'guid');
      var id = guid ? 'g:' + encodeURIComponent(guid) : 'u:' + encodeURIComponent(media);
      if (seen[id] || seen['url:' + media]) continue;
      seen[id] = seen['url:' + media] = true;
      episodes.push({ chapter_id: id, title: plain(field(item, 'title')) || '未命名单集', duration: seconds(field(item, 'duration')), order: episodes.length + 1, mediaUrl: media });
      if (episodes.length > 10000) throw new Error('RSS 超过 10000 集，请使用较小的订阅源');
    }
    var image = children(channel, 'image')[0], author = children(channel, 'author')[0];
    var cover = image && (image.attrs.href || field(image, 'url')) || field(channel, 'logo');
    try { cover = cover ? resolve(url, cover) : ''; } catch (_) { cover = ''; }
    return { title: plain(field(channel, 'title')), author: clean(author ? field(author, 'name') || text(author) : field(channel, 'creator')), image: cover, description: clean(field(channel, 'description') || field(channel, 'subtitle')), episodes: episodes };
  }
  async function loadFeed(url, fresh) {
    url = absolute(url);
    var key = 'feed:' + url, value = !fresh && cached(key);
    if (value) return value;
    return remember(key, parseFeed(await get(url, false), url), 60000);
  }
  function result(item) {
    if (!item.feedUrl || !item.collectionId) return null;
    try { absolute(item.feedUrl); } catch (_) { return null; }
    return { id: 'apple:' + item.collectionId, bookTitle: item.collectionName || item.trackName || '播客', bookImage: item.artworkUrl600 || item.artworkUrl100 || '', bookAnchor: item.artistName || '', bookDesc: (item.genres || []).join(' · '), feedUrl: item.feedUrl };
  }
  async function lookup(ids, country) {
    var data = await get('https://itunes.apple.com/lookup?id=' + ids.join(',') + '&entity=podcast&country=' + country, true);
    if (!Array.isArray(data.results)) throw new Error('播客目录格式已改变');
    return data.results.map(result).filter(Boolean);
  }
  async function feedUrl(params) {
    var id = String(params.bookId || '');
    if (id.startsWith('rss:')) return absolute(decodeURIComponent(id.slice(4)));
    if (!/^apple:\d+$/.test(id)) throw new Error('节目 ID 无效，请重新搜索节目');
    if (params.feedUrl) return absolute(params.feedUrl);
    for (var country of ['hk', 'tw', 'us']) {
      var books = await lookup([id.slice(6)], country);
      if (books.length) return books[0].feedUrl;
    }
    throw new Error('该节目没有公开 RSS，或已从目录移除');
  }
  registerSource({
    id: 'public-podcasts',
    async search(params) {
      var keyword = String(params.keyword || '').trim(), page = integer(params.page, 1, 10000), size = integer(params.limit, 30, 100);
      if (!keyword) return [];
      var direct = keyword.replace(/^rss:\s*/i, '');
      if (/^https?:\/\//i.test(direct)) {
        if (page > 1) return [];
        var url = absolute(direct);
        return [feedBook(await loadFeed(url), url)];
      }
      var country = 'hk', prefix = keyword.match(/^(hk|tw|us):\s*(.+)$/i);
      if (prefix) { country = prefix[1].toLowerCase(); keyword = prefix[2]; }
      var start = (page - 1) * size;
      if (start >= 200) return [];
      var key = 'search:' + country + ':' + keyword, books = cached(key);
      if (!books) {
        var data = await get('https://itunes.apple.com/search?media=podcast&entity=podcast&limit=200&explicit=Yes&country=' + country + '&term=' + encodeURIComponent(keyword), true);
        if (!Array.isArray(data.results)) throw new Error('搜索响应缺少节目列表');
        var seen = Object.create(null);
        books = data.results.map(result).filter(function (b) { if (!b || seen[b.id]) return false; seen[b.id] = true; return true; });
        remember(key, books, 300000);
      }
      return books.slice(start, start + size);
    },
    async discoverCategories() { return categories; },
    async discover(params) {
      var category = categories.find(function (c) { return c.id === params.categoryId; });
      if (!category) throw new Error('未知播客分类');
      var page = integer(params.page, 1, 10000), size = integer(params.limit, 30, 100), start = (page - 1) * size;
      if (start >= 100) return { books: [], hasMore: false };
      var key = 'chart:' + category.id, entries = cached(key), parts = category.id.split(':');
      if (!entries) {
        var data = await get('https://itunes.apple.com/' + parts[0] + '/rss/toppodcasts/limit=100/' + (parts[1] === 'all' ? '' : 'genre=' + parts[1] + '/') + 'json', true);
        if (!data.feed || (data.feed.entry !== undefined && !Array.isArray(data.feed.entry))) throw new Error('榜单响应格式已改变');
        entries = (data.feed.entry || []).map(function (e) { return e.id && e.id.attributes && e.id.attributes['im:id']; }).filter(function (id) { return /^\d+$/.test(String(id)); });
        remember(key, entries, 300000);
      }
      var ids = entries.slice(start, start + size), books = [], byId = Object.create(null);
      for (var offset = 0; offset < ids.length; offset += 50) {
        var batch = await lookup(ids.slice(offset, offset + 50), parts[0]);
        batch.forEach(function (b) { byId[b.id.slice(6)] = b; });
      }
      ids.forEach(function (id) { if (byId[id]) books.push(byId[id]); });
      return { books: books, hasMore: start + size < entries.length };
    },
    async chapters(params) {
      var feed = await loadFeed(await feedUrl(params));
      if (!feed.episodes.length) throw new Error('此 RSS 没有公开音频单集');
      return feed.episodes;
    },
    async audio(params) {
      var feed = await loadFeed(await feedUrl(params), true);
      var item = feed.episodes.find(function (e) { return e.chapter_id === params.chapterId; });
      if (!item) throw new Error('该单集已从 RSS 移除，请刷新节目章节列表');
      return { url: item.mediaUrl, headers: { 'User-Agent': UA } };
    },
  });
})();
