/* Discover compatible map objects without depending on a provider hostname. */
(function(){
 'use strict';
 if(!window.__mapProvider || location.origin!==window.__mapProvider.origin || window.__mapDiscovery)return;
 let current=null;const hooked=new WeakSet(),observed=new WeakSet();
 function kind(m){
  if(!m || typeof m.on!=='function')return '';
  if(typeof m.setView==='function' && typeof m.containerPointToLatLng==='function')return 'leaflet';
  if(typeof m.jumpTo==='function' && typeof m.getCenter==='function')return 'gl';
  if(typeof m.getView==='function' && typeof m.getViewport==='function')return 'ol';
  return '';
 }
 function capture(m){if(kind(m))current=m;}
 function leaflet(l){
  if(!l || (typeof l!=='object' && typeof l!=='function'))return;
  // UMD libraries publish their namespace before assigning Map. Observe that assignment once.
  if(!l.Map && !observed.has(l)){
   const descriptor=Object.getOwnPropertyDescriptor(l,'Map');
   if(!descriptor || descriptor.configurable){
    observed.add(l);let value;
    try{Object.defineProperty(l,'Map',{configurable:true,enumerable:true,get(){return value;},set(v){value=v;leaflet(l);}});}catch(ignored){}
   }
  }
  if(!l.Map || typeof l.Map.addInitHook!=='function' || hooked.has(l.Map))return;
  hooked.add(l.Map);l.Map.addInitHook(function(){capture(this);});
 }
 function find(){
  leaflet(window.L);
  if(current)return current;
  for(const key of ['map','mapa','mymap','myMap','mapInstance','olMap','mapboxMap']){
   try{capture(window[key]);if(current)return current;}catch(ignored){}
  }
  try{if(typeof map!=='undefined')capture(map);}catch(ignored){}
  return current;
 }
 Object.defineProperty(window,'__mapDiscovery',{value:Object.freeze({find,kind,leaflet}),configurable:false});
 leaflet(window.L);
})();
