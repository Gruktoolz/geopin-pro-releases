/* Generic adapters for exposed Leaflet, MapLibre/Mapbox and OpenLayers maps. No coverage inference. */
(function(){
 'use strict';
 if(!window.__mapProvider || location.origin!==window.__mapProvider.origin || window.__coverageAdapter)return;
 let bound=null,engine='';
 const notify=data=>{if(window.PointChannel)PointChannel.postMessage(JSON.stringify(data));};
 const valid=(lat,lon)=>Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180;
 function projection(m){const p=m.getView().getProjection();return typeof p==='string'?p:p.getCode();}
 function project(lon,lat,code){
  if(code==='EPSG:4326')return [lon,lat];
  if(code!=='EPSG:3857' && code!=='EPSG:900913')throw new Error('unsupported-projection');
  const limited=Math.max(-85.05112878,Math.min(85.05112878,lat));
  return [lon*20037508.342789244/180,Math.log(Math.tan((90+limited)*Math.PI/360))*6378137];
 }
 function unproject(point,code){
  if(code==='EPSG:4326')return {lon:point[0],lat:point[1]};
  if(code!=='EPSG:3857' && code!=='EPSG:900913')throw new Error('unsupported-projection');
  return {lon:point[0]*180/20037508.342789244,lat:(2*Math.atan(Math.exp(point[1]/6378137))-Math.PI/2)*180/Math.PI};
 }
 function attach(){
  const m=window.__mapDiscovery && window.__mapDiscovery.find();if(!m)return false;
  if(bound!==m){bound=m;engine=window.__mapDiscovery.kind(m);
   if(engine==='leaflet')window.__mapPerformance.attach(m);
   m.on(engine==='ol'?'movestart':'dragstart',()=>notify({type:'map-moved'}));
   m.on('click',e=>{try{const p=engine==='ol'?unproject(e.coordinate,projection(m)):{lat:e.latlng?e.latlng.lat:e.lngLat.lat,lon:e.latlng?e.latlng.lng:e.lngLat.lng};
    if(valid(p.lat,p.lon))notify({type:'point',lat:p.lat,lon:p.lon});}catch(ignored){}});
  }
  return true;
 }
 window.__coverageAdapter=Object.freeze({ready:attach,
  snapshot(){try{if(!attach())return null;let p,z;
   if(engine==='leaflet'){const v=bound.containerPointToLatLng(bound.getSize().divideBy(2));p={lat:v.lat,lon:v.lng};z=bound.getZoom();}
   else if(engine==='gl'){const v=bound.getCenter();p={lat:v.lat,lon:v.lng};z=bound.getZoom();}
   else{p=unproject(bound.getView().getCenter(),projection(bound));z=bound.getView().getZoom();}
   return valid(p.lat,p.lon)?{lat:p.lat,lon:p.lon,zoom:z}:null;
  }catch(ignored){return null;}},
  center(){try{if(!attach())return false;let p;
   if(engine==='leaflet'){const v=bound.containerPointToLatLng(bound.getSize().divideBy(2));p={lat:v.lat,lon:v.lng};}
   else if(engine==='gl'){const v=bound.getCenter();p={lat:v.lat,lon:v.lng};}
   else p=unproject(bound.getView().getCenter(),projection(bound));
   if(!valid(p.lat,p.lon))return false;notify({type:'point',source:'center',lat:p.lat,lon:p.lon});return true;
  }catch(ignored){return false;}},
  query(lat,lon,id=0,zoom=18){try{
   if(!valid(lat,lon))return 'invalid-point';if(!attach())return 'not-ready';
   zoom=Math.max(5,Math.min(20,zoom));
   if(engine==='leaflet')bound.setView([lat,lon],zoom,{animate:false});
   else if(engine==='gl')bound.jumpTo({center:[lon,lat],zoom});
   else{const view=bound.getView();view.setCenter(project(lon,lat,projection(bound)));view.setZoom(zoom);}
   if(window.__virtualLocation)window.__virtualLocation.set(lat,lon);
   notify({type:'location-result',id,ok:true,map_state:'ready',total_ms:0,colony_ms:0,tiles_ms:0});return 'requested';
  }catch(ignored){return 'not-ready';}}
 });attach();
})();
