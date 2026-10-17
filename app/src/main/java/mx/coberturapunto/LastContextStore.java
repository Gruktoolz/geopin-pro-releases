package mx.coberturapunto;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Automatically persists and restores the user's active work zone context.
 * Enables the map to open directly on the last used work zone upon cold start.
 */
public final class LastContextStore {
    private static final String PREF_NAME = "work_zone_context";
    private static final String KEY_LAT = "last_lat";
    private static final String KEY_LON = "last_lon";
    private static final String KEY_CITY = "last_city";
    private static final String KEY_ZOOM = "last_zoom";
    private static final String KEY_TIME = "last_time";

    private LastContextStore() {}

    public static void save(Context context, double lat, double lon, String city, int zoom) {
        if (context == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putFloat(KEY_LAT, (float) lat)
                .putFloat(KEY_LON, (float) lon)
                .putString(KEY_CITY, city != null ? city : "")
                .putInt(KEY_ZOOM, zoom > 0 ? zoom : 17)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply();
    }

    public static LocationInput.Point getLastLocation(Context context) {
        if (context == null) return null;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (!sp.contains(KEY_LAT) || !sp.contains(KEY_LON)) {
            // Check if ZoneManager has an automatic habitual zone
            ZoneManager zm = RasterVisualDetector.getZoneManager();
            if (zm != null) {
                WorkZone lastZone = zm.getLastUsedZone();
                if (lastZone != null) {
                    return new LocationInput.Point(lastZone.lastCenterLat, lastZone.lastCenterLon);
                }
            }
            return null;
        }
        double lat = sp.getFloat(KEY_LAT, 0f);
        double lon = sp.getFloat(KEY_LON, 0f);
        if (lat == 0f && lon == 0f) return null;
        return new LocationInput.Point(lat, lon);
    }

    public static String getLastCity(Context context) {
        if (context == null) return "";
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_CITY, "");
    }

    public static int getLastZoom(Context context) {
        if (context == null) return 17;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_ZOOM, 17);
    }
}
