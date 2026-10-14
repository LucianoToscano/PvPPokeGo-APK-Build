(function(){
'use strict';
const action=__ACTION__,mode=__MODE__,shiny=__SHINY__;
const clean=s=>String(s||'').replace(/\u00a0/g,' ').trim();
const all=q=>Array.from(document.querySelectorAll(q));
const txt=e=>clean(e&&(e.innerText||e.textContent));
const visible=e=>!!(e&&e.getClientRects().length);
const buttons=label=>all('button,[role=button]').filter(b=>visible(b)&&new RegExp('^'+label+'$','i').test(txt(b)));
function press(label){const b=buttons(label);if(b.length){b[b.length-1].click();return true;}return false;}
function setValue(el,value){
 if(!el)return false;
 try{
   const proto=el.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
   const setter=Object.getOwnPropertyDescriptor(proto,'value').set;
   setter.call(el,String(value));
   el.dispatchEvent(new Event('input',{bubbles:true}));
   el.dispatchEvent(new Event('change',{bubbles:true}));
   return true;
 }catch(e){return false;}
}
function candidates(pattern){
 return all('label,span,strong,div').filter(e=>visible(e)&&pattern.test(txt(e))&&txt(e).length<65)
   .sort((a,b)=>txt(a).length-txt(b).length);
}
function inputs(parent){return Array.from(parent.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio])')).filter(visible);}
function nearInput(pattern){
 for(const c of candidates(pattern)){let p=c;for(let i=0;i<4&&p;i++,p=p.parentElement){
   const v=inputs(p);if(v.length===1)return v[0];
 }}return null;
}
function setShiny(want){
 for(const lab of candidates(/Shiny only/i)){
   let node=lab;
   for(let i=0;i<5&&node;i++,node=node.parentElement){
     const sw=node.querySelector('input[type=checkbox],[role=switch]');
     if(sw){
       const checked=sw.matches('input')?sw.checked:sw.getAttribute('aria-checked')==='true';
       if(checked!==want)sw.click();
       return true;
     }
   }
 }return false;
}
function applyIv(){
 press('Stats');press('Raw IV');
 for(const lab of candidates(/^IV$/i)){
   let node=lab;for(let depth=0;depth<6&&node;depth++,node=node.parentElement){
     const v=inputs(node);
     if(v.length>=3&&v.length<=5){v.slice(0,3).forEach(el=>setValue(el,'15'));return true;}
   }
 }return false;
}
function applyGl(){
 press('PvP');
 const top=nearInput(/^Top ranking$/i);
 if(top)setValue(top,'1');
 // Great League + previous evolutions Yes should be selected in site.
 // Complex dropdowns must be verified in a logged-in test.
 return !!top;
}
function getCards(){
 const btns=buttons('Get coords');
 return btns.slice(0,10).map((b,index)=>{
   let box=b.parentElement;
   for(let i=0;i<8&&box;i++,box=box.parentElement){
     const t=txt(box);
     if(t.length<2400&&/DESPAWN/i.test(t)&&/LOCATION/i.test(t)&&/(?:CP|LVL)/i.test(t))break;
   }
   if(!box)box=b.parentElement;
   const body=txt(box);
   const lines=body.split(/\n/).map(clean).filter(Boolean);
   const take=re=>{const m=body.match(re);return m?clean(m[1]):''};
   const images=Array.from(box.querySelectorAll('img[src]'));
   const image=images.length?images[0].src:'';
   const heading=box.querySelector('h2,h3,h4,h5');
   let name=heading?txt(heading):'';
   if(!name)name=lines.find(v=>/^[\p{L}\p{N}\- .♀♂]+\s*(?:✨)?$/u.test(v)&&!/^LOCATION|DESPAWN|Get coords|CP|LVL|IV$/i.test(v))||'Pokémon';
   const number=take(/#(\d{1,4})/);
   const detail=lines.find(l=>l.includes('#')&&/Male|Female|Genderless|♀|♂/i.test(l))||'';
   const size=/(?:^|[ ·])((?:XXS|XS|XL|XXL|M|S|L))(?:$|[ ·])/i.exec(detail);
   const moves=lines.find(v=>v.includes(' / ')&&!/LOCATION|DESPAWN/i.test(v))||'';
   return {
     name:name.replace(/✨/g,'').trim(),image,
     cp:take(/(?:^|\n)\s*CP\s*\n\s*(\d+)/i),
     level:take(/(?:^|\n)\s*(?:LVL|LEVEL)\s*\n\s*([\d.]+)/i),
     iv:take(/(?:^|\n)\s*IV\s*\n\s*([^\n]+)/i),
     rank:take(/Rank\s*(\d+)/i),
     shiny:name.includes('✨'),
     number:number?'#'+number:'',gender:/Female|♀/i.test(detail)?'♀':/Male|♂/i.test(detail)?'♂':'',
     size:size?size[1]:'',moves,
     location:take(/(?:^|\n)\s*LOCATION\s*\n\s*([^\n]+)/i),
     despawn:take(/(?:^|\n)\s*DESPAWN\s*\n\s*([\d:]+)/i),
     coords:''
   };
 });
}
if(action==='collect')return JSON.stringify({ok:true,cards:getCards()});
if(action.startsWith('coords:')){
 const index=parseInt(action.slice(7),10);
 const btn=buttons('Get coords')[index];
 if(!btn)return JSON.stringify({ok:false,status:'Botão Get coords não localizado'});
 btn.click();
 return JSON.stringify({ok:true,status:'Get coords acionado no site'});
}
if(action==='search'){
 const sh=setShiny(shiny),modeFound=press(mode==='iv'?'Stats':'PvP');
 setTimeout(()=>{
   const filters=mode==='iv'?applyIv():applyGl();
   setTimeout(()=>press('Search'),500);
 },450);
 return JSON.stringify({ok:sh&&modeFound,shiny,mode});
}
return JSON.stringify({ok:false});
})()
