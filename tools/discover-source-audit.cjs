/* Read-only upstream protocol inventory plus isolated adapter contract checks. No source init/network/login. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const repo = process.argv[2];
if (!repo) throw new Error('Usage: node tools/discover-source-audit.cjs <venera-configs checkout>');
const bridge = fs.readFileSync(path.join(root, 'app/src/main/assets/venera/discover_bridge.js'), 'utf8');
const runtime = fs.readFileSync(path.join(root, 'app/src/main/assets/venera/_venera_.js'), 'utf8');
const output = path.join(root, 'artifacts/discover-review');
fs.mkdirSync(output, { recursive: true });
const comic = { id: 'fixture', title: '协议测试', tags: ['Adventure'] };
async function contracts() {
  const sandbox = vm.createContext({});
  vm.runInContext(bridge, sandbox);
  let checks = 0;
  async function check(source, query, verify) {
    sandbox.src = source; sandbox.query = { dimension: 'updates', range: '最新', page: 1, ...query };
    const result = await vm.runInContext('__cialloDiscover(src, query)', sandbox);
    verify(result); checks++;
  }
  await check({ explore: [{ title: '首页', type: 'multiPageComicList', load: p => ({ comics: [comic], maxPage: 3 }) }] }, { feedId: 'native' },
    r => { assert.equal(r.entries.length, 1); assert.equal(r.hasMore, true); assert.equal(r.native, true); });
  await check({ explore: [{ title: '首页', type: 'multiPageComicList', loadNext: next => { assert.equal(next, 'token'); return { comics: [comic], next: 'next' }; } }] },
    { feedId: 'native', cursor: 'token', page: 2, offset: 30 }, r => { assert.equal(r.nextCursor, 'next'); assert.equal(r.entries[0].nativeOrder, 31); });
  await check({ explore: [{ title: '首页', type: 'multiPageComicList', loadNext: () => ({ comics: [comic], next: 'token' }) }] },
    { feedId: 'native', cursor: 'token' }, r => assert.equal(r.hasMore, false));
  await check({ explore: [{ title: '首页', type: 'multiPartPage', load: () => [{ title: '最新更新', comics: [comic] }, { title: '热门', comics: [{ ...comic, id: 'popular' }] }] }] }, {},
    r => { assert.equal(r.entries.length, 1); assert.equal(r.entries[0].id, 'fixture'); });
  await check({ explore: [{ title: '首页', type: 'singlePageWithMultiPart', load: () => ({ '最近更新': [comic], '账号': [{ ...comic, id: '__account__' }] }) }] }, {},
    r => assert.equal(r.entries.length, 1));
  await check({ explore: [{ title: '混合', type: 'mixed', load: index => { assert.equal(index, 1); return { data: [[comic], { title: '分组', comics: [comic] }], maxPage: 2 }; } }] },
    { feedId: 'native' }, r => { assert.equal(r.entries.length, 2); assert.equal(r.hasMore, true); });
  await check({ explore: [{ title: '最新', type: 'multiPageComicList', load: () => ({ comics: [comic], maxPage: null }) }] }, {}, r => assert.equal(r.hasMore, true));
  await check({ explore: [{ title: '最新', type: 'multiPageComicList', load: () => ({ comics: [], maxPage: null }) }] }, {}, r => assert.equal(r.hasMore, false));
  await check({ categoryComics: { ranking: { options: ['week-周榜'], load: (o,p) => { assert.equal(o,'week'); assert.equal(p,2); return { comics:[comic], maxPage:4 }; } } } },
    { dimension:'ranking', range:'热门', page:2, offset:30 }, r => { assert.equal(r.entries[0].rank,31); assert.equal(r.hasMore,true); });
  await check({ categoryComics: { ranking: { options: ['day-日榜'], loadWithNext: (o,next) => { assert.equal(next,null); return { comics:[comic], next:'n' }; } } } },
    { feedId:'ranking:day' }, r => assert.equal(r.nextCursor,'n'));
  await check({ category: { parts:[{ name:'题材', categories:['冒险'], categoryParams:['42'] }] },
    categoryComics:{ optionList:[{ options:['recent-最近更新'] }], load:(c,p,options,page) => { assert.equal(c,'冒险'); assert.equal(p,'42'); assert.equal(options[0],'recent'); return { comics:[comic],maxPage:1 }; } } },
    { categoryId:'category:0:0' }, r => { assert.equal(r.native,true); assert.equal(r.entries[0].tags.includes('冒险'),true); });
  await check({ category:{ parts:[{ type:'dynamic', loader:() => [{label:'书库',target:{page:'category',attributes:{category:'library',param:'1'}}}] }] },
    categoryComics:{load:(c,p) => {assert.equal(c,'library');assert.equal(p,'1');return {comics:[comic],maxPage:1};}} },
    {categoryId:'dynamic:0:0'}, r => assert.equal(r.entries.length,1));
  await check({ explore:[{title:'热门',type:'multiPageComicList',load:()=>({comics:[comic],maxPage:1})}] },
    {dimension:'clicks',range:'日'}, r => {assert.equal(r.supported,false);assert.equal(r.entries.length,0);});
  await check({ explore:[{title:'点击周榜',type:'multiPageComicList',load:()=>({comics:[comic],maxPage:1})}] },
    {dimension:'clicks',range:'周'}, r => assert.equal(r.entries.length,1));
  await check({ explore:[{title:'账号',type:'singlePageWithMultiPart',load:()=>{throw Error('account must not load');}},
    {title:'最新更新',type:'multiPageComicList',load:()=>({comics:[comic],maxPage:1})}] }, {feedId:'native'},
    r => {assert.equal(r.entries.length,1);assert.equal(r.catalog.feeds.length,1);});
  await check({category:{parts:[{name:'题材',categories:['冒险']}]},categoryComics:{optionLoader:()=>[{options:['new-最新']}],
    load:(c,p,o)=>{assert.equal(o[0],'new');return {comics:[comic],maxPage:1};}}}, {categoryId:'category:0:0'}, r=>assert.equal(r.entries.length,1));
  await check({category:{parts:[{categories:['冒险']}]},categoryComics:{load:()=>({comics:[{...comic,tags:{author:['作者']}}],maxPage:1})}},
    {categoryId:'category:0:0'},r=>{assert.equal(r.entries[0].tags.author[0],'作者');assert.equal(r.entries[0].tags['分类'][0],'冒险');});
  await check({category:{parts:[{categories:['冒险'],itemType:'search'}]},search:{__cialloCursorOnly:true,
    load:()=>{throw Error('synthetic first-page wrapper must not load');},loadNext:(k,o,next)=>{assert.equal(next,'token');return {comics:[comic],next:'n'};}}},
    {categoryId:'category:0:0',cursor:'token',page:2},r=>assert.equal(r.nextCursor,'n'));
  await check({explore:[{title:'混合',type:'mixed',pageBase:0,load:index=>{assert.equal(index,0);return {data:[[comic]],maxPage:1};}}]},
    {feedId:'native'},r=>assert.equal(r.hasMore,false));
  return checks;
}
async function inventory() {
  const index = JSON.parse(fs.readFileSync(path.join(repo, 'index.json'), 'utf8'));
  const local = ['bilimanga','vomic','pufei'].map(key => ({key,name:key,fileName:key+'.js',local:true}));
  const rows = [];
  for (const meta of [...index, ...local]) {
    const file = path.join(meta.local ? path.join(root, 'app/src/main/assets/js_extra') : repo, meta.fileName || meta.filename);
    const row = { key:meta.key, file:path.basename(file), name:meta.name };
    try {
      const script = fs.readFileSync(file, 'utf8');
      const className = /class\s+(\w+)\s+extends\s+ComicSource/.exec(script)?.[1];
      if (!className) throw new Error('No ComicSource class');
      const sandbox = vm.createContext({console:{log(){},warn(){},error(){}},APP:{locale:'zh-CN'},sendMessage:()=>null,sendMessageSync:()=>null});
      vm.runInContext(runtime+'\n'+script+'\n'+bridge+'\n globalThis.auditSource = new '+className+'();',sandbox,{timeout:3000});
      const catalog = vm.runInContext('__cialloDiscoverCatalog(auditSource)',sandbox,{timeout:1000});
      Object.assign(row,{feeds:catalog.feeds,categories:catalog.categories,dynamicCategories:catalog.dynamicCategories,
        hasCategoryLoader:catalog.hasCategoryLoader,explicit:catalog.explicit});
      row.legacyMatched = catalog.feeds.filter(f=>/更新|讨论|榜|排行|点击|views|clicks/i.test(f.label)).length;
      row.browseAvailable = catalog.feeds.length>0 || catalog.hasCategoryLoader;
      // Exercise every declared source profile against deterministic transport fixtures.
      // Source init/login/network stay disabled: these checks prove dispatch, not live availability.
      vm.runInContext(`
        const fixture = {id:'contract-book',title:'协议作品',tags:['Adventure']};
        for (const section of auditSource.explore || []) {
          const data = section.type === 'multiPartPage' ? [{title:section.title,comics:[fixture]}]
            : section.type === 'singlePageWithMultiPart' ? {[section.title]:[fixture]}
            : section.type === 'mixed' ? {data:[[fixture]],maxPage:2} : {comics:[fixture],maxPage:2,next:'next'};
          if (typeof section.load === 'function') section.load = async () => data;
          if (typeof section.loadNext === 'function') section.loadNext = async () => data;
        }
        if(auditSource.categoryComics) {
          const api=auditSource.categoryComics;
          if(typeof api.load==='function') api.load=async()=>({comics:[fixture],maxPage:2});
          if(typeof api.loadNext==='function') api.loadNext=async()=>({comics:[fixture],next:'next'});
          if(typeof api.optionLoader==='function') api.optionLoader=async()=>[];
          if(typeof api.optionList==='function') api.optionList=async()=>[];
          if(api.ranking) for(const name of ['load','loadNext','loadWithNext'])
            if(typeof api.ranking[name]==='function') api.ranking[name]=async()=>({comics:[fixture],maxPage:2,next:'next'});
        }
      `,sandbox,{timeout:1000});
      row.dispatchChecks = 0;
      for (const feed of catalog.feeds) {
        sandbox.auditQuery={dimension:'updates',range:'最新',page:1,feedId:feed.id};
        const result=await vm.runInContext('__cialloDiscover(auditSource,auditQuery)',sandbox,{timeout:1000});
        assert.equal(result.entries.length,1,meta.key+' '+feed.label);
        assert.equal(result.native,true); row.dispatchChecks++;
      }
      if(catalog.categories.length && catalog.hasCategoryLoader) {
        sandbox.auditQuery={dimension:'updates',range:'最新',page:1,categoryId:catalog.categories[0].id};
        const result=await vm.runInContext('__cialloDiscover(auditSource,auditQuery)',sandbox,{timeout:1000});
        assert.equal(result.entries.length,1,meta.key+' native category'); row.dispatchChecks++;
      }
    } catch(e) { row.error = e.message; }
    rows.push(row);
  }
  return rows;
}
(async()=>{
  const checks = await contracts();
  const sources = await inventory();
  const summary = { contractChecks:checks, sources:sources.length, inventoried:sources.filter(s=>!s.error).length,
    nativeBrowse:sources.filter(s=>s.browseAvailable).length, profileDispatchChecks:sources.reduce((n,s)=>n+(s.dispatchChecks||0),0),
    verificationScope:'protocol dispatch with mocked transport; no live availability claim', errors:sources.filter(s=>s.error) };
  fs.writeFileSync(path.join(output,'source-protocol-inventory.json'),JSON.stringify({summary,sources},null,2));
  console.log(JSON.stringify(summary,null,2));
  if(summary.errors.length || summary.nativeBrowse!==summary.sources) process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
