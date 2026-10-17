package mx.coberturapunto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages automatic user work zones with intelligent pyramid LOD caching.
 * Restricts large downloads and tile pre-caching strictly to unmetered Wi-Fi.
 */
public final class ZoneManager {

    public interface ZoneProgressListener {
        void onProgress(WorkZone zone, int completed, int total);
        void onComplete(WorkZone zone);
        void onError(WorkZone zone, String reason);
    }

    private static final int MAX_HABITUAL_ZONES = 5;

    private final File storageFile;
    private final TileStorage tileStorage;
    private final TileRequestCoordinator coordinator;
    private final NetworkQualityMonitor networkMonitor;
    private final RasterTileProfile tileProfile;

    private final Map<String, WorkZone> zones = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> activeDownloads = new ConcurrentHashMap<>();
    private final ExecutorService zoneExecutor = Executors.newSingleThreadExecutor();

    public ZoneManager(File storageFile,
                       TileStorage tileStorage,
                       TileRequestCoordinator coordinator,
                       NetworkQualityMonitor networkMonitor,
                       RasterTileProfile tileProfile) {
        this.storageFile = storageFile;
        this.tileStorage = tileStorage;
        this.coordinator = coordinator;
        this.networkMonitor = networkMonitor;
        this.tileProfile = tileProfile;
        loadZones();
    }

    public synchronized List<WorkZone> getHabitualZones() {
        List<WorkZone> list = new ArrayList<>(zones.values());
        list.sort(Comparator.comparingLong((WorkZone z) -> z.lastUsed).reversed());
        return Collections.unmodifiableList(list);
    }

    public synchronized WorkZone getZone(String id) {
        return zones.get(id);
    }

    public synchronized WorkZone findZoneAt(double lat, double lon) {
        for (WorkZone zone : zones.values()) {
            if (zone.geometry.contains(lat, lon)) {
                return zone;
            }
        }
        return null;
    }

    public synchronized WorkZone getLastUsedZone() {
        WorkZone best = null;
        for (WorkZone z : zones.values()) {
            if (best == null || z.lastUsed > best.lastUsed) {
                best = z;
            }
        }
        return best;
    }

    /**
     * Automatic usage tracker: called whenever a location query or map pan settles.
     */
    public synchronized WorkZone recordUsage(double lat, double lon, int zoom) {
        WorkZone zone = findZoneAt(lat, lon);
        if (zone == null) {
            // Radio base de 30 km delimitado geográficamente a México con LODs 12 al 18
            ZoneGeometry geo = ZoneGeometry.fromCenterRadiusKm(lat, lon, 30.0);
            String name = String.format(Locale.ROOT, "zone_30km_%.3f_%.3f", lat, lon);
            zone = createZone(name, geo, new int[]{12, 13, 14, 15, 16, 17, 18});
        }

        zone.updateUsage(lat, lon, zoom);
        pruneOldZonesIfNeeded();
        saveZones();

        // Enqueue background pyramid sync (runs strictly if Wi-Fi is available)
        startOrResumeZone(zone.id, null);
        return zone;
    }

    public synchronized WorkZone createZone(String name, ZoneGeometry geometry, int[] zooms) {
        String id = "zone_" + System.currentTimeMillis();
        WorkZone zone = new WorkZone(id, name, geometry, zooms);

        // Calculate total tiles based on intelligent LOD pyramid
        double centerLat = (geometry.minLat + geometry.maxLat) / 2.0;
        double centerLon = (geometry.minLon + geometry.maxLon) / 2.0;
        int total = 0;
        for (int z : zone.zoomLevels) {
            ZoneGeometry lodGeo = getGeometryForZoom(geometry, centerLat, centerLon, z);
            List<ZoneGeometry.TileCoord> coords = lodGeo.calculateIntersectingTiles(z);
            total += coords.size() * 2; // base + coverage
        }
        zone.totalTiles = total;

        zones.put(id, zone);
        pruneOldZonesIfNeeded();
        saveZones();
        refreshPinnedTiles();
        return zone;
    }

    /**
     * Intelligent LOD Pyramid geometry calculation:
     * - z <= 14: Full 30 km zone (macro / city coverage ~40 tiles)
     * - z == 15, 16: Urban ring (10 km ~150 tiles)
     * - z >= 17: Local street ring (1.5 km ~50 tiles)
     */
    private ZoneGeometry getGeometryForZoom(ZoneGeometry baseGeo, double lat, double lon, int zoom) {
        if (zoom <= 14) {
            return baseGeo;
        } else if (zoom <= 16) {
            return ZoneGeometry.fromCenterRadiusKm(lat, lon, 10.0);
        } else {
            return ZoneGeometry.fromCenterRadiusKm(lat, lon, 1.5);
        }
    }

    private synchronized void pruneOldZonesIfNeeded() {
        if (zones.size() <= MAX_HABITUAL_ZONES) return;

        List<WorkZone> list = new ArrayList<>(zones.values());
        list.sort(Comparator.comparingLong((WorkZone z) -> z.lastUsed));

        while (list.size() > MAX_HABITUAL_ZONES) {
            WorkZone oldest = list.remove(0);
            deleteZone(oldest.id);
        }
    }

    public synchronized boolean deleteZone(String id) {
        pauseZone(id);
        WorkZone removed = zones.remove(id);
        if (removed != null) {
            saveZones();
            refreshPinnedTiles();
            return true;
        }
        return false;
    }

