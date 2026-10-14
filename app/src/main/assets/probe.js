(function(){
"use strict";
const action=__ACTION__,mode=__MODE__,shinyOnly=__SHINY__;
const clean=s=>String(s??"").replace(/\u00a0/g," ").replace(/[ \t]+/g," ").trim();
const text=e=>clean(e&&(e.innerText||e.textContent)||"");
const elements=s=>Array.from(document.querySelectorAll(s));
const vis=e=>!!(e&&e.getClientRects().length);
const labels=(root,pattern)=>Array.from(root.querySelectorAll("span,label,strong,small,dt,div,p")).filter(e=>vis(e)&&pattern.test(text(e))&&text(e).length<50).sort((a,b)=>text(a).length-text(b).length);
function oneButton(label){
 const re=new RegExp("^\\s*"+label+"\\s*$","i");
 return elements("button,[role=button]").find(b=>vis(b)&&re.test(text(b)))||null;
}
function click(label){let e=oneButton(label);if(e){e.click();return true;}return false;}
function valueInput(input,value){
 if(!input)return false;
 try{
 const proto=input.tagName==="TEXTAREA"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
 Object.getOwnPropertyDescriptor(proto,"value").set.call(input,String(value));
 input.dispatchEvent(new Event("input",{bubbles:true}));
 input.dispatchEvent(new Event("change",{bubbles:true}));
 return true;
 }catch(_){return false;}
}
function inputNear(label){
 const matches=labels(document,new RegExp("^"+label+"$","i"));
 for(const e of matches){
  let p=e;
  for(let i=0;i<5&&p;i++,p=p.parentElement){
   const ins=Array.from(p.querySelectorAll('input:not([type="checkbox"]):not([type="radio"])')).filter(vis);
   if(ins.length===1)return ins[0];
  }
 }
 return null;
}
function customSelect(label,want){
 const found=labels(document,new RegExp("^"+label+"$","i"));
 for(const e of found){
  let p=e;
  for(let i=0;i<5&&p;i++,p=p.parentElement){
   const select=p.querySelector("select");
   if(select){const o=Array.from(select.options).find(x=>text(x).toLowerCase()===want.toLowerCase());
     if(o){select.value=o.value;select.dispatchEvent(new Event("change",{bubbles:true}));return true;}}
   const b=p.querySelector('button[role="combobox"],[role="combobox"]');
   if(b){
    if(text(b).toLowerCase().includes(want.toLowerCase()))return true;
    b.click();
    const opt=elements('[role="option"]').find(x=>vis(x)&&text(x).toLowerCase()===want.toLowerCase());
    if(opt){opt.click();return true;}return false;
   }
  }
 }
 return false;
}
function setShiny(want){
 for(const el of labels(document,/Shiny only/i)){
  let p=el;
  for(let i=0;i<5&&p;i++,p=p.parentElement){
   const sw=p.querySelector('input[type=checkbox],[role=switch],button[aria-checked]');
   if(sw){
    const now=sw.matches("input")?sw.checked:sw.getAttribute("aria-checked")==="true"||sw.getAttribute("data-state")==="checked";
    if(now!==want)sw.click();
    return true;
   }
  }
 }
 return false;
}
function applyIv(){
 click("Stats");click("Raw IV");
 let done=false;
 for(const el of labels(document,/^IV$/i)){
  let p=el;
  for(let depth=0;depth<6&&p;depth++,p=p.parentElement){
   const ins=Array.from(p.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio])')).filter(vis);
   if(ins.length>=3&&ins.length<=5){done=ins.slice(0,3).every(input=>valueInput(input,15));break;}
  }
  if(done)break;
 }
 return done;
}
function applyGl(){
 click("PvP");
 let ok=!!valueInput(inputNear("Top ranking"),1);
 const league=customSelect("League","Great League");
 const prior=customSelect("Include previous evolutions","Yes");
 return {ok,league,prior};
}
function cardRoots(){
 const btns=elements("button,[role=button]").filter(e=>vis(e)&&/^Get coords$/i.test(text(e)));
 return btns.slice(0,10).map((b,i)=>{
  let node=b,chosen=b.parentElement;
  for(let depth=0;depth<10&&node;depth++,node=node.parentElement){
   const t=text(node);
   if(/\bLOCATION\b/i.test(t)&&/\bDESPAWN\b/i.test(t)&&/\bCP\b/i.test(t)&&t.length<2500){chosen=node;break;}
  }
  return {node:chosen,sourceIndex:i};
 });
}
function nextLabelValue(root,label){
 const re=new RegExp("^"+label+"$","i");
 const c=Array.from(root.querySelectorAll("span,div,p,small,label,dt")).filter(e=>re.test(text(e))&&text(e).length<18).sort((a,b)=>text(a).length-text(b).length);
 for(const x of c){
  if(x.nextElementSibling){
   const s=text(x.nextElementSibling);
   if(s&&s.length<80&&!re.test(s))return s;
  }
  const p=x.parentElement;
  if(p&&p.children.length<8){
   const s=text(p).replace(new RegExp("^"+label+"\\s*:?\\s*","i"),"");
   if(s&&s!==text(p)&&s.length<80)return s;
  }
 }
 return "";
}
function parseCard(box,sourceIndex){
 const body=text(box),lines=body.split(/\n+/).map(clean).filter(Boolean);
 const ivMatch=body.match(/(\d{1,3})\s*%\s*[\(（]\s*(\d{1,2})\s*\/\s*(\d{1,2})\s*\/\s*(\d{1,2})\s*[\)）]/);
 const raw=ivMatch?[ivMatch[2],ivMatch[3],ivMatch[4]].join("/"):
    ((body.match(/(?:^|[^\d])(\d{1,2}\s*\/\s*\d{1,2}\s*\/\s*\d{1,2})(?:[^\d]|$)/)||[])[1]||"").replace(/\s/g,"");
 const percent=ivMatch?ivMatch[1]:(raw?String(Math.round(raw.split("/").map(Number).reduce((a,b)=>a+b,0)/45*100)):"");
 const val=(label,re)=>{let candidate=nextLabelValue(box,label);let m=candidate.match(re);if(m)return m[1];
     m=body.match(new RegExp("(?:^|\\n)\\s*"+label+"\\s*:?\\s*([0-9.]+)","im"));return m?m[1]:"";};
 const heading=box.querySelector("h2,h3,h4,h5,[class*=font-semibold],[class*=font-bold]");
 let title=heading?text(heading):"";
 if(!title||title.length>85||/^(?:CP|IV|LVL)$/i.test(title)){
  title=lines.find(s=>/^[\p{L}\p{N} _'.\-♀♂()✨]+$/u.test(s)&&!/(?:^CP$|^IV$|^LVL$|LOCATION|DESPAWN|Get coords)/i.test(s)&&s.length<45)||"Pokémon";
 }
 const meta=lines.find(s=>/#\s*\d{1,4}\b/.test(s))||"";
 const size=((meta.match(/(?:^|[\s·])(?:XXS|XS|XXL|XL|M)(?=$|[\s·])/i)||[])[0]||"").trim();
 const move=lines.find(s=>/\s\/\s/.test(s)&&!/\d+\s*\/\s*\d+\s*\/\s*\d+/.test(s))||"";
 const loc=nextLabelValue(box,"LOCATION")||(body.match(/\bLOCATION\s*\n?\s*([^\n]+?)(?=\n|DESPAWN|$)/i)||[])[1]||"";
 const desp=nextLabelValue(box,"DESPAWN")||(body.match(/\bDESPAWN\s*(\d{1,2}:\d{2})/i)||[])[1]||"";
 const image=Array.from(box.querySelectorAll("img[src]")).find(e=>/^https?:/.test(e.src)||e.src.startsWith("data:"));
 const rankMatch=body.match(/\b(?:PvP\s*)?(?:Rank|Ranking|Rk)\s*#?\s*(\d+)\b/i);
 const isShiny=/✨/.test(title);
 return {
  sourceIndex,name:title.replace(/✨/g,"").trim(),image:image?image.src:"",
  number:(meta.match(/#\d{1,4}/)||[])[0]||"",
  gender:/\bFemale\b|♀/i.test(meta)?"♀":/\bMale\b|♂/i.test(meta)?"♂":"",
  size:size,moves:move,location:clean(loc),despawn:(desp.match(/\d{1,2}:\d{2}/)||[])[0]||"",
  cp:val("CP",/(\d{1,5})/),level:val("(?:LVL|Level)",/([\d.]+)/),
  iv:raw,ivPercent:percent,rank:rankMatch?rankMatch[1]:"",
  shiny:isShiny,form:(title.match(/\(([^)]+)\)/)||[])[1]||""
 };
}
function collect(){
 const found=cardRoots().map(item=>parseCard(item.node,item.sourceIndex));
 const valid=mode==="iv"?found.filter(x=>x.iv==="15/15/15"||x.ivPercent==="100"):found;
 const uncertain=mode==="iv"&&found.some(x=>!(x.iv==="15/15/15"||x.ivPercent==="100"));
 return JSON.stringify({ok:true,cards:valid,found:found.length,uncertain});
}
if(action==="collect")return collect();
if(action.startsWith("coords:")){
 const index=Number(action.slice(7));
 const btn=elements("button,[role=button]").filter(e=>vis(e)&&/^Get coords$/i.test(text(e)))[index];
 if(!btn)return JSON.stringify({ok:false,status:"Get coords não está disponível para este Pokémon"});
 btn.click();
 return JSON.stringify({ok:true,status:"Botão Get coords acionado no PoGoCoordinates"});
}
if(action==="search"){
 const shin=setShiny(shinyOnly);
 const tab=click(mode==="iv"?"Stats":"PvP");
 setTimeout(()=>{
  const fields=mode==="iv"?applyIv():applyGl().ok;
  setTimeout(()=>click("Search"),650);
 },400);
 return JSON.stringify({ok:tab,shinyConfirmed:shin,mode});
}
return JSON.stringify({ok:false});
})()