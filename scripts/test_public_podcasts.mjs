import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

export async function runTests() {
  const code = await fs.readFile(new URL('../samples/public-podcasts/podcasts.js', import.meta.url), 'utf8');
  const manifest = JSON.parse(await fs.readFile(new URL('../samples/public-podcasts/manifest.json', import.meta.url), 'utf8'));
  const passed = [];
  function source(handler) {
    let api;
    const calls = [];
    const sandbox = vm.createContext({ registerSource(value) { api = value; }, http: {
      async get(url, options) { calls.push({ url, options }); return handler(url, options, calls.length); },
    } });
    vm.runInContext(code, sandbox, { timeout: 1000 });
    return { api, calls };
  }
  const response = body => ({ status: 200, body: typeof body === 'string' ? body : JSON.stringify(body) });
  const book = (id = 1) => ({ collectionId: id, collectionName: 'Podcast ' + id, feedUrl: 'https://feeds.example.com/' + id, artistName: 'Host', genres: ['Technology'] });
  const rss = (url = '/episode.mp3?x=1&amp;y=2', guid = 'stable-guid') => `<?xml version="1.0"?><rss version="2.0" xmlns:itunes="urn:itunes"><channel><title><![CDATA[Podcast & more]]></title><itunes:author>Host</itunes:author><itunes:image href="/cover.jpg"/><item><title><![CDATA[Episode <one>]]></title><guid>${guid}</guid><itunes:duration>01:02:03</itunes:duration><enclosure type="audio/mpeg" url="${url}" length="1"/></item><item><title>Video</title><enclosure type="video/mp4" url="https://cdn.example.com/video.mp4"/></item></channel></rss>`;
  const id = 'rss:' + encodeURIComponent('https://feeds.example.com/show/rss');
  async function test(name, action) { await action(); passed.push(name); }
  await test('manifest and DOM-free sandbox expose all stages', async () => {
    const { api } = source(() => response(''));
    assert.equal(api.id, manifest.sources[0].id);
    for (const stage of ['search', 'chapters', 'audio', 'discover', 'discoverCategories']) assert.equal(typeof api[stage], 'function');
    assert.equal(manifest.allowInsecure, false);
  });
  await test('search caches and paginates 200 directory results without duplication', async () => {
    const { api, calls } = source(() => response({ results: [...Array.from({ length: 65 }, (_, i) => book(i + 1)), book(1), { collectionId: 99 }] }));
    const first = await api.search({ keyword: '科技 & 故事', page: 1, limit: 30 });
    const second = await api.search({ keyword: '科技 & 故事', page: 2, limit: 30 });
    const third = await api.search({ keyword: '科技 & 故事', page: 3, limit: 30 });
    assert.equal(first.length, 30); assert.equal(second.length, 30); assert.equal(third.length, 5);
    assert.equal(second[0].id, 'apple:31'); assert.equal(calls.length, 1);
    assert.match(calls[0].url, /term=%E7%A7%91%E6%8A%80%20%26%20%E6%95%85%E4%BA%8B/);
    assert.equal((await api.search({ keyword: 'x', page: 8, limit: 30 })).length, 0);
  });
  await test('search supports regions and real RSS URLs without reading platform pages', async () => {
    const { api, calls } = source(url => url.includes('/search?') ? response({ results: [book()] }) : response(rss()));
    await api.search({ keyword: 'us:science' }); assert.match(calls[0].url, /country=us/);
    const books = await api.search({ keyword: 'rss:https://feeds.example.com/show/rss' });
    assert.equal(books[0].bookTitle, 'Podcast & more');
    assert.equal(books[0].bookImage, 'https://feeds.example.com/cover.jpg');
    assert.equal((await api.search({ keyword: 'https://feeds.example.com/show/rss', page: 2 })).length, 0);
  });
  await test('discovery keeps chart ordering when lookup returns a different order', async () => {
    const { api } = source(url => url.includes('/rss/') ? response({ feed: { entry: [1, 2, 3].map(id => ({ id: { attributes: { 'im:id': String(id) } } })) } }) : response({ results: [book(2), book(1)] }));
    assert.ok((await api.discoverCategories()).length >= 8);
    const first = await api.discover({ categoryId: 'hk:all', page: 1, limit: 2 });
    assert.deepEqual(Array.from(first.books, b => b.id), ['apple:1', 'apple:2']); assert.equal(first.hasMore, true);
    const end = await api.discover({ categoryId: 'hk:all', page: 2, limit: 2 }); assert.equal(end.hasMore, false);
    await assert.rejects(() => api.discover({ categoryId: 'not-real' }), /未知/);
  });
  await test('RSS parses CDATA, entities, relative media and durations, skips video', async () => {
    const { api } = source(() => response(rss()));
    const chapters = await api.chapters({ bookId: id });
    assert.equal(chapters.length, 1); assert.equal(chapters[0].title, 'Episode <one>'); assert.equal(chapters[0].duration, 3723);
    assert.equal(chapters[0].mediaUrl, 'https://feeds.example.com/episode.mp3?x=1&y=2');
  });
  await test('Atom feeds expose public audio enclosure links', async () => {
    const { api } = source(() => response(`<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title><entry><id>urn:episode:1</id><title>A &amp; B</title><link rel="enclosure" type="audio/mp4" href="../a.m4a"/></entry></feed>`));
    const items = await api.chapters({ bookId: id }); assert.equal(items[0].title, 'A & B');
    assert.equal(items[0].mediaUrl, 'https://feeds.example.com/a.m4a');
  });
  await test('chapter identifiers stay stable and audio re-resolves after a cold restart', async () => {
    const first = source(() => response(rss('https://cdn.example.com/old.mp3')));
    const chapters = await first.api.chapters({ bookId: id });
    const cold = source(url => url.includes('/lookup?') ? response({ results: [book(1)] }) : response(rss('https://cdn.example.com/fresh.mp3')));
    const audio = await cold.api.audio({ bookId: 'apple:1', chapterId: chapters[0].chapter_id });
    assert.equal(audio.url, 'https://cdn.example.com/fresh.mp3'); assert.match(cold.calls[0].url, /lookup\?id=1/);
  });
  await test('deleted episodes and audio-free feeds produce actionable errors', async () => {
    const { api } = source(() => response(rss()));
    await assert.rejects(() => api.audio({ bookId: id, chapterId: 'g:gone' }), /移除/);
    const empty = source(() => response('<rss><channel><title>Empty</title></channel></rss>'));
    await assert.rejects(() => empty.api.chapters({ bookId: id }), /没有公开音频/);
  });
  await test('HTTP failures and malformed directory JSON are not treated as empty results', async () => {
    await assert.rejects(() => source(() => ({ status: 429, body: '' })).api.search({ keyword: 'test' }), /429/);
    await assert.rejects(() => source(() => response('<html>challenge</html>')).api.search({ keyword: 'test' }), /JSON/);
    await assert.rejects(() => source(() => response({})).api.search({ keyword: 'test' }), /缺少/);
  });
  await test('XML rejects truncation, mismatched tags, entity declarations and HTML', async () => {
    for (const xml of ['<rss><channel>', '<rss><channel></rss>', '<!DOCTYPE rss [<!ENTITY e SYSTEM "file:///etc/passwd">]><rss/>', '<html><body>Denied</body></html>']) {
      await assert.rejects(() => source(() => response(xml)).api.chapters({ bookId: id }));
    }
  });
  await test('non-HTTP enclosures are skipped and duplicate GUIDs/URLs deduplicate', async () => {
    const xml = '<rss><channel>' + ['javascript:bad', 'https://cdn.example.com/a.mp3', 'https://cdn.example.com/a.mp3'].map((url, i) => `<item><guid>${i}</guid><title>E</title><enclosure url="${url}"/></item>`).join('') + '</channel></rss>';
    const { api } = source(() => response(xml)); assert.equal((await api.chapters({ bookId: id })).length, 1);
    await assert.rejects(() => api.search({ keyword: 'https://user:pass@example.com/feed' }), /有效/);
  });
  return { tests: passed.length, passed, failed: 0 };
}
if (typeof process !== 'undefined' && process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  console.log(JSON.stringify(await runTests(), null, 2));
}
