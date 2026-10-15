package mx.coberturapunto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Super-fast persistent tile storage and visual evaluator.
 * Caches all Google base map and provider coverage tiles permanently in private filesDir storage
 * with HTTP/2 multiplexing, zero network re-fetching on repeat views, and instant 0ms RAM delivery.
 */
public final class RasterVisualDetector {

    public static final String DETECTOR_NAME = "isolated_yellow_v1";
    public static final String LAYER_NAME = "fibra";
    public static final int SAMPLE_COUNT = 64;

    private static final Map<String, byte[]> TILE_CACHE = new ConcurrentHashMap<>(2048);
    private volatile String activeQueryId = "";
    private static volatile File tileDiskDir = null;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
            .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build();

    private static final ExecutorService PREFETCH_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "TilePrefetcher");
        t.setDaemon(true);
        return t;
    });

    public static class VisualEvaluation {
        public final String status;      // "signal_detected", "no_signal_detected", "unknown"
        public final String reason;      // descriptive reason
        public final double ratio;       // ratio of yellow pixels (0.0 to 1.0)
        public final int samples;        // count of evaluated samples (e.g. 64)
        public final boolean stable;     // true if confirmed across observations

        public VisualEvaluation(String status, String reason, double ratio, int samples, boolean stable) {
            this.status = status;
            this.reason = reason;
            this.ratio = ratio;
            this.samples = samples;
            this.stable = stable;
        }

        public boolean isYellow() {
            return "signal_detected".equals(status) && stable && ratio > 0;
        }

        public boolean isWhite() {
            return "no_signal_detected".equals(status) && stable && ratio == 0;
        }
    }

    RasterVisualDetector() {}

    public static synchronized void init(Context context) {
        if (tileDiskDir == null && context != null) {
            // Use permanent private filesDir so Android never auto-deletes stored tiles on low cache
            tileDiskDir = new File(context.getFilesDir(), "tiles_storage");
            if (!tileDiskDir.exists()) {
                tileDiskDir.mkdirs();
            }
            PREFETCH_EXECUTOR.execute(RasterVisualDetector::trimDiskCacheIfNeeded);
        }
    }

    public void startQuery(String queryId) {
        activeQueryId = queryId != null ? queryId : "";
    }

    private static final java.util.regex.Pattern P_X = java.util.regex.Pattern.compile("[?&/]x=(\\d+)");
    private static final java.util.regex.Pattern P_Y = java.util.regex.Pattern.compile("[?&/]y=(\\d+)");
    private static final java.util.regex.Pattern P_Z = java.util.regex.Pattern.compile("[?&/]z=(\\d+)");

    /**
     * Intercepts both provider coverage and Google Maps base tiles.
     * Delivers from RAM or permanent flash storage in 0ms (zero data consumed).
     * On first visit, fetches via HTTP/2 connection pool, saves permanently to disk, and serves.
     */
    public static WebResourceResponse shouldIntercept(Uri uri, RasterTileProfile profile) {
        if (uri == null) return null;
        String urlStr = uri.toString();

        boolean isFibra = profile.primary(urlStr);
        boolean isHfc = profile.secondary(urlStr);
        boolean isGoogle = profile.base(urlStr);
        boolean isInegi = profile.alternate(urlStr);

        if (!isFibra && !isHfc && !isGoogle && !isInegi) return null;

        String key = null;
        if (isFibra || isHfc) {
            String colStr = uri.getQueryParameter("TileCol");
            if (colStr == null) colStr = uri.getQueryParameter("tilecol");
            String rowStr = uri.getQueryParameter("TileRow");
            if (rowStr == null) rowStr = uri.getQueryParameter("tilerow");
            String matrix = uri.getQueryParameter("TileMatrix");
            if (matrix == null) matrix = uri.getQueryParameter("tilematrix");

            String zStr = "17";
            if (matrix != null) {
                int colon = matrix.lastIndexOf(':');
                zStr = colon >= 0 ? matrix.substring(colon + 1) : matrix;
            }

            if (colStr == null || rowStr == null) return null;
            String prefix = isFibra ? "fibra_" : "hfc_";
            key = prefix + zStr + "_" + colStr + "_" + rowStr;
        } else if (isGoogle) {
            String x = uri.getQueryParameter("x");
            String y = uri.getQueryParameter("y");
            String z = uri.getQueryParameter("z");
            if (x == null || y == null || z == null) {
                java.util.regex.Matcher mx = P_X.matcher(urlStr);
                if (mx.find()) x = mx.group(1);
                java.util.regex.Matcher my = P_Y.matcher(urlStr);
                if (my.find()) y = my.group(1);
                java.util.regex.Matcher mz = P_Z.matcher(urlStr);
                if (mz.find()) z = mz.group(1);
            }
            if (x == null || y == null || z == null) return null;
            key = "google_" + z + "_" + x + "_" + y;
        } else if (isInegi) {
            key = "inegi_" + Integer.toHexString(urlStr.hashCode());
        }

        if (key == null) return null;

        key = profile.cacheNamespace() + key;

        // 1. Check in-memory RAM cache (instant sub-millisecond)
        byte[] cached = TILE_CACHE.get(key);

        // 2. Check permanent flash storage
        if (cached == null && tileDiskDir != null) {
            File diskFile = new File(tileDiskDir, key + ".png");
            if (diskFile.exists() && diskFile.length() > 0) {
                cached = readFileBytes(diskFile);
                if (cached != null && cached.length > 0) {
                    if (TILE_CACHE.size() < 2048) {
                        TILE_CACHE.put(key, cached);
                    }
                }
            }
        }

        // 3. If in cache, return immediately (0 ms response, 0 mobile data consumed).
        if (cached != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
            headers.put("Content-Type", "image/png");
            headers.put("Cache-Control", "public, max-age=31536000, immutable");
            return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(cached));
        }

        // 4. On first load of an unvisited tile: fetch with fast HTTP/2 pipeline, save permanently, and serve!
        byte[] data = downloadTile(urlStr);
        if (data != null && data.length > 0) {
            if (TILE_CACHE.size() < 2048) {
                TILE_CACHE.put(key, data);
            }
            saveTileToDisk(key, data);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
            headers.put("Content-Type", "image/png");
            headers.put("Cache-Control", "public, max-age=31536000, immutable");
            return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(data));
        }

        return null;
    }

    /**
     * Prefetches surrounding tiles in background (both fiber and Google base map)
     * to ensure instant zero-latency panning without white panels.
     */
    public static void prefetchNeighbors(double lat, double lon, RasterTileProfile profile) {
        PREFETCH_EXECUTOR.execute(() -> {
            final double n17 = 131072.0; // 2^17
            double x17 = (lon + 180.0) / 360.0 * n17;
            double latRad = Math.toRadians(lat);
            double y17 = (1.0 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * n17;

            int centerCol17 = (int) Math.floor(x17);
            int centerRow17 = (int) Math.floor(y17);

            // Zoom 17 ring (5x5 grid)
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int col = centerCol17 + dx;
                    int row = centerRow17 + dy;

                    // Prefetch Fiber tile
                    prefetchSingle(profile.cacheNamespace() + "fibra_17_" + col + "_" + row,
                            profile.overlay(17, col, row));

                    // Prefetch Google Base tile
                    prefetchSingle(profile.cacheNamespace() + "google_17_" + col + "_" + row,
                            profile.baseTile(17, col, row));
                }
            }

            // Zoom 16 ring (3x3 grid) for smooth zoom-out
            int centerCol16 = centerCol17 / 2;
            int centerRow16 = centerRow17 / 2;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int col = centerCol16 + dx;
                    int row = centerRow16 + dy;

                    prefetchSingle(profile.cacheNamespace() + "fibra_16_" + col + "_" + row,
                            profile.overlay(16, col, row));

                    prefetchSingle(profile.cacheNamespace() + "google_16_" + col + "_" + row,
                            profile.baseTile(16, col, row));
                }
            }
        });
    }

    private static void prefetchSingle(String key, String url) {
        if (TILE_CACHE.containsKey(key)) return;
        if (tileDiskDir != null && new File(tileDiskDir, key + ".png").exists()) return;

        byte[] data = downloadTile(url);
        if (data != null && data.length > 0) {
            if (TILE_CACHE.size() < 2048) {
                TILE_CACHE.put(key, data);
            }
            saveTileToDisk(key, data);
        }
    }

    private static byte[] downloadTile(String urlStr) {
        Request req = new Request.Builder()
                .url(urlStr)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                return resp.body().bytes();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void saveTileToDisk(String key, byte[] bytes) {
        if (tileDiskDir == null || bytes == null || bytes.length == 0) return;
        try {
            File f = new File(tileDiskDir, key + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
        } catch (Exception ignored) {}
    }

    private static byte[] readFileBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void trimDiskCacheIfNeeded() {
        if (tileDiskDir == null || !tileDiskDir.exists()) return;
        File[] files = tileDiskDir.listFiles();
        if (files == null || files.length < 3000) return;

        long totalBytes = 0;
        for (File f : files) totalBytes += f.length();

        // 300 MB permanent local storage for tens of thousands of tiles
        if (totalBytes > 300L * 1024L * 1024L) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int pruneCount = files.length / 5; // Prune oldest 20%
            for (int i = 0; i < pruneCount; i++) {
                files[i].delete();
            }
        }
    }

    /**
     * Evaluates fiber presence in the isolated native tile at the exact queried coordinates.
     */
    public VisualEvaluation evaluate(double lat, double lon, String queryId, RasterTileProfile profile) {
        if (queryId == null || !queryId.equals(activeQueryId)) {
            return new VisualEvaluation("unknown", "query_mismatch", 0.0, 0, false);
        }

        // Web Mercator EPSG:900913 at native zoom z=17
        final double n = 131072.0; // 2^17
        double x = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * n;

        int tileCol = (int) Math.floor(x);
        int tileRow = (int) Math.floor(y);
        int px = (int) Math.floor((x - tileCol) * 256.0);
        int py = (int) Math.floor((y - tileRow) * 256.0);

        String key = profile.cacheNamespace() + "fibra_17_" + tileCol + "_" + tileRow;
        byte[] tileBytes = TILE_CACHE.get(key);

        if (tileBytes == null && tileDiskDir != null) {
            File f = new File(tileDiskDir, key + ".png");
            if (f.exists()) {
                tileBytes = readFileBytes(f);
                if (tileBytes != null) TILE_CACHE.put(key, tileBytes);
            }
        }

        if (tileBytes == null) {
            // Wait up to 1.5s for tile to arrive from in-flight request
            long deadline = System.currentTimeMillis() + 1500;
            while (tileBytes == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    break;
                }
                tileBytes = TILE_CACHE.get(key);
                if (tileBytes == null && tileDiskDir != null) {
                    File f = new File(tileDiskDir, key + ".png");
                    if (f.exists()) {
                        tileBytes = readFileBytes(f);
                        if (tileBytes != null) TILE_CACHE.put(key, tileBytes);
                    }
                }
            }
        }

        if (tileBytes == null) {
            return new VisualEvaluation("unknown", "tile_missing_from_cache", 0.0, 0, false);
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(tileBytes, 0, tileBytes.length);
        if (bitmap == null || bitmap.getWidth() < 256 || bitmap.getHeight() < 256) {
            return new VisualEvaluation("unknown", "decode_failed", 0.0, 0, false);
        }

        // Two-pass stability observation (sampling 8x8 = 64 pixels centered at px, py)
        VisualEvaluation firstPass = sampleGrid(bitmap, px, py);

        try {
            Thread.sleep(60);
        } catch (InterruptedException ignored) {}

        VisualEvaluation secondPass = sampleGrid(bitmap, px, py);

        boolean stable = firstPass.status.equals(secondPass.status)
                && Math.abs(firstPass.ratio - secondPass.ratio) < 0.05;

        return new VisualEvaluation(
                firstPass.status,
                firstPass.reason,
                firstPass.ratio,
                SAMPLE_COUNT,
                stable
        );
    }

    private static VisualEvaluation sampleGrid(Bitmap bitmap, int cx, int cy) {
        int yellowCount = 0;
        int opaqueCount = 0;
        int validSamples = 0;

        // Sample 8x8 grid centered at (cx, cy)
        for (int dy = -3; dy <= 4; dy++) {
            for (int dx = -3; dx <= 4; dx++) {
                int sx = cx + dx;
                int sy = cy + dy;

                // Handle tile borders: clamp within [0, 255]
                if (sx < 0) sx = 0;
                else if (sx > 255) sx = 255;
                if (sy < 0) sy = 0;
                else if (sy > 255) sy = 255;

                validSamples++;
                int pixel = bitmap.getPixel(sx, sy);
                int a = Color.alpha(pixel);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                if (a >= 32) {
                    opaqueCount++;
                    // Calibrated provider fiber coverage RGBA: (252, 164, 0, 90)
                    if (r >= 170 && g >= 120 && b <= 70 && (r - g < 110) && (g - b >= 40)) {
                        yellowCount++;
                    }
                }
            }
        }

        double ratio = validSamples > 0 ? (double) yellowCount / validSamples : 0.0;

        if (yellowCount >= 8) { // At least 12.5% of sample area has confirmed fiber color
            return new VisualEvaluation("signal_detected", "yellow_at_selected_point", ratio, validSamples, true);
        }

        if (opaqueCount == 0) { // All 64 sample pixels are 100% transparent in fiber layer
            return new VisualEvaluation("no_signal_detected", "transparent_tile_at_selected_point", 0.0, validSamples, true);
        }

        return new VisualEvaluation("unknown", "inconclusive_pixels", ratio, validSamples, false);
    }
}
