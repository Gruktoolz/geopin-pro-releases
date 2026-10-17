package mx.coberturapunto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.*;

/**
 * Persisted work zone representation.
 * Automatically tracks bounds, center, zoom, usage recency, layers, and version.
 * Supports persistent retention of habitual work zones.
 */
public final class WorkZone implements Serializable {
    private static final long serialVersionUID = 2L;

    public enum Status {
        IDLE,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        ERROR
    }

    public final String id;
    public final String name;
    public final ZoneGeometry geometry;
    public double lastCenterLat;
    public double lastCenterLon;
    public int lastZoom;
    public long lastUsed;
    public final List<String> availableLayers;
    public final int[] zoomLevels;
    public String dataVersion;
    public long createdAt;
    public long lastValidatedAt;
    public Status status;

    public int totalTiles;
    public int completedTiles;
    public int failedTiles;
    public int expiredTiles;

    public WorkZone(String id, String name, ZoneGeometry geometry, int[] zoomLevels) {
        this.id = id;
        this.name = name;
        this.geometry = geometry;
        this.zoomLevels = zoomLevels != null ? zoomLevels.clone() : new int[]{15, 16, 17};
        this.availableLayers = new ArrayList<>(Arrays.asList("base", "fibra", "hfc"));
        this.lastCenterLat = (geometry.minLat + geometry.maxLat) / 2.0;
        this.lastCenterLon = (geometry.minLon + geometry.maxLon) / 2.0;
        this.lastZoom = 17;
        this.createdAt = System.currentTimeMillis();
        this.lastUsed = this.createdAt;
        this.lastValidatedAt = this.createdAt;
        this.dataVersion = "megacable_v1";
        this.status = Status.IDLE;
    }

    public void updateUsage(double lat, double lon, int zoom) {
        this.lastCenterLat = lat;
        this.lastCenterLon = lon;
        if (zoom > 0) this.lastZoom = zoom;
        this.lastUsed = System.currentTimeMillis();
    }

    public long getEstimatedSizeBytes() {
        return totalTiles * 18_432L;
    }

    public int getProgressPercent() {
        if (totalTiles <= 0) return 0;
        return (int) Math.min(100, (completedTiles * 100L) / totalTiles);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("geometry", geometry.toJson());
        obj.put("lastCenterLat", lastCenterLat);
        obj.put("lastCenterLon", lastCenterLon);
        obj.put("lastZoom", lastZoom);
        obj.put("lastUsed", lastUsed);
        JSONArray layers = new JSONArray();
        for (String l : availableLayers) layers.put(l);
        obj.put("availableLayers", layers);
        JSONArray zooms = new JSONArray();
        for (int z : zoomLevels) zooms.put(z);
        obj.put("zoomLevels", zooms);
        obj.put("dataVersion", dataVersion != null ? dataVersion : "v1");
        obj.put("createdAt", createdAt);
        obj.put("lastValidatedAt", lastValidatedAt);
        obj.put("status", status.name());
        obj.put("totalTiles", totalTiles);
        obj.put("completedTiles", completedTiles);
        obj.put("failedTiles", failedTiles);
        obj.put("expiredTiles", expiredTiles);
        return obj;
    }

    public static WorkZone fromJson(JSONObject obj) throws JSONException {
        String id = obj.getString("id");
        String name = obj.getString("name");
        ZoneGeometry geo = ZoneGeometry.fromJson(obj.getJSONObject("geometry"));
        JSONArray zooms = obj.optJSONArray("zoomLevels");
        int[] zLevels;
        if (zooms != null) {
            zLevels = new int[zooms.length()];
            for (int i = 0; i < zooms.length(); i++) zLevels[i] = zooms.getInt(i);
        } else {
            zLevels = new int[]{15, 16, 17};
        }
        WorkZone zone = new WorkZone(id, name, geo, zLevels);
        zone.lastCenterLat = obj.optDouble("lastCenterLat", zone.lastCenterLat);
        zone.lastCenterLon = obj.optDouble("lastCenterLon", zone.lastCenterLon);
        zone.lastZoom = obj.optInt("lastZoom", 17);
        zone.lastUsed = obj.optLong("lastUsed", zone.createdAt);
        zone.dataVersion = obj.optString("dataVersion", "megacable_v1");
        zone.status = Status.valueOf(obj.optString("status", Status.IDLE.name()));
        zone.lastValidatedAt = obj.optLong("lastValidatedAt", zone.createdAt);
        zone.totalTiles = obj.optInt("totalTiles", 0);
        zone.completedTiles = obj.optInt("completedTiles", 0);
        zone.failedTiles = obj.optInt("failedTiles", 0);
        zone.expiredTiles = obj.optInt("expiredTiles", 0);

        JSONArray layersArr = obj.optJSONArray("availableLayers");
        if (layersArr != null) {
            zone.availableLayers.clear();
            for (int i = 0; i < layersArr.length(); i++) {
                zone.availableLayers.add(layersArr.getString(i));
            }
        }
        return zone;
    }
}
