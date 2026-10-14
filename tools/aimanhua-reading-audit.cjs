/* Targeted real reading check. No other sources, accounts, shelf writes or emulator. */
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {host,closeTransport}=require('./discover-live-audit.cjs');
const out=path.resolve('artifacts/discover-final-repair/aimanhua-reading');fs.mkdirSync(out,{recursive:true});
const h=host({fileName:'aimanhua.js'});
(async()=>{
 const id='69008588',book='https://www.alipaypay.com/'+id+'.html';
 const info=await h.run('src.comic.loadInfo('+JSON.stringify(book)+')');
 if(info.title!=='顶级气运，悄悄修炼千年')throw Error('Mirror identity mismatch');
 const rows=await h.run('src.catalog('+JSON.stringify(id)+')');
 const chapter=rows.find(r=>!r.locked&&r.pages>=2&&!/请假|请 假|延更|通知|^！$/.test(r.title));
 if(!chapter)throw Error('No public chapter available');
 const ep=book.substring(0,book.lastIndexOf('/'))+chapter.path;
 const data=await h.run('src.comic.loadEp('+JSON.stringify(book)+','+JSON.stringify(ep)+')');
 if(data.images.length!==chapter.pages)throw Error('Incomplete image list');
 const images=[];
 for(const url of [...new Set([data.images[0],data.images.at(-1)])]){
  const result=await h.run('Network.fetchBytes("GET",'+JSON.stringify(url)+',src.comic.onImageLoad().headers).then(r=>({status:r.status,url:r.url,headers:r.headers,body:r.body}))');
  if(result.status!==200)throw Error('Image request failed: '+result.status);
  const body=Buffer.from(result.body,'base64'),file='sample-'+images.length+'.image';
  fs.writeFileSync(path.join(out,file),body);
  images.push({url,status:result.status,contentType:result.headers['content-type']||result.headers['Content-Type'],
   file,bytes:body.length,sha256:crypto.createHash('sha256').update(body).digest('hex')});
 }
 const report={task:'targeted public reading',title:info.title,canonicalBook:book,actualOrigin:await h.run('src.readOrigin'),
  chapters:rows.length,chapter:chapter.title,expectedPages:chapter.pages,actualPages:data.images.length,sampledImages:images,
  sourceSha256:crypto.createHash('sha256').update(fs.readFileSync('app/src/main/assets/js_extra/aimanhua.js')).digest('hex'),http:h.evidence,
  completedAt:new Date().toISOString(),limitations:['Desktop production JS/DOM; no Android/device runtime check']};
 fs.writeFileSync(path.join(out,'reading.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({title:report.title,actualOrigin:report.actualOrigin,chapters:report.chapters,pages:report.actualPages,sampledImages:images.length}));
})().catch(e=>{console.error(String(e));process.exitCode=1;}).finally(()=>{h.close();closeTransport();});
