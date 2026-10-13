import {transform} from 'esbuild';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const source=path.join(root,'docs/promo-site');
const argument=process.argv.indexOf('--out-dir');
const output=path.resolve(argument>=0?process.argv[argument+1]:path.join(root,'artifacts/site-performance/preview/after'));
const allowed=process.env.RUNNER_TEMP?path.resolve(process.env.RUNNER_TEMP):path.join(root,'artifacts');
const relative=path.relative(allowed,output);
if(!relative||relative.startsWith('..')||path.isAbsolute(relative))throw Error('Build output must be inside the temporary artifact directory');
const marker=path.join(output,'_site-version.json');
try{
 const existing=JSON.parse(await fs.readFile(marker,'utf8'));
 if(existing.generator!=='Ciallo Reader static build')throw Error('Output is not an owned build directory');
 await fs.rm(output,{recursive:true,force:true});
}catch(error){if(error.code!=='ENOENT')throw error;if(await fs.stat(output).catch(()=>null))throw Error('Refusing to replace an unmarked directory')}
await fs.cp(source,output,{recursive:true});
await fs.mkdir(path.join(output,'assets'),{recursive:true});
const files=['site.css','experience.css','site.js','curve-engine.js','flow-worker.js'];
const assets={};let jsBytes=0;
for(const file of files){
 const input=await fs.readFile(path.join(source,file),'utf8');
 const result=await transform(input,{loader:file.endsWith('.css')?'css':'js',minify:true,target:file.endsWith('.css')?['chrome109','safari15','firefox115']:'es2020',legalComments:'none',charset:'utf8'});
 const hash=createHash('sha256').update(result.code).digest('hex').slice(0,12),extension=path.extname(file);
 const name=path.basename(file,extension)+'.'+hash+extension;
 await fs.writeFile(path.join(output,'assets',name),result.code);
 assets[file]=name;if(extension==='.js')jsBytes+=Buffer.byteLength(result.code);
}
if(jsBytes>=30000)throw Error('Published JavaScript exceeds the 30 KB budget');
const version=createHash('sha256').update(JSON.stringify(assets)).digest('hex').slice(0,16);
for(const file of ['index.html','en/index.html']){
 let html=await fs.readFile(path.join(source,file),'utf8');
 const prefix=file.startsWith('en/')?'../':'./';
 for(const name of ['site.css','experience.css','site.js'])html=html.replace(prefix+name,prefix+'assets/'+assets[name]);
 html=html.replace('data-flow-worker="./flow-worker.js"','data-flow-worker="./'+assets['flow-worker.js']+'"');
 html=html.replace('data-flow-engine="./curve-engine.js"','data-flow-engine="./'+assets['curve-engine.js']+'"');
 html=html.replace('<meta charset="utf-8">','<meta charset="utf-8">\n  <meta name="ciallo-site-version" content="'+version+'">');
 await fs.writeFile(path.join(output,file),html);
}
await fs.writeFile(path.join(output,'.nojekyll'),'');
await fs.writeFile(marker,JSON.stringify({generator:'Ciallo Reader static build',version,commit:process.env.GITHUB_SHA||null,assets,jsBytes},null,2));
console.log(JSON.stringify({output,version,jsBytes,assets}));
