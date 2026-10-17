package mx.coberturapunto;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-world network quality observer and transport discriminator.
 * Distinguishes Wi-Fi vs Cellular / Metered data.
 * Measures observed throughput, latency, consecutive errors and timeouts.
 * Enforces:
 * - Aggressive zone synchronization and deep prefetching EXCLUSIVELY on Wi-Fi.
 * - Mobile / metered data: strictly priority 4 (selected point) and priority 3 (viewport).
 * - Calibrated handling for constrained connections (e.g. 22 kb/s and 22 KB/s).
 */
public final class NetworkQualityMonitor {

    public enum QualityGrade {
        OFFLINE,
        VERY_POOR, // e.g. <= 22 kb/s (~2.75 KB/s) or <= 22 KB/s
        DEGRADED,  // ~22 - 100 KB/s
        MODERATE,  // ~100 - 500 KB/s
        EXCELLENT  // > 500 KB/s
    }

    // Thresholds in bytes per second:
    // 22 kb/s (kilobits) = 22,000 / 8 = 2,750 bytes/s
    public static final long BYTES_PER_SEC_22_KBPS = 2750L;
    // 22 KB/s (kilobytes) = 22 * 1024 = 22,528 bytes/s
    public static final long BYTES_PER_SEC_22_KB_S = 22528L;

    private static final NetworkQualityMonitor INSTANCE = new NetworkQualityMonitor();

    public static NetworkQualityMonitor getInstance() {
        return INSTANCE;
    }

    private final AtomicLong smoothedBytesPerSec = new AtomicLong(250_000); // start assumed moderate
    private final AtomicLong smoothedLatencyMs = new AtomicLong(250);
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
    private final AtomicInteger totalRequestsCompleted = new AtomicInteger(0);
    private final AtomicInteger total304Hits = new AtomicInteger(0);

    // Concurrency control: bounded between 1 and 4
    private final AtomicInteger targetConcurrency = new AtomicInteger(2);

    // Transport flags
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean isWifi = new AtomicBoolean(false); // Fail closed until the transport is known
    private final AtomicBoolean isMetered = new AtomicBoolean(false);

    NetworkQualityMonitor() {}

