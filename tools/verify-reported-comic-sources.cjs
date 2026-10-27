// Offline regressions for the two reported adapters; no accounts or other source routes.
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
function source(file, name, Network) {
  return vm.runInNewContext(fs.readFileSync('app/src/main/assets/js_extra/' + file + '.js', 'utf8') + '\nnew ' + name + '()',
    {ComicSource: class {}, Network});
}
(async () => {
  const calls = [];
  const nineteen = source('nineteen', 'Nineteen', {get: async url => {
    calls.push(url);
    if (url.startsWith('https://m.')) throw Error('connection reset');
    return {status: 200, body: 'var Mcpath = {};'};
  }});
  await nineteen.page('/comic/123');
  await nineteen.page('/chapter/456');
  assert.equal(calls.length, 3);
  assert.equal(calls[2], 'https://www.sxchex.com/chapter/456');
  assert.equal(nineteen.baseUrl, 'https://m.sxchex.com');
  await assert.rejects(nineteen.page('//untrusted.test/'), /请求地址无效/);
  const unavailable = source('nineteen', 'Nineteen', {get: async () => ({status: 200, body: 'blocked page'})});
  await assert.rejects(unavailable.page('/comic/123'), /两个站点线路均不可用/);

  const jar = new Map(); let searches = 0, guards = 0, tickets = 0;
  const bili = source('bilimanga', 'BiliManga', {
    getCookies: async () => [...jar].map(([name, value]) => ({name, value})),
    setCookies: async (_, cookies) => cookies.forEach(c => jar.set(c.name, c.value)),
    get: async url => {
      if (url.includes('guard=css')) { guards++; jar.set('jieqiSearchCss', 'css'); return {status: 200}; }
      if (url.includes('guard=js')) return {status: 200, body: 'document.cookie="jieqiSearchJs=a=b; path=/";'};
      if (url.includes('guard=redeem')) { tickets++; jar.set('jieqiSearchTicket', 'ticket'); return {status: 200}; }
      return {status: ++searches === 1 ? 403 : 200, body: 'valid-results'};
    }
  });
  bili.parseSearch = () => ({comics: [{id: 'fixture'}]});
  assert.equal((await bili.search.load('fixture', [], 1)).comics.length, 1);
  assert.equal(guards, 2); assert.equal(tickets, 2); assert.equal(jar.get('jieqiSearchJs'), 'a=b');
  bili.headers['User-Agent'] = 'verified-browser';
  assert.equal(bili.readerHeaders()['User-Agent'], 'verified-browser');
  assert.equal(bili.comic.onImageLoad().headers['User-Agent'], 'verified-browser');
  const blocked = source('bilimanga', 'BiliManga', {getCookies: async () => [], get: async () => ({status: 403})});
  await assert.rejects(blocked.search.load('fixture', [], 1), /验证/);
  await assert.rejects(blocked.page('https://www.bilimanga.net/'), /HTTP 403/);
  console.log('PASS 19 mirror fallback, canonical IDs, invalid pages; Bili expired tickets, 403 guidance and verified image identity');
})().catch(error => { console.error(error); process.exitCode = 1; });
