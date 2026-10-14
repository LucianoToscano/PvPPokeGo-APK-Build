(function(){
"use strict";
// Explicit Shiny-only opt-in. Execute only after tapping ✨ in the overlay.
if(window.__pokesearchShinyRequestStarted)return;
window.__pokesearchShinyRequestStarted=true;
const started=Date.now();
function visible(e){return !!(e&&e.getClientRects().length);}
function text(e){return String(e&&e.textContent||"").replace(/\s+/g," ").trim();}
function enabled(e){
 if(e.matches('input[type="checkbox"]'))return e.checked;
 for(const attr of ["aria-checked","aria-pressed"]){
  if(e.hasAttribute(attr))return e.getAttribute(attr)==="true";
 }
 if(e.hasAttribute("data-state"))return e.getAttribute("data-state")==="checked";
 return null;
}
function findSwitch(){
 const labels=Array.from(document.querySelectorAll("label,span,strong,div"))
  .filter(e=>visible(e)&&/^(?:✨\s*)?Shiny only$/i.test(text(e))&&e.children.length<=2)
  .sort((a,b)=>a.querySelectorAll("*").length-b.querySelectorAll("*").length);
 for(const label of labels){
  for(let el=label,depth=0;el&&depth<6;el=el.parentElement,depth++){
   const controls=Array.from(el.querySelectorAll('input[type="checkbox"],[role="switch"],[role="checkbox"],button[aria-checked]'));
   if(controls.length===1)return controls[0];
   const button=el.querySelector("button[data-state],button[aria-pressed]");
   if(button)return button;
  }
  if(label.tagName==="LABEL")return label;
 }
 return null;
}
let timer=null;
function finish(){if(timer)clearInterval(timer);timer=null;}
function run(){
 if(Date.now()-started>12000){finish();return;}
 if(!location.pathname.startsWith("/search"))return;
 // A site's login dialog might already be open; never click through it.
 if(Array.from(document.querySelectorAll('[role="dialog"],[aria-modal="true"]'))
  .some(e=>visible(e)&&/shiny only|discord|google|log in|sign in/i.test(text(e)))){
  finish();return;
 }
 const switchElement=findSwitch();
 if(!switchElement)return;
 if(enabled(switchElement)!==true&&!switchElement.disabled)switchElement.click();
 finish();
}
timer=setInterval(run,350);
run();
})();