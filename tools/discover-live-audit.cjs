/* Live anonymous discovery API audit. Actual production-patched scripts, no emulator or mocked HTTP. */
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const crypto = require('node:crypto'), {spawn} = require('node:child_process'), readline = require('node:readline');
const root = path.resolve(__dirname, '..'), sourceDir = path.resolve(process.argv[2] || 'artifacts/discover-live/patched');
const deps = process.argv[3] || 'C:/Users/GuanXingRen/.codex/tmp/discover-live-host/node_modules';
const cheerio = require(path.join(deps, 'cheerio')), iconv = require(path.join(deps, 'iconv-lite'));
const out = path.resolve(process.env.DISCOVER_LIVE_OUT || path.join(root, 'artifacts/discover-live')); fs.mkdirSync(out, {recursive:true});
const runtime = fs.readFileSync(path.join(root,'app/src/main/assets/venera/_venera_.js'),'utf8');
const engine = fs.readFileSync(path.join(root,'app/src/main/java/com/example/source/js/JsSourceEngine.kt'),'utf8');
const override = engine.split('private val SYNC_OVERRIDE = """')[1].split('""".trimIndent()')[0].replaceAll("${'$'}", '$');
const bridge = fs.readFileSync(path.join(root,'app/src/main/assets/venera/discover_bridge.js'),'utf8');
const transport = spawn('python',['-u',path.join(root,'tools/discover-live-http.py')],{stdio:['pipe','pipe','inherit']});
const pending = new Map(); let serial=0;
const credentials=process.env.DISCOVER_LIVE_CREDENTIALS_FILE ? JSON.parse(fs.readFileSync(process.env.DISCOVER_LIVE_CREDENTIALS_FILE,'utf8').replace(/^\uFEFF/,'')) : {};
const secretValues=Object.values(credentials).flatMap(c=>[c.account,c.password]).filter(Boolean);
const auditHosts=[], auditWarnings=[];
// Optional source prefetches can reject outside a loader's returned promise. Retain
// that evidence instead of allowing one source to kill the entire source audit.
process.on('unhandledRejection',reason=>{const error=clean(reason);const owner=auditHosts.find(h=>h.evidence.some(e=>e.error===error));if(owner)owner.backgroundErrors.push(error);else auditWarnings.push(error);});
readline.createInterface({input:transport.stdout}).on('line',line=>{const r=JSON.parse(line);const p=pending.get(r.id);if(p){pending.delete(r.id);p(r);}});
function rpc(source,payload){return new Promise(resolve=>{const id=++serial;pending.set(id,resolve);transport.stdin.write(JSON.stringify({id,source,...payload})+'\n');});}
const buffer = value => typeof value==='string' ? Buffer.from(value) : Buffer.from(ArrayBuffer.isView(value) ? new Uint8Array(value.buffer,value.byteOffset,value.byteLength) : new Uint8Array(value));
const arrayBuffer = value => Uint8Array.from(value).buffer;
function clean(error){let value=String(error?.message||error);for(const secret of secretValues)value=value.split(secret).join('[redacted]');return value.replace(/Bearer\s+[\w.\-]+/gi,'Bearer [redacted]').replace(/([?&](?:token|key|auth|password|secret)=)[^&\s]*/gi,'$1[redacted]').slice(0,240);}
function host(meta,sourceOverride) {
 const storage=new Map(), settings=new Map(), documents=new Map(), objects=new Map();let objectId=0,closed=false,stage='init',requests=0;
 const evidence=[], cookies=[];
 function ref(node,doc){if(!node)return null;const id=++objectId;objects.set(id,{node,doc});return id;}
 function html(m){
  if(m.function==='parse'){documents.set(m.key,cheerio.load(m.data));return null;}
  if(m.function==='dispose'){documents.delete(m.key);return null;}
  // Document keys and element keys have independent numeric namespaces in the App.
  const object=m.doc == null ? null : objects.get(m.key), $=documents.get(m.doc ?? m.key), node=object?.node;
  if(!$)throw Error('HOST_DOM: unknown document');
  const selection = selector=>{try{return node ? $(node).find(selector) : $(selector);}catch(e){throw Error('HOST_SELECTOR: '+clean(e));}};
  switch(m.function){
   case 'querySelector':case 'dom_querySelector':return ref(selection(m.query).get(0),m.doc??m.key);
   case 'querySelectorAll':case 'dom_querySelectorAll':return selection(m.query).toArray().map(n=>ref(n,m.doc??m.key));
   case 'getElementById':return ref($('#'+m.id).get(0),m.key);
   case 'getText':case 'node_text':return $(node).text().replace(/\s+/g,' ').trim();
   case 'getAttributes':return node.attribs||{};
   case 'getChildren':return $(node).children().toArray().map(n=>ref(n,m.doc));
   case 'getNodes':return (node.children||[]).map(n=>ref(n,m.doc));
   case 'getInnerHTML':return $(node).html()||'';
   case 'getParent':return ref(node.parent,m.doc);
   case 'getClassNames':return (node.attribs?.class||'').split(/\s+/).filter(Boolean);
   case 'getId':return node.attribs?.id||'';
   case 'getLocalName':return node.name||'';
   case 'getPreviousSibling':return ref($(node).prev().get(0),m.doc);
   case 'getNextSibling':return ref($(node).next().get(0),m.doc);
   case 'node_type':return node.type==='text'?'text':node.type==='comment'?'comment':'element';
   case 'node_toElement':return node.type==='text'||node.type==='comment'?null:m.key;
   default:throw Error('HOST_DOM_UNSUPPORTED: '+m.function);
  }
 }
 function convert(m){
  const data=buffer(m.value);
  if(m.type==='utf8'||m.type==='gbk')return m.isEncode ? arrayBuffer(iconv.encode(String(m.value),m.type)) : iconv.decode(data,m.type);
  if(m.type==='base64')return m.isEncode ? data.toString('base64') : arrayBuffer(Buffer.from(String(m.value),'base64'));
  if(m.type==='hex')return data.toString('hex');
  if(['md5','sha1','sha256','sha512'].includes(m.type))return arrayBuffer(crypto.createHash(m.type).update(data).digest());
  if(m.type==='hmac'){const value=crypto.createHmac(m.hash,buffer(m.key)).update(data).digest();return m.isString?value.toString('hex'):arrayBuffer(value);}
  if(m.type.startsWith('aes-')){const key=buffer(m.key),mode=m.type.slice(4);const cipher=crypto.createDecipheriv(`aes-${key.length*8}-${mode}`,key,mode==='ecb'?null:buffer(m.iv||new Uint8Array(16)));cipher.setAutoPadding(mode==='cbc');return arrayBuffer(Buffer.concat([cipher.update(data),cipher.final()]));}
  throw Error('HOST_CONVERT_UNSUPPORTED: '+m.type);
 }
 let context;
 function sync(m){
  switch(m.method){
   case 'load_data':return storage.get(m.data_key)??null;
   case 'save_data':storage.set(m.data_key,m.data);return null;
   case 'delete_data':storage.delete(m.data_key);return null;
   case 'load_setting':return settings.get(m.setting_key)??null;
   case 'isLogged':return storage.has('token');
   case 'getLocale':return 'zh-CN';
   case 'getPlatform':return 'android';
   case 'html':return html(m);
   case 'convert':return convert(m);
   case 'uuid':return crypto.randomUUID();
   case 'random':return m.type==='int'?Math.floor(m.min+Math.random()*(m.max-m.min+1)):m.min+Math.random()*(m.max-m.min);
   case 'cookie':if(m.function==='set'){cookies.push(...m.cookies);return null;}if(m.function==='get')return cookies;return null;
   case 'execute_js':return vm.runInContext(String(m.code||m.js||''),context,{timeout:2000});
   case 'log':return null;
   default:throw Error('HOST_API_UNSUPPORTED: '+m.method);
  }
 }
 async function message(m){
  if(m.method==='delay'){if(closed)return;await new Promise(r=>setTimeout(r,Math.min(Number(m.time)||0,2000)));return null;}
  if(m.method==='http'){
   const repeated=evidence.filter(e=>e.url===String(m.url)&&e.status>=400);
   if(repeated.length>=3)throw Error('AUDIT_REPEATED_HTTP_'+repeated.at(-1).status);
   if(closed)throw Error('AUDIT_FINISHED');if(++requests>128)throw Error('AUDIT_REQUEST_LIMIT');
   const requestStage=stage;
   const r=await rpc(meta.fileName,{method:m.http_method,url:m.url,headers:m.headers,data:m.data});
   evidence.push({stage:requestStage,...(r.evidence||{url:String(m.url).split('?')[0],error:clean(r.error)}),ms:r.ms});
   if(r.error)return {error:r.error};
   return {status:r.status,url:r.url,headers:r.headers,body:m.bytes?r.bytes:r.body};
  }
  if(m.method==='cookie'&&m.function==='get'){const r=await rpc(meta.fileName,{action:'cookies'});return [...(r.cookies||[]),...cookies];}
  return sync(m);
 }
 context=vm.createContext({sendMessage:message,sendMessageSync:sync,APP:{locale:'zh-CN',version:'1.6.0'},console:{log(){},warn(){},error(){}},URL,URLSearchParams,TextDecoder,TextEncoder});
 const code=sourceOverride||fs.readFileSync(path.join(sourceDir,meta.fileName),'utf8'),className=/class\s+(\w+)\s+extends\s+ComicSource/.exec(code)?.[1];
 if(!className)throw Error('Source class not found');
 vm.runInContext(runtime+'\n'+override+'\nAPP={locale:"zh-CN",version:"1.7.0",platform:"android"};globalThis.appVersion="1.7.0";\n'+code+'\n'+bridge+'\n globalThis.src=new '+className+'();',context,{timeout:3000});
 for(const [key,definition] of Object.entries(vm.runInContext('src.settings||{}',context)))settings.set(key,definition.default??definition.defaultValue??null);
 const result={context,evidence,backgroundErrors:[],setStage(value){stage=value;},close(){closed=true;},async run(code){return vm.runInContext(code,context,{timeout:5000});}};auditHosts.push(result);return result;
}
async function bounded(promise,ms=35000){let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('AUDIT_STAGE_TIMEOUT')),ms);})]);}finally{clearTimeout(timer);}}
function failure(error,evidence){const text=clean(error);if(/HOST_/.test(text))return 'host_unsupported';if(/AUDIT_REQUEST_LIMIT/.test(text))return 'audit_budget_exceeded';if(evidence.some(e=>e.challenge))return 'verification_required';if(/login|log(?:ged)?[ -]?in|登录|登入|帳號|账号|Authorization|API.?key/i.test(text)||evidence.some(e=>e.status===401))return 'login_or_key_required';if(evidence.some(e=>e.status===403))return 'http_403';if(evidence.some(e=>e.status===404))return 'http_404';if(/AUDIT_REPEATED_HTTP_4\d\d/.test(text))return 'http_4xx';if(/timeout|timed out|TIMEOUT/i.test(text))return 'timeout';if(/ConnectionError|SSLError|DNS|NameResolution|ECONN|TLS/i.test(text)||evidence.some(e=>/ConnectionError|SSLError|NameResolution/i.test(e.error||'')))return 'network_error';if(evidence.some(e=>e.status>=500))return 'http_5xx';return 'parse_or_api_error';}
async function audit(meta){
 const row={key:meta.key,name:meta.name,file:meta.fileName,offered:meta.offered,nativeEquivalent:!!meta.nativeEquivalent,sourceSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(sourceDir,meta.fileName))).digest('hex'),startedAt:new Date().toISOString(),stages:[]};let h;
 try{
  h=host(meta);
  async function stage(name,code){h.setStage(name);const before=h.evidence.length,start=Date.now();try{const data=await bounded(h.run(code));const entries=(data?.entries||[]).filter(e=>e.id&&e.title&&!String(e.id).startsWith('__'));const result={name,status:entries.length?'available':data?.supported===false?'unsupported':'empty',count:entries.length,hasMore:data?.hasMore||false,ms:Date.now()-start};if(name==='init')result.status='initialized';row.stages.push(result);return data;}catch(error){row.stages.push({name,status:failure(error,h.evidence.slice(before)),error:clean(error),ms:Date.now()-start});return null;}}
  await stage('init','Promise.resolve(src.init?.()).then(()=>({}))');
  if(credentials[meta.key]){const c=credentials[meta.key];await stage('login','Promise.resolve(src.account.login('+JSON.stringify(c.account)+','+JSON.stringify(c.password)+')).then(()=>({}))');row.authenticated=await h.run('src.isLogged');}
  const catalog=await h.run('__cialloDiscoverCatalog(src)');row.catalog={feeds:catalog.feeds.length,categories:catalog.categories.length,dynamic:catalog.dynamicCategories};
  if(['lanraragi','komga','kavita'].includes(meta.key)){row.status='self_host_configuration_required';row.reason='No user server URL/account supplied';}
  else{
   const data=await stage('native','__cialloDiscover(src,{dimension:"updates",range:"最新",page:1,feedId:"native"})');
   const hard=row.stages.at(-1).status;
   if(!['verification_required','login_or_key_required','http_403','timeout','network_error'].includes(hard)){
    async function nextPage(name,first,query){const data=await stage(name,'__cialloDiscover(src,'+JSON.stringify(query)+')');if(data?.entries?.length){const seen=new Set((first.entries||[]).map(e=>String(e.id)));const newCount=data.entries.filter(e=>!seen.has(String(e.id))).length;row.stages.at(-1).newCount=newCount;if(!newCount){row.stages.at(-1).status='pagination_no_progress';row.stages.at(-1).error='Second page repeated every first-page ID';}}}
    if(process.env.DISCOVER_LIVE_PAGES==='2'&&data?.hasMore)await nextPage('native_page2',data,{dimension:'updates',range:'最新',page:2,feedId:'native',cursor:data.nextCursor??null});
    for(const kind of ['updates','ranking','discussion','clicks']){
     const feed=catalog.feeds.find(f=>f.kind===kind);
     if(feed)await stage(kind,'__cialloDiscover(src,'+JSON.stringify({dimension:kind,range:kind==='updates'?'最新':kind==='ranking'?'热门':kind==='discussion'?'24h':'总',page:1,feedId:feed.id})+')');
    }
    const category=catalog.categories.find(c=>/冒险|冒險|action|adventure|恋爱|戀愛/i.test(c.label))||catalog.categories[0];
    if(category){const data=await stage('category','__cialloDiscover(src,'+JSON.stringify({dimension:'updates',range:'最新',page:1,categoryId:category.id})+')');
     if(process.env.DISCOVER_LIVE_PAGES==='2'&&data?.hasMore)await nextPage('category_page2',data,{dimension:'updates',range:'最新',page:2,categoryId:category.id,cursor:data.nextCursor??null});}
   }
   const relevant=row.stages.filter(s=>s.name!=='init');row.status=relevant.some(s=>s.status==='available')?(relevant.some(s=>!['available','unsupported','empty'].includes(s.status))?'partially_available':'available'):(relevant.find(s=>!['unsupported','empty'].includes(s.status))?.status||'no_discovery_data');
   if(!catalog.feeds.length&&!catalog.categories.length){await stage('site_http','Network.get(src.baseUrl||src.url).then(r=>({supported:false}))');row.siteOnly=true;row.siteReachable=h.evidence.some(e=>e.stage==='site_http'&&e.status>=200&&e.status<300);}
  }
 }catch(error){row.status=failure(error,h?.evidence||[]);row.error=clean(error);}
 finally{h?.close();row.http=h?.evidence||[];row.backgroundErrors=h?.backgroundErrors||[];row.finishedAt=new Date().toISOString();fs.appendFileSync(path.join(out,'live-results.jsonl'),JSON.stringify(row)+'\n');console.log(JSON.stringify({key:row.key,file:row.file,status:row.status,stages:row.stages.map(s=>`${s.name}:${s.status}:${s.count||0}`)}));}
 return row;
}
async function main(){
 if(process.env.DISCOVER_LIVE_SELF_TEST==='1'){
  const h=host({fileName:'self-test'},'class HostSelfTest extends ComicSource { key="self-test"; }');
  await h.run('(()=>{for(let i=0;i<16;i++){const d=new HtmlDocument("<article><h1>Document "+i+"</h1><span>second</span></article>");const a=d.querySelector("article");if(d.querySelector("h1").text!=="Document "+i||a.querySelector("h1").text!=="Document "+i||d.querySelectorAll("span").length!==1)throw Error("HOST_DOM_NAMESPACE_REGRESSION");d.dispose();}if(APP.locale!=="zh-CN"||APP.platform!=="android")throw Error("HOST_APP_PARITY");})()');
  await h.run('(()=>{const c=new Comic({id:"1",title:"metadata",author:"Artist",updatedAt:"2026-10-13",latestChapter:"chapter",clicks:12,discussions:0,extraSecret:"must-not-copy"});if(!(c instanceof Comic)||c.author!=="Artist"||c.clicks!==12||c.discussions!==0||c.updatedAt!=="2026-10-13"||c.latestChapter!=="chapter"||c.extraSecret!==undefined)throw Error("COMIC_METADATA_LOST");})()');
  const v=host({fileName:'vomic.js'});
  const record={id:123,name:'Quoted "title"',cover:'https://example.invalid/'+('x'.repeat(300)),intro:'a "quoted" description',updated_at:'2026-10-13 10:00:00',author:'Artist',process:'连载',tags:'冒险,奇幻',view_num:12,comment_num:3};
  const flight='1:'+JSON.stringify({items:[record]})+'\n',markup='<script>self.__next_f.push('+JSON.stringify([1,flight])+')</script>';
  for(const text of [flight,markup]){const rows=await v.run('src.parseHomeCards('+JSON.stringify(text)+')');if(rows.length!==1||rows[0].name!==record.name||rows[0].cover!==record.cover||rows[0].author!=='Artist'||rows[0].clicks!==12||rows[0].discussions!==3)throw Error('VOMIC_FLIGHT_METADATA_REGRESSION');}
  v.close();
  const j=host({fileName:'jm.js'});
  await j.run(`(async()=>{
   const routes=['first.invalid','second.invalid','third.invalid','fourth.invalid','fifth.invalid','sixth.invalid'];
   src.getApiHeaders=()=>({});src.convertData=text=>text;
   JM.apiDomains=[...routes];JM.fallbackServers=[];let calls=[];
   Network.get=async url=>{calls.push(url);return calls.length===1?{status:200,body:'proxy error'}:calls.length===2?{status:200,body:JSON.stringify({data:'invalid decoded JSON'})}:{status:200,body:JSON.stringify({data:'{"ok":true}'})};};
   const path='/categories?page=2';const result=await src.get(src.baseUrl+path);
   if(!JSON.parse(result).ok||calls.length!==3||!calls.every(url=>url.endsWith(path))||JM.apiDomains[0]!==routes[2])throw Error('JM_GET_VALIDATED_FAILOVER');
   JM.apiDomains=[...routes];calls=[];Network.get=async url=>{calls.push(url);return {status:401};};
   try{await src.get(src.baseUrl+path);throw Error('JM_AUTH_ACCEPTED');}catch(e){if(!/Login expired/.test(String(e))||calls.length!==1)throw Error('JM_AUTH_MUST_NOT_RETRY');}
   calls=[];Network.get=async url=>{calls.push(url);throw Error('offline');};
   try{await src.get(src.baseUrl+path);}catch(e){}
   if(calls.length!==5)throw Error('JM_RETRY_ROUTE_CAP');
   calls=[];try{await src.get('https://unrelated.invalid/book');}catch(e){}
   if(calls.length!==1||calls[0]!=='https://unrelated.invalid/book')throw Error('JM_FOREIGN_URL_MUST_NOT_HOP');
   const originalNow=Date.now;let now=0;Date.now=()=>now;calls=[];
   Network.get=async url=>{calls.push(url);now=19000;throw Error('offline');};
   try{await src.get(src.baseUrl+path);}catch(e){}finally{Date.now=originalNow;}
   if(calls.length!==1)throw Error('JM_RETRY_TIME_BUDGET');
  })()`);
  j.close();
  const m=host({fileName:'mycomic.js'});
  await m.run(`(()=>{headers['User-Agent']='verified-browser-agent';
   if(src.comic.onImageLoad('https://biccam.com/example').headers['User-Agent']!=='verified-browser-agent'||
      src.comic.onThumbnailLoad('https://biccam.com/example').headers['User-Agent']!=='verified-browser-agent')throw Error('MYCOMIC_VERIFIED_IMAGE_IDENTITY');})()`);
  const chapters=[{id:42,title:'第01话 [完] "引号"'},{id:43,title:'next'}];
  const chapterMarkup='<div x-data="'+('{chapters:'+JSON.stringify(chapters)+'}').replaceAll('"','&quot;')+'"></div>';
  const parsed=await m.run('(()=>{const d=new HtmlDocument('+JSON.stringify(chapterMarkup)+');try{return JSON.parse(__cialloChapterJson(d,"" )[1]);}finally{d.dispose();}})()');
  if(parsed.length!==2||parsed[0].title!==chapters[0].title)throw Error('MYCOMIC_CHAPTER_JSON_ESCAPES');
  m.close();
  h.close();console.log('Host DOM/App/metadata, HTML/RSC parsing, JM bounded failover and MYCOMIC verified image identity contracts passed (no live requests)');transport.stdin.end();return;
 }
 let profiles=JSON.parse(fs.readFileSync(path.join(sourceDir,'index.json'),'utf8'));
 if(process.env.DISCOVER_LIVE_OFFERED_ONLY==='1')profiles=profiles.filter(m=>m.offered);
 const wanted=process.env.DISCOVER_LIVE_KEYS?.split(',').filter(Boolean);if(wanted)profiles=profiles.filter(m=>wanted.includes(m.key));
 fs.writeFileSync(path.join(out,'live-results.jsonl'),'');
 const results=[];let next=0;const startedAt=new Date().toISOString();
 await Promise.all(Array.from({length:3},async()=>{while(next<profiles.length){const meta=profiles[next++];results.push(await audit(meta));}}));
 const totals={};for(const row of results)totals[row.status]=(totals[row.status]||0)+1;
 const provenance={};for(const [name,data] of Object.entries({runtime,override,bridge,engine}))provenance[name]=crypto.createHash('sha256').update(data).digest('hex');
 const report={startedAt,finishedAt:new Date().toISOString(),scope:'production-patched configurations; real HTTP; desktop '+(process.env.DISCOVER_LIVE_HTTP_BACKEND||'requests')+'/Cheerio; optional user-authorized account, no Android/Cronet/WebView parity claim',provenance,auditWarnings,profiles:results.length,totals,sources:results};
 fs.writeFileSync(path.join(out,'live-results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({profiles:results.length,totals}));transport.stdin.end();
}
module.exports = {host, closeTransport:()=>transport.stdin.end()};
if (require.main === module) main().catch(error=>{console.error(clean(error));process.exitCode=1;transport.stdin.end();});
