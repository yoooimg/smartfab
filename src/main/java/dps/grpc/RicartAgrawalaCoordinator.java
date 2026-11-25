package dps.grpc;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure implementation of the Ricart-Agrawala distributed mutual exclusion
 * algorithm, adapted to use each request's criticality (and, as a
 * tie-break, the requester's id) as priority instead of a Lamport
 * timestamp. Has no dependency on gRPC or any other transport: it only
 * tracks state and tells the caller what to do (send requests, answer
 * immediately or defer, notify deferred requesters on release). Sending
 * the actual RPCs is the caller's responsibility.
 */
public class RicartAgrawalaCoordinator {

    private final int ownId;
    private final String tag;
    private Double ownCriticality;
    private boolean underCalibration;

    /** Wall-clock instant this line entered UnderCalibration, null otherwise. */
    private Instant calibrationStart;

    /** Total calibration duration in milliseconds, as decided by the
     *  caller (the same value used for the real {@code Thread.sleep}). */
    private long calibrationDurationMs;

    private final Set<Integer> knownLines = new HashSet<>();
    private final Set<Integer> pendingReplies = new HashSet<>();
    private final Set<Integer> deferredRequesters = new HashSet<>();

    /**
     * Creates a new coordinator for the local line.
     *
     * @param ownId this line's id
     */
    public RicartAgrawalaCoordinator(int ownId) {
        this.ownId = ownId;
        this.tag = "RicartAgrawala " + ownId;
    }

    /**
     * Adds given line id to caller known lines set.
     *
     * @param id line id
     */
    public synchronized void addLine(int id) {
        knownLines.add(id);
    }

    /**
     * Removes given line id from caller known lines set.
     *
     * @param id line id
     */
    public synchronized void removeLine(int id) {
        knownLines.remove(id);
    }

    /**
     * Registers a new calibration request.
     *
     * @param criticality given criticality for the request
     */
    public synchronized void requestCalibration(double criticality) {
        this.ownCriticality = criticality;
        this.pendingReplies.addAll(knownLines);
    }

    /**
     * @param requesterId requester id
     * @param requesterCriticality requester criticality
     * @return the outcome describing whether to defer and, if so, whether
     *         a precise remaining time is known
     */
    public synchronized CalibrationRequestOutcome onCalibrationRequestReceived(int requesterId, double requesterCriticality) {
        if (underCalibration) {
            long remainingMs = Math.max(0, calibrationDurationMs - (Duration.between(calibrationStart, Instant.now()).toMillis()));
            deferredRequesters.add(requesterId);
            return new CalibrationRequestOutcome(true, true, remainingMs);
        }

        if (ownCriticality != null && !hasPriority(requesterCriticality, requesterId)) {
            deferredRequesters.add(requesterId);
            return new CalibrationRequestOutcome(true, false, 0);
        }
        return new CalibrationRequestOutcome(false, false, 0);
    }

    /**
     * @param requesterCriticality requester criticality
     * @param requesterId requester id
     * @return true if requester has priority
     */
    private boolean hasPriority(double requesterCriticality, int requesterId) {
        return requesterCriticality != ownCriticality ? requesterCriticality > ownCriticality : requesterId > ownId;
    }

    /**
     * @param granterId granter id
     * @return true if this was the last pending reply, meaning access to
     *         calibration is now granted
     */
    public synchronized boolean onReplyReceived(int granterId) {
        boolean removed = pendingReplies.remove(granterId);
        if (removed && pendingReplies.isEmpty()) {
            notifyAll();
            return true;
        }
        return false;
    }

    /**
     * @param id line's id
     * @return true if this line is still waiting a Reply from that line
     */
    public synchronized boolean isWaitingForReplyFrom(int id) {
        return pendingReplies.contains(id);
    }

    /**
     * Removes a crashed line from the local view: from known lines, from
     * requests this line is deferring, and — if this line was waiting on
     * a Reply from it — from the pending replies, exactly as if that
     * Reply had arrived (a crashed line can never send one).
     *
     * @param id crashed line id
     * @return true if this was the last pending reply, meaning access to
     *         calibration is now granted
     */
    public synchronized boolean onLineCrashed(int id) {
        knownLines.remove(id);
        deferredRequesters.remove(id);
        boolean removed = pendingReplies.remove(id);
        if (removed && pendingReplies.isEmpty()) {
            notifyAll();
            return true;
        }
        return false;
    }

    /**
     * Marks the local line as currently under calibration.
     *
     * @param durationMs the calibration duration, in milliseconds — must
     *                   be the same value the caller uses for its own
     *                   {@code Thread.sleep}, so the lease reported to
     *                   other lines matches reality
     */
    public synchronized void enterCalibration(long durationMs) {
        underCalibration = true;
        calibrationStart = Instant.now();
        calibrationDurationMs = durationMs;
    }

    /**
     * Ends the local calibration and returns the requesters that were
     * deferred while it was in progress, so the caller can send them
     * a {@code Reply}.
     *
     * @return the ids of the deferred requesters to notify now
     */
    public synchronized List<Integer> release() {
        underCalibration = false;
        ownCriticality = null;
        calibrationStart = null;
        List<Integer> toNotify = List.copyOf(deferredRequesters);
        deferredRequesters.clear();
        return toNotify;
    }

    /**
     * Blocks the calling thread until this line has received every
     * pending reply for its current calibration request (or a crash has
     * unblocked it, see {@link #onLineCrashed}).
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public synchronized void awaitCalibrationAccess() throws InterruptedException {
        while(!pendingReplies.isEmpty()) {
            wait();
        }
    }

    /**
     * Cancels this line's own pending calibration request, used when
     * exiting the network while still WaitingForCalibration.
     * Must never be called while UnderCalibration — a calibration already
     * in progress must complete, not be canceled.
     */
    public synchronized void cancelPendingRequest() {
        ownCriticality = null;
        pendingReplies.clear();
    }
}