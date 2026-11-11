import {chromium} from '../styleframes/node_modules/playwright-core/index.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
const here=path.dirname(fileURLToPath(import.meta.url));
const out=path.join(here,'review');await mkdir(out,{recursive:true});
const browser=await chromium.launch({executablePath:'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',headless:true});
const errors=[],failures=[];
const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
page.on('pageerror',e=>errors.push(e.message));
page.on('response',r=>{if(r.status()>=400)failures.push(`${r.status()} ${r.url()}`);});
const check=(value,message)=>{if(!value)throw new Error(message);};
try{
 await page.goto('http://127.0.0.1:4173',{waitUntil:'networkidle'});await page.evaluate(()=>document.fonts.ready);
 await page.screenshot({path:path.join(out,'desktop-hero.png')});
 for(const [name,id,fraction]of[['story','#stories',.52],['glass','#glass',.22],['roxy','#companion',0],['closing','#next',0]]){
  await page.evaluate(({id,fraction})=>{const n=document.querySelector(id);scrollTo({top:n.offsetTop+Math.max(0,n.offsetHeight-innerHeight)*fraction,behavior:'instant'});},{id,fraction});
  await page.waitForTimeout(550);await page.screenshot({path:path.join(out,`desktop-${name}.png`)});
 }
 await page.locator('#tab-glass').click();await page.waitForTimeout(400);
 check(await page.locator('#display-image').getAttribute('src')==='assets/glass.webp','Glass tab did not display real glass asset');
 await page.locator('#tab-glass').press('ArrowRight');await page.waitForTimeout(400);
 check(await page.locator('#tab-novel').getAttribute('aria-selected')==='true','Keyboard tabs failed');
 check(await page.locator('#display-image').evaluate(el=>el.complete&&el.naturalWidth>0),'Selected image failed to decode');
 await page.locator('#say-hello').click();
 check((await page.locator('.roxy-response').textContent()).includes('Ciallo'),'Roxy response missing');
 await page.waitForTimeout(400);await page.screenshot({path:path.join(out,'roxy-greeting.png')});
 await page.locator('#sound-toggle').click();await page.waitForTimeout(2200);
 console.log('Audio state',await page.locator('#ambient-audio').evaluate(el=>({paused:el.paused,time:el.currentTime,ready:el.readyState,network:el.networkState,error:el.error?.message,src:el.currentSrc,hidden:document.hidden})));
 check(await page.locator('#ambient-audio').evaluate(el=>!el.paused&&el.currentTime>0),'Audio toggle did not play');
 await page.locator('#sound-toggle').click();
 check(await page.locator('#ambient-audio').evaluate(el=>el.paused),'Audio toggle did not pause');
 await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));await page.locator('#autoplay-toggle').click();await page.waitForTimeout(5600);
 check(await page.evaluate(()=>scrollY>0),'Auto presentation did not advance');
 await page.mouse.wheel(0,120);await page.waitForTimeout(120);
 check(await page.locator('#autoplay-toggle').getAttribute('aria-pressed')==='false','Manual scroll did not pause presentation');
 await page.evaluate(()=>scrollTo({top:document.querySelector('#glass').offsetTop,behavior:'instant'}));
 await page.locator('#autoplay-toggle').click();await page.waitForTimeout(5400);
 check(await page.locator('#tab-reader').getAttribute('aria-selected')==='true','Guided presentation did not advance the interface exhibit');
 await page.locator('#tab-glass').click();await page.waitForTimeout(400);
 check(await page.locator('#autoplay-toggle').getAttribute('aria-pressed')==='false','Manual tab interaction did not pause guide');
 const broken=await page.locator('img').evaluateAll(imgs=>imgs.filter(i=>!i.complete||!i.naturalWidth).map(i=>i.src));
 check(broken.length===0,`Broken image(s): ${broken.join(',')}`);
 await page.setViewportSize({width:390,height:844});
 for(const [name,id,fraction]of[['hero','#beginning',0],['story','#stories',.56],['glass','#glass',.2],['roxy','#companion',0],['closing','#next',0]]){
  await page.evaluate(({id,fraction})=>{const n=document.querySelector(id);scrollTo({top:n.offsetTop+Math.max(0,n.offsetHeight-innerHeight)*fraction,behavior:'instant'});},{id,fraction});
  await page.waitForTimeout(450);await page.screenshot({path:path.join(out,`mobile-${name}.png`)});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`Horizontal overflow in mobile ${name}`);
 }
 await page.emulateMedia({reducedMotion:'reduce'});await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));await page.waitForTimeout(300);
 await page.screenshot({path:path.join(out,'mobile-reduced-motion.png')});
 const direct=await browser.newPage({viewport:{width:1280,height:800}});await direct.goto(pathToFileURL(path.join(here,'index.html')).href,{waitUntil:'load'});await direct.evaluate(()=>document.fonts.ready);
 check(await direct.locator('.tile-reader img').evaluate(el=>el.naturalWidth>0),'Direct file open cannot load assets');await direct.close();
 check(errors.length===0,`Browser errors: ${errors.join(';')}`);check(failures.length===0,`HTTP errors: ${failures.join(';')}`);
 const report={status:'passed',viewports:['1440x1000','390x844'],checks:['full page asset load','real frame tabs','keyboard tabs','Roxy response','audio playback and pause','autoplay advances','guided interface changes','manual interruption','mobile horizontal overflow','reduced motion view','direct file open'],errors,failures};
 await writeFile(path.join(out,'checks.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}finally{await browser.close();}
