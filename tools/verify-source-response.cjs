// Execute production response adapters against synthetic HTML/JSON, without accounts or live content.
// npm install --prefix .tools/dom linkedom --no-audit --no-fund
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const parseHTML = (...args) => require(process.env.CIALLO_DOM_MODULE || path.join(root, '.tools/dom/node_modules/linkedom')).parseHTML(...args);
const acorn = require(path.join(root, 'app/src/main/assets/js_safety/acorn.js'));
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const assets = 'app/src/main/assets/venera/';
let passed = 0;
async function check(name, run) {
  if (process.env.CIALLO_RESPONSE_FILTER && !name.startsWith(process.env.CIALLO_RESPONSE_FILTER)) return;
  await run(); passed++; console.log('PASS ' + name);
}
function context(extra = {}) {
  const state = {opened: 0, disposed: 0};
  class HtmlDocument {
    constructor(html) { this.doc = parseHTML(html).document; this.closed = false; state.opened++; }
    wrap(element) {
      if (!element) return null;
      const owner = this;
      return {get text() { assert.ok(!owner.closed, 'access after document disposal'); return element.textContent; },
        get attributes() { assert.ok(!owner.closed); return Object.fromEntries(Array.from(element.attributes, a => [a.name, a.value])); }};
    }
    querySelector(selector) { return this.wrap(this.doc.querySelector(selector)); }
    querySelectorAll(selector) { return Array.from(this.doc.querySelectorAll(selector), node => this.wrap(node)); }
    dispose() { assert.ok(!this.closed); this.closed = true; state.disposed++; }
  }
  const ctx = vm.createContext({HtmlDocument, ComicDetails: class {constructor(value) {Object.assign(this, value);}}, ...extra});
  for (const name of ['source_responses.js', 'wnacg_info.js', 'jm_response.js']) vm.runInContext(read(assets + name), ctx);
  return {ctx, state};
}
async function wnacg(pages) {
  const {ctx, state} = context({Network: {get: async url => {
    const page = Number(/index-page-(\d+)/.exec(url)[1]);
    return pages[page - 1] || {status: 404};
  }}});
  const src = {baseUrl: 'https://fixture.invalid', comic: {}};
  ctx.CialloSourceResponse.install(src, 'wnacg');
  try { return await src.comic.loadInfo('123'); }
  finally { assert.equal(state.opened, state.disposed, 'all DOM handles must be released'); }
}
async function main() {
  await check('production adapter syntax and all upstream source snapshot syntax', () => {
    for (const name of ['source_responses.js', 'wnacg_info.js', 'jm_response.js']) acorn.parse(read(assets + name), {ecmaVersion: 'latest'});
    const dir = path.join(root, '.source-verification/upstream');
    if (fs.existsSync(dir)) {
      const scripts = fs.readdirSync(dir).filter(file => file.endsWith('.js'));
      assert.ok(scripts.length >= 30);
      for (const file of scripts) acorn.parse(fs.readFileSync(path.join(dir, file), 'utf8'), {ecmaVersion: 'latest'});
      console.log('Upstream scripts: ' + scripts.length);
    }
  });
  await check('JSON BOM, scalars, null and revivers retain JSON semantics', () => {
    const {ctx} = context();
    assert.equal(vm.runInContext('JSON.parse("\\uFEFF {\\\"id\\\":7}").id', ctx), 7);
    assert.equal(vm.runInContext('JSON.parse("7", (k,v) => typeof v === "number" ? v+1 : v)', ctx), 8);
    assert.equal(vm.runInContext('JSON.parse("null")', ctx), null);
    assert.equal(vm.runInContext('(() => {const error = new SyntaxError("intentional"); try {JSON.parse("7", () => {throw error;});} catch(e) {return e === error;}})()', ctx), true);
  });
  await check('fetch text and JSON use response decoding while binary data remains intact', async () => {
    const bytes = Buffer.from('\uFEFF{"title":"测试"}', 'utf16le');
    let calls = 0;
    const {ctx} = context({Network: {fetchBytes: async () => ({status:200,body:bytes,headers:{'Content-Type':'application/json; charset=UTF-16LE'}})},
      Convert: {decodeResponseText: (value, headers) => {calls++; assert.ok(headers['Content-Type'].includes('UTF-16LE'));return Buffer.from(value).toString('utf16le').replace(/^\uFEFF/, '');}}});
    const runtime = read(assets + '_venera_.js');
    const begin = runtime.indexOf('async function fetch(');
    const end = runtime.indexOf('\n/**', begin);
    vm.runInContext(runtime.slice(begin, end), ctx);
    const response = await ctx.fetch('https://fixture.invalid/api');
    assert.equal((await response.json()).title, '测试');
    assert.equal(await response.text(), '{"title":"测试"}');
    assert.equal(await response.arrayBuffer(), bytes); assert.equal(calls, 2);
  });
  await check('empty, truncated and HTML JSON responses fail without leaking body', () => {
    const {ctx} = context();
    for (const text of ['', '{"data":', '<html><title>Just a moment...</title>PRIVATE</html>', '<!doctype html>PRIVATE']) {
      ctx.input = text;
      assert.throws(() => vm.runInContext('JSON.parse(input)', ctx), error => !String(error).includes('PRIVATE') && /空响应|验证|网页|格式/.test(String(error)));
    }
    ctx.input = 'null';
    assert.throws(() => ctx.CialloSourceResponse.object(ctx.input), /结构异常/);
  });
  await check('chapter maps preserve row objects groups and invalid nulls without string coercion', () => {
    const {ctx} = context();
    const flat = JSON.parse(vm.runInContext('JSON.stringify(CialloSourceResponse.chapters(new Map([["1", {title:"第1话",order:1}], ["2", null]])))', ctx));
    assert.deepEqual(flat, [{id:'1', title:'第1话', order:1}, {id:'2', title:null}]);
    const groups = JSON.parse(vm.runInContext('JSON.stringify(CialloSourceResponse.chapters({卷一:new Map([["a", "第1话"]]),卷二:[{id:"b",title:"第2话"}]}))', ctx));
    assert.deepEqual(groups, [{id:'a', title:'第1话',group:'卷一'}, {id:'b',title:'第2话',group:'卷二'}]);
  });
  await check('Wnacg desktop optional labels description and uploader may be absent', async () => {
    const result = await wnacg([{status: 200, body: '<div class="userwrap"><h2>测试作品</h2><div class="asTB"><div class="uwthumb"><img src="////img.invalid/cover.jpg"></div></div></div>'}]);
    assert.equal(result.title, '测试作品'); assert.equal(result.cover, 'https://img.invalid/cover.jpg');
    assert.equal(result.description, ''); assert.equal(result.uploader, ''); assert.equal(result.chapters, undefined);
  });
  await check('current upstream Wnacg null.text regression is reproduced and fixed', async () => {
    const script = path.join(root, '.source-verification/upstream/wnacg.js');
    if (!fs.existsSync(script)) return;
    const body = '<div id="comicName">测试作品</div><div id="Cover"><img src="/cover.jpg"></div>';
    const {ctx} = context({ComicSource: class {loadSetting(key) {return key === 'domainSelection' ? '0' : 'fixture.invalid';}},
      Network: {get: async () => ({status: 200, body})}});
    vm.runInContext(fs.readFileSync(script, 'utf8') + '\nglobalThis.fixtureSource = new Wnacg();', ctx);
    await assert.rejects(ctx.fixtureSource.comic.loadInfo.call(ctx.fixtureSource, '123'), /null|text/);
    ctx.CialloSourceResponse.install(ctx.fixtureSource, 'wnacg');
    assert.equal((await ctx.fixtureSource.comic.loadInfo('123')).title, '测试作品');
  });
  await check('Wnacg mobile detail and relative cover are accepted', async () => {
    const result = await wnacg([{status: 200, body: '<div id="comicName">手机作品</div><div id="Cover"><img data-src="/cover.jpg"></div><div class="Introduct_Sub">简介</div><a class="introName">上传者</a>'}]);
    assert.equal(result.title, '手机作品'); assert.equal(result.cover, 'https://fixture.invalid/cover.jpg');
    assert.equal(result.description, '简介'); assert.equal(result.uploader, '上传者');
  });
  await check('Wnacg single-gallery slideshow link does not become a series', async () => {
    const result = await wnacg([{status: 200, body: '<h1>单图集</h1><a href="/photos-slide-aid-123.html">阅读</a>'}]);
    assert.equal(result.chapters, undefined);
  });
  await check('Wnacg current upstream series pagination preserves initial metadata and order', async () => {
    const result = await wnacg([
      {status: 200, body: '<h1>连载</h1><div id="sr_pub"></div><div class="Introduct_Sub">简介</div><a data-chid="11">第1话</a><div class="paginator"><a href="/photos-index-page-2-aid-123.html">2</a></div>'},
      {status: 200, body: '<a data-chid="12">第2话</a><a data-chid="11">第1话</a>'}
    ]);
    assert.deepEqual(Array.from(result.chapters, pair => [pair[0], pair[1]]), [['11', '第1话'], ['12', '第2话']]);
    assert.equal(result.description, '简介');
  });
  await check('Wnacg challenge removed work and partial catalogue never become dummy chapters', async () => {
    await assert.rejects(wnacg([{status: 200, body: '<html><title>Just a moment...</title></html>'}]), /验证/);
    await assert.rejects(wnacg([{status: 200, body: '<h2>维护中</h2>'}]), /未返回作品详情/);
    await assert.rejects(wnacg([{status: 200, body: '<h1>连载</h1><div id="sr_pub"></div><a data-chid="11">第1话</a><div class="paginator"><a href="/photos-index-page-2-aid-123.html">2</a></div>'}, {status: 200, body: '<h1>维护</h1>'}]), /目录不完整/);
  });
  await check('JM HTML 200, 404 and truncated JSON rotate until validated data arrives', async () => {
    const calls = [];
    class JM {
      static apiDomains = ['one.invalid', 'two.invalid', 'three.invalid', 'four.invalid'];
      loadSetting() {return this.selection;}
      get() {} getApiHeaders() {return {};}
      convertData(value) {return value;}
    }
    const {ctx} = context({Network: {get: async url => {
      calls.push(url); const n = calls.length;
      if (n === 1) return {status: 200, body: '<html>error</html>'};
      if (n === 2) return {status: 404};
      if (n === 3) return {status: 200, body: '{"data":'};
      return {status: 200, body: JSON.stringify({data: JSON.stringify({id: 364074, name: '测试作品'})})};
    }}});
    const src = new JM(); src.selection = '1'; ctx.CialloSourceResponse.install(src, 'jm');
    assert.equal(JSON.parse(await src.get(src.baseUrl + '/album?id=364074')).id, 364074);
    assert.equal(calls.length, 4); assert.ok(calls.every(url => url.endsWith('/album?id=364074')));
    assert.equal(src.baseUrl, 'https://four.invalid');
    src.selection = '2'; assert.equal(src.baseUrl, 'https://two.invalid');
  });
  await check('JM malformed envelopes and decrypted bodies cannot be successful', async () => {
    for (const body of ['{}', 'null', '{"data":null}', '{"data":"truncated"}']) {
      class JM {static fallbackServers = ['one.invalid']; get() {} loadSetting() {return '1';} getApiHeaders() {return {};} convertData(v) {return v;}}
      const {ctx} = context({Network: {get: async () => ({status: 200, body})}});
      const src = new JM(); ctx.CialloSourceResponse.install(src, 'jm');
      await assert.rejects(src.get(src.baseUrl + '/album?id=1'));
    }
  });
  await check('JM encrypted error objects retry while optional recommendations may be null', async () => {
    let calls = 0;
    class JM {static apiDomains = ['one.invalid','two.invalid']; get() {} loadSetting() {return '1';} getApiHeaders() {return {};} convertData(v) {return v;}}
    const {ctx} = context({Network: {get: async () => ({status:200,body:JSON.stringify({data:JSON.stringify(++calls === 1 ? {message:'maintenance'} : {name:'测试作品',related_list:null})})})}});
    const src = new JM(); ctx.CialloSourceResponse.install(src, 'jm');
    const data = JSON.parse(await src.get(src.baseUrl + '/album?id=1'));
    assert.equal(calls, 2); assert.deepEqual(data.related_list, []);
  });
  await check('JM authorization rejection stops without replaying on other domains', async () => {
    let calls = 0;
    class JM {static apiDomains = ['one.invalid','two.invalid']; get() {} loadSetting() {return '1';} getApiHeaders() {return {};} convertData(v) {return v;}}
    const {ctx} = context({Network: {get: async () => {calls++; return {status: 401, body: '{"errorMsg":"請先登入會員"}'};}}});
    const src = new JM(); ctx.CialloSourceResponse.install(src, 'jm');
    await assert.rejects(src.get(src.baseUrl + '/album?id=1'), /登入/); assert.equal(calls, 1);
  });
  await check('JM merged adapter caps routes and elapsed retry budget', async () => {
    for (const expire of [false, true]) {
      let calls = 0, elapsed = 0;
      class JM {static apiDomains = Array.from({length: 12}, (_, i) => i + '.invalid'); get() {} loadSetting() {return '1';} getApiHeaders() {return {};} convertData(v) {return v;}}
      const {ctx} = context({Date: {now: () => elapsed}, Network: {get: async () => {
        calls++; if (expire) elapsed = 19000; throw new Error('transport failure');
      }}});
      const src = new JM(); ctx.CialloSourceResponse.install(src, 'jm');
      await assert.rejects(src.get(src.baseUrl + '/album?id=1'));
      assert.equal(calls, expire ? 1 : 5);
    }
  });
  await check('JM JSON authorization rejection never rotates', async () => {
    let calls = 0;
    class JM {static apiDomains = ['one.invalid','two.invalid']; get() {} loadSetting() {return '1';} getApiHeaders() {return {};} convertData(v) {return v;}}
    const {ctx} = context({Network: {get: async () => {calls++; return {status: 200, body: '{"code":401}'};}}});
    const src = new JM(); ctx.CialloSourceResponse.install(src, 'jm');
    await assert.rejects(src.get(src.baseUrl + '/album?id=1'), /Login expired/); assert.equal(calls, 1);
  });
  console.log('Passed ' + passed + ' source response checks');
}
main().catch(error => {console.error(error); process.exitCode = 1;});
