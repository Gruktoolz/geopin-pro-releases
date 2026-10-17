package mx.coberturapunto;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable specification of a map tile request.
 * Contains provider, layer, projection, coordinates and cache directives.
 * Does not contain sensitive credentials or user data.
 */
public final class TileSpec implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String providerId;
    public final String layerId;
    public final String versionOrConfig;
    public final String projection;
    public final int tileSize;
    public final int z;
    public final int x;
    public final int y;
    public final String url;
    public final long ttlMillis;
    public final boolean isCoverage;
    public final boolean isBaseMap;

    public TileSpec(String providerId, String layerId, String versionOrConfig,
                    String projection, int tileSize, int z, int x, int y,
                    String url, long ttlMillis, boolean isCoverage, boolean isBaseMap) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.layerId = Objects.requireNonNull(layerId, "layerId");
        this.versionOrConfig = versionOrConfig != null ? versionOrConfig : "v1";
        this.projection = projection != null ? projection : "EPSG:900913";
        this.tileSize = tileSize > 0 ? tileSize : 256;
        this.z = z;
        this.x = x;
        this.y = y;
        this.url = url != null ? url : "";
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : 7L * 24 * 3600 * 1000;
        this.isCoverage = isCoverage;
        this.isBaseMap = isBaseMap;
    }

    /**
     * Unique, canonical cache key for this exact tile resource.
     * Prevents collision across different layers, projections and providers.
     */
    public String cacheKey() {
        return providerId + "_" + layerId + "_" + versionOrConfig + "_" + projection
                + "_" + tileSize + "_" + z + "_" + x + "_" + y;
    }

    /**
     * Legacy filename compatibility with existing cached tiles on disk.
     */
    public String legacyKey() {
        if (isCoverage) {
            return (layerId.contains("hfc") ? "hfc_" : "fibra_") + z + "_" + x + "_" + y;
        } else if (isBaseMap) {
            return "google_" + z + "_" + x + "_" + y;
        }
        return cacheKey();
    }

    /**
     * Creates a parent tile spec at zoom (z - 1) for overzoom visual fallback.
     */
    public TileSpec asZoom(int targetZ, int targetX, int targetY) {
        return new TileSpec(providerId, layerId, versionOrConfig, projection, tileSize,
                targetZ, targetX, targetY, "", ttlMillis, isCoverage, isBaseMap);
    }

    public TileSpec parentSpec() {
        if (z <= 0) return null;
        int pz = z - 1;
        int px = x / 2;
        int py = y / 2;
        return new TileSpec(providerId, layerId, versionOrConfig, projection, tileSize,
                pz, px, py, "", ttlMillis, isCoverage, isBaseMap);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TileSpec)) return false;
        TileSpec that = (TileSpec) o;
        return z == that.z && x == that.x && y == that.y &&
                tileSize == that.tileSize &&
                providerId.equals(that.providerId) &&
                layerId.equals(that.layerId) &&
                versionOrConfig.equals(that.versionOrConfig) &&
                projection.equals(that.projection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, layerId, versionOrConfig, projection, tileSize, z, x, y);
    }

    @Override
    public String toString() {
        return cacheKey();
    }
}
