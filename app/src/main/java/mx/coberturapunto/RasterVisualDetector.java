package mx.coberturapunto;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * High-performance raster visual evaluation and network tile interceptor.
 * - Viewport-first high priority tile dispatch.
 * - Strict Wi-Fi only automatic zone synchronization with LOD pyramid.
 * - Transparent empty tile detection strictly for coverage layers.
 * - Conditional HTTP revalidation (ETag / If-None-Match 304).
 * - Instant 0 ms local RAM/flash delivery.
 */
public final class RasterVisualDetector {

    public static final String DETECTOR_NAME = "isolated_yellow_v1";
    public static final String LAYER_NAME = "fibra";
    private static final int SAMPLE_COUNT = 64;

    private static Context appContext;
    private static TileStorage storage;
    private static TileRequestCoordinator coordinator;
    private static NetworkQualityMonitor networkMonitor;
    private static ZoneManager zoneManager;

    private static final okhttp3.OkHttpClient FAST_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectionPool(new okhttp3.ConnectionPool(64, 5, TimeUnit.MINUTES))
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build();

    private static final Set<String> EMPTY_TILES_REGISTRY = ConcurrentHashMap.newKeySet();

    private String activeQueryId;

    public static synchronized void init(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
        Context target = appContext != null ? appContext : ctx;
        if (storage == null) {
            networkMonitor = NetworkQualityMonitor.getInstance();
            if (target != null) {
                storage = new TileStorage(target);
                networkMonitor.registerConnectivity(target);
                coordinator = new TileRequestCoordinator(storage, networkMonitor);
                File zonesFile = new File(target.getFilesDir(), "work_zones.json");
                RasterTileProfile defaultProfile = ProviderRegistry.DEFAULT.resolve(
                        java.net.URI.create("https://cobertura.megacable.com.mx")).tileProfile();
                zoneManager = new ZoneManager(zonesFile, storage, coordinator, networkMonitor, defaultProfile);
            } else {
                File fallbackDir = new File(System.getProperty("java.io.tmpdir", "."), "geopin_tiles");
                storage = new TileStorage(fallbackDir);
                coordinator = new TileRequestCoordinator(storage, networkMonitor);
            }
        }
    }

    public static TileStorage getStorage() {
        if (storage == null) init(appContext);
        return storage;
    }

    public static ZoneManager getZoneManager() {
        if (zoneManager == null) init(appContext);
        return zoneManager;
    }

    public static NetworkQualityMonitor getNetworkMonitor() {
        if (networkMonitor == null) init(appContext);
        return networkMonitor;
    }

    public static void setAppContext(Context ctx) {
        appContext = ctx.getApplicationContext();
        init(appContext);
    }

    public void startQuery(String id) {
        this.activeQueryId = id;
        if (coordinator != null) {
            coordinator.bumpSearchGeneration(coordinator.getActiveSearchGeneration() + 1);
        }
    }

