/* Observes the website's own responses and Leaflet tile layer loading state.
   Never sends requests, mutates responses, or uses artificial fixed delays. */
(function(){
  'use strict';
  if(window.__createCoverageFeedback)return;
  window.__createCoverageFeedback=function(notify){
    let current=null,cleanupTiles=null;
    const tracked=new WeakMap();
    function start(id,lat,lon){
      if(cleanupTiles){cleanupTiles();cleanupTiles=null;}
      current={id,lat,lon,startTime:Date.now(),done:false};
    }
    function sent(xhr,settings){
      if(!current || current.done)return;
      try{
        const data=JSON.parse(settings.data),p=data.params.locacion.split(',').map(Number);
        if(Math.abs(p[0]-current.lat)>1e-6 || Math.abs(p[1]-current.lon)>1e-6)return;
        if(data.endpoint==='geoinvmex' || data.endpoint==='geoinvcol')tracked.set(xhr,{target:current,endpoint:data.endpoint});
      }catch(ignored){}
    }
    function complete(xhr){
      const request=tracked.get(xhr);tracked.delete(xhr);
      if(!request || request.target!==current || current.done)return;
      let status='';try{status=String((xhr.responseJSON || JSON.parse(xhr.responseText)).STATUS);}catch(ignored){}
      const httpOk=xhr.status>=200 && xhr.status<300;
      if(request.endpoint==='geoinvmex'){
        if(httpOk && status==='200')return;
        // Outside Mexico or error
        const target=current;target.done=true;
        const totalMs=Date.now()-target.startTime;
        notify({type:'location-result',id:target.id,ok:false,stage:status==='204'?'mexico_validation':'geoinvmex',map_state:'error',total_ms:totalMs});
        return;
      }
      if(request.endpoint!=='geoinvcol')return;
      const ok=httpOk && (status==='200' || status==='204');
      const target=current;
      const colonyMs=Date.now()-target.startTime;
      if(!ok){
        target.done=true;
        const totalMs=Date.now()-target.startTime;
        notify({type:'location-result',id:target.id,ok:false,stage:'geoinvcol',map_state:'error',colony_ms:colonyMs,total_ms:totalMs});
        return;
      }
      // Colony resolved. Now observe tile layer completion rather than guessing with a fixed delay.
      observeTiles(target,colonyMs);
    }
    function observeTiles(target,colonyMs){
      const layer=window.cobertura_fibra || (window.map && window.map._layers ? findTileLayer(window.map) : null);
      const tilesStart=Date.now();
      // If tiles are already cached or not actively loading, finish immediately.
      if(!layer || typeof layer.isLoading!=='function' || !layer.isLoading()){
        if(target!==current || target.done)return;
        target.done=true;
        const totalMs=Date.now()-target.startTime;
        notify({type:'location-result',id:target.id,ok:true,map_state:'ready',colony_ms:colonyMs,tiles_ms:0,total_ms:totalMs});
        return;
      }
      let timer=null;
      function cleanup(){
        if(timer){clearTimeout(timer);timer=null;}
        if(layer){
          layer.off('load',onLoad);
          layer.off('tileerror',onError);
        }
        cleanupTiles=null;
      }
      cleanupTiles=cleanup;
      function finish(state){
        cleanup();
        if(target!==current || target.done)return;
        target.done=true;
        const tilesMs=Date.now()-tilesStart;
        const totalMs=Date.now()-target.startTime;
        notify({type:'location-result',id:target.id,ok:true,map_state:state,colony_ms:colonyMs,tiles_ms:tilesMs,total_ms:totalMs});
      }
      function onLoad(){finish('ready');}
      function onError(){finish('unknown');} // tileerror -> unknown, never absent
      layer.once('load',onLoad);
      layer.once('tileerror',onError);
      timer=setTimeout(()=>{finish('unknown');},4000); // 4s guard timeout -> unknown
    }
    function findTileLayer(map){
      for(const k in map._layers){
        if(map._layers[k] && typeof map._layers[k].isLoading==='function')return map._layers[k];
      }
      return null;
    }
    return Object.freeze({start,sent,complete});
  };
})();