    public void startOrResumeZone(String id, ZoneProgressListener listener) {
        WorkZone zone = getZone(id);
        if (zone == null) {
            if (listener != null) listener.onError(null, "Zone not found");
            return;
        }

        // Must be allowed to do aggressive sync (strictly Wi-Fi)
        if (!networkMonitor.isAggressiveDownloadAllowed()) {
            if (listener != null) listener.onError(zone, "wifi_required_for_zone_sync");
            return;
        }

        AtomicBoolean activeFlag = activeDownloads.computeIfAbsent(id, k -> new AtomicBoolean(true));
        activeFlag.set(true);

        zone.status = WorkZone.Status.DOWNLOADING;
        saveZones();

        zoneExecutor.execute(() -> executeZoneSync(zone, activeFlag, listener));
    }

    public void pauseZone(String id) {
        AtomicBoolean active = activeDownloads.get(id);
        if (active != null) {
            active.set(false);
        }
        WorkZone zone = getZone(id);
        if (zone != null && zone.status == WorkZone.Status.DOWNLOADING) {
            zone.status = WorkZone.Status.PAUSED;
            saveZones();
        }
    }

    private void executeZoneSync(WorkZone zone, AtomicBoolean activeFlag, ZoneProgressListener listener) {
        int completed = 0;
        int failed = 0;
        double centerLat = (zone.geometry.minLat + zone.geometry.maxLat) / 2.0;
        double centerLon = (zone.geometry.minLon + zone.geometry.maxLon) / 2.0;

        for (int z : zone.zoomLevels) {
            if (!activeFlag.get() || !networkMonitor.isAggressiveDownloadAllowed()) break;

            ZoneGeometry lodGeo = getGeometryForZoom(zone.geometry, centerLat, centerLon, z);
            List<ZoneGeometry.TileCoord> coords = lodGeo.calculateIntersectingTiles(z);
            for (ZoneGeometry.TileCoord c : coords) {
                if (!activeFlag.get() || !networkMonitor.isAggressiveDownloadAllowed()) break;

                // 1. Coverage tile
                if (tileProfile != null) {
                    TileSpec covSpec = new TileSpec("coverage-v1", "fibra", "v1", "EPSG:900913", 256,
                            z, c.x, c.y, tileProfile.overlay(z, c.x, c.y), 7L * 24 * 3600 * 1000, true, false);

                    if (tileStorage.get(covSpec) != null) {
                        completed++;
                    } else {
                        byte[] data = coordinator.getOrFetchSync(covSpec, 2500);
                        if (data != null && data.length > 0) completed++;
                        else failed++;
                    }
                }

                // 2. Base tile
                String baseUrl = "https://mt1.google.com/vt/lyrs=m&x=" + c.x + "&y=" + c.y + "&z=" + z;
                TileSpec baseSpec = new TileSpec("coverage-v1", "base", "v1", "EPSG:900913", 256,
                        z, c.x, c.y, baseUrl, 30L * 24 * 3600 * 1000, false, true);

                if (tileStorage.get(baseSpec) != null) {
                    completed++;
                } else {
                    byte[] data = coordinator.getOrFetchSync(baseSpec, 2500);
                    if (data != null && data.length > 0) completed++;
                    else failed++;
                }

                zone.completedTiles = completed;
                zone.failedTiles = failed;
                if (listener != null) listener.onProgress(zone, completed, zone.totalTiles);
            }
        }

        if (activeFlag.get() && networkMonitor.isAggressiveDownloadAllowed()) {
            zone.status = (failed == 0) ? WorkZone.Status.COMPLETED : WorkZone.Status.IDLE;
            zone.lastValidatedAt = System.currentTimeMillis();
            refreshPinnedTiles();
            if (listener != null) listener.onComplete(zone);
        } else {
            zone.status = WorkZone.Status.PAUSED;
        }

        activeFlag.set(false);
        saveZones();
    }

    public synchronized void refreshPinnedTiles() {
        for (WorkZone zone : zones.values()) {
            double centerLat = (zone.geometry.minLat + zone.geometry.maxLat) / 2.0;
            double centerLon = (zone.geometry.minLon + zone.geometry.maxLon) / 2.0;
            for (int z : zone.zoomLevels) {
                ZoneGeometry lodGeo = getGeometryForZoom(zone.geometry, centerLat, centerLon, z);
                List<ZoneGeometry.TileCoord> coords = lodGeo.calculateIntersectingTiles(z);
                for (ZoneGeometry.TileCoord c : coords) {
                    String baseKey = "coverage-v1_base_v1_" + z + "_" + c.x + "_" + c.y;
                    String covKey = "coverage-v1_fibra_v1_" + z + "_" + c.x + "_" + c.y;
                    tileStorage.pinKey(baseKey);
                    tileStorage.pinKey(covKey);
                }
            }
        }
    }

    private synchronized void saveZones() {
        try {
            JSONArray arr = new JSONArray();
            for (WorkZone z : zones.values()) {
                arr.put(z.toJson());
            }
            File temp = new File(storageFile.getParentFile(), storageFile.getName() + ".tmp");
            try (FileWriter fw = new FileWriter(temp)) {
                fw.write(arr.toString());
            }
            temp.renameTo(storageFile);
        } catch (Exception ignored) {}
    }

    private synchronized void loadZones() {
        if (!storageFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(storageFile))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                WorkZone zone = WorkZone.fromJson(obj);
                zones.put(zone.id, zone);
            }
        } catch (Exception ignored) {}
    }
}
