package mx.coberturapunto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified single-source storage engine for GeoPin Pro.
 * Reused identically by:
 * - Render (WebView interceptor)
 * - Coverage Evaluator (pixel analyzer)
 * - Viewport coordinator
 * - Point queries
 * - Automatic work zone sync
 * - Complete offline operation
 *
 * Implements strict memory tiers:
 * - PINNED: Critical fixed-point tile and immediate neighbors (never evicted during session).
 * - HOT: Active visible viewport tiles in RAM.
 * - WARM: Preceding viewport tiles retained across pan.
 * - COLD: Rest of zone persisted on disk.
 *
 * Enforces atomic file updates (OLD VALID -> temp -> validated -> NEW VALID)
 * and visual pyramid hierarchy (mosaics and scaled parents marked strictly as isFallback=true).
 */
public final class TileStorage {

    public static final class StoredTile {
        public final byte[] data;
        public final long timestamp;
        public final long lastValidated;
        public final long ttlMillis;
        public final String etag;
        public final String lastModified;
        public final boolean isFallback;
        public final int sourceZoom;

        public StoredTile(byte[] data, long timestamp, long lastValidated, long ttlMillis,
                          String etag, String lastModified, boolean isFallback, int sourceZoom) {
            this.data = data;
            this.timestamp = timestamp;
            this.lastValidated = lastValidated;
            this.ttlMillis = ttlMillis;
            this.etag = etag;
            this.lastModified = lastModified;
            this.isFallback = isFallback;
            this.sourceZoom = sourceZoom;
        }

        public boolean isExpired() {
            if (ttlMillis <= 0) return false;
            return System.currentTimeMillis() > (lastValidated + ttlMillis);
        }
    }

    private static final int RAM_HOT_MAX_TILES = 120;
    private static final int RAM_WARM_MAX_TILES = 120;
    private static final long DEFAULT_DISK_BUDGET_BYTES = 128L * 1024 * 1024; // 128 MB

    private final File diskDir;

    // Memory Tiers:
    // PINNED: Never evicted by LRU during session
    private final Set<String> pinnedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, StoredTile> pinnedRam = new ConcurrentHashMap<>();

    // HOT: Current visible viewport in RAM
    private final LruCache<String, StoredTile> hotRamCache = new LruCache<String, StoredTile>(RAM_HOT_MAX_TILES) {
        @Override
        protected void entryRemoved(boolean evicted, String key, StoredTile oldValue, StoredTile newValue) {
            if (evicted && oldValue != null && !pinnedKeys.contains(key)) {
                warmRamCache.put(key, oldValue);
            }
        }
    };

    // WARM: Preceding viewport in RAM
    private final LruCache<String, StoredTile> warmRamCache = new LruCache<>(RAM_WARM_MAX_TILES);

    public TileStorage(Context context) {
        File baseDir = context != null ? context.getFilesDir() : new File(System.getProperty("java.io.tmpdir", "/tmp"));
        this.diskDir = new File(baseDir, "unified_tiles_v1");
        if (!diskDir.exists()) diskDir.mkdirs();
    }

    public TileStorage(File customDir) {
        this.diskDir = customDir;
        if (!diskDir.exists()) diskDir.mkdirs();
    }

    public synchronized void pinKey(String key) {
        if (key == null) return;
        pinnedKeys.add(key);
        StoredTile t = hotRamCache.get(key);
        if (t == null) t = warmRamCache.get(key);
        if (t != null) pinnedRam.put(key, t);
    }

    public synchronized void unpinKey(String key) {
        if (key == null) return;
        pinnedKeys.remove(key);
        pinnedRam.remove(key);
    }

