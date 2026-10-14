(function(){
  if(window.__psoPresetsInstalled)return;
  window.__psoPresetsInstalled=true;
  const trim=e=>String(e?.textContent||"").trim();
  const textInput=e=>e&&e.tagName==="INPUT"&&e.type!=="checkbox"&&e.type!=="radio";
  function setBlank(e,value){
    if(!textInput(e)||e.value.trim()!=="")return;
    const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set;
    setter.call(e,value);
    e.dispatchEvent(new Event("input",{bubbles:true}));
    e.dispatchEvent(new Event("change",{bubbles:true}));
  }
  function scan(){
    const labels=Array.from(document.querySelectorAll("label,span,div,p,strong")).filter(e=>e.children.length<=2&&e.getClientRects().length&&trim(e).length<35);
    for(const label of labels){
      const name=trim(label);
      if(name==="Top ranking"){
        let el=label.parentElement;
        for(let d=0;el&&d<4;d++,el=el.parentElement){
          const inputs=Array.from(el.querySelectorAll("input")).filter(x=>x.getClientRects().length&&textInput(x));
          if(inputs.length===1){setBlank(inputs[0],"1");break;}
        }
      }
      if(name==="IV"){
        let el=label.parentElement;
        for(let d=0;el&&d<5;d++,el=el.parentElement){
          const inputs=Array.from(el.querySelectorAll("input")).filter(x=>x.getClientRects().length&&textInput(x));
          if(inputs.length===3){inputs.forEach(x=>setBlank(x,"15"));break;}
        }
      }
    }
  }
  let pending=false;
  function schedule(){
    if(pending)return;pending=true;
    setTimeout(()=>{pending=false;scan();},180);
  }
  document.addEventListener("click",schedule,true);
  const observer=new MutationObserver(schedule);
  observer.observe(document.documentElement,{childList:true,subtree:true});
  schedule();
})();