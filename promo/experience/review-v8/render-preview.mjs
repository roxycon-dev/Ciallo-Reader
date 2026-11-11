// Artifact rendering only: deterministic screenshots piped to a preview movie.
import {chromium} from '../../styleframes/node_modules/playwright-core/index.mjs';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const ff='C:/Users/GuanXingRen/Miniconda3/Lib/site-packages/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe';
const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const page=await browser.newPage({viewport:{width:1920,height:1080}});
await page.goto('http://127.0.0.1:4173/?record=1&frame=0');
await page.waitForFunction(()=>window.CialloPromo?.ready);
await page.evaluate(()=>document.fonts.ready);
const duration=await page.evaluate(()=>CialloPromo.duration),fps=30;
const encoder=spawn(ff,['-hide_banner','-loglevel','error','-y','-f','image2pipe','-framerate',String(fps),'-vcodec','mjpeg','-i','-','-an','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',path.join(root,'motion-silent.mp4')],{windowsHide:true});
encoder.stderr.on('data',d=>process.stderr.write(d));
try{
  for(let f=0;f<Math.ceil(duration*fps);f++){
    await page.evaluate(t=>CialloPromo.seek(t),f/fps);
    const jpeg=await page.screenshot({type:'jpeg',quality:91});
    if(!encoder.stdin.write(jpeg))await once(encoder.stdin,'drain');
    if(f%300===0)console.log(`Rendered ${(f/fps).toFixed(0)} / ${duration.toFixed(1)} seconds`);
  }
  encoder.stdin.end();await once(encoder,'close');console.log('Motion preview rendered.');
}finally{await browser.close()}
