package mx.coberturapunto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.*;

/**
 * Common geometry model for provider coverage bounds, user work zones, and visible viewports.
 * Supports bounding boxes and closed polygonal boundaries with slippy-tile intersection calculation.
 */
public final class ZoneGeometry implements Serializable {
    private static final long serialVersionUID = 1L;

    // Provider coverage boundary scope (Mexico geographic bounding box)
    public static final ZoneGeometry MEXICO_SCOPE = new ZoneGeometry(14.53, -118.4, 32.72, -86.7);

    public static final class Point implements Serializable {
        public final double lat;
        public final double lon;
        public Point(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    public final double minLat;
    public final double minLon;
    public final double maxLat;
    public final double maxLon;
    public final List<Point> polygon; // optional polygon vertices (empty if pure bounding box)

    public ZoneGeometry(double minLat, double minLon, double maxLat, double maxLon) {
        this(minLat, minLon, maxLat, maxLon, Collections.emptyList());
    }

    public ZoneGeometry(double minLat, double minLon, double maxLat, double maxLon, List<Point> polygon) {
        this.minLat = Math.min(minLat, maxLat);
        this.minLon = Math.min(minLon, maxLon);
        this.maxLat = Math.max(minLat, maxLat);
        this.maxLon = Math.max(minLon, maxLon);
        this.polygon = polygon != null ? Collections.unmodifiableList(new ArrayList<>(polygon)) : Collections.emptyList();
    }

    /**
     * Compute a bounded work zone for a given radius in km around (lat, lon),
     * strictly clamped within Mexico geographic boundaries.
     */
    public static ZoneGeometry fromCenterRadiusKm(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / 111.0;
        double radLat = Math.toRadians(lat);
        double dLon = radiusKm / (111.0 * Math.max(0.1, Math.cos(radLat)));

        double minLat = Math.max(MEXICO_SCOPE.minLat, lat - dLat);
        double maxLat = Math.min(MEXICO_SCOPE.maxLat, lat + dLat);
        double minLon = Math.max(MEXICO_SCOPE.minLon, lon - dLon);
        double maxLon = Math.min(MEXICO_SCOPE.maxLon, lon + dLon);

        return new ZoneGeometry(minLat, minLon, maxLat, maxLon);
    }

    public boolean contains(double lat, double lon) {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
            return false;
        }
        if (polygon.isEmpty()) {
            return true;
        }
        // Ray casting algorithm for polygon inclusion
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point pi = polygon.get(i);
            Point pj = polygon.get(j);
            if ((pi.lat > lat) != (pj.lat > lat) &&
                    (lon < (pj.lon - pi.lon) * (lat - pi.lat) / (pj.lat - pi.lat) + pi.lon)) {
                inside = !inside;
            }
        }
        return inside;
    }

    public boolean intersects(double bMinLat, double bMinLon, double bMaxLat, double bMaxLon) {
        return !(minLon > bMaxLon || maxLon < bMinLon || minLat > bMaxLat || maxLat < bMinLat);
    }

    /**
     * Compute tile ranges (z, x, y) that intersect this geometry.
     */
    public List<TileCoord> calculateIntersectingTiles(int z) {
        int n = 1 << z;
        int minX = lon2tileX(minLon, z);
        int maxX = lon2tileX(maxLon, z);
        int minY = lat2tileY(maxLat, z); // Note: tile Y increases southward
        int maxY = lat2tileY(minLat, z);

        minX = Math.max(0, Math.min(n - 1, minX));
        maxX = Math.max(0, Math.min(n - 1, maxX));
        minY = Math.max(0, Math.min(n - 1, minY));
        maxY = Math.max(0, Math.min(n - 1, maxY));

        List<TileCoord> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                double tMinLon = tileX2lon(x, z);
                double tMaxLon = tileX2lon(x + 1, z);
                double tMaxLat = tileY2lat(y, z);
                double tMinLat = tileY2lat(y + 1, z);

                if (intersects(tMinLat, tMinLon, tMaxLat, tMaxLon)) {
                    result.add(new TileCoord(z, x, y));
                }
            }
        }
        return result;
    }

    public static final class TileCoord {
        public final int z, x, y;
        public TileCoord(int z, int x, int y) {
            this.z = z; this.x = x; this.y = y;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TileCoord)) return false;
            TileCoord t = (TileCoord) o;
            return z == t.z && x == t.x && y == t.y;
        }
        @Override public int hashCode() {
            return Objects.hash(z, x, y);
        }
    }

    public static int lon2tileX(double lon, int z) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
    }

    public static int lat2tileY(double lat, int z) {
        double latRad = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * (1 << z));
    }

    public static double tileX2lon(int x, int z) {
        return x / (double) (1 << z) * 360.0 - 180.0;
    }

    public static double tileY2lat(int y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / (double) (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("minLat", minLat);
        obj.put("minLon", minLon);
        obj.put("maxLat", maxLat);
        obj.put("maxLon", maxLon);
        if (!polygon.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Point p : polygon) {
                arr.put(new JSONObject().put("lat", p.lat).put("lon", p.lon));
            }
            obj.put("polygon", arr);
        }
        return obj;
    }

    public static ZoneGeometry fromJson(JSONObject obj) {
        double minLat = obj.optDouble("minLat");
        double minLon = obj.optDouble("minLon");
        double maxLat = obj.optDouble("maxLat");
        double maxLon = obj.optDouble("maxLon");
        List<Point> poly = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("polygon");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) poly.add(new Point(p.optDouble("lat"), p.optDouble("lon")));
            }
        }
        return new ZoneGeometry(minLat, minLon, maxLat, maxLon, poly);
    }
}