    public synchronized void pinPoint(int z, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                String k = z + "_" + (x + dx) + "_" + (y + dy);
                pinnedKeys.add(k);
            }
        }
    }

    public synchronized StoredTile get(TileSpec spec) {
        if (spec == null) return null;
        String key = spec.cacheKey();

        // 1. PINNED tier check
        if (pinnedKeys.contains(key) && pinnedRam.containsKey(key)) {
            return pinnedRam.get(key);
        }

        // 2. HOT tier check
        StoredTile hot = hotRamCache.get(key);
        if (hot != null) return hot;

        // 3. WARM tier check (promote back to HOT)
        StoredTile warm = warmRamCache.get(key);
        if (warm != null) {
            warmRamCache.remove(key);
            hotRamCache.put(key, warm);
            return warm;
        }

        // 4. COLD disk tier check
        File targetFile = new File(diskDir, key + ".png");
        if (isValidTileFile(targetFile)) {
            StoredTile diskTile = readTileFromDisk(targetFile, key, spec.ttlMillis);
            if (diskTile != null) {
                hotRamCache.put(key, diskTile);
                if (pinnedKeys.contains(key)) {
                    pinnedRam.put(key, diskTile);
                }
                return diskTile;
            }
        }

        return null;
    }

    /**
     * Atomic put: writes to temporary file, validates PNG integrity,
     * and performs atomic swap to guarantee zero corrupted or empty slots.
     */
    public synchronized void put(TileSpec spec, byte[] data, String etag, String lastModified) {
        if (spec == null || data == null || data.length < 32 || !isPng(data)) {
            return;
        }

        String key = spec.cacheKey();
        long now = System.currentTimeMillis();
        StoredTile tile = new StoredTile(data, now, now, spec.ttlMillis, etag, lastModified, false, spec.z);

        // Put in RAM
        hotRamCache.put(key, tile);
        if (pinnedKeys.contains(key)) {
            pinnedRam.put(key, tile);
        }

        // Put in Disk with Atomic Swap (OLD VALID -> temp -> validated -> NEW VALID)
        saveTileToDiskAtomic(key, tile);
    }

    public synchronized void markValidated(TileSpec spec, String etag, String lastModified) {
        if (spec == null) return;
        String key = spec.cacheKey();
        StoredTile existing = get(spec);
        if (existing != null) {
            long now = System.currentTimeMillis();
            StoredTile updated = new StoredTile(existing.data, existing.timestamp, now,
                    existing.ttlMillis, etag != null ? etag : existing.etag,
                    lastModified != null ? lastModified : existing.lastModified,
                    existing.isFallback, existing.sourceZoom);
            hotRamCache.put(key, updated);
            if (pinnedKeys.contains(key)) pinnedRam.put(key, updated);

            File metaFile = new File(diskDir, key + ".meta");
            writeMetadata(metaFile, updated);
        }
    }

    /**
     * Requirement: Visual Pyramid & Fallback.
     * Scale a parent tile (z-1 or z-2) or regional mosaic strictly as visual background.
     * MUST ALWAYS return isFallback = true to protect CoverageEvaluator.
     */
    public synchronized StoredTile getVisualFallback(TileSpec spec) {
        if (spec == null || spec.z <= 5) return null;

        // Try z-1
        StoredTile fb = getParentFallback(spec, spec.z - 1);
        if (fb != null) return fb;

        // Try z-2
        if (spec.z > 6) {
            fb = getParentFallback(spec, spec.z - 2);
            if (fb != null) return fb;
        }

        // Regional mosaic for far zooms (z <= 14)
        if (spec.z <= 14) {
            return getRegionalMosaicFallback(spec);
        }

        return null;
    }

    private StoredTile getParentFallback(TileSpec child, int parentZ) {
        int zoomDiff = child.z - parentZ;
        if (zoomDiff <= 0 || zoomDiff > 2) return null;

        int parentX = child.x >> zoomDiff;
        int parentY = child.y >> zoomDiff;

        TileSpec parent = child.asZoom(parentZ, parentX, parentY);
        StoredTile parentTile = get(parent);
        if (parentTile == null || parentTile.data == null || parentTile.data.length < 32) return null;

        try {
            Bitmap src = BitmapFactory.decodeByteArray(parentTile.data, 0, parentTile.data.length);
            if (src == null) return null;

            int factor = 1 << zoomDiff;
            int subSize = 256 / factor;
            int subX = (child.x % factor) * subSize;
            int subY = (child.y % factor) * subSize;

            if (subX + subSize > src.getWidth() || subY + subSize > src.getHeight()) {
                src.recycle();
                return null;
            }

            Bitmap cropped = Bitmap.createBitmap(src, subX, subY, subSize, subSize);
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] fallbackBytes = baos.toByteArray();

            src.recycle();
            if (cropped != scaled) cropped.recycle();
            scaled.recycle();

            return new StoredTile(fallbackBytes, parentTile.timestamp, parentTile.lastValidated,
                    parentTile.ttlMillis, null, null, true, parent.z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private StoredTile getRegionalMosaicFallback(TileSpec spec) {
        // Compose or fetch 4x4 parent tile as a regional mosaic
        TileSpec regionalSpec = spec.asZoom(Math.max(5, spec.z - 3), spec.x >> 3, spec.y >> 3);
        StoredTile reg = get(regionalSpec);
        if (reg != null) {
            return new StoredTile(reg.data, reg.timestamp, reg.lastValidated, reg.ttlMillis, null, null, true, regionalSpec.z);
        }
        return null;
    }

    private boolean isPng(byte[] data) {
        return data.length >= 8 &&
                data[0] == (byte) 0x89 &&
                data[1] == (byte) 'P' &&
                data[2] == (byte) 'N' &&
                data[3] == (byte) 'G' &&
                data[4] == (byte) 0x0D &&
                data[5] == (byte) 0x0A &&
                data[6] == (byte) 0x1A &&
                data[7] == (byte) 0x0A;
    }

    private boolean isValidTileFile(File f) {
        return f.exists() && f.length() >= 32;
    }

    private void saveTileToDiskAtomic(String key, StoredTile tile) {
        if (!diskDir.exists()) diskDir.mkdirs();

        File tempFile = new File(diskDir, key + ".tmp_" + System.nanoTime());
        File targetFile = new File(diskDir, key + ".png");
        File metaFile = new File(diskDir, key + ".meta");

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(tile.data);
            fos.flush();
            tempFile.renameTo(targetFile);
            writeMetadata(metaFile, tile);
        } catch (IOException ignored) {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private StoredTile readTileFromDisk(File file, String key, long defaultTtl) {
        byte[] data = readFileBytes(file);
        if (data == null || data.length < 32 || !isPng(data)) {
            file.delete(); // Delete corrupt file
            return null;
        }

        File metaFile = new File(diskDir, key + ".meta");
        long timestamp = file.lastModified();
        long lastValidated = timestamp;
        long ttl = defaultTtl;
        String etag = null;
        String lastModified = null;

        if (metaFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(metaFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ts=")) timestamp = Long.parseLong(line.substring(3).trim());
                    else if (line.startsWith("lv=")) lastValidated = Long.parseLong(line.substring(3).trim());
                    else if (line.startsWith("ttl=")) ttl = Long.parseLong(line.substring(4).trim());
                    else if (line.startsWith("etag=")) etag = line.substring(5).trim();
                    else if (line.startsWith("lm=")) lastModified = line.substring(3).trim();
                }
            } catch (Exception ignored) {}
        }

        return new StoredTile(data, timestamp, lastValidated, ttl, etag, lastModified, false, 0);
    }

    private void writeMetadata(File metaFile, StoredTile tile) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(metaFile))) {
            pw.println("ts=" + tile.timestamp);
            pw.println("lv=" + tile.lastValidated);
            pw.println("ttl=" + tile.ttlMillis);
            if (tile.etag != null) pw.println("etag=" + tile.etag);
            if (tile.lastModified != null) pw.println("lm=" + tile.lastModified);
        } catch (Exception ignored) {}
    }

    private byte[] readFileBytes(File file) {
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

    public static final class StoredWebAsset {
        public final String mimeType;
        public final byte[] data;
        public StoredWebAsset(String mimeType, byte[] data) {
            this.mimeType = mimeType;
            this.data = data;
        }
    }

    private static String hashKey(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public synchronized void putWebAsset(String url, String mimeType, byte[] data) {
        if (url == null || data == null || data.length == 0) return;
        if (!diskDir.exists()) diskDir.mkdirs();
        String hash = hashKey(url);
        File f = new File(diskDir, "web_" + hash + ".dat");
        File meta = new File(diskDir, "web_" + hash + ".meta");
        try {
            File temp = new File(diskDir, "web_" + hash + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                fos.write(data);
                fos.flush();
            }
            temp.renameTo(f);

            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put(url, url);
            obj.put(mimeType, mimeType);
            obj.put("ts", System.currentTimeMillis());
            try (FileWriter fw = new FileWriter(meta)) {
                fw.write(obj.toString());
            }
        } catch (Exception ignored) {}
    }

    public synchronized StoredWebAsset getWebAsset(String url) {
        if (url == null) return null;
        String hash = hashKey(url);
        File f = new File(diskDir, "web_" + hash + ".dat");
        File meta = new File(diskDir, "web_" + hash + ".meta");
        if (!f.exists() || !meta.exists()) return null;
        try {
            String metaStr;
            try (BufferedReader br = new BufferedReader(new FileReader(meta))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) sb.append(l);
                metaStr = sb.toString();
            }
            org.json.JSONObject obj = new org.json.JSONObject(metaStr);
            String mime = obj.optString("mimeType", "text/html");
            byte[] data = readFileBytes(f);
            if (data != null && data.length > 0) {
                return new StoredWebAsset(mime, data);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public synchronized void trimDiskCacheIfNeeded(long maxBytes) {
        if (!diskDir.exists()) return;
        File[] files = diskDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null || files.length < 500) return;

        long total = 0;
        List<File> prunable = new ArrayList<>();
        for (File f : files) {
            total += f.length();
            String name = f.getName().replace(".png", "");
            if (!pinnedKeys.contains(name)) {
                prunable.add(f);
            }
        }

        long budget = maxBytes > 0 ? maxBytes : DEFAULT_DISK_BUDGET_BYTES;
        if (total > budget) {
            prunable.sort(Comparator.comparingLong(File::lastModified));
            int toDelete = Math.min(prunable.size(), files.length / 5);
            for (int i = 0; i < toDelete; i++) {
                File f = prunable.get(i);
                String metaName = f.getName().replace(".png", ".meta");
                f.delete();
                new File(diskDir, metaName).delete();
            }
        }
    }
}
