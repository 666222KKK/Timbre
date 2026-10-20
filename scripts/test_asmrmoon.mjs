import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

export async function runTests() {
  const code = await fs.readFile(new URL('../samples/asmrmoon/asmrmoon.js', import.meta.url), 'utf8');
  const manifest = JSON.parse(await fs.readFile(new URL('../samples/asmrmoon/manifest.json', import.meta.url), 'utf8'));
  const passed = [];
  function source(handler) {
    let registered;
    const calls = [];
    const context = vm.createContext({
      registerSource(value) { registered = value; },
      http: {
        async sleep() {},
        async post(url, options) {
          calls.push({ url, options });
          return handler(url, options, calls.length);
        },
      },
    });
    vm.runInContext(code, context, { timeout: 1000 });
    return { api: registered, calls };
  }
  const json = data => ({ status: 200, body: JSON.stringify({ code: 200, data }) });
  async function test(name, action) { await action(); passed.push(name); }
  await test('manifest and sandbox contract', async () => {
    const { api } = source(() => json({}));
    assert.equal(api.id, manifest.sources[0].id);
    for (const stage of ['search', 'chapters', 'audio', 'discover', 'discoverCategories']) assert.equal(typeof api[stage], 'function');
    assert.equal(manifest.allowInsecure, false);
    assert.match(manifest.id, /^[a-z0-9][a-z0-9._-]{1,63}$/);
  });
  await test('search uses pagination, filters files and preserves stable paths', async () => {
    const { api, calls } = source(() => json({ total: 3, content: [
      { parent: '/English', name: 'Relax', is_dir: true },
      { parent: '/English/Relax', name: '01 rain.mp3', is_dir: false },
      { parent: '/English', name: 'README.md', is_dir: false },
    ] }));
    const books = await api.search({ keyword: 'rain & sleep', page: 2, limit: 20 });
    assert.equal(books.length, 2);
    assert.equal(calls[0].options.json.keywords, 'rain & sleep');
    assert.equal(calls[0].options.json.page, 2);
    assert.equal(calls[0].options.json.per_page, 20);
    assert.equal(decodeURIComponent(books[0].id.slice(2)), '/English/Relax');
  });
  await test('discovery exposes real root categories and bounded pagination', async () => {
    const { api } = source(() => json({ total: 31, content: [{ name: 'Relax', is_dir: true }] }));
    assert.equal((await api.discoverCategories()).length, 3);
    assert.equal((await api.discover({ categoryId: '/English', page: 1, limit: 30 })).hasMore, true);
    assert.equal((await api.discover({ categoryId: '/English', page: 2, limit: 30 })).hasMore, false);
    await assert.rejects(() => api.discover({ categoryId: '/private', page: 1 }), /未知/);
  });
  await test('recursive chapters sort numerically, include subfolders, skip non-audio', async () => {
    const { api } = source((url, options) => json({ total: options.json.path.endsWith('/Disc 2') ? 1 : 4, content:
      options.json.path.endsWith('/Disc 2') ? [{ name: '01 wind.flac', is_dir: false }] : [
        { name: '10 rain.mp3', is_dir: false }, { name: '2 rain.mp3', is_dir: false },
        { name: 'cover.jpg', is_dir: false }, { name: 'Disc 2', is_dir: true },
      ],
    }));
    const chapters = await api.chapters({ bookId: 'd:' + encodeURIComponent('/English/Relax') });
    assert.equal(chapters.length, 3);
    assert.equal(chapters[0].title, '2 rain.mp3');
    assert.equal(chapters[1].title, '10 rain.mp3');
    assert.equal(chapters[2].title, 'Disc 2/01 wind.flac');
    assert.equal(chapters[2].order, 3);
  });
  await test('large directory loads every page', async () => {
    const { api, calls } = source((url, options) => json({ total: 101, content: Array.from(
      { length: options.json.page === 1 ? 100 : 1 }, (_, i) => ({ name: `${(options.json.page - 1) * 100 + i}.mp3`, is_dir: false }),
    ) }));
    assert.equal((await api.chapters({ bookId: 'd:%2FEnglish%2FRelax' })).length, 101);
    assert.equal(calls.length, 2);
  });
  await test('single file works without process caches', async () => {
    const { api, calls } = source(() => json({ raw_url: 'https://cdn.example.com/one.mp3?sign=renewed' }));
    const id = 'f:' + encodeURIComponent('/English/Relax/one.mp3');
    assert.equal((await api.chapters({ bookId: id })).length, 1);
    const audio = await api.audio({ bookId: 'ignored', chapterId: id });
    assert.equal(audio.url, 'https://cdn.example.com/one.mp3?sign=renewed');
    assert.equal(calls[0].options.json.path, '/English/Relax/one.mp3');
    assert.equal(audio.headers.Referer, 'https://asmrmoon.com/');
    assert.equal(audio.headers.Cookie, undefined);
  });
  await test('each playback fetches a fresh signed URL', async () => {
    const { api } = source((url, options, call) => json({ raw_url: `https://cdn.example.com/a.mp3?sign=${call}` }));
    const p = { chapterId: 'f:%2Fa.mp3' };
    assert.notEqual((await api.audio(p)).url, (await api.audio(p)).url);
  });
  await test('HTML verification is an actionable error, not an empty search', async () => {
    const { api } = source(() => ({ status: 403, body: '<html><title>月盾 MOONSHIELD</title></html>' }));
    await assert.rejects(() => api.search({ keyword: 'rain' }), /网站验证/);
  });
  await test('protected folders are never bypassed or silently dropped', async () => {
    const { api } = source(() => ({ status: 200, body: '{"code":403,"message":"password required"}' }));
    await assert.rejects(() => api.chapters({ bookId: 'd:%2Fprivate' }), /受保护/);
  });
  await test('repeated server pagination stops safely', async () => {
    const { api } = source(() => json({ total: 999, content: [{ name: 'one.mp3', is_dir: false }] }));
    await assert.rejects(() => api.chapters({ bookId: 'd:%2Fa' }), /分页重复/);
  });
  await test('traversal and invalid names are rejected', async () => {
    const { api } = source(() => json({ total: 1, content: [{ name: '../escape.mp3', is_dir: false }] }));
    await assert.rejects(() => api.chapters({ bookId: 'd:%2Fa' }), /无效文件名/);
    await assert.rejects(() => api.audio({ chapterId: 'f:%2F..%2Fa.mp3' }), /不允许/);
  });
  await test('invalid JSON, HTTP failures and malformed responses are errors', async () => {
    for (const response of [
      { status: 500, body: 'server error' }, { status: 200, body: 'invalid json' }, json({ content: {}, total: 1 }),
    ]) {
      const { api } = source(() => response);
      await assert.rejects(() => api.search({ keyword: 'rain' }));
    }
  });
  await test('invalid or insecure media URLs are not returned', async () => {
    const { api } = source(() => json({ raw_url: 'http://cdn.example.com/a.mp3' }));
    await assert.rejects(() => api.audio({ chapterId: 'f:%2Fa.mp3' }), /HTTPS/);
  });
  return { tests: passed.length, passed, failed: 0 };
}

if (typeof process !== 'undefined' && process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  console.log(JSON.stringify(await runTests(), null, 2));
}