    /**
     * Intercepts WebView tile requests and dispatches via fast non-blocking pipeline.
     */
    public static WebResourceResponse shouldIntercept(Uri uri, RasterTileProfile profile) {
        if (uri == null || profile == null) return null;
        String urlStr = uri.toString();

        boolean isFibra = profile.primary(urlStr);
        boolean isHfc = profile.secondary(urlStr);
        boolean isGoogle = profile.base(urlStr);
        boolean isInegi = profile.alternate(urlStr);

        if (!isFibra && !isHfc && !isGoogle && !isInegi) return null;

        TileSpec spec = parseTileSpec(uri, urlStr, isFibra, isHfc, isGoogle, isInegi, profile);
        if (spec == null) return null;

        if (storage == null) {
            init(appContext);
        }

        // 1. Instant RAM/Flash cache hit (0 ms, 0 network bytes)
        TileStorage.StoredTile cached = storage != null ? storage.get(spec) : null;
        if (cached != null) {
            return buildWebResponse(cached.data, cached.etag, cached.lastModified, cached.isFallback);
        }

        // 2. Empty Tile Cache: if previously confirmed transparent empty (coverage ONLY)
        String tileCoordKey = spec.layerId + "_" + spec.z + "_" + spec.x + "_" + spec.y;
        if ((isFibra || isHfc) && EMPTY_TILES_REGISTRY.contains(tileCoordKey)) {
            return buildEmptyTransparentResponse();
        }

        // 3. Offline mode: serve fallback from parent if available
        if (networkMonitor != null && !networkMonitor.isOnline()) {
            TileStorage.StoredTile fallback = storage != null ? storage.getVisualFallback(spec) : null;
            if (fallback != null) {
                return buildWebResponse(fallback.data, null, null, true);
            }
            return null;
        }

        // 4. Parallel HTTP/2 fetch with ETag / 304 conditional revalidation
        try {
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(urlStr)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

            if (cached != null && cached.etag != null) {
                rb.header("If-None-Match", cached.etag);
            }
            if (cached != null && cached.lastModified != null) {
                rb.header("If-Modified-Since", cached.lastModified);
            }

            try (okhttp3.Response resp = FAST_CLIENT.newCall(rb.build()).execute()) {
                if (resp.code() == 304 && cached != null) {
                    if (storage != null) {
                        storage.markValidated(spec, resp.header("ETag"), resp.header("Last-Modified"));
                    }
                    return buildWebResponse(cached.data, cached.etag, cached.lastModified, false);
                }

                if (resp.isSuccessful() && resp.body() != null) {
                    byte[] data = resp.body().bytes();
                    if (data != null && data.length > 0) {
                        if ((isFibra || isHfc) && data.length < 350) {
                            EMPTY_TILES_REGISTRY.add(tileCoordKey);
                        }

                        if (storage != null) {
                            storage.put(spec, data, resp.header("ETag"), resp.header("Last-Modified"));
                        }
                        if (networkMonitor != null) {
                            networkMonitor.recordSample(data.length, 100, resp.code());
                        }
                        return buildWebResponse(data, resp.header("ETag"), resp.header("Last-Modified"), false);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 5. If download timed out or failed, attempt visual fallback from parent
        if (storage != null) {
            TileStorage.StoredTile fallback = storage.getVisualFallback(spec);
            if (fallback != null) {
                return buildWebResponse(fallback.data, null, null, true);
            }
        }

        return null;
    }

    private static final byte[] EMPTY_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, (byte) 0xc4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0b, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9c, 0x63, 0x60, 0x00, 0x02, 0x00,
            0x00, 0x05, 0x00, 0x01, (byte) 0xe9, 0x34, 0x2e, (byte) 0xea,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
            (byte) 0xae, 0x42, 0x60, (byte) 0x82
    };

    private static WebResourceResponse buildEmptyTransparentResponse() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "image/png");
        headers.put("Cache-Control", "public, max-age=604800");
        return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(EMPTY_PNG));
    }

