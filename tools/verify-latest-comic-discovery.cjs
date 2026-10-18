// Production bridge contract: source routes, status/sort propagation and real page numbers.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
async function main() {
  for (const [key, name, route] of [['nineteen','Nineteen','/category/tags/901'],['xipi','Xipi','/genre/new-genre']]) {
    const ctx = vm.createContext({ComicSource:class {}, Comic:class {constructor(v) {Object.assign(this,v);}},
      Convert:{encodeUtf8:s=>Buffer.from(s),decodeUtf8:b=>Buffer.from(b).toString(),
        encodeBase64:b=>Buffer.from(b).toString('base64'),decodeBase64:s=>Buffer.from(s,'base64')}});
    vm.runInContext(fs.readFileSync(path.join(root,`app/src/main/assets/js_extra/${key}.js`),'utf8')+
      `\nglobalThis.src=new ${name}();`,ctx);
    vm.runInContext(fs.readFileSync(path.join(root,'app/src/main/assets/venera/discover_bridge.js'),'utf8'),ctx);
    const src = ctx.src;
    if (key === 'xipi') {
      ctx.HtmlDocument=class { constructor(body) {this.body=body;} };
      ctx.Network={get:async (_,headers)=>{
        assert.match(headers.Accept,/text\/html/);
        assert.ok(!headers.Accept.includes('application/json'));
        return {status:200,body:'<div data-category-id="" data-genre-id="901"></div>'};
      }};
      await src.browseHtml('/genre/new-genre');
      console.log('PASS xipi: browse HTML uses HTML Accept headers separately from JSON API requests');
    }
    src.nativeCategories = async group => group === (key === 'nineteen'?'标签':'题材') ?
      [{label:'新题材',target:{page:'category',attributes:{category:'新题材',param:route}}}] : [];
    let requested;
    if(key === 'nineteen') {
      src.page=async p=>{requested=p;return 'fixture';};
      src.parseCards=(_,page)=>({comics:[{id:'book-'+page,title:'结果'}],maxPage:3});
    } else {
      src.nativeFilter=async p=>{assert.equal(p,route);return {genre:'901'};};
      src.api=async p=>{requested=p;return {total_pages:3,items:[{mid:'bTo0Mg',title:'结果',vertical_image_url:'/cover.webp',genres:['新题材']}]};};
    }
    const catalog=await vm.runInContext('__cialloDiscover(src,{catalogOnly:true,resolveDynamic:true})',ctx);
    assert.equal(catalog.categories.length,1);
    assert.deepEqual([...catalog.statuses],['连载','完结']);
    ctx.query={dimension:'ranking',range:'热门',page:2,categoryId:catalog.categories[0].id,status:'完结'};
    const result=await vm.runInContext('__cialloDiscover(src,query)',ctx);
    assert.equal(result.entries.length,1);
    assert.equal(result.entries[0].status,'完结');
    assert.equal(result.hasMore,true);
    if(key === 'nineteen') assert.equal(requested,'/category/tags/901/finish/2/order/hits/page/2');
    else {
      const q=new URL('https://fixture'+requested).searchParams;
      assert.equal(q.get('genre'),'901');assert.equal(q.get('status'),'completed');
      assert.equal(q.get('sort'),'popular');assert.equal(q.get('page'),'2');
    }
    if(key === 'nineteen') await assert.rejects(src.browse('//invalid',[],1));
    console.log(`PASS ${key}: dynamic route, completed filter, native popular sort and page 2 through production bridge`);
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
