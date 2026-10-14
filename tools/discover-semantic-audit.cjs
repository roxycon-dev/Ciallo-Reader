const fs=require('fs'),assert=require('assert/strict');
const {host,closeTransport}=require('./discover-live-audit.cjs');
const h=host({fileName:'comick.js'});
(async()=>{
 await h.run(`(()=>{
   globalThis.requests=[];
   Network.get=async (url,headers)=>{requests.push({url,headers});return {status:200,body:JSON.stringify({data:[{slug:'test',title:'测试',status:2,uploaded_at:'2026-10-14T01:00:00Z',view_count:12345,user_follow_count:321,genres:[{slug:'fantasy'}]}],next_cursor:'next'})};};
 })()`);
 const searched=await h.run('src.search.load("测试",[],1)');assert.equal(searched.comics[0].status,'完结');assert.equal(searched.comics[0].clicks,12345);assert.deepEqual([...searched.comics[0].tags],['fantasy']);
 await h.run('src.search.load("测试",[],2)');assert((await h.run('requests[1].url')).includes('/api/search?'));assert((await h.run('requests[1].url')).includes('cursor=next'));
 await h.run(`__cialloDiscover(src,{dimension:'updates',range:'最新',page:1,status:'完结',offset:0})`);
 assert((await h.run('requests.at(-1).url')).includes('order_by=uploaded'));assert((await h.run('requests.at(-1).url')).includes('status=2'));
 assert.equal(await h.run(`__cialloDiscoverKind('新作上架')`),'ranking');assert.equal(await h.run(`__cialloDiscoverKind('最新更新')`),'updates');
 const genre=await h.run(`__cialloDiscover(src,{dimension:'ranking',range:'热门',categoryId:'category:0:0',page:1,offset:0})`);
 assert.equal(genre.nextCursor,'next');assert((await h.run('requests.at(-1).url')).includes('order_by=user_follow_count'));
 await h.run(`__cialloDiscover(src,{dimension:'ranking',range:'热门',categoryId:'category:0:0',page:2,cursor:'next',offset:1})`);
 assert((await h.run('requests.at(-1).url')).includes('cursor=next'));
 await h.run(`delete src.__categoryCursors; __cialloDiscover(src,{dimension:'ranking',range:'热门',categoryId:'category:0:0',page:3,cursor:'saved-cursor',offset:2})`);
 assert((await h.run('requests.at(-1).url')).includes('cursor=saved-cursor'));
 assert.deepEqual(JSON.parse(JSON.stringify(await h.run(`__cialloDiscoverCapabilities([{label:'新作上架',kind:'ranking'}])`))),{ranking:['新作']});
 // Native option values are opaque: choose matching labels, retaining negative keys and the right window.
 await h.run(`globalThis.fake={key:'fixture',category:{parts:[{name:'题材',categories:['奇幻'],categoryParams:['fantasy']}]},categoryComics:{optionList:[{options:['-day-日点击','-week-周点击','-month-月点击']},{options:['all-全部','ongoing-连载','completed-完结']}],load:async(c,p,o,page)=>{globalThis.actualOptions=o;return {comics:[{id:'one',title:'结果',clicks:100}],maxPage:1}}}}`);
 for(const [range,key] of [['周','-week'],['月','-month']]){
  const result=await h.run(`__cialloDiscover(fake,{dimension:'clicks',range:${JSON.stringify(range)},status:'完结',categoryId:'category:0:0',page:1})`);
  assert.deepEqual([...(await h.run('actualOptions'))],[key,'completed']);assert(result.entries[0].origin.includes(range+'点击'));
 }
 console.log('10 Comick API, metadata, state, restored category cursor, provenance and native week/month option contracts passed (offline).');
})().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(()=>{h.close();closeTransport()});
