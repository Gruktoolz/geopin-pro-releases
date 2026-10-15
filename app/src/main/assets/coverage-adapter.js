/* Small replaceable adapter: point selection and activating the website's own button.
   It does not replace the website, call private endpoints, or copy coverage data. */
(function () {
  'use strict';
  if ((!window.__mapProvider || location.origin !== window.__mapProvider.origin) || window.__coverageAdapter) return;
  const notify = (data) => { if (window.PointChannel) PointChannel.postMessage(JSON.stringify(data)); };
  let boundMap = null;
  let viewport = null;
  const feedback=window.__createCoverageFeedback(notify);
  function attach() {
    const m = window.map;
    if (!m || typeof m.on !== 'function') return false;
    if (boundMap !== m) {
      boundMap = m;
      viewport=window.__createCoverageViewportGuard(m);
      // Read-only observation of the site's location lookups. This includes late
      // responses from an earlier selection, so they cannot override the latest view.
      function isLocationLookup(settings){
        try {
          const url=new URL(settings.url,location.href);
          const request=JSON.parse(settings.data);
          return url.origin===location.origin && typeof request.params?.locacion==='string';
        } catch(ignored){return false;}
      }
      if(window.jQuery){
        jQuery(document).on('ajaxSend.coverageViewport',(event,xhr,settings)=>{
          if(isLocationLookup(settings)){viewport.requestStarted(xhr);feedback.sent(xhr,settings);}
        });
        jQuery(document).on('ajaxComplete.coverageViewport',(event,xhr)=>{viewport.requestFinished(xhr);feedback.complete(xhr);});
      }
      // Colony polygons can consume Leaflet click events to open their popup.
      // Capture actual taps on the map surface before that, without selecting after a drag.
      const container=m.getContainer();
      // The aiming point is the rendered map's exact pixel center.
      // It belongs to the map container, so resizing or opening the keyboard keeps it aligned.
      const aim=document.createElement('div');
      aim.id='coverage-center-aim';
      aim.setAttribute('aria-hidden','true');
      aim.style.cssText='position:absolute;left:50%;top:50%;width:10px;height:10px;box-sizing:border-box;border:2px solid white;border-radius:50%;background:#073b59;transform:translate(-50%,-50%);z-index:650;pointer-events:none;';
      container.appendChild(aim);
      let dragged=false;
      container.addEventListener('pointerdown',()=>{dragged=false;viewport.cancel();},true);
      container.addEventListener('wheel',()=>viewport.cancel(),{capture:true,passive:true});
      container.addEventListener('keydown',()=>viewport.cancel(),true);
      m.on('dragstart',()=>{dragged=true;viewport.cancel();notify({type:'map-moved'});});
      container.addEventListener('click',e=>{
        if(dragged || !(e.target instanceof Element) || e.target.closest('.leaflet-control, .leaflet-popup'))return;
        const point=m.mouseEventToLatLng(e);
        notify({type:'point',lat:point.lat,lon:point.lng});
      },true);
      window.__mapPerformance.attach(m,()=>boundMap);
    }
    return true;
  }
  window.__coverageAdapter = Object.freeze({
    ready(){return attach();},
    center() {
      if (!attach()) return false;
      const p = boundMap.containerPointToLatLng(boundMap.getSize().divideBy(2));
      notify({type:'point',source:'center',lat:p.lat,lon:p.lng});
      return true;
    },
    query(lat, lon, id=0) {
      if (!window.__virtualLocation || !attach()) return 'not-ready';
      const button = document.querySelector(window.__mapProvider.locateControl);
      if (!button) return 'missing-control';
      // Keep the selected view through the site's later fit-to-colony response.
      feedback.start(id,lat,lon);
      viewport.start(lat,lon,18);
      window.__virtualLocation.set(lat,lon);
      if (window.__virtualLocation.watchCount() === 0) button.click();
      return 'requested';
    }
  });
  attach();
})();
