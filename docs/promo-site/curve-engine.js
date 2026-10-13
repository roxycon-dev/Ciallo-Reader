/* Shared, allocation-free curve rasterizer for the worker and compatibility path. */
(function(scope){
  'use strict';
  const RIBBONS=[
    [[[-.18,.72],[-.30,.14],[0,0],[.39,.01],[0,0],[.61,.61],[1.22,.18]],[[-.18,1.18],[-.03,.42],[0,0],[.28,.16],[0,0],[.94,-.13],[1.19,-.09]]],
    [[[-.12,.91],[.26,.57],[0,0],[.48,.84],[0,0],[.79,.34],[1.20,.45]],[[-.14,1.17],[.18,.74],[0,0],[.55,.58],[0,0],[.60,.04],[1.22,-.03]]],
    [[[-.14,.42],[.23,-.02],[0,0],[.59,.71],[0,0],[1.06,.64],[1.20,.67]],[[-.14,.24],[.32,-.12],[0,0],[.55,.95],[0,0],[1.12,.94],[1.20,.89]]]
  ];
  const COLORS=[
    [['117,92,206','157,126,210','109,139,216'],['66,156,178','103,135,207','139,117,207'],['181,112,157','151,121,196','144,111,193']],
    [['151,130,242','174,145,231','121,157,237'],['100,186,203','134,166,238','167,143,239'],['202,145,184','174,153,231','156,130,218']]
  ];
  class Geometry{
    constructor(width,height,count,steps){
      this.width=width;this.height=height;this.steps=steps;this.length=steps*2+1;
      this.artWidth=Math.max(width,height*.9);this.offset=(width-this.artWidth)/2;
      this.weights=new Float64Array((steps+1)*4);
      for(let j=0;j<=steps;j++){const u=j/steps,v=1-u,k=j*4;this.weights[k]=v*v*v;this.weights[k+1]=3*v*v*u;this.weights[k+2]=3*v*u*u;this.weights[k+3]=u*u*u}
      this.knots=new Float64Array(14);
      this.outlines=Array.from({length:6},()=>new Float64Array(this.length*2));
      this.vertices=new Float64Array(this.length*2);
      const per=Math.ceil(count/3);
      this.lines=Array.from({length:count},(_,id)=>{const z=id%per/(per-1),depth=.48+.52*Math.sin(Math.PI*z);return{band:Math.floor(id/per),q:.9*z+.1*z*z*(3-2*z),depth,width:.78+.3*depth+(id%12===0?.18:0)}});
    }
    update(time){
      const k=this.knots,weights=this.weights,n=this.steps;
      for(let band=0;band<3;band++)for(let side=0;side<2;side++){
        const base=RIBBONS[band][side],target=this.outlines[band*2+side];
        for(let i=0;i<7;i++){k[i*2]=base[i][0]+.012*Math.sin(time*.7+i*.83+band*1.4);k[i*2+1]=base[i][1]+.018*Math.sin(time+i*.61+band*1.2)}
        const dx=(k[10]-k[2])*.28,dy=(k[11]-k[3])*.28;
        k[4]=k[6]-dx;k[5]=k[7]-dy;k[8]=k[6]+dx;k[9]=k[7]+dy;
        let at=0;
        for(let part=0;part<2;part++){const p=part*6;for(let j=part?1:0;j<=n;j++){
          const w=j*4,A=weights[w],B=weights[w+1],C=weights[w+2],D=weights[w+3];
          target[at++]=(A*k[p]+B*k[p+2]+C*k[p+4]+D*k[p+6])*this.artWidth+this.offset;
          target[at++]=(A*k[p+1]+B*k[p+3]+C*k[p+5]+D*k[p+7])*this.height;
        }}
      }
    }
    line(index,state,config){
      const line=this.lines[index],a=this.outlines[line.band*2],b=this.outlines[line.band*2+1],q=line.q,out=this.vertices;
      const pressure=state.pressure,sigma=Math.max(config.sigmaMin,Math.min(config.sigmaMax,this.width*config.sigmaRatio)),inverse=1/(2*sigma*sigma);
      const pull=config.collapse*(.7+state.energy*.3)*pressure,spin=config.vortex*config.pressureSpin*(.55+state.energy*.45)*pressure;
      let near=0;
      for(let i=0;i<out.length;i+=2){
        const x=a[i]*(1-q)+b[i]*q,y=a[i+1]*(1-q)+b[i+1]*q;
        if(pressure){const dx=x-state.mx,dy=y-state.my,w=Math.exp(-(dx*dx+dy*dy)*inverse);if(w>near)near=w;out[i]=x-dx*w*pull-dy*w*spin;out[i+1]=y-dy*w*pull+dx*w*spin}
        else{out[i]=x;out[i+1]=y}
      }
      return near*pressure;
    }
  }
  class Painter{
    constructor(canvases,config,makeCanvas){
      this.canvases=canvases;this.contexts=canvases.map(c=>c.getContext('2d'));
      this.source=makeCanvas();this.ctx=this.source.getContext('2d');this.config=config;
      if(!this.ctx||this.contexts.some(c=>!c))throw Error('Canvas 2D unavailable');
    }
    resize(options){
      this.options=options;const{width:w,height:h,dpr,mobile,count,steps}=options;
      this.source.width=Math.round(w*dpr);this.source.height=Math.round(h*dpr);this.ctx.setTransform(dpr,0,0,dpr,0,0);
      this.canvases.forEach((canvas,i)=>{const scale=i<2?(mobile?1/3:1/2):1;canvas.width=Math.round(w*dpr*scale);canvas.height=Math.round(h*dpr*scale)});
      this.geometry=new Geometry(w,h,count,steps);this.gradients=[null,null];
    }
    palette(dark){
      const theme=dark?1:0;if(this.gradients[theme])return this.gradients[theme];
      const{width:w,height:h}=this.options;
      return this.gradients[theme]=COLORS[theme].map(pair=>{
        const g=this.ctx.createLinearGradient(-w*.12,h*.3,w*1.1,h*.75);
        g.addColorStop(0,'rgba('+pair[0]+',0)');g.addColorStop(.13,'rgba('+pair[0]+',.72)');g.addColorStop(.45,'rgba('+pair[1]+',1)');g.addColorStop(.82,'rgba('+pair[2]+',.86)');g.addColorStop(1,'rgba('+pair[2]+',0)');return g;
      });
    }
    draw(state){
      const ctx=this.ctx,geometry=this.geometry,{width:w,height:h,lowPower}=this.options;
      geometry.update(state.time);ctx.clearRect(0,0,w,h);ctx.lineCap='round';
      const palette=this.palette(state.dark),vertices=geometry.vertices;
      for(let index=0;index<geometry.lines.length;index++){
        const line=geometry.lines[index],near=geometry.line(index,state,this.config);
        ctx.strokeStyle=palette[line.band];ctx.globalAlpha=(state.dark?.68:.62)*line.depth*(1+.10*near);ctx.lineWidth=line.width*(1+.16*near);
        ctx.beginPath();ctx.moveTo(vertices[0],vertices[1]);
        for(let i=2;i<vertices.length-2;i+=2)ctx.quadraticCurveTo(vertices[i],vertices[i+1],(vertices[i]+vertices[i+2])/2,(vertices[i+1]+vertices[i+3])/2);
        ctx.lineTo(vertices[vertices.length-2],vertices[vertices.length-1]);ctx.stroke();
      }
      ctx.globalAlpha=1;
      this.canvases.forEach((canvas,i)=>{if(lowPower&&i===1)return;const c=this.contexts[i];c.clearRect(0,0,canvas.width,canvas.height);c.drawImage(this.source,0,0,canvas.width,canvas.height)});
    }
  }
  const api={Geometry,Painter};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;else scope.CialloFlow=api;
})(typeof self!=='undefined'?self:globalThis);
