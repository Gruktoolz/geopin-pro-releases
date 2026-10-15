package mx.coberturapunto;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import androidx.webkit.WebViewCompat;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Non-blocking event facade. Confirmed points retain their existing payload and filtering. */
public final class AppTelemetry {
    private static volatile TelemetryBackend backend=new JsonHttpTelemetryBackend(EndpointCatalog.telemetry(),EndpointCatalog.ingest());
    public static void setBackend(TelemetryBackend replacement){
        if(replacement==null)throw new IllegalArgumentException("Falta backend");
        backend=replacement;
    }
    private static final int QUEUE_CAPACITY = 50;
    private static final BlockingQueue<JSONObject> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GeoPinWorker");
        t.setDaemon(true);
        return t;
    });

    private static String installId;
    private static String appVersion = "1.0.0-pro";
    private static String androidVersion = "Android " + Build.VERSION.RELEASE;
    private static String webviewVersion = "unknown";

    private static volatile boolean appStartedSent = false;

    private AppTelemetry() {}

    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000L;
    private static String currentSessionId = null;

    public static synchronized void checkSession(Context context) {
        if (context == null) return;
        init(context);
        if (installId == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("geopin_prefs", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastActive = prefs.getLong("last_active_ms", 0);
        if (currentSessionId == null || (now - lastActive) >= SESSION_TIMEOUT_MS) {
            currentSessionId = UUID.randomUUID().toString();
            prefs.edit().putLong("last_active_ms", now).putString("last_session_id", currentSessionId).apply();
            try {
                JSONObject payload = new JSONObject();
                payload.put("install_id", installId);
                payload.put("session_id", currentSessionId);
                payload.put("app_version", appVersion);
                payload.put("android_version", androidVersion);
                payload.put("timestamp", java.time.Instant.ofEpochMilli(now).toString());
                enqueue("app_session", payload);
            } catch (Exception ignored) {}
        } else {
            prefs.edit().putLong("last_active_ms", now).apply();
        }
    }


    public static synchronized void init(Context context) {
        if (installId != null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("geopin_prefs", Context.MODE_PRIVATE);
        installId = prefs.getString("install_id", null);
        if (installId == null) {
            installId = UUID.randomUUID().toString();
            prefs.edit().putString("install_id", installId).apply();
        }
        try {
            PackageInfo p = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            if (p != null && p.versionName != null) appVersion = p.versionName;
        } catch (Exception ignored) {}
        try {
            PackageInfo w = WebViewCompat.getCurrentWebViewPackage(app);
            if (w != null && w.versionName != null) webviewVersion = w.versionName;
        } catch (Exception ignored) {}
    }

    public static void appStarted(Context context) {
        if (appStartedSent) return;
        init(context);
        if (installId == null) return;
        appStartedSent = true;
        try {
            JSONObject payload = new JSONObject();
            payload.put("install_id", installId);
            payload.put("app_version", appVersion);
            payload.put("android_version", androidVersion);
            payload.put("webview_version", webviewVersion);
            enqueue("app_started", payload);
        } catch (Exception ignored) {}
    }

    public static void locationQuery(String source) {
        // Consolidated into locationReady/locationError to prevent multiple updates in Google Sheets per query
    }

    public static void locationResolved(String source, String city, double lat, double lon) {
        // Consolidated into locationReady/locationError to prevent multiple updates in Google Sheets per query
    }

    public static void locationReadyYellow(String queryId, double lat, double lon, String source,
                                           String city, long totalMs, long colonyMs, long tilesMs,
                                           String mapState, RasterVisualDetector.VisualEvaluation eval) {
        if (installId == null || eval == null || !eval.isYellow()) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("install_id", installId);
            payload.put("query_id", queryId != null ? queryId : UUID.randomUUID().toString());
            payload.put("query_lat", lat);
            payload.put("query_lon", lon);
            payload.put("source", sanitizeSource(source));
            payload.put("map_state", sanitizeState(mapState));
            payload.put("visual_status", "signal_detected");
            payload.put("visual_detector", RasterVisualDetector.DETECTOR_NAME);
            payload.put("visual_stable", true);
            payload.put("visual_layer", RasterVisualDetector.LAYER_NAME);
            payload.put("visual_samples", eval.samples);
            payload.put("visual_ratio", Math.round(eval.ratio * 1000.0) / 1000.0);
            payload.put("visual_reason", eval.reason);
            payload.put("app_version", appVersion);

            payload.put("city", sanitizeText(city));
            payload.put("coarse_lat", Math.round(lat * 100.0) / 100.0);
            payload.put("coarse_lon", Math.round(lon * 100.0) / 100.0);
            payload.put("total_ms", Math.max(0, totalMs));
            payload.put("colony_ms", Math.max(0, colonyMs));
            payload.put("tiles_ms", Math.max(0, tilesMs));
            payload.put("android_version", androidVersion);
            payload.put("webview_version", webviewVersion);

            enqueue("location_ready", payload);
        } catch (Exception ignored) {}
    }

    public static void whiteConfirmed(RasterVisualDetector.VisualEvaluation eval) {
        if (installId == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("install_id", installId);
            payload.put("app_version", appVersion);
            payload.put("map_state", "ready");
            payload.put("visual_status", "no_signal_detected");
            payload.put("visual_detector", RasterVisualDetector.DETECTOR_NAME);
            payload.put("visual_stable", true);
            payload.put("visual_layer", RasterVisualDetector.LAYER_NAME);
            payload.put("visual_samples", eval != null ? eval.samples : 64);
            payload.put("visual_ratio", 0.0);
            payload.put("visual_reason", eval != null ? eval.reason : "transparent_tile_at_selected_point");
            // No coordinates (query_lat, query_lon, coarse_lat, coarse_lon) or city sent for white
            enqueue("location_ready", payload);
        } catch (Exception ignored) {}
    }

    public static void locationIndeterminate(RasterVisualDetector.VisualEvaluation eval) {
        if (installId == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("install_id", installId);
            payload.put("app_version", appVersion);
            payload.put("map_state", "unknown");
            payload.put("visual_status", "unknown");
            payload.put("visual_detector", RasterVisualDetector.DETECTOR_NAME);
            payload.put("visual_stable", false);
            payload.put("visual_layer", RasterVisualDetector.LAYER_NAME);
            payload.put("visual_samples", eval != null ? eval.samples : 64);
            payload.put("visual_ratio", eval != null ? Math.round(eval.ratio * 1000.0) / 1000.0 : 0.0);
            payload.put("visual_reason", eval != null ? eval.reason : "inconclusive_pixels");
            // Zero coordinates or locations sent for indeterminate
            enqueue("location_ready", payload);
        } catch (Exception ignored) {}
    }

    public static void locationReady(String source, String city, double lat, double lon,
                                     long totalMs, long colonyMs, long tilesMs, String mapState) {
        // Retained for backward compatibility; visual filter delegates via locationReadyYellow
    }

    public static void locationError(String stage, String source, String city, Double lat, Double lon) {
        if (installId == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("install_id", installId);
            payload.put("stage", sanitizeStage(stage));
            payload.put("source", sanitizeSource(source));
            payload.put("city", sanitizeText(city));
            if (lat != null && lon != null) {
                payload.put("coarse_lat", Math.round(lat * 100.0) / 100.0);
                payload.put("coarse_lon", Math.round(lon * 100.0) / 100.0);
            }
            enqueue("location_error", payload);
        } catch (Exception ignored) {}
    }

    public static void screenshotSaved(boolean success, long totalMs) {
        if (installId == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("install_id", installId);
            payload.put("success", success);
            payload.put("total_ms", Math.max(0, totalMs));
            enqueue("screenshot_saved", payload);
        } catch (Exception ignored) {}
    }

    private static void enqueue(String eventName, JSONObject payload) {
        if (!backend.enabled()) return;
        try {
            JSONObject root = new JSONObject();
            root.put("event", eventName);
            root.put("payload", payload);
            while (!QUEUE.offer(root)) {
                QUEUE.poll(); // Evict oldest event if queue is full
            }
            WORKER.execute(AppTelemetry::dispatch);
        } catch (Exception ignored) {}
    }

    private static void dispatch() {
        JSONObject item;
        while((item=QUEUE.poll())!=null){
            try{backend.send(item);}catch(Exception ignored){/* Same best-effort delivery; no endpoint or payload logs. */}
        }
    }

    private static String sanitizeSource(String s) {
        if (s == null) return "other";
        switch (s) {
            case "shared_maps":
            case "geo_uri":
            case "coordinates":
            case "search":
            case "fix_center":
                return s;
            default:
                return "other";
        }
    }

    private static String sanitizeStage(String s) {
        if (s == null) return "other";
        switch (s) {
            case "geocoder":
            case "mexico_validation":
            case "geoinvmex":
            case "geoinvcol":
            case "wmts":
            case "timeout":
            case "webview":
                return s;
            default:
                return "other";
        }
    }

    private static String sanitizeState(String s) {
        if ("ready".equals(s) || "unknown".equals(s) || "error".equals(s)) return s;
        return "unknown";
    }

    private static String sanitizeText(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("=") || t.startsWith("+") || t.startsWith("-") || t.startsWith("@")) {
            t = "'" + t; // Prevent formula injection
        }
        return t.length() > 64 ? t.substring(0, 64) : t;
    }
}
