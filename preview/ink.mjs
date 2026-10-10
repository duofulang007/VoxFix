export class InkPad {
  constructor(canvas,onCandidates,onError) {
    this.canvas=canvas;this.onCandidates=onCandidates;this.onError=onError;this.strokes=[];this.serial=0;this.active=null;
    if(globalThis.VoxFixIme)globalThis.voxfixInkResult=(serial,words,error)=>{if(this.pending?.serial!==serial)return;const pending=this.pending;this.pending=null;if(error)pending.reject(Error(error));else pending.resolve({candidates:words});};
    this.observer=new ResizeObserver(()=>this.draw());this.observer.observe(canvas);
    canvas.addEventListener('pointerdown',event=>{
      if(event.button!==0||this.active!==null)return;
      event.preventDefault();event.stopPropagation();this.invalidate();
      if(this.strokes.length>=100||this.strokes.flat().length>=2000){onError('笔画过多，请选字或清空后继续。');return;}
      this.active=event.pointerId;canvas.setPointerCapture(event.pointerId);this.strokes.push([this.point(event)]);onCandidates([]);this.draw();
    });
    canvas.addEventListener('pointermove',event=>{
      if(this.active!==event.pointerId)return;event.preventDefault();
      const stroke=this.strokes.at(-1),point=this.point(event),previous=stroke.at(-1);
      if(Math.hypot(point[0]-previous[0],point[1]-previous[1])>2 && this.strokes.flat().length<2000){stroke.push(point);this.draw();}
    });
    const end=event=>{if(this.active!==event.pointerId)return;this.active=null;if(canvas.hasPointerCapture(event.pointerId))canvas.releasePointerCapture(event.pointerId);this.timer=setTimeout(()=>this.recognize(),650);};
    canvas.addEventListener('pointerup',end);canvas.addEventListener('pointercancel',end);
  }
  point(event){const rect=this.canvas.getBoundingClientRect();return [Math.round(Math.max(0,Math.min(1000,(event.clientX-rect.left)/rect.width*1000))*10)/10,Math.round(Math.max(0,Math.min(1000,(event.clientY-rect.top)/rect.height*1000))*10)/10];}
  get hasInk(){return this.strokes.length>0;}
  invalidate(){this.serial++;clearTimeout(this.timer);this.abort?.abort();}
  clear(){this.invalidate();const pointer=this.active;this.active=null;if(pointer!==null&&this.canvas.hasPointerCapture(pointer))this.canvas.releasePointerCapture(pointer);this.strokes=[];this.onCandidates([]);this.draw();}
  draw(){
    const rect=this.canvas.getBoundingClientRect();if(!rect.width||!rect.height)return;
    const ratio=window.devicePixelRatio||1;this.canvas.width=Math.round(rect.width*ratio);this.canvas.height=Math.round(rect.height*ratio);
    const context=this.canvas.getContext('2d');context.setTransform(ratio,0,0,ratio,0,0);context.clearRect(0,0,rect.width,rect.height);
    context.lineWidth=5;context.lineCap='round';context.lineJoin='round';context.strokeStyle='#263f56';context.fillStyle='#263f56';
    for(const stroke of this.strokes){context.beginPath();stroke.forEach(([x,y],index)=>{x=x/1000*rect.width;y=y/1000*rect.height;if(index===0)context.moveTo(x,y);else context.lineTo(x,y);});context.stroke();if(stroke.length===1){const [x,y]=stroke[0];context.beginPath();context.arc(x/1000*rect.width,y/1000*rect.height,2.5,0,Math.PI*2);context.fill();}}
  }
  async recognize(){
    if(!this.hasInk)return;
    const serial=this.serial;this.abort=new AbortController();const controller=this.abort;const timeout=setTimeout(()=>controller.abort(),15000);
    try{
      let result;
      if(globalThis.VoxFixIme){
        result=await new Promise((resolve,reject)=>{this.pending={serial,resolve,reject};controller.signal.addEventListener('abort',()=>{if(this.pending?.serial===serial){this.pending=null;reject(new DOMException('Cancelled','AbortError'));}},{once:true});globalThis.VoxFixIme.recognizeInk(serial,JSON.stringify({w:1000,h:1000,strokes:this.strokes}));});
      }else{
        const response=await fetch('/api/handwriting',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({w:1000,h:1000,strokes:this.strokes}),signal:controller.signal});
        result=await response.json();if(!response.ok)throw Error(result.error||'本地手写识别失败');
      }
      if(serial===this.serial)this.onCandidates(result.candidates);
    }catch(error){if(serial===this.serial)this.onError(error.name==='AbortError'?'手写识别超时，请稍后再试。':error.message);}finally{clearTimeout(timeout);}
  }
}
