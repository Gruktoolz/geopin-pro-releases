package mx.coberturapunto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;

/** Exercises production storage and evaluation on Android, without provider traffic. */
final class OfflineEngineChecks {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static TileSpec spec(int z, int x, int y) {
        return new TileSpec("fixture", "fibra", "v1", "EPSG:900913", 256,
                z, x, y, "https://fixture.invalid/tile", 604800000, true, false);
    }
    static String run(Context context) throws Exception {
        File root = new File(context.getCacheDir(), "offline-check-" + System.nanoTime());
        File dir = new File(root, "unified_tiles_v1");
        TileStorage store = new TileStorage(dir);
        Bitmap image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.argb(90, 252, 164, 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
        byte[] png = out.toByteArray();
        TileSpec tile = spec(17, 22923, 52888);
        store.put(tile, png, "v1", "yesterday");
        File legacyDir = new File(root, "tiles_storage");
        check(legacyDir.mkdirs(), "Create isolated old cache");
        TileSpec legacy = new TileSpec("coverage-v1", "fibra", "v1", "EPSG:900913", 256,
                17, 5, 5, "", 604800000, true, false);
        File legacyFile = new File(legacyDir, legacy.legacyKey() + ".png");
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(legacyFile)) {file.write(png);}
        check(store.get(legacy) != null && legacyFile.exists(), "Legacy cache imported without deleting old data");
        TileStorage reopened = new TileStorage(dir);
        check(Arrays.equals(png, reopened.get(tile).data), "Disk survives storage recreation");
        store.put(tile, new byte[100], null, null);
        check(Arrays.equals(png, new TileStorage(dir).get(tile).data), "Invalid replacement preserves disk");
        reopened.markValidated(tile, "v2", null);
        check("v2".equals(new TileStorage(dir).get(tile).etag), "304 metadata persists");
        reopened.putWebAsset("https://fixture.invalid/", "application/javascript", "const ok=1;".getBytes());
        check("application/javascript".equals(new TileStorage(dir).getWebAsset("https://fixture.invalid/").mimeType), "Web MIME persists");
        TileStorage.StoredTile fallback = reopened.getVisualFallback(spec(18, tile.x * 2, tile.y * 2));
        check(fallback != null && fallback.isFallback, "Parent fallback marked");
        check(CoverageEvaluator.evaluate(fallback.data, 32, -117, 18, tile.x * 2, tile.y * 2,
                true, fallback.timestamp, true).isDataMissing(), "Fallback cannot assert coverage");
        reopened.pinPoint(tile);
        for (int i = 0; i < 260; i++) reopened.put(spec(17, i, 1), png, null, null);
        File pinned = new File(dir, tile.cacheKey() + ".png");
        check(pinned.delete(), "Remove test disk copy to distinguish RAM pinning");
        check(reopened.get(tile) != null, "Selected point survives both RAM LRU tiers");
        out.reset();
        image.compress(Bitmap.CompressFormat.JPEG, 80, out);
        TileSpec jpeg = spec(12, 1, 1);
        reopened.put(jpeg, out.toByteArray(), null, null);
        check(new TileStorage(dir).get(jpeg) != null, "JPEG base tiles persist");
        image.recycle();
        NetworkQualityMonitor monitor = new NetworkQualityMonitor();
        check(!monitor.isAggressiveDownloadAllowed(), "Unknown transport fails closed");
        monitor.setWifiConnected(false);
        check(!monitor.isPrefetchAllowed(), "Cellular blocks speculative downloads");
        monitor.setWifiConnected(true);
        monitor.setMetered(false);
        monitor.simulateSpeed(2750);
        check(!monitor.isAggressiveDownloadAllowed(), "22 kbps blocks zone sync");
        monitor.simulateSpeed(250000);
        check(monitor.isAggressiveDownloadAllowed(), "Healthy unmetered Wi-Fi permits zone sync");
        return "PASS:production-storage-legacy-import-reopen-invalid-write-304-metadata-web-mime-parent-fallback-pinned-RAM-JPEG-network-policy";
    }
}
