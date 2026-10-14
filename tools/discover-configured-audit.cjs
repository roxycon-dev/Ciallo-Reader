/* Production configured-source routing audit. Deterministic loaders; no live availability claim. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {host,closeTransport}=require('./discover-live-audit.cjs');
const root=path.resolve(__dirname,'..'),output=path.join(root,'artifacts/discover-rework-2026-10-14');
const index=JSON.parse(fs.readFileSync(path.join(root,'artifacts/discover-live/patched/index.json')));
const profiles=index.filter(s=>s.offered&&!s.nativeEquivalent);
async function audit(meta){
 const h=host(meta),row={key:meta.key,feeds:0,categories:0,checks:0};
 try {
  const catalog=await h.run('__cialloDiscoverCatalog(src)');
  assert.equal(new Set(catalog.feeds.map(f=>f.id)).size,catalog.feeds.length,meta.key+' duplicate feed identities');
  await h.run(`(()=>{
    globalThis.routingCalls=[];
    const result=(route,page=1)=>{routingCalls.push({route,page});return {comics:[{id:route+'-'+page,title:'路由校验',tags:['Adventure']}],maxPage:3,next:'cursor-'+page};};
    for(const [i,s] of (src.explore||[]).entries()){
      if(typeof s.load==='function')s.load=async p=>s.type==='singlePageWithMultiPart'?{[s.title]:result('feed:'+i,p).comics}:s.type==='multiPartPage'?[{title:s.title,comics:result('feed:'+i,p).comics}]:s.type==='mixed'?{data:[result('feed:'+i,p).comics],maxPage:3}:result('feed:'+i,p);
      if(typeof s.loadNext==='function')s.loadNext=async cursor=>result('feed:'+i,1);
    }
    if(src.categoryComics){const a=src.categoryComics;
      if(typeof a.load==='function')a.load=async(c,p,o,page)=>result('category:'+c+':'+p,page);
      if(typeof a.loadNext==='function')a.loadNext=async(c,p,o,cursor)=>result('category:'+c+':'+p,1);
      if(typeof a.optionLoader==='function')a.optionLoader=async()=>[];
      if(typeof a.optionList==='function')a.optionList=async()=>[];
      if(a.ranking)for(const name of ['load','loadNext','loadWithNext'])if(typeof a.ranking[name]==='function')a.ranking[name]=async value=>result('ranking:'+value,1);
    }
    if(src.search){const a=src.search;if(typeof a.load==='function')a.load=async(c,o,page)=>result('search:'+c,page);if(typeof a.loadNext==='function')a.loadNext=async(c,o,next)=>result('search:'+c,1);}
  })()`);
  for(const [field,items] of [['feedId',catalog.feeds],['categoryId',catalog.categories]]){
   for(const item of items){
    const q={dimension:'updates',range:'最新',page:1,[field]:item.id};
    const result=await h.run('__cialloDiscover(src,'+JSON.stringify(q)+')');
    assert.ok(result.entries.length,meta.key+' '+item.label+' empty');
    assert.equal(result.supported,true,meta.key+' '+item.label+' unsupported');
    assert.equal(result.native,true);row.checks++;
   }
  }
  if(meta.key==='mycomic') {
   assert.equal(JSON.stringify(catalog.feeds.filter(f=>f.type==='ranking').map(f=>f.value)),JSON.stringify(['-views','-week','-month']));
   const routes=await h.run('routingCalls.map(c=>c.route)');
   for(const value of ['-views','-week','-month'])assert.ok(routes.includes('ranking:'+value),'MYCOMIC sort lost '+value);
  }
  row.feeds=catalog.feeds.length;row.categories=catalog.categories.length;row.capabilities=catalog.capabilities;
  assert.equal(h.evidence.length,0,'Offline profile unexpectedly requested HTTP');
 }catch(e){row.error=String(e.stack||e)}finally{h.close()}
 return row;
}
async function contracts(){
 const h=host(profiles[0]);let checks=0;
 try{
  // Stable dynamic identities survive loader order changes, and cached catalogs avoid reloads.
  await h.run(`globalThis.loads=0;src.category={parts:[{type:'dynamic',name:'题材',loader:async()=>{loads++;return ['冒险','恋爱'].map(label=>({label,target:{page:'category',attributes:{category:label,param:label}}}));}}]};
  src.categoryComics={load:async(c,p,o,page)=>({comics:[{id:c+'-'+page,title:c}],maxPage:c==='冒险'?1:3})};`);
  const first=await h.run('__cialloDiscover(src,{catalogOnly:true,resolveDynamic:true})');
  const second=await h.run('__cialloDiscover(src,{catalogOnly:true,resolveDynamic:true})');
  assert.equal(await h.run('loads'),1);assert.deepEqual(first.categories.map(c=>c.id),second.categories.map(c=>c.id));checks++;
  await h.run(`src.__cialloDynamicCategories=null;const loader=src.category.parts[0].loader;src.category.parts[0].loader=async()=> (await loader()).reverse();`);
  const reordered=await h.run('__cialloDiscover(src,{catalogOnly:true,resolveDynamic:true})');
  assert.deepEqual(first.categories.map(c=>c.id).sort(),reordered.categories.map(c=>c.id).sort());checks++;
  const ids=first.categories.map(c=>c.id);
  const q={dimension:'updates',range:'最新',page:1,categoryIds:ids};
  const page=await h.run('__cialloDiscover(src,'+JSON.stringify(q)+')');
  assert.equal(page.entries.length,2);assert.equal(JSON.parse(page.nextCursor)[ids[0]],false);checks++;
  const next=await h.run('__cialloDiscover(src,'+JSON.stringify({...q,page:2,cursor:page.nextCursor})+')');
  assert.equal(next.entries.length,1);assert.equal(next.entries[0].title,'恋爱');checks++;
  await h.run(`src.category={parts:[{categories:['分类'],itemType:'search'}]};src.search={optionList:[{default:['0','2']}],load:async(c,o,p)=>{if(o[0]!=='["0","2"]')throw Error('array default corrupted');return {comics:[{id:'array',title:'array'}],maxPage:1};}};`);
  assert.equal((await h.run('__cialloDiscover(src,{dimension:"updates",range:"最新",page:1,categoryId:"category:0:0"})')).entries.length,1);checks++;
 }finally{h.close()}
 return checks;
}
async function biliContract(){
 const script=fs.readFileSync(path.join(root,'app/src/main/assets/js_extra/bilimanga.js'),'utf8');
 const h=host({key:'bilimanga',fileName:'bilimanga.js'},script);
 try{
  await h.run(`src.page=async()=>({body:'<a class="book-layout" href="/detail/1706.html"><img alt="小女神花鈴chu" data-src="/cover.jpg"><span class="book-author">作者小夏鈍帆</span><em class="tag-small yellow">奇幻 魔法 校園 戀愛 女性視角</em><em class="tag-small red">已完結</em><em class="tag-small blue">0.1萬</em></a><a href="/filter/FantasyAdventure/2.html">2</a><a href="/filter/FantasyAdventure/17.html">17</a>'});`);
  const result=await h.run('src.categoryComics.load("奇幻冒險","FantasyAdventure",[],1)');
  assert.equal(result.maxPage,17);assert.equal(result.comics[0].author,'小夏鈍帆');
  assert.ok(result.comics[0].tags.includes('魔法'));assert.ok(!result.comics[0].tags.includes('0.1萬'));
  assert.equal(result.comics[0].status,'已完結');assert.equal(result.comics[0].clicks,1000);
  assert.equal(h.evidence.length,0);return 1;
 }finally{h.close()}
}
(async()=>{const sources=[];for(const p of profiles)sources.push(await audit(p));const contractChecks=await contracts()+await biliContract();
 const summary={configuredJsSources:sources.length,feeds:sources.reduce((n,s)=>n+s.feeds,0),categories:sources.reduce((n,s)=>n+s.categories,0),dispatchChecks:sources.reduce((n,s)=>n+s.checks,0),contractChecks,errors:sources.filter(s=>s.error),scope:'Configured production scripts; deterministic dispatch and pagination. MangaDex covered by Kotlin tests; no repeated online availability claim.'};
 fs.mkdirSync(output,{recursive:true});fs.writeFileSync(path.join(output,'configured-routing.json'),JSON.stringify({summary,sources},null,2));console.log(JSON.stringify(summary,null,2));if(summary.errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1}).finally(closeTransport);