    /**
     * Bind system ConnectivityManager to automatically track Wi-Fi vs Cellular.
     */
    public void registerConnectivity(Context context) {
        if (context == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            updateFromConnectivityManager(cm);

            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    connected.set(true);
                    boolean wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    boolean metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    isWifi.set(wifi);
                    isMetered.set(metered);
                }

                @Override
                public void onLost(Network network) {
                    connected.set(false);
                    isWifi.set(false);
                    isMetered.set(true);
                    consecutiveErrors.incrementAndGet();
                    targetConcurrency.set(1);
                }
            });
        } catch (Throwable ignored) {}
    }

    private void updateFromConnectivityManager(ConnectivityManager cm) {
        try {
            Network active = cm.getActiveNetwork();
            connected.set(false);
            isWifi.set(false);
            isMetered.set(true);
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps != null) {
                    connected.set(true);
                    isWifi.set(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                    isMetered.set(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                }
            }
        } catch (Throwable ignored) {}
    }

    public boolean isWifiConnected() {
        return isWifi.get() && isOnline();
    }

    public void setWifiConnected(boolean wifi) {
        this.isWifi.set(wifi);
        connected.set(true);
    }

    public boolean isMetered() {
        return isMetered.get();
    }

    public void setMetered(boolean metered) {
        this.isMetered.set(metered);
    }

    /**
     * Requirement: Large downloads (full zone, deep prefetch, massive sync)
     * are strictly allowed EXCLUSIVELY over Wi-Fi when connection is healthy.
     */
    public boolean isAggressiveDownloadAllowed() {
        return isWifiConnected() && !isMetered.get() &&
               getQualityGrade() != QualityGrade.OFFLINE &&
               getQualityGrade() != QualityGrade.VERY_POOR;
    }

    /**
     * Record a completed HTTP download observation.
     */
    public void recordSample(long bytes, long durationMs, int httpCode) {
        if (httpCode == 304) {
            total304Hits.incrementAndGet();
            consecutiveErrors.set(0);
            return;
        }

        if (httpCode >= 200 && httpCode < 300 && bytes > 0 && durationMs > 0) {
            consecutiveErrors.set(0);
            consecutiveTimeouts.set(0);
            totalBytesDownloaded.addAndGet(bytes);
            totalRequestsCompleted.incrementAndGet();

            long sampleSpeed = (bytes * 1000L) / Math.max(durationMs, 1L);

            // Exponential moving average: 0.7 current + 0.3 new sample
            long currentSpeed = smoothedBytesPerSec.get();
            long updatedSpeed = (long) (currentSpeed * 0.7 + sampleSpeed * 0.3);
            smoothedBytesPerSec.set(updatedSpeed);

            long currentLatency = smoothedLatencyMs.get();
            long updatedLatency = (long) (currentLatency * 0.7 + durationMs * 0.3);
            smoothedLatencyMs.set(updatedLatency);

            updateTargetConcurrency(updatedSpeed);
        } else if (httpCode == 404 || httpCode == 410) {
            // Resource missing on server: valid HTTP response, not a connection failure!
            totalRequestsCompleted.incrementAndGet();
        } else {
            // 5xx or connection error
            consecutiveErrors.incrementAndGet();
            if (targetConcurrency.get() > 1) {
                targetConcurrency.set(1); // Immediate back-down on error
            }
        }
    }

    /**
     * Record a connection failure or socket timeout.
     */
    public void recordFailure(boolean isTimeout) {
        consecutiveErrors.incrementAndGet();
        if (isTimeout) {
            consecutiveTimeouts.incrementAndGet();
        }
        // Immediately drop to 1 concurrency under network drops
        targetConcurrency.set(1);
    }

    private void updateTargetConcurrency(long speedBytesSec) {
        // Hysteresis: require stable speed before increasing concurrency
        if (consecutiveErrors.get() > 0 || speedBytesSec <= BYTES_PER_SEC_22_KB_S) {
            targetConcurrency.set(1);
        } else if (speedBytesSec < 150_000) {
            targetConcurrency.set(2);
        } else if (speedBytesSec < 500_000) {
            targetConcurrency.set(3);
        } else {
            targetConcurrency.set(4);
        }
    }

    public QualityGrade getQualityGrade() {
        if (consecutiveErrors.get() >= 4) return QualityGrade.OFFLINE;
        long speed = smoothedBytesPerSec.get();
        if (speed <= BYTES_PER_SEC_22_KB_S || consecutiveTimeouts.get() > 0) return QualityGrade.VERY_POOR;
        if (speed < 100_000) return QualityGrade.DEGRADED;
        if (speed < 500_000) return QualityGrade.MODERATE;
        return QualityGrade.EXCELLENT;
    }

    public int getRecommendedConcurrency() {
        return Math.max(1, Math.min(4, targetConcurrency.get()));
    }

    public boolean isOnline() {
        return connected.get() && getQualityGrade() != QualityGrade.OFFLINE;
    }

    /**
     * Under poor or constrained conditions (e.g. <= 22 KB/s or 22 kb/s),
     * speculative neighbor prefetching must be suspended so it never blocks queries.
     */
    public boolean isPrefetchAllowed() {
        return isWifiConnected() && !isMetered.get() && getQualityGrade() != QualityGrade.VERY_POOR &&
               getQualityGrade() != QualityGrade.OFFLINE &&
               consecutiveErrors.get() == 0;
    }

    public long getEstimatedBandwidthBytesPerSec() {
        return smoothedBytesPerSec.get();
    }

    public double getSpeedKBPerSec() {
        return smoothedBytesPerSec.get() / 1024.0;
    }

    public double getSpeedKbps() {
        return (smoothedBytesPerSec.get() * 8.0) / 1000.0;
    }

    public long getTotalBytesDownloaded() {
        return totalBytesDownloaded.get();
    }

    public int getTotal304Hits() {
        return total304Hits.get();
    }

    /**
     * Test simulation helper to inject explicit connection speeds (e.g. 22 kb/s or 22 KB/s).
     */
    public void simulateSpeed(long bytesPerSec) {
        smoothedBytesPerSec.set(bytesPerSec);
        consecutiveErrors.set(0);
        consecutiveTimeouts.set(0);
        updateTargetConcurrency(bytesPerSec);
    }
}
