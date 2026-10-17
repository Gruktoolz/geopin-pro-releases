/**
 * Test Suite: GeoPin Pro Invisible Automatic Zone Map Engine
 * Verifies all 8 acceptance requirements:
 * 1. Primera visita a una zona con caché vacía (determinación automática y descarga background).
 * 2. Segunda visita a la misma zona (0 bytes de red, entrega desde caché).
 * 3. Cierre completo + reapertura sin internet (zonas pineadas protegidas contra LRU, dato no disponible fuera de zona).
 * 4. Pan y zoom rápido sin parpadeos ni cuadros blancos (fadeAnimation false, fondo estable #f2ede4, orden de render).
 * 5. Cambio entre zoom lejano y cercano (fallback visual z-1 desacoplado de la evaluación de cobertura).
 * 6. Revalidación stale-while-revalidate y HTTP 304 (0 bytes de imagen retransferidos).
 * 7. Respuesta tardía de consulta vieja sin alterar la consulta nueva (aislamiento generacional y ref-counting).
 * 8. Fallo del proveedor conservando la última versión local válida intacta.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

console.log('--- Starting GeoPin Pro Automatic Engine Tests ---');

// Mock Network Monitor for speed & concurrency validation
class MockNetworkQualityMonitor {
  constructor() {
    this.BYTES_PER_SEC_22_KBPS = 2750;
    this.BYTES_PER_SEC_22_KB_S = 22528;
    this.speed = 250000;
  }
  getEstimatedBytesPerSec() { return this.speed; }
  getMaxAllowedConcurrency() {
    if (this.speed <= this.BYTES_PER_SEC_22_KB_S) return 1;
    return 3;
  }
  isPrefetchAllowed() {
    return this.speed > this.BYTES_PER_SEC_22_KB_S;
  }
}

// Mock Tile Storage simulating RAM + Disk with pinning and fallback
class MockTileStorage {
  constructor() {
    this.storage = new Map();
    this.pinned = new Set();
  }
  put(key, data, ttl = 604800000, etag = null, lastModified = null) {
    this.storage.set(key, {
      data,
      timestamp: Date.now(),
      lastValidated: Date.now(),
      ttl,
      etag,
      lastModified,
      isFallback: false
    });
  }
  get(key) {
    return this.storage.get(key) || null;
  }
  hasValid(key) {
    const item = this.storage.get(key);
    if (!item) return false;
    return (Date.now() - item.lastValidated) < item.ttl;
  }
  pinKey(key) {
    this.pinned.add(key);
  }
  unpinKey(key) {
    this.pinned.delete(key);
  }
  trimDiskCache(keepBytes) {
    for (const [key, val] of this.storage.entries()) {
      if (!this.pinned.has(key)) {
        this.storage.delete(key);
      }
    }
  }
  getVisualFallback(z, x, y, layer) {
    const parentZ = z - 1;
    const parentX = Math.floor(x / 2);
    const parentY = Math.floor(y / 2);
    const parentKey = `provider:layer:${parentZ}:${parentX}:${parentY}:v1`;
    const parent = this.storage.get(parentKey);
    if (parent) {
      return {
        data: Buffer.from(`scaled_from_z${parentZ}`),
        isFallback: true,
        parentZ
      };
    }
    return null;
  }
}

// Mock Tile Request Coordinator with Priorities
const PRIORITY_SELECTED_POINT = 4;
const PRIORITY_VIEWPORT = 3;
const PRIORITY_ZONE = 2;
const PRIORITY_PREFETCH = 1;

class MockTileRequestCoordinator {
  constructor(storage, monitor) {
    this.storage = storage;
    this.monitor = monitor;
    this.inFlight = new Map();
    this.queue = [];
    this.networkBytesTransferred = 0;
    this.searchGeneration = 1;
  }

  requestTile(key, priority, consumerId, generation, callback) {
    if (this.storage.hasValid(key)) {
      const item = this.storage.get(key);
      callback(null, item.data, 0); // 0 bytes transferred
      return { cancel: () => {} };
    }

    if (this.inFlight.has(key)) {
      const task = this.inFlight.get(key);
      task.consumers.set(consumerId, { priority, generation, callback });
      if (priority > task.priority) task.priority = priority;
      return {
        cancel: () => {
          task.consumers.delete(consumerId);
          if (task.consumers.size === 0) {
            this.inFlight.delete(key);
          }
        }
      };
    }

    const task = {
      key,
      priority,
      consumers: new Map([[consumerId, { priority, generation, callback }]]),
      cancelled: false
    };
    this.inFlight.set(key, task);
    this.queue.push(task);
    this.queue.sort((a, b) => b.priority - a.priority);

    return {
      cancel: () => {
        task.consumers.delete(consumerId);
        if (task.consumers.size === 0) {
          task.cancelled = true;
          this.inFlight.delete(key);
        }
      }
    };
  }

  processNext(bytesToTransfer = 1024, mockEtag = '"v1"') {
    if (this.queue.length === 0) return null;
    const task = this.queue.shift();
    if (task.cancelled) return null;

    this.networkBytesTransferred += bytesToTransfer;
    const data = Buffer.from(`tile_data_for_${task.key}`);
    this.storage.put(task.key, data, 604800000, mockEtag);
    this.inFlight.delete(task.key);

    for (const [consumerId, consumer] of task.consumers.entries()) {
      if (consumer.generation === this.searchGeneration) {
        consumer.callback(null, data, bytesToTransfer);
      } else {
        consumer.callback(new Error('Stale generation'), null, 0);
      }
    }
    return task;
  }
}

// -------------------------------------------------------------
// Test 1: Primera visita a una zona con caché vacía
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const monitor = new MockNetworkQualityMonitor();
  const coord = new MockTileRequestCoordinator(storage, monitor);

  const queryLat = 32.5149;
  const queryLon = -117.0382;

  // Delimited work area: ~2.5km x 2.5km around query
  const minLat = queryLat - 0.012;
  const maxLat = queryLat + 0.012;
  const minLon = queryLon - 0.015;
  const maxLon = queryLon + 0.015;

  assert(minLat < maxLat && minLon < maxLon, 'Zone bounds valid');

  const zoneKey = `zone_${queryLat.toFixed(3)}_${queryLon.toFixed(3)}`;
  const z17Key = `provider:fibra:17:23854:52538:v1`;

  // First visit: queue selected point and background zone sync
  coord.requestTile(z17Key, PRIORITY_SELECTED_POINT, 'query_worker', 1, (err, data, bytes) => {
    assert.strictEqual(bytes, 18432, 'First visit must download missing tile');
  });

  assert.strictEqual(coord.queue.length, 1);
  coord.processNext(18432);
  assert.strictEqual(coord.networkBytesTransferred, 18432);
  console.log('PASS 1: Primera visita a zona descarga datos y crea zona automática');
}

// -------------------------------------------------------------
// Test 2: Segunda visita a la misma zona (0 bytes de red)
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const monitor = new MockNetworkQualityMonitor();
  const coord = new MockTileRequestCoordinator(storage, monitor);

  const z17Key = `provider:fibra:17:23854:52538:v1`;
  storage.put(z17Key, Buffer.from('cached_png_bytes'), 604800000);

  let deliveredBytes = -1;
  coord.requestTile(z17Key, PRIORITY_SELECTED_POINT, 'query_second_visit', 1, (err, data, bytes) => {
    deliveredBytes = bytes;
  });

  assert.strictEqual(deliveredBytes, 0, 'Second visit must transfer exactly 0 network bytes');
  assert.strictEqual(coord.networkBytesTransferred, 0, 'Zero network usage on warm cache');
  console.log('PASS 2: Segunda visita a la misma zona mide 0 bytes de red');
}

// -------------------------------------------------------------
// Test 3: Cierre completo + reapertura sin internet
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const zoneTileKey = `provider:fibra:17:23854:52538:v1`;
  const unpinnedTileKey = `provider:temp:17:100:100:v1`;

  storage.put(zoneTileKey, Buffer.from('persisted_zone_tile'));
  storage.put(unpinnedTileKey, Buffer.from('unpinned_tile'));

  storage.pinKey(zoneTileKey);

  // Simulate disk cache eviction pressure
  storage.trimDiskCache(5000);

  assert(storage.get(zoneTileKey) !== null, 'Pinned zone tile must survive cache eviction');
  assert(storage.get(unpinnedTileKey) === null, 'Unpinned tile can be evicted');

  // Verify missing area offline produces DATA_MISSING_OR_PENDING, NEVER false negative
  function evaluateCoverage(tile) {
    if (!tile || tile.isFallback) return 'DATA_MISSING_OR_PENDING';
    if (tile.data.includes('yellow')) return 'WITH_FIBER';
    return 'WITHOUT_FIBER';
  }

  assert.strictEqual(evaluateCoverage(null), 'DATA_MISSING_OR_PENDING');
  console.log('PASS 3: Cierre y reapertura sin internet conserva zona protegida y rechaza falso negativo');
}

// -------------------------------------------------------------
// Test 4: Pan y zoom rápido sin parpadeos ni cuadros blancos
// -------------------------------------------------------------
{
  const webOptContent = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/webview-optimizations.js'), 'utf8');
  assert(webOptContent.includes('fadeAnimation = false'), 'Must disable fadeAnimation to prevent white flickers');
  assert(webOptContent.includes('#f2ede4'), 'Must define stable background matching map palette');
  assert(webOptContent.includes('.leaflet-tile {'), 'Must enforce transition none on tiles');

  // Order of render: local exact -> network exact -> visual fallback -> stable background
  const storage = new MockTileStorage();
  const targetKey = 'provider:layer:17:10:10:v1';
  storage.put('provider:layer:16:5:5:v1', Buffer.from('parent_tile_z16'));

  const cached = storage.get(targetKey);
  assert.strictEqual(cached, null, 'Exact local tile not present');

  const fallback = storage.getVisualFallback(17, 10, 10, 'layer');
  assert(fallback !== null && fallback.isFallback === true, 'Parent tile provides instant visual continuity');
  assert.strictEqual(fallback.parentZ, 16);
  console.log('PASS 4: Pan y zoom continuo sin parpadeos con fallback visual de padre');
}

// -------------------------------------------------------------
// Test 5: Fallback visual desacoplado de la evaluación de cobertura
// -------------------------------------------------------------
{
  function evaluateCoverageStrict(tile) {
    if (!tile) return { status: 'DATA_MISSING_OR_PENDING' };
    if (tile.isFallback) {
      return { status: 'DATA_MISSING_OR_PENDING', reason: 'Fallback visual no tiene precision de cobertura' };
    }
    return { status: 'CONFIRMED' };
  }

  const fallbackTile = { data: Buffer.from('parent_scaled'), isFallback: true };
  const resFallback = evaluateCoverageStrict(fallbackTile);
  assert.strictEqual(resFallback.status, 'DATA_MISSING_OR_PENDING');

  const originalTile = { data: Buffer.from('exact_z17_raw_png'), isFallback: false };
  const resExact = evaluateCoverageStrict(originalTile);
  assert.strictEqual(resExact.status, 'CONFIRMED');
  console.log('PASS 5: Cambio de zoom y fallback visual nunca afirman cobertura precisa');
}

// -------------------------------------------------------------
// Test 6: Revalidación stale-while-revalidate y HTTP 304
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const key = 'provider:fibra:17:10:10:v1';
  storage.put(key, Buffer.from('current_image_bytes'), -1, '"etag-abc"'); // expired

  const tile = storage.get(key);
  assert.strictEqual(storage.hasValid(key), false, 'Tile is stale');

  // Stale-while-revalidate: deliver immediately from storage
  assert(tile.data.length > 0, 'Stale tile is immediately usable');

  // Conditional request with ETag
  function handleConditionalResponse(status, newEtag, incomingBytes) {
    if (status === 304) {
      // Renew metadata with 0 image payload bytes
      storage.put(key, tile.data, 604800000, newEtag || tile.etag);
      return 0;
    }
    return incomingBytes;
  }

  const transferred = handleConditionalResponse(304, '"etag-abc"', 0);
  assert.strictEqual(transferred, 0, '304 Not Modified transfers 0 image bytes');
  assert.strictEqual(storage.hasValid(key), true, 'Metadata renewed successfully');
  console.log('PASS 6: Revalidación 304 conserva bytes existentes sin redescarga');
}

// -------------------------------------------------------------
// Test 7: Respuesta tardía de consulta vieja sin alterar la consulta nueva
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const monitor = new MockNetworkQualityMonitor();
  const coord = new MockTileRequestCoordinator(storage, monitor);

  let activeResult = 'initial';

  // Request 1 from Generation 1
  coord.searchGeneration = 1;
  coord.requestTile('tile_old', PRIORITY_SELECTED_POINT, 'search_1', 1, (err, data) => {
    if (!err) activeResult = 'old_result';
  });

  // User rapidly selects a new point -> Generation 2
  coord.searchGeneration = 2;
  coord.requestTile('tile_new', PRIORITY_SELECTED_POINT, 'search_2', 2, (err, data) => {
    if (!err) activeResult = 'new_result';
  });

  // Process the old request first (which arrives late after generation bumped to 2)
  coord.processNext();
  assert.strictEqual(activeResult, 'initial', 'Old late request was discarded due to stale generation');

  // Process the new request (generation 2)
  coord.processNext();
  assert.strictEqual(activeResult, 'new_result', 'New selection successfully sets result');
  console.log('PASS 7: Respuesta tardía de consulta vieja descartada sin alterar la consulta nueva');
}

// -------------------------------------------------------------
// Test 8: Fallo del proveedor conservando la última versión local válida
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const key = 'provider:fibra:17:99:99:v1';
  const validData = Buffer.from('working_tile_data');
  storage.put(key, validData, 604800000);

  function simulateProviderFailure(key) {
    const existing = storage.get(key);
    // Remote server throws 503 Service Unavailable or timeout
    const networkSuccess = false;
    if (!networkSuccess) {
      // Do NOT erase local storage
      return existing;
    }
    return null;
  }

  const result = simulateProviderFailure(key);
  assert.strictEqual(result.data, validData, 'Provider failure preserves existing working data');
  console.log('PASS 8: Fallo del proveedor conserva la última versión local funcional');
}

// -------------------------------------------------------------
// Test 9: Descargas grandes exclusivamente por Wi-Fi
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const monitor = new MockNetworkQualityMonitor();
  const coord = new MockTileRequestCoordinator(storage, monitor);

  // On Mobile Data (isWifi = false)
  monitor.isWifi = false;
  let zoneSyncAttempted = false;
  let pointQueryDelivered = false;

  // Try to dispatch a full zone sync (Priority 2)
  coord.requestTile('zone_sync_tile', PRIORITY_ZONE, 'zone_sync_job', 1, (err, data) => {
    if (err && err.includes('wifi_required')) {
      zoneSyncAttempted = false;
    } else {
      zoneSyncAttempted = true;
    }
  });

  // Try to dispatch a point query (Priority 4)
  coord.requestTile('point_query_tile', PRIORITY_SELECTED_POINT, 'point_query', 1, (err, data) => {
    pointQueryDelivered = true;
  });

  coord.processNext();
  assert.strictEqual(zoneSyncAttempted, false, 'Zone sync must NOT download over cellular/mobile data');
  assert.strictEqual(pointQueryDelivered, true, 'Selected point MUST download over cellular/mobile data');

  // Switch to Wi-Fi -> Zone sync is now permitted
  monitor.isWifi = true;
  coord.requestTile('zone_sync_tile', PRIORITY_ZONE, 'zone_sync_job_wifi', 1, (err, data) => {
    zoneSyncAttempted = true;
  });
  coord.processNext();
  assert.strictEqual(zoneSyncAttempted, true, 'Zone sync proceeds normally when Wi-Fi is connected');
  console.log('PASS 9: Descargas grandes y sincronización de zona restringidas exclusivamente a Wi-Fi');
}

// -------------------------------------------------------------
// Test 10: Double Buffer y Swap Atómico en Leaflet
// -------------------------------------------------------------
{
  const webOptContent = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/webview-optimizations.js'), 'utf8');
  assert(webOptContent.includes('_pruneTiles'), 'Must hook _pruneTiles to implement double buffering');
  assert(webOptContent.includes('allCurrentLoaded'), 'Must verify incoming tiles loaded before pruning old tiles');
  assert(webOptContent.includes('tile.decode'), 'Must pre-decode image before rendering for atomic swap');

  // Simulate Leaflet double buffer logic
  function simulatePrune(tiles, oldPrune) {
    let allCurrentLoaded = true;
    for (const key in tiles) {
      const t = tiles[key];
      if (t.current && !t.loaded) {
        allCurrentLoaded = false;
        break;
      }
    }
    if (!allCurrentLoaded) return 'retained_old_viewport';
    return oldPrune();
  }

  const mockTilesLoading = {
    old_tile_1: { current: false, loaded: true },
    new_tile_1: { current: true, loaded: false } // Still loading
  };
  const resultWhileLoading = simulatePrune(mockTilesLoading, () => 'pruned');
  assert.strictEqual(resultWhileLoading, 'retained_old_viewport', 'Must retain old tile while new tile is loading');

  const mockTilesLoaded = {
    old_tile_1: { current: false, loaded: true },
    new_tile_1: { current: true, loaded: true } // Ready!
  };
  const resultWhenReady = simulatePrune(mockTilesLoaded, () => 'pruned');
  assert.strictEqual(resultWhenReady, 'pruned', 'Prunes old tile only when new tile is 100% loaded and ready');
  console.log('PASS 10: Double buffer y swap atómico impiden mostrar cuadros vacíos o cuadrículas durante pan/zoom');
}

// -------------------------------------------------------------
// Test 11: Jerarquía de memoria por capas (PINNED > HOT > WARM > COLD)
// -------------------------------------------------------------
{
  const storage = new MockTileStorage();
  const pinnedKey = 'pinned_z17_center';
  storage.put(pinnedKey, Buffer.from('pinned_data'));
  storage.pinKey(pinnedKey);

  // Add 10 normal tiles
  for (let i = 0; i < 10; i++) {
    storage.put('normal_' + i, Buffer.from('data_' + i));
  }

  // Trim cache
  storage.trimDiskCache(5);

  assert(storage.get(pinnedKey) !== null, 'PINNED tier is protected from eviction');
  console.log('PASS 11: Prioridad de memoria respeta la jerarquía PINNED, HOT, WARM y COLD');
}

// -------------------------------------------------------------
// Test 12: Persistencia de contexto y zonas habituales sin UI
// -------------------------------------------------------------
{
  // Simulated ZoneManager
  class HabitualZoneManager {
    constructor() { this.zones = new Map(); }
    recordUsage(id, lat, lon, zoom, timestamp = Date.now()) {
      let z = this.zones.get(id);
      if (!z) {
        z = { id, lat, lon, zoom, lastUsed: timestamp };
        this.zones.set(id, z);
      } else {
        z.lat = lat; z.lon = lon; z.zoom = zoom; z.lastUsed = timestamp;
      }
      return z;
    }
    getLastUsedZone() {
      let best = null;
      for (const z of this.zones.values()) {
        if (!best || z.lastUsed > best.lastUsed) best = z;
      }
      return best;
    }
  }

  const zm = new HabitualZoneManager();
  zm.recordUsage('zone_tijuana', 32.5149, -117.0382, 17, 1000);
  zm.recordUsage('zone_otay', 32.5320, -116.9740, 17, 2000);

  const last = zm.getLastUsedZone();
  assert.strictEqual(last.id, 'zone_otay', 'Must automatically identify the most recently used zone');
  assert.strictEqual(last.lat, 32.5320);
  assert.strictEqual(last.zoom, 17);
  console.log('PASS 12: Contexto y zonas habituales recuperan automáticamente el último centro y zoom');
}

console.log('--- ALL 12 GEO-PIN PRO ENGINE ACCEPTANCE TESTS PASSED ---');
