/* Generic WebView-scoped provider. No device location calls or website internals. */
(function () {
  'use strict';
  if ((!window.__mapProvider || location.origin !== window.__mapProvider.origin) || window.__virtualLocation) return;
  let selected = null;
  let nextId = 1;
  const watchers = new Map();
  function position(point) {
    return { coords: { latitude: point.lat, longitude: point.lon, accuracy: 1,
      altitude: null, altitudeAccuracy: null, heading: null, speed: null }, timestamp: Date.now() };
  }
  function deliver(success, error, point, valid) {
    setTimeout(function () {
      if (valid && !valid()) return;
      if (point) success(position(point));
      else if (typeof error === 'function') error({ code: 2,
        message: 'Selecciona un punto en la app.', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
    }, 0);
  }
  const provider = {
    getCurrentPosition(success, error) {
      if (typeof success !== 'function') throw new TypeError('Falta callback');
      deliver(success, error, selected);
    },
    watchPosition(success, error) {
      if (typeof success !== 'function') throw new TypeError('Falta callback');
      const id = nextId++;
      watchers.set(id, {success, error});
      deliver(success, error, selected, () => watchers.has(id));
      return id;
    },
    clearWatch(id) { watchers.delete(id); }
  };
  // Replacing just this JS object affects this document, never Android's GPS.
  Object.defineProperty(navigator, 'geolocation', { value: provider, configurable: false });
  Object.defineProperty(window, '__virtualLocation', { value: Object.freeze({
    set(lat, lon) {
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
        throw new RangeError('Coordenadas inválidas');
      selected = Object.freeze({lat, lon});
      watchers.forEach((w, id) => deliver(w.success, w.error, selected, () => watchers.has(id)));
    },
    watchCount() { return watchers.size; },
    point() { return selected; }
  }), configurable: false });

})();
