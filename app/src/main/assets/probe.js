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
function field(label){
 const re=new RegExp("^"+label+"$","i");
 const found=elements("label,span,div,p,strong").filter(e=>vis(e)&&re.test(text(e))&&e.children.length<3);
 for(const el of found){
  let p=el.parentElement;
  for(let i=0;i<5&&p;p=p.parentElement,i++){
   const controls=Array.from(p.querySelectorAll('input:not([type=hidden]),select,[role=combobox],button[aria-haspopup]')).filter(vis);
   if(controls.length===1)return controls[0];
  }
 }
 return null;
}
function inputNear(label){const e=field(label);return e&&e.matches("input")?e:null;}
function choice(label,want){
 const e=field(label);
 if(!e)return false;
 if(e.tagName==="SELECT"){
  const opt=Array.from(e.options).find(o=>text(o).toLowerCase()===want.toLowerCase());
  if(!opt)return false;
  e.value=opt.value;e.dispatchEvent(new Event("input",{bubbles:true}));e.dispatchEvent(new Event("change",{bubbles:true}));
  return true;
 }
 if(text(e).toLowerCase()===want.toLowerCase())return true;
 e.click();
 const opt=elements('[role=option],option').find(o=>vis(o)&&text(o).toLowerCase()===want.toLowerCase());
 if(!opt)return false;opt.click();return true;
}
function readChoice(label){
 const e=field(label);
 if(!e)return null;
 if(e.tagName==="SELECT")return text(e.selectedOptions[0]);
 return text(e);
}
function tab(label){
 const b=oneButton(label);
 if(!b)return false;
 if(b.getAttribute("aria-selected")==="true"||b.getAttribute("data-state")==="active")return true;
 b.click();return true;
}
function currentMode(){
 if(field("Top ranking"))return "gl";
 if(elements("input").some(e=>e.value==="15")&&labels(document,/^Raw IV$/i).length)return "iv";
 return null;
}
function shinyControl(){
 const lab=elements("label,span,strong,div").filter(e=>vis(e)&&/^\s*(?:✨\s*)?Shiny only\s*$/i.test(text(e))).sort((a,b)=>a.querySelectorAll("*").length-b.querySelectorAll("*").length)[0];
 if(!lab)return null;
 for(let p=lab,i=0;p&&i<5;p=p.parentElement,i++){
  const controls=Array.from(p.querySelectorAll('input[type=checkbox],[role=switch],[role=checkbox],button[aria-checked]'));
  if(controls.length===1)return controls[0];
  const toggle=p.querySelector('button[data-state],button[aria-pressed]');
  if(toggle)return toggle;
 }
 return null;
}
function readShiny(){
 const sw=shinyControl();if(!sw)return null;
 if(sw.matches('input[type=checkbox]'))return sw.checked;
 if(sw.getAttribute("aria-checked")!==null)return sw.getAttribute("aria-checked")==="true";
 if(sw.getAttribute("aria-pressed")!==null)return sw.getAttribute("aria-pressed")==="true";
 if(sw.getAttribute("data-state")!==null)return sw.getAttribute("data-state")==="checked";
 return null;
}
function setShiny(want){
 const sw=shinyControl();if(!sw)return {ok:false,reason:"Shiny switch não encontrado"};
 const before=readShiny();
 if(before===want)return {ok:true,changed:false};
 if(before===null)return {ok:false,reason:"Estado do Shiny não identificável"};
 sw.click();return {ok:true,changed:true};
}
function rawIVInputs(){
 const list=labels(document,/^IV$/i);
 for(const el of list){
  for(let p=el.parentElement,i=0;p&&i<6;p=p.parentElement,i++){
   const inputs=Array.from(p.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio])')).filter(vis);
   if(inputs.length===3)return inputs;
  }
 }
 return [];
}
function applyIv(){
 tab("Stats");tab("Raw IV");
 const ins=rawIVInputs();
 if(ins.length!==3)return false;
 return ins.every(x=>valueInput(x,"15"));
}
function applyGl(){
 const rank=inputNear("Top ranking");
 return !!rank&&valueInput(rank,"1")&&choice("League","Great League")&&choice("Include previous evolutions","Yes");
}
function check(){
 const currentShiny=readShiny();
 if(currentShiny!==shinyOnly)return {ok:false,reason:"Shiny não confirmado",shiny:currentShiny};
 if(mode==="iv"){
  const ins=rawIVInputs();
  if(ins.length!==3||ins.some(x=>x.value!=="15"))return {ok:false,reason:"Raw IV 15/15/15 não confirmado"};
  return {ok:true};
 }
 const rank=inputNear("Top ranking");
 const league=readChoice("League"),prior=readChoice("Include previous evolutions");
 if(!rank||rank.value!=="1"||league!=="Great League"||prior!=="Yes")
  return {ok:false,reason:"Grande Liga R1 não confirmada",rank:rank?rank.value:null,league,prior};
 return {ok:true};
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
 if(!btn)return JSON.stringify({ok:false,status:"Botão Get coords não localizado"});
 btn.click();
 return JSON.stringify({ok:true,status:"Solicitando coordenadas no site"});
}
if(action.startsWith("readCoords:")){
 const query=JSON.parse(action.slice("readCoords:".length));
 const latlong=/(-?\d{1,2}(?:\.\d{1,12})?)\s*[,;]\s*(-?\d{1,3}(?:\.\d{1,12})?)/g;
 function coordinate(s){
  for(const m of String(s||"").matchAll(latlong)){
   const lat=Number(m[1]),lon=Number(m[2]);
   if(Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180)return m[1]+","+m[2];
  }
  return "";
 }
 // The website replaces Get coords with "Open in Maps" and puts coordinates beneath.
 const buttons=elements('button,a,[role=button]').filter(x=>vis(x)&&/Open in Maps|Get coords/i.test(text(x)));
 for(const b of buttons){
  if(!/Open in Maps/i.test(text(b)))continue;
  let card=b.parentElement;
  for(let i=0;i<9&&card;i++,card=card.parentElement){
   const t=text(card);
   if(!/\bLOCATION\b/i.test(t)||!/\bDESPAWN\b/i.test(t)||t.length>3500)continue;
   const name=String(query.name||"").toLowerCase();
   if(name&&!t.toLowerCase().includes(name))continue;
   const m=coordinate(t);
   if(m)return JSON.stringify({ok:true,coords:m,status:"Coordenadas obtidas"});
   // Matching card reached, avoid reading another Pokémon's coords.
   break;
  }
 }
 return JSON.stringify({ok:false,status:"O site ainda não exibiu coordenadas"});
}
if(action==="search"){
 const target=mode==="iv"?"Stats":"PvP";
 const ok=tab(target);
 setTimeout(()=>{
  if(mode==="iv"){
   tab("Raw IV");
   const fields=rawIVInputs();
   fields.slice(0,3).forEach(e=>valueInput(e,"15"));
  }else{
   const rank=inputNear("Top ranking");
   if(rank)valueInput(rank,"1");
   choice("League","Great League");
   choice("Include previous evolutions","Yes");
  }
  const s=shinyControl();
  if(s){
   const current=readShiny();
   if(current!==null&&current!==shinyOnly)s.click();
  }
  setTimeout(()=>click("Search"),750);
 },600);
 return JSON.stringify({ok,mode,attempted:true});
}
return JSON.stringify({ok:false});
})()