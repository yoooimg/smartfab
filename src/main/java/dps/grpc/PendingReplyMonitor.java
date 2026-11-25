package dps.grpc;

import dps.utility.Log;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks, per line this line is waiting a Reply from, an active
 * watchdog thread that pings the line once the appropriate deadline
 * elapses without a Reply having arrived. This is what actually
 * triggers {@link GrpcClientPool#sendPing} (and, on failure, the
 * rest of the failure-detection pipeline) for lines this line is
 * only passively waiting on, since no outbound call happens on its
 * own while waiting.
 */
public class PendingReplyMonitor {

    private static final long FALLBACK_TIMEOUT_MS = 5_000;
    private static final long RETRY_INTERVAL_MS = 3_000;
    private static final long LEASE_MARGIN_MS = 500;

    private final int ownId;
    private final String tag;
    private final GrpcClientPool grpcClientPool;
    private final RicartAgrawalaCoordinator ricartAgrawalaCoordinator;
    private final Map<Integer, Thread> activeMonitors = new HashMap<>();

    private final long fallbackTimeoutMs;
    private final long retryIntervalMs;
    private final long leaseMarginMs;

    /**
     * Package-visible constructor allowing shorter timeouts, used only
     * by tests so watchdog behavior can be verified without waiting
     * multiple real seconds per test case. Production code should
     * always use the public constructor, which applies the real
     * timeouts.
     *
     * @param ownId this line's own id
     * @param grpcClientPool used to send the liveness ping when a deadline elapses
     * @param ricartAgrawalaCoordinator used to check whether a Reply has already arrived
     * @param fallbackTimeoutMs grace period before pinging when an immediate Reply was expected
     * @param retryIntervalMs interval between pings when the peer's own wait time is unknown
     * @param leaseMarginMs extra margin added on top of a peer-reported precise lease
     */
    PendingReplyMonitor(int ownId, GrpcClientPool grpcClientPool, RicartAgrawalaCoordinator ricartAgrawalaCoordinator, long fallbackTimeoutMs, long retryIntervalMs, long leaseMarginMs) {
        this.ownId = ownId;
        this.tag = "PendingReplyMonitor " + ownId;
        this.grpcClientPool = grpcClientPool;
        this.ricartAgrawalaCoordinator = ricartAgrawalaCoordinator;
        this.fallbackTimeoutMs = fallbackTimeoutMs;
        this.retryIntervalMs = retryIntervalMs;
        this.leaseMarginMs = leaseMarginMs;
    }

    /**
     * @param ownId this line's own id, used when pinging a silent peer
     * @param grpcClientPool used to send the liveness ping when a deadline elapses
     * @param ricartAgrawalaCoordinator used to check whether a Reply has already arrived
     */
    public PendingReplyMonitor(int ownId, GrpcClientPool grpcClientPool, RicartAgrawalaCoordinator ricartAgrawalaCoordinator) {
        this(ownId, grpcClientPool, ricartAgrawalaCoordinator, FALLBACK_TIMEOUT_MS, RETRY_INTERVAL_MS, LEASE_MARGIN_MS);
    }

    /**
     * Starts watching a line this line just sent a Calibration request
     * to, choosing the wait strategy based on its response.
     *
     * @param otherId the line to watch
     * @param outcome the CalibrationAck-derived outcome received from that line
     */
    public synchronized void startWatching(int otherId, CalibrationRequestOutcome outcome) {
        Thread watcher;
        if (outcome.deferred() && outcome.knownDeadline()) {
            watcher = leaseWatcher(otherId, outcome.remainingMs());
        } else if (outcome.deferred()) {
            watcher = retryWatcher(otherId);
        } else {
            watcher = fallbackWatcher(otherId);
        }
        activeMonitors.put(otherId, watcher);
        watcher.start();
    }

    /**
     * Stops watching a line, called once its real Reply has arrived.
     *
     * @param otherId the line whose watch is no longer needed
     */
    public synchronized void stopWatching(int otherId) {
        Thread watcher = activeMonitors.remove(otherId);
        if (watcher != null) {
            watcher.interrupt();
        }
    }

    /**
     * @param otherId the line to check
     * @return true if a watcher for that line is currently active
     */
    synchronized boolean isWatching(int otherId) {
        return activeMonitors.containsKey(otherId);
    }

    /** Single check after a precise lease: the line said exactly how long it needs. */
    private Thread leaseWatcher(int otherId, long remainingMs) {
        Thread watcher =  new Thread(() -> sleepThenCheckOnce(otherId, remainingMs + leaseMarginMs), "lease-watcher-" + ownId + "-to-" + otherId);
        watcher.setDaemon(true);
        return watcher;
    }

    /** Repeated checks: the peer itself doesn't know how long it will take. */
    private Thread retryWatcher(int otherId) {
        Thread watcher = new Thread(() -> {
            while (ricartAgrawalaCoordinator.isWaitingForReplyFrom(otherId)) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException e) {
                    return; // stopWatching() interrompe: attesa risolta, esci
                }
                if (ricartAgrawalaCoordinator.isWaitingForReplyFrom(otherId)) {
                    Log.warn(tag, "Line " + otherId + " silent — pinging");
                    grpcClientPool.sendPing(otherId, ownId);
                }
            }
        }, "retry-watcher-" + ownId + "-to-" + otherId);
        watcher.setDaemon(true);
        return watcher;
    }

    /** Single check after a fixed grace period: a Reply was expected shortly. */
    private Thread fallbackWatcher(int otherId) {
        Thread watcher = new Thread(() -> sleepThenCheckOnce(otherId, fallbackTimeoutMs), "fallback-watcher-" + ownId + "-to-" + otherId);
        watcher.setDaemon(true);
        return watcher;
    }

    /**
     * Sleeps for {@code delayMs}, then checks whether a Reply from {@code otherId} is still
     * pending and, if so, sends it a liveness ping. Used by both the lease and fallback
     * strategies, which only need a single check after their respective delay - unlike
     * the retry strategy, which loops.
     *
     * @param otherId the line to check and, if still silent, ping
     * @param delayMs how long to sleep before checking
     */
    private void sleepThenCheckOnce(int otherId, long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            return;
        }
        if (ricartAgrawalaCoordinator.isWaitingForReplyFrom(otherId)) {
            Log.warn(tag, "Line " + otherId + " silent — pinging");
            grpcClientPool.sendPing(otherId, ownId);
        }
    }
}
