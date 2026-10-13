import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import vm from 'node:vm';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const argument=process.argv.indexOf('--out-dir');
const output=path.resolve(argument>=0?process.argv[argument+1]:path.join(root,'artifacts/site-performance/preview/after'));
const manifest=JSON.parse(await fs.readFile(path.join(output,'_site-version.json'),'utf8'));
assert.equal(manifest.generator,'Ciallo Reader static build');
let bytes=0,references=0;
for(const [source,name] of Object.entries(manifest.assets)){
  const code=await fs.readFile(path.join(output,'assets',name),'utf8');
  const hash=createHash('sha256').update(code).digest('hex').slice(0,12);
  assert.equal(name,path.basename(source,path.extname(source))+'.'+hash+path.extname(source));
  if(source.endsWith('.js')){new vm.Script(code,{filename:name});bytes+=Buffer.byteLength(code)}
}
assert.equal(bytes,manifest.jsBytes);
assert.ok(bytes<30000,'Published JavaScript must stay below 30 KB');
for(const page of ['index.html','en/index.html']){
  const html=await fs.readFile(path.join(output,page),'utf8');
  const base=new URL(page,'https://example.test/Ciallo-Reader/');
  assert.ok(html.includes('name="ciallo-site-version" content="'+manifest.version+'"'));
  const script=html.match(/<script[^>]+src="([^"]+)"[^>]*data-flow-worker="([^"]+)"[^>]*data-flow-engine="([^"]+)"/);
  assert.ok(script,'Each language must load the versioned controller, worker, and engine');
  const js=new URL(script[1],base);
  for(const [url,file] of [[js,'site.js'],[new URL(script[2],js),'flow-worker.js'],[new URL(script[3],js),'curve-engine.js']]){
    assert.equal(url.pathname,'/Ciallo-Reader/assets/'+manifest.assets[file]);
  }
  for(const match of html.matchAll(/<(?:img|script|link)\b[^>]*\b(?:src|href)="([^"]+)"/g)){
    const target=match[1];if(!target||/^(?:https?:|data:|#)/.test(target))continue;
    const url=new URL(target,base);
    const relative=decodeURIComponent(url.pathname.slice('/Ciallo-Reader/'.length));
    assert.ok(!relative.startsWith('..'),'Local references must stay in the site');
    await fs.access(path.join(output,relative));references++;
  }
}
console.log(JSON.stringify({version:manifest.version,jsBytes:bytes,localReferencesChecked:references,languages:2}));
