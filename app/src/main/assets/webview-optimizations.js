(function(){
'use strict';
if(!window.__mapProvider || location.origin!==window.__mapProvider.origin || window.__mapPerformance)return;
  // Hook Leaflet from the very instant it is defined to disable updateWhenIdle and expand keepBuffer
  let _leaflet = undefined;
  function patchLeaflet(l) {
    if (!l || !l.GridLayer || !l.GridLayer.prototype) return;
    try {
      l.GridLayer.prototype.options.updateWhenIdle = false;
      l.GridLayer.prototype.options.updateInterval = 16;
      l.GridLayer.prototype.options.keepBuffer = 24;
    } catch (ignored) {}
  }
  Object.defineProperty(window, 'L', {
    configurable: true,
    enumerable: true,
    get() { return _leaflet; },
    set(val) {
      _leaflet = val;
      patchLeaflet(val);
    }
  });

  try {
    const style = document.createElement('style');
    style.textContent = '.leaflet-tile { transition: none !important; } .leaflet-container { background: #f2ede4 !important; }';
    (document.head || document.documentElement).appendChild(style);
  } catch(ignored) {}

window.__mapPerformance=Object.freeze({attach(m, getMap=()=>m){
      function optimizeAllLayers(){
        const boundMap=getMap();
        const minZ = 5;
        if (!boundMap) return;
        boundMap.options.minZoom = minZ;
        if (typeof boundMap.getMinZoom === 'function') {
          boundMap.getMinZoom = function () { return minZ; };
        }
        if (boundMap._layers) {
          for (const id in boundMap._layers) {
            const l = boundMap._layers[id];
            if (!l || !l.options) continue;
            if (l.options.minZoom > minZ) {
              l.options.minZoom = minZ;
              if (l.options.minNativeZoom !== undefined) {
                l.options.minNativeZoom = minZ;
              }
              if (l._map && typeof l.redraw === 'function') {
                try { l.redraw(); } catch(e) {}
              }
            }
            l.options.updateWhenIdle = false;
            l.options.updateInterval = 16;
            l.options.keepBuffer = 24;
            if (l._map) {
              if (!l._onMove && typeof l._onMoveEnd === 'function') {
                l._onMove = (window.L && window.L.Util && window.L.Util.throttle)
                  ? window.L.Util.throttle(l._onMoveEnd, 16, l)
                  : l._onMoveEnd;
              }
              if (l._onMove) {
                boundMap.off('move', l._onMove, l);
                boundMap.on('move', l._onMove, l);
              }
            }
          }
        }
        if (boundMap._zoomBoundLayers) {
          for (const id in boundMap._zoomBoundLayers) {
            const l = boundMap._zoomBoundLayers[id];
            if (l && l.options && l.options.minZoom > minZ) {
              l.options.minZoom = minZ;
            }
          }
        }
        if (typeof boundMap._updateZoomLevels === 'function') {
          try { boundMap._updateZoomLevels(); } catch(e) {}
        }
        if (boundMap.zoomControl && typeof boundMap.zoomControl._updateDisabled === 'function') {
          try { boundMap.zoomControl._updateDisabled(); } catch(e) {}
        }
      }
      optimizeAllLayers();
      m.on('layeradd dragstart movestart', optimizeAllLayers);
      for (const d of [150, 400, 800, 1500, 3000]) {
        setTimeout(optimizeAllLayers, d);
      }
      const domGeo = document.getElementById(window.__mapProvider.blurElement);
      if (domGeo) {
        domGeo.addEventListener('blur', () => {
          setTimeout(() => { if (m.invalidateSize) m.invalidateSize({ animate: false }); }, 250);
        });
      }
      window.addEventListener('resize', () => {
        setTimeout(() => { if (m.invalidateSize) m.invalidateSize({ animate: false }); }, 250);
      });
}});
})();
