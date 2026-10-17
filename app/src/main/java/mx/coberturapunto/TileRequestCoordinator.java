package mx.coberturapunto;

import okhttp3.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Traffic and network coordinator for map tiles:
 * - Prioritized dispatch:
 *     Priority 4: Selected Point (critical z17/18 evaluation)
 *     Priority 3: Viewport (visible area)
 *     Priority 2: Zone Sync (full work zone preparation - WI-FI EXCLUSIVE)
 *     Priority 1: Neighbor prefetch (speculative)
 * - Restricts massive downloads and deep zone preparation to WI-FI only.
 * - Mobile data: strictly selected point and visible viewport.
 * - Enforces single in-flight request per tile (de-duplication).
 * - Automatic 304 Not Modified validation without body payload transfer.
 */
public final class TileRequestCoordinator {

    public static final int PRIORITY_SELECTED_POINT = 4;
    public static final int PRIORITY_VIEWPORT = 3;
    public static final int PRIORITY_ZONE = 2;
    public static final int PRIORITY_PREFETCH = 1;

    public interface TileCallback {
        void onSuccess(byte[] data, boolean fromCache, boolean isFallback);
        void onError(String reason, int httpCode);
    }

    private static final class ConsumerRef {
        final String id;
        final int priority;
        final int searchGeneration;
        final TileCallback callback;

        ConsumerRef(String id, int priority, int searchGeneration, TileCallback callback) {
            this.id = id;
            this.priority = priority;
            this.searchGeneration = searchGeneration;
            this.callback = callback;
        }
    }

    private static final class InFlightTask implements Comparable<InFlightTask> {
        final TileSpec spec;
        final String cacheKey;
        final Map<String, ConsumerRef> consumers = new ConcurrentHashMap<>();
        volatile Call activeCall;
        volatile boolean running = false;
        final long createdAt = System.currentTimeMillis();

        InFlightTask(TileSpec spec, ConsumerRef initialConsumer) {
            this.spec = spec;
            this.cacheKey = spec.cacheKey();
            if (initialConsumer != null) {
                consumers.put(initialConsumer.id, initialConsumer);
            }
        }

        int getHighestPriority() {
            int p = 0;
            for (ConsumerRef c : consumers.values()) {
                if (c.priority > p) p = c.priority;
            }
            return p;
        }

        void addConsumer(ConsumerRef c) {
            consumers.put(c.id, c);
        }

        boolean removeConsumer(String id) {
            consumers.remove(id);
            return consumers.isEmpty();
        }

        @Override
        public int compareTo(InFlightTask o) {
            int pDiff = o.getHighestPriority() - this.getHighestPriority();
            if (pDiff != 0) return pDiff;
            return Long.compare(this.createdAt, o.createdAt);
        }
    }

    private final TileStorage storage;
    private final NetworkQualityMonitor networkMonitor;
    private final OkHttpClient httpClient;

    private final Map<String, InFlightTask> inFlight = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<InFlightTask> pendingQueue = new PriorityBlockingQueue<>();
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final AtomicInteger activeSearchGeneration = new AtomicInteger(0);

