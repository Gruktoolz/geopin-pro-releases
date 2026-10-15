/* Generic Leaflet adapter: navigation only; no coverage claims. */
(function(){
 'use strict';
 if(!window.__mapProvider || location.origin!==window.__mapProvider.origin || window.__coverageAdapter)return;
 let bound=null;
 const notify=data=>{if(window.PointChannel)PointChannel.postMessage(JSON.stringify(data));};
 function attach(){
  const m=window.map || (typeof map!=='undefined'?map:null);if(!m || typeof m.setView!=='function' || typeof m.on!=='function')return false;
  if(bound!==m){bound=m;window.__mapPerformance.attach(m);
   m.on('dragstart',()=>notify({type:'map-moved'}));
   m.on('click',e=>notify({type:'point',lat:e.latlng.lat,lon:e.latlng.lng}));}
  return true;
 }
 window.__coverageAdapter=Object.freeze({ready:attach,
  center(){if(!attach())return false;const p=bound.containerPointToLatLng(bound.getSize().divideBy(2));notify({type:'point',source:'center',lat:p.lat,lon:p.lng});return true;},
  query(lat,lon,id=0){if(!attach())return 'not-ready';bound.setView([lat,lon],18,{animate:false});
   notify({type:'location-result',id,ok:true,map_state:'ready',total_ms:0,colony_ms:0,tiles_ms:0});return 'requested';}
 });attach();
})();