    private static TileSpec parseTileSpec(Uri uri, String urlStr, boolean isFibra, boolean isHfc,
                                          boolean isGoogle, boolean isInegi, RasterTileProfile profile) {
        int z = 17, x = -1, y = -1;
        String layer = isFibra ? "fibra" : (isHfc ? "hfc" : (isGoogle ? "base" : "inegi"));
        boolean isCoverage = isFibra || isHfc;
        boolean isBase = isGoogle || isInegi;

        if (isCoverage || isInegi) {
            String colStr = uri.getQueryParameter("TileCol");
            if (colStr == null) colStr = uri.getQueryParameter("tilecol");
            String rowStr = uri.getQueryParameter("TileRow");
            if (rowStr == null) rowStr = uri.getQueryParameter("tilerow");
            String tileMatrixStr = uri.getQueryParameter("TileMatrix");
            if (tileMatrixStr == null) tileMatrixStr = uri.getQueryParameter("tilematrix");

            if (colStr != null && rowStr != null) {
                try {
                    x = Integer.parseInt(colStr);
                    y = Integer.parseInt(rowStr);
                    if (tileMatrixStr != null) {
                        String cleanZ = tileMatrixStr.replaceAll("[^0-9]", "");
                        if (!cleanZ.isEmpty()) z = Integer.parseInt(cleanZ);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (x < 0 || y < 0) {
            String path = uri.getPath();
            if (path != null) {
                String[] segments = path.split("/");
                for (int i = 0; i < segments.length - 2; i++) {
                    try {
                        int pz = Integer.parseInt(segments[i]);
                        int px = Integer.parseInt(segments[i + 1]);
                        String last = segments[i + 2].replaceAll("\\.[a-zA-Z]+$", "");
                        int py = Integer.parseInt(last);
                        if (pz >= 0 && pz <= 22) {
                            z = pz; x = px; y = py;
                            break;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (x < 0 || y < 0) {
            String query = uri.getQuery();
            if (query != null) {
                try {
                    String qx = uri.getQueryParameter("x");
                    String qy = uri.getQueryParameter("y");
                    String qz = uri.getQueryParameter("z");
                    if (qx != null && qy != null) {
                        x = Integer.parseInt(qx);
                        y = Integer.parseInt(qy);
                        if (qz != null) z = Integer.parseInt(qz);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (x < 0 || y < 0) return null;

        long ttl = isCoverage ? 7L * 24 * 3600 * 1000 : 30L * 24 * 3600 * 1000;
        return new TileSpec("coverage-v1", layer, "v1", "EPSG:900913", 256, z, x, y, urlStr, ttl, isCoverage, isBase);
    }

    private static WebResourceResponse buildWebResponse(byte[] data, String etag, String lastModified, boolean isFallback) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "image/png");
        headers.put("Access-Control-Allow-Origin", "*");
        if (etag != null) headers.put("ETag", etag);
        if (lastModified != null) headers.put("Last-Modified", lastModified);
        if (isFallback) headers.put("X-Tile-Fallback", "scaled_parent");
        headers.put("Cache-Control", "public, max-age=" + (isFallback ? "60" : "604800"));

        return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(data));
    }

    public static void prefetchNeighbors(double lat, double lon, RasterTileProfile profile) {
        if (profile == null) return;
        if (storage == null) init(appContext);

        final double n17 = 131072.0;
        double x17 = (lon + 180.0) / 360.0 * n17;
        double latRad = Math.toRadians(lat);
        double y17 = (1.0 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * n17;

        int centerCol17 = (int) Math.floor(x17);
        int centerRow17 = (int) Math.floor(y17);

        // Pre-fetch 3x3 ring at Priority 1 (PREFETCH)
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int col = centerCol17 + dx;
                int row = centerRow17 + dy;

                TileSpec fiberSpec = new TileSpec("coverage-v1", "fibra", "v1", "EPSG:900913", 256,
                        17, col, row, profile.overlay(17, col, row), 7L * 24 * 3600 * 1000, true, false);

                coordinator.requestTile(fiberSpec, TileRequestCoordinator.PRIORITY_PREFETCH,
                        "prefetch_neighbor", coordinator.getActiveSearchGeneration(), null);
            }
        }
    }

    public VisualEvaluation evaluate(double lat, double lon, String queryId, RasterTileProfile profile) {
        if (queryId == null || !queryId.equals(activeQueryId)) {
            return new VisualEvaluation("unknown", "query_mismatch", 0.0, 0, false);
        }

        // Automatic Work Zone determination:
        // Automatically creates a bounded persistent work zone (~30km pyramid)
        // and synchronizes missing pyramid tiles in the background with Wi-Fi.
        if (zoneManager != null) {
            zoneManager.recordUsage(lat, lon, 17);
        }

        final double n = 131072.0; // 2^17
        double x = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * n;

        int tileCol = (int) Math.floor(x);
        int tileRow = (int) Math.floor(y);

        TileSpec spec = new TileSpec("coverage-v1", "fibra", "v1", "EPSG:900913", 256,
                17, tileCol, tileRow, profile.overlay(17, tileCol, tileRow), 7L * 24 * 3600 * 1000, true, false);
        if (storage != null) {
            storage.pinPoint(17, tileCol, tileRow);
        }

        if (coordinator == null) {
            init(null);
        }

        // Fetch synchronously with top Priority 4 (SELECTED_POINT)
        byte[] tileBytes = coordinator.getOrFetchSync(spec, 3500);

        TileStorage.StoredTile stored = storage.get(spec);
        boolean isSavedData = stored != null;
        long tileTs = stored != null ? stored.timestamp : System.currentTimeMillis();
        boolean isFallback = stored != null && stored.isFallback;

        CoverageEvaluator.EvaluationResult res = CoverageEvaluator.evaluate(
                tileBytes, lat, lon, 17, tileCol, tileRow, isSavedData, tileTs, isFallback);

        return new VisualEvaluation(
                res.status,
                res.reason,
                res.ratio,
                res.samples,
                res.stable,
                res.isSavedData,
                res.tileTimestamp,
                res.ageDays,
                res.isFallback
        );
    }

    public static final class VisualEvaluation {
        public final String status;
        public final String reason;
        public final double ratio;
        public final int samples;
        public final boolean stable;
        public final boolean isSavedData;
        public final long tileTimestamp;
        public final int ageDays;
        public final boolean isFallback;

        public VisualEvaluation(String status, String reason, double ratio, int samples, boolean stable) {
            this(status, reason, ratio, samples, stable, false, System.currentTimeMillis(), 0, false);
        }

        public VisualEvaluation(String status, String reason, double ratio, int samples, boolean stable,
                                boolean isSavedData, long tileTimestamp, int ageDays, boolean isFallback) {
            this.status = status;
            this.reason = reason;
            this.ratio = ratio;
            this.samples = samples;
            this.stable = stable;
            this.isSavedData = isSavedData;
            this.tileTimestamp = tileTimestamp;
            this.ageDays = ageDays;
            this.isFallback = isFallback;
        }

        public boolean isYellow() {
            return "signal_detected".equals(status) && stable && ratio > 0 && !isFallback;
        }

        public boolean isWhite() {
            return "no_signal_detected".equals(status) && stable && ratio == 0 && !isFallback;
        }

        public boolean isDataMissing() {
            return isFallback || "data_missing_or_pending".equals(status);
        }

        public boolean isUnknown() {
            return !isYellow() && !isWhite() && !isDataMissing();
        }
    }
}
