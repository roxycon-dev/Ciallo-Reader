import {chromium} from '../styleframes/node_modules/playwright-core/index.mjs';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
import {writeFile} from 'node:fs/promises';
const here=path.dirname(fileURLToPath(import.meta.url));
const browser=await chromium.launch({executablePath:'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
await page.context().setOffline(true);
const errors=[];page.on('pageerror',e=>errors.push(e.message));
try{
 await page.goto(pathToFileURL(path.join(here,'../output/Ciallo-reading-experience.html')).href,{waitUntil:'load'});
 await page.evaluate(()=>document.fonts.ready);
 await page.screenshot({path:path.join(here,'review/offline-hero.png')});
 await page.locator('#tab-reader').click();await page.waitForTimeout(400);
 const display=await page.locator('#display-image').evaluate(el=>({loaded:el.complete&&el.naturalWidth>0,embedded:el.src.startsWith('data:')}));
 await page.locator('#sound-toggle').click();await page.waitForTimeout(1600);
 const audio=await page.locator('#ambient-audio').evaluate(el=>({playing:!el.paused&&el.currentTime>0,embedded:el.src.startsWith('data:')}));
 const fonts=await page.evaluate(()=>[...document.fonts].map(f=>({family:f.family,status:f.status})));
 if(!display.loaded||!display.embedded||!audio.playing||!audio.embedded||errors.length)throw Error(JSON.stringify({display,audio,errors}));
 const report={status:'passed',network:'offline',display,audio,fonts,errors};
 await writeFile(path.join(here,'review/offline-checks.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}finally{await browser.close();}
