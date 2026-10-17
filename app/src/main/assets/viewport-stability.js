/* Local presentation only. Observes request completion without changing requests,
   responses, or provider's map methods. Never issues a second location query. */
(function () {
  'use strict';
  if (window.__createCoverageViewportGuard) return;
  window.__createCoverageViewportGuard = function (map, options) {
    const quietMs=options?.quietMs ?? 400;
    const maxMs=options?.maxMs ?? 30000;
    const requests=new Set();
    let target=null, observed=false, correcting=false;
    let quietTimer=null, deadlineTimer=null, frame=null;
    function clearQuiet(){clearTimeout(quietTimer);quietTimer=null;}
    function cancel(){
      target=null;clearQuiet();clearTimeout(deadlineTimer);deadlineTimer=null;
      if(frame!==null)cancelAnimationFrame(frame);
      frame=null;
    }
    function settle(){
      clearQuiet();
      // Wait for the real asynchronous response, not an arbitrary fixed delay.
      if(target && observed && requests.size===0)quietTimer=setTimeout(cancel,quietMs);
    }
    function restore(){
      if(!target)return;
      const desired=target;
      const offset=map.latLngToContainerPoint([desired.lat,desired.lon]).distanceTo(map.getSize().divideBy(2));
      if(map.getZoom()!==desired.zoom || offset>1){
        correcting=true;
        try {map.stop();map.setView([desired.lat,desired.lon],desired.zoom,{animate:false});}
        finally {correcting=false;}
      }
      settle();
    }
    function changed(){
      if(!target || correcting)return;
      clearQuiet();
      if(frame===null)frame=requestAnimationFrame(()=>{frame=null;restore();});
    }
    map.on('moveend zoomend',changed);
    return Object.freeze({
      start(lat,lon,zoom){
        cancel();target={lat,lon,zoom};observed=requests.size>0;
        deadlineTimer=setTimeout(cancel,maxMs);
        restore();
      },
      requestStarted(request){requests.add(request);if(target){observed=true;clearQuiet();}},
      requestFinished(request){if(!requests.delete(request))return;if(target){changed();settle();}},
      cancel,
      active(){return target!==null;}
    });
  };
})();
