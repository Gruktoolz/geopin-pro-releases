/* Shared search transport for the explicitly configured site field. No endpoint guessing. */
(function(){
 'use strict';
 if(!window.__mapProvider || location.origin!==window.__mapProvider.origin || window.__searchMirror)return;
 const fieldId=window.__mapProvider.blurElement;
 if(!fieldId)return;
 const notify=data=>{if(window.PointChannel)window.PointChannel.postMessage(JSON.stringify(data));};
 let field=null,widget=null,originalSource=null,respond=null,pendingText='',revision=-1,muted=false;
 let requestSerial=0,siteTimer=null,lastResults=[];
 function attach(){
  const el=document.getElementById(fieldId),$=window.jQuery;
  if(!el||!$||!$.fn.autocomplete)return false;
  const instance=$(el).autocomplete('instance');if(!instance)return false;
  if(field===el&&widget===instance)return true;
  field=el;widget=instance;originalSource=instance.options.source;
  if(typeof originalSource!=='function'){field=null;widget=null;return false;}
  $(el).autocomplete('option',{
   minLength:1,delay:0,
   source(request,response){
    if(muted){response([]);return;}
    if(respond){respond([]);respond=null;}
    respond=response;pendingText=request.term;lastResults=[];
    notify({type:'search-edit',text:request.term});
   },
   select(event,ui){
    if(!ui.item || !Number.isInteger(ui.item.id))return false;
    el.value=ui.item.label;
    notify({type:'search-select',generation:revision,row:ui.item.id});
    return false;
   },
   focus(){return false;}
  });
  instance._renderItem=function(ul,item){
   const line=$('<div>').text(item.label);
   $('<small>').text(' · '+(item.source||'')).appendTo(line);
   return $('<li>').append(line).appendTo(ul);
  };
  el.addEventListener('input',()=>{if(!muted && !el.value){notify({type:'search-edit',text:''});}});
  el.addEventListener('keydown',event=>{
   if(event.key==='Enter' && !window.jQuery(el).autocomplete('widget').is(':visible')){
    event.preventDefault();event.stopImmediatePropagation();notify({type:'search-submit',text:el.value});
   }
  },true);
  el.addEventListener('focus',()=>{if(el.value)window.jQuery(el).autocomplete('search',el.value);});
  el.addEventListener('blur',()=>setTimeout(()=>{if(document.activeElement!==el)notify({type:'search-blur'});},220));
  return true;
 }
 window.__searchMirror=Object.freeze({
  ready:attach,
  request(text,id,fromWeb){
   if(!attach())return false;
   revision=id;requestSerial++;const serial=requestSerial;
   if(siteTimer)clearTimeout(siteTimer);
   if(!fromWeb || !text.trim()){if(respond){respond([]);respond=null;}muted=true;field.value=text;window.jQuery(field).autocomplete('close');muted=false;}
   // Original site source is enriched only after its original minimum, reducing traffic.
   if(text.trim().length<3 || text.length>180 || /[:/\n]/.test(text))return true;
   siteTimer=setTimeout(()=>{
    try{originalSource.call(widget,{term:text},rows=>{
     if(serial!==requestSerial || revision!==id)return;
     const found=[];
     for(const item of (Array.isArray(rows)?rows:[]).slice(0,24)){
      const point=String(item.locacion||'').split(',');const lat=Number(point[0]),lon=Number(point[1]);
      if(point.length!==2||!Number.isFinite(lat)||!Number.isFinite(lon))continue;
      found.push({label:String(item.label||'').slice(0,300),lat,lon});
     }
     notify({type:'search-results',generation:id,rows:found});
    });}catch(ignored){}
   },350);
   return true;
  },
  results(text,id,rows){
   if(!attach() || id!==revision || text.trim()!==pendingText.trim())return false;
   lastResults=Array.isArray(rows)?rows:[];
   if(document.activeElement===field){
    if(respond){const complete=respond;respond=null;complete(lastResults);}
    else if(typeof widget.__response==='function')widget.__response(lastResults);
   }
   return true;
  },
  selected(label){
   requestSerial++;if(siteTimer)clearTimeout(siteTimer);if(respond){respond([]);respond=null;}
   if(!attach())return false;
   muted=true;field.value=label;window.jQuery(field).autocomplete('close');muted=false;field.blur();return true;
  },
  dismiss(){requestSerial++;if(respond){respond([]);respond=null;}if(siteTimer)clearTimeout(siteTimer);if(attach())window.jQuery(field).autocomplete('close');}
 });
 attach();
})();