    private final ExecutorService networkPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "TileNetWorker");
        t.setDaemon(true);
        return t;
    });

    private final ReentrantLock dispatchLock = new ReentrantLock();

    public TileRequestCoordinator(TileStorage storage, NetworkQualityMonitor networkMonitor) {
        this.storage = storage;
        this.networkMonitor = networkMonitor != null ? networkMonitor : NetworkQualityMonitor.getInstance();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void bumpSearchGeneration(int gen) { activeSearchGeneration.set(gen); }
    public void incrementSearchGeneration() {
        activeSearchGeneration.incrementAndGet();
    }

    public int getActiveSearchGeneration() {
        return activeSearchGeneration.get();
    }

    /**
     * Request a tile asynchronously with priority.
     */
    public void requestTile(TileSpec spec, int priority, String consumerId, int searchGeneration, TileCallback callback) {
        if (spec == null) {
            if (callback != null) callback.onError("invalid_spec", 0);
            return;
        }

        // Rule: Zone background synchronization and massive downloads are WI-FI ONLY
        if (priority == PRIORITY_ZONE && !networkMonitor.isAggressiveDownloadAllowed()) {
            if (callback != null) callback.onError("wifi_required_for_zone_sync", 0);
            return;
        }

        // Serve first from local persistent storage
        TileStorage.StoredTile cached = storage.get(spec);
        if (cached != null && !cached.isExpired()) {
            if (callback != null) callback.onSuccess(cached.data, true, false);
            return;
        }

        // Under poor or mobile network, do not proceed with speculative prefetch
        if (priority == PRIORITY_PREFETCH && !networkMonitor.isPrefetchAllowed()) {
            if (callback != null) callback.onError("prefetch_suspended", 0);
            return;
        }

        ConsumerRef consumer = new ConsumerRef(consumerId, priority, searchGeneration, callback);
        String key = spec.cacheKey();

        InFlightTask task;
        boolean isNew = false;
        synchronized (inFlight) {
            task = inFlight.get(key);
            if (task == null) {
                task = new InFlightTask(spec, consumer);
                inFlight.put(key, task);
                pendingQueue.offer(task);
                isNew = true;
            } else {
                task.addConsumer(consumer);
            }
        }

        // If stale data exists, deliver it immediately for visual continuity while refreshing in background
        if (cached != null && callback != null) {
            callback.onSuccess(cached.data, true, false);
        } else if (isNew) {
            // Check overzoom parent fallback for visual continuity while downloading
            TileStorage.StoredTile fallback = storage.getVisualFallback(spec);
            if (fallback != null && callback != null) {
                callback.onSuccess(fallback.data, true, true);
            }
        }

        dispatchNext();
    }

    /**
     * Synchronous request for point evaluation with bounded timeout.
     */
    public byte[] getOrFetchSync(TileSpec spec, int timeoutMs) {
        if (spec == null) return null;

        // 1. Check storage immediately
        TileStorage.StoredTile cached = storage.get(spec);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }

        // 2. Dispatch with top priority
        final CompletableFuture<byte[]> future = new CompletableFuture<>();
        String cid = "sync_query_" + System.nanoTime();

        requestTile(spec, PRIORITY_SELECTED_POINT, cid, activeSearchGeneration.get(), new TileCallback() {
            @Override
            public void onSuccess(byte[] data, boolean fromCache, boolean isFallback) {
                if (!isFallback) {
                    future.complete(data);
                }
            }

            @Override
            public void onError(String reason, int httpCode) {
                future.complete(null);
            }
        });

        try {
            return future.get(Math.max(timeoutMs, 500), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            cancelConsumer(spec.cacheKey(), cid);
            return cached != null ? cached.data : null;
        }
    }

    public void cancelConsumer(String cacheKey, String consumerId) {
        InFlightTask task = inFlight.get(cacheKey);
        if (task != null) {
            boolean empty = task.removeConsumer(consumerId);
            if (empty) {
                Call call = task.activeCall;
                if (call != null && !call.isCanceled()) {
                    call.cancel();
                }
                inFlight.remove(cacheKey);
                pendingQueue.remove(task);
            }
        }
    }

    private void dispatchNext() {
        dispatchLock.lock();
        try {
            int maxConcurrency = networkMonitor.getRecommendedConcurrency();
            while (activeDownloads.get() < maxConcurrency) {
                InFlightTask nextTask = pendingQueue.poll();
                if (nextTask == null) break;

                if (nextTask.consumers.isEmpty()) {
                    inFlight.remove(nextTask.cacheKey);
                    continue;
                }

                // Double check Wi-Fi rule at execution time for zone sync
                if (nextTask.getHighestPriority() == PRIORITY_ZONE && !networkMonitor.isAggressiveDownloadAllowed()) {
                    inFlight.remove(nextTask.cacheKey);
                    notifyConsumers(nextTask, null, false, 403);
                    continue;
                }

                activeDownloads.incrementAndGet();
                nextTask.running = true;
                networkPool.execute(() -> executeDownload(nextTask));
            }
        } finally {
            dispatchLock.unlock();
        }
    }

    private void executeDownload(InFlightTask task) {
        long startTime = System.currentTimeMillis();
        TileSpec spec = task.spec;
        TileStorage.StoredTile existing = storage.get(spec);

        Request.Builder rb = new Request.Builder()
                .url(spec.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");

        // Conditional HTTP request (304) headers if existing data is present
        if (existing != null) {
            if (existing.etag != null && !existing.etag.isBlank()) {
                rb.header("If-None-Match", existing.etag);
            }
            if (existing.lastModified != null && !existing.lastModified.isBlank()) {
                rb.header("If-Modified-Since", existing.lastModified);
            }
        }

        Call call = httpClient.newCall(rb.build());
        task.activeCall = call;

        byte[] downloadedData = null;
        int httpCode = 0;
        String etag = null;
        String lastModified = null;
        boolean success = false;

        try (Response resp = call.execute()) {
            httpCode = resp.code();
            etag = resp.header("ETag");
            lastModified = resp.header("Last-Modified");

            if (httpCode == 304 && existing != null) {
                // Not Modified: Renew validation timestamp without downloading image body!
                storage.markValidated(spec, etag, lastModified);
                downloadedData = existing.data;
                success = true;
                networkMonitor.recordSample(0, System.currentTimeMillis() - startTime, 304);
            } else if (resp.isSuccessful() && resp.body() != null) {
                downloadedData = resp.body().bytes();
                long duration = System.currentTimeMillis() - startTime;
                if (downloadedData != null && downloadedData.length > 0) {
                    success = true;
                    // Atomic save to storage
                    storage.put(spec, downloadedData, etag, lastModified);
                    networkMonitor.recordSample(downloadedData.length, duration, httpCode);
                }
            } else {
                networkMonitor.recordSample(0, System.currentTimeMillis() - startTime, httpCode);
            }
        } catch (IOException e) {
            boolean isTimeout = e instanceof java.net.SocketTimeoutException;
            if (!call.isCanceled()) {
                networkMonitor.recordFailure(isTimeout);
            }
        } finally {
            inFlight.remove(task.cacheKey);
            activeDownloads.decrementAndGet();

            notifyConsumers(task, downloadedData, success, httpCode);
            dispatchNext();
        }
    }

    private void notifyConsumers(InFlightTask task, byte[] data, boolean success, int httpCode) {
        int currentGen = activeSearchGeneration.get();
        for (ConsumerRef ref : task.consumers.values()) {
            if (ref.callback == null) continue;

            if (ref.priority == PRIORITY_SELECTED_POINT && ref.searchGeneration < currentGen) {
                continue;
            }

            if (success && data != null) {
                ref.callback.onSuccess(data, false, false);
            } else {
                ref.callback.onError("http_" + httpCode, httpCode);
            }
        }
    }

    public int getInFlightCount() {
        return inFlight.size();
    }

    public int getQueueSize() {
        return pendingQueue.size();
    }
}
