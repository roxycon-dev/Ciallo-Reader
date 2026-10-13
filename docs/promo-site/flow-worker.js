'use strict';
let painter=null;
self.onmessage=event=>{
  const data=event.data;
  try{
    if(data.type==='init'){
      importScripts(data.engine);
      painter=new self.CialloFlow.Painter(data.canvases,data.config,()=>new OffscreenCanvas(1,1));
      painter.resize(data.options);self.postMessage({type:'ready'});
    }else if(data.type==='resize')painter.resize(data.options);
    else if(data.type==='draw'){
      const start=performance.now();painter.draw(data.state);
      self.postMessage({type:'done',version:data.version,duration:performance.now()-start});
    }
  }catch(error){self.postMessage({type:'error',message:String(error)})}
};
