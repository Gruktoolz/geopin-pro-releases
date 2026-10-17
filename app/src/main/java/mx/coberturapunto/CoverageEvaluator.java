package mx.coberturapunto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

/**
 * Pure coverage evaluation engine.
 * Never performs network requests or disk caching on its own.
 * Takes original tile bytes and queried coordinates, evaluating pixel colors
 * using the calibrated provider fiber coverage thresholds.
 * Distinguishes confirmed coverage from missing, expired, fallback or corrupted data.
 */
public final class CoverageEvaluator {

    public static final String DETECTOR_NAME = "isolated_yellow_v1";
    public static final String LAYER_NAME = "fibra";
    public static final int SAMPLE_COUNT = 64;

    public static final class EvaluationResult {
        public final String status;      // "signal_detected", "no_signal_detected", "data_missing_or_pending", "data_expired", "out_of_bounds", "unknown"
        public final String reason;      // descriptive reason
        public final double ratio;       // ratio of yellow pixels (0.0 to 1.0)
        public final int samples;        // count of evaluated samples (e.g. 64)
        public final boolean stable;     // true if confirmed across observations
        public final boolean isSavedData;// true if retrieved from local offline cache/zone
        public final long tileTimestamp; // timestamp when tile was acquired
        public final int ageDays;        // age in days
        public final boolean isFallback; // true if this is an upscaled parent tile

        public EvaluationResult(String status, String reason, double ratio, int samples,
                                boolean stable, boolean isSavedData, long tileTimestamp,
                                int ageDays, boolean isFallback) {
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
            return "data_missing_or_pending".equals(status) || isFallback;
        }
    }

    /**
     * Pure function to evaluate coverage from raw tile bytes and coordinates.
     */
    public static EvaluationResult evaluate(byte[] tileBytes, double lat, double lon,
                                            int z, int tileCol, int tileRow,
                                            boolean isSavedData, long tileTimestamp,
                                            boolean isFallback) {
        if (tileBytes == null || tileBytes.length < 32) {
            return new EvaluationResult("data_missing_or_pending", "tile_not_available", 0.0, 0,
                    false, isSavedData, tileTimestamp, 0, false);
        }

        // An upscaled visual fallback tile serves for visual continuity ONLY.
        // It must NEVER be used to assert precise coverage!
        if (isFallback) {
            return new EvaluationResult("data_missing_or_pending", "visual_fallback_not_precise", 0.0, 0,
                    false, isSavedData, tileTimestamp, 0, true);
        }

        // Geographic boundaries of provider (Mexico)
        if (!ZoneGeometry.MEXICO_SCOPE.contains(lat, lon)) {
            return new EvaluationResult("out_of_bounds", "outside_provider_coverage_area", 0.0, 0,
                    true, isSavedData, tileTimestamp, 0, false);
        }

        // Calculate exact pixel offset within this tile
        final double n = 1 << z;
        double x = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * n;

        int px = (int) Math.floor((x - tileCol) * 256.0);
        int py = (int) Math.floor((y - tileRow) * 256.0);

        if (px < 0 || px >= 256 || py < 0 || py >= 256) {
            return new EvaluationResult("out_of_bounds", "point_outside_tile_bounds", 0.0, 0,
                    false, isSavedData, tileTimestamp, 0, false);
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(tileBytes, 0, tileBytes.length);
        if (bitmap == null || bitmap.getWidth() < 256 || bitmap.getHeight() < 256) {
            return new EvaluationResult("corrupted_or_error", "tile_decode_failed", 0.0, 0,
                    false, isSavedData, tileTimestamp, 0, false);
        }

        try {
            int ageDays = (int) ((System.currentTimeMillis() - tileTimestamp) / (24L * 3600 * 1000));
            return sampleGrid(bitmap, px, py, isSavedData, tileTimestamp, ageDays);
        } finally {
            bitmap.recycle();
        }
    }

    private static EvaluationResult sampleGrid(Bitmap bitmap, int cx, int cy,
                                               boolean isSavedData, long tileTimestamp, int ageDays) {
        int yellowCount = 0;
        int opaqueCount = 0;
        int validSamples = 0;

        // Sample 8x8 grid centered at (cx, cy)
        for (int dy = -3; dy <= 4; dy++) {
            for (int dx = -3; dx <= 4; dx++) {
                int sx = cx + dx;
                int sy = cy + dy;

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
            return new EvaluationResult("signal_detected", "yellow_at_selected_point", ratio, validSamples,
                    true, isSavedData, tileTimestamp, ageDays, false);
        }

        if (opaqueCount == 0) { // All 64 sample pixels are 100% transparent in fiber layer
            return new EvaluationResult("no_signal_detected", "transparent_tile_at_selected_point", 0.0, validSamples,
                    true, isSavedData, tileTimestamp, ageDays, false);
        }

        return new EvaluationResult("unknown", "inconclusive_pixels", ratio, validSamples,
                false, isSavedData, tileTimestamp, ageDays, false);
    }
}
