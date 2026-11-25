package dps.grpc;

import dps.utility.Line;
import dps.utility.Log;
import dps.rest.LineRegisterClient;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import proto.generated.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static proto.generated.CoordinationServiceGrpc.*;

/**
 * Manages outbound gRPC connections from this production line to its
 * known lines. Channels are created lazily, on first actual use, not
 * eagerly for every known line. Handles line removal for controlled
 * exits ({@code Leave}) and for detecting unreachable lines on call
 * failure, with SWIM-style indirect confirmation before declaring a
 * line dead.
 */
public class GrpcClientPool {

    private final String tag;

    private static final long CALL_DEADLINE_SECONDS = 5;

    private final RicartAgrawalaCoordinator ricartAgrawalaCoordinator;

    private final LineRegisterClient lineRegisterClient;

    /**
     * Wired after construction via {@link #setPendingReplyMonitor}, to
     * break the circular dependency between this class and
     * {@link PendingReplyMonitor} (which itself needs a GrpcClientPool
     * in its constructor).
     */
    private PendingReplyMonitor pendingReplyMonitor;

    /**
     * Known lines' network address, keyed by line id.
     * Populated as soon as a line is known (via Presentation),
     * independently of whether a channel toward it has been opened yet.
     */
    private final Map<Integer, Line> lines = new HashMap<>();

    /** Lazily-opened channels toward known lines, keyed by line id. */
    private final Map<Integer, ManagedChannel> channels = new HashMap<>();

    /**
     * @param ownId this line's own id, used only to tag log messages
     * @param ricartAgrawalaCoordinator this line's Ricart-Agrawala state,
     *                                  notified when a line is confirmed crashed
     * @param lineRegisterClient used to deregister a line from the
     *                           Administration Server once this line
     *                           has confirmed its crash
     */
    public GrpcClientPool(int ownId, RicartAgrawalaCoordinator ricartAgrawalaCoordinator, LineRegisterClient lineRegisterClient) {
        this.tag = "GrpcClientPool " + ownId;
        this.ricartAgrawalaCoordinator = ricartAgrawalaCoordinator;
        this.lineRegisterClient = lineRegisterClient;
    }

    /**
     * Wires the pending-reply monitor after construction, breaking the
     * circular dependency between this class and PendingReplyMonitor
     * (which itself needs a GrpcClientPool in its constructor).
     *
     * @param pendingReplyMonitor the monitor to notify when a line is
     *                            removed, so its watchdog can be canceled
     */
    public void setPendingReplyMonitor(PendingReplyMonitor pendingReplyMonitor) {
        this.pendingReplyMonitor = pendingReplyMonitor;
    }

    /**
     * Sends the line's presence to a single other line.
     *
     * @param otherLine line to present to
     * @param self this line's own network identity
     */
    public void sendPresentation(Line otherLine, Line self) {
        register(otherLine);
        ricartAgrawalaCoordinator.addLine(otherLine.id());
        try {
            getStub(otherLine.id()).presentation(LineMapper.toProto(self));
        } catch (StatusRuntimeException e) {
            handleUnreachable(otherLine.id(), e);
        }
    }

    /**
     * Sends a {@code Reply} (calibration grant) to a specific,
     * previously-deferred line.
     *
     * @param targetId the line to notify (the one that was deferred)
     * @param granterId this line's own id (the one granting access)
     */
    public void sendReply(int targetId, int granterId) {
        Grant grant = Grant.newBuilder().setGranterId(granterId).build();
        try {
            getStub(targetId).reply(grant);
        } catch (StatusRuntimeException e) {
            handleUnreachable(targetId, e);
        }
    }

    /**
     * Sends a liveness ping to a specific line.
     *
     * @param targetId the line to ping
     * @param requesterId this line's own id
     * @throws IllegalStateException if {@code targetId} is not a known line
     */
    public void sendPing(int targetId, int requesterId) {
        PingRequest pingRequest = PingRequest.newBuilder().setRequesterId(requesterId).build();
        try {
            getStub(targetId).ping(pingRequest);
        } catch (StatusRuntimeException e) {
            handleUnreachable(targetId, e);
        }
    }

    /**
     * Sends a calibration request to every known line, in parallel. Plain
     * {@code Thread}s only. Returns each line's individual response, so
     * the caller (ProductionLine) can start the appropriate
     * {@code PendingReplyMonitor} watch per line.
     *
     * @param requesterId the requester line's id
     * @param criticality the requester line's computed criticality for the request
     * @return each responding line's id mapped to its calibration outcome;
     *         a peer that failed to respond (e.g. unreachable) is simply
     *         absent from the map
     */
    public Map<Integer, CalibrationRequestOutcome> broadcastCalibrationRequest(int requesterId, double criticality) {
        Map<Integer, CalibrationRequestOutcome> outcomes = new HashMap<>();
        CalibrationRequest request = CalibrationRequest
                .newBuilder()
                .setRequesterId(requesterId)
                .setCriticality(criticality)
                .build();
        broadcast(id -> {
            CalibrationAck ack = getStub(id).calibration(request);
            CalibrationRequestOutcome outcome = new CalibrationRequestOutcome(
                    ack.getDeferred(),
                    ack.getKnownDeadline(),
                    ack.getRemainingMs());
            synchronized (outcomes) {
                outcomes.put(id, outcome);
            }
        }, knownIds());
        return outcomes;
    }

    /**
     * Notifies every known line that line is leaving the network, in parallel.
     *
     * @param self the line's own network identity.
     */
    public void broadcastLeave(Line self) {
        var protoSelf = LineMapper.toProto(self);
        broadcast(id -> getStub(id).leave(protoSelf), knownIds());
    }

    /**
     * Notifies every other known line that {@code deadId} has been
     * confirmed crashed (single-hop gossip, sufficient in a fully
     * connected mesh — no re-propagation needed).
     *
     * @param deadId the confirmed-dead line's id
     */
    public void broadcastDown(int deadId) {
        Unreachable unreachableId = Unreachable.newBuilder().setLineId(deadId).build();
        broadcast(id -> getStub(id).down(unreachableId), knownIds().stream().filter(id -> id != deadId).toList());
    }

    /**
     * Asks a single third line whether it can reach a suspected line.
     * A failure to even reach the verifier itself is not evidence about
     * the target — it's treated as "no confirmation available", not
     * escalated as a separate crash investigation.
     *
     * @param verifierId the third line asked to perform the check
     * @param targetId the suspected line to verify
     * @return true only if the verifier explicitly reports it can reach the target
     */
    public boolean reachable(int verifierId, int targetId) {
        ReachableRequest reachableRequest = ReachableRequest.newBuilder().setTargetId(targetId).build();
        try {
            return getStub(verifierId).verifyReachability(reachableRequest).getReachable();
        } catch (StatusRuntimeException e) {
            return false;
        }
    }

    /**
     * Confirms, via indirect verification, whether a suspected line is
     * really dead: asks every other known line to try reaching it in
     * parallel, and declares it dead only if none of them can. If no other
     * line is known to ask (network too small to confirm), the direct
     * suspicion is trusted as-is — there is nothing left to verify against.
     *
     * @param targetId the suspected line
     * @return true if the line is confirmed dead (or unverifiable), false
     *         if at least one verifier reports it reachable
     */
    public boolean isDead(int targetId) {

        List<Integer> verifiersId = knownIds().stream()
                .filter(verifierId -> verifierId != targetId)
                .toList();

        if (verifiersId.isEmpty()) {
            return true;
        }

        boolean[] reachableResults = new boolean[verifiersId.size()];
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < verifiersId.size(); i++) {
            int index = i;
            int verifierId = verifiersId.get(i);
            Thread t = new Thread(() -> reachableResults[index] = reachable(verifierId, targetId));
            threads.add(t);
            t.start();
        }

        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        for (boolean reachableResult : reachableResults) {
            if (reachableResult) return false;
        }

        return true;
    }

    /**
     * Tries to reach a line with a plain ping, with no side effects on
     * failure — used only when this line is itself acting as a verifier
     * for another line's suspicion, where a failure here is not evidence
     * about a crash from this line's own perspective.
     *
     * @param targetId the line to ping
     * @param senderId this line's own id
     * @return true if the ping succeeds
     */
    public boolean tryPing(int targetId, int senderId) {
        PingRequest pingRequest = PingRequest.newBuilder().setRequesterId(senderId).build();
        try {
            getStub(targetId).ping(pingRequest);
            return true;
        } catch (StatusRuntimeException e) {
            return false;
        }
    }

    /**
     * Registers a line's address for future calls. Does not open a channel yet.
     * The channel is created on first actual use by {@link #getStub(int)}.
     *
     * @param line the given line
     */
    public synchronized void register(Line line) {
        lines.put(line.id(), line);
    }

    /**
     * Returns the stub to call the given line, creating and caching the underlying channel on first use.
     *
     * @param id the target line id
     * @return a blocking stub for the coordination service on that line
     * @throws IllegalStateException if {@code id} is not a known line.
     */
    public synchronized CoordinationServiceBlockingStub getStub(int id) {

        if (!lines.containsKey(id)) {
            throw new IllegalStateException("line " + id + " not found");
        }

        ManagedChannel channel = channels.computeIfAbsent(id, i -> {
            Line line = lines.get(i);
            return ManagedChannelBuilder
                    .forAddress(line.address(), line.port())
                    .usePlaintext()
                    .build();
        });

        return newBlockingStub(channel).withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Removes a line, shutting down its channel if one was open.
     * Used both when a line leaves in a controlled way ({@code Leave} received)
     * and when a call to it fails (crash detection).
     *
     * @param id the line's id to remove.
     */
    public synchronized void removeChannel(int id) {
        lines.remove(id);
        ManagedChannel channel = channels.remove(id);
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    /**
     * Deregisters a confirmed-crashed line from the Administration
     * Server. Only the line that discovered and confirmed the crash calls this — lines
     * that only receive the gossip {@code Down} notification do not,
     * avoiding redundant REST calls (a redundant one would be harmless
     * anyway, since the server answers 404 for an already-removed id).
     *
     * @param id the confirmed-dead line's id
     */
    public synchronized void deregisterFromAdminServer(int id) {
        try {
            lineRegisterClient.deregister(id);
        } catch (IOException | InterruptedException e) {
            Log.warn(tag, "Failed to deregister crashed line " + id + " from Administration Server: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Centralized handling of a failed call to a line: distinguishes a
     * genuine network failure (line unreachable) from other gRPC
     * errors, confirms the suspicion via indirect verification before
     * declaring the line dead, and — only if confirmed — removes it,
     * notifies the coordinator, cancels its pending-reply watch,
     * deregisters it from the Administration Server, and
     * propagates the news to the rest of the network.
     * Package-visible for testing.
     *
     * @param id the line that was being called
     * @param e the exception raised by the failed call
     */
    void handleUnreachable(int id, StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();

        if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
            if (isDead(id)) {
                Log.error(tag, "Line " + id + " confirmed unreachable - removed from network");
                removeChannel(id);

                ricartAgrawalaCoordinator.onLineCrashed(id);

                if (pendingReplyMonitor != null) {
                    pendingReplyMonitor.stopWatching(id);
                }
                deregisterFromAdminServer(id);
                broadcastDown(id);
            }
        }
    }

    /**
     * @return a snapshot copy of the currently known line ids, safe to iterate over without holding any lock.
     */
    private synchronized List<Integer> knownIds() {
        return new ArrayList<>(lines.keySet());
    }

    /**
     * Runs {@code action} against every known line in parallel, using
     * one plain {@code Thread} per line and waiting for all of them to finish
     * before returning. Any {@link StatusRuntimeException} raised by
     * {@code action} for a given line is routed to
     * {@link #handleUnreachable(int, StatusRuntimeException)} individually,
     * so one failing line does not affect the others.
     *
     * @param action the per-line call to perform (e.g. a single gRPC call)
     * @param ids target lines ids.
     */
    private void broadcast(Consumer<Integer> action, List<Integer> ids) {
        List<Thread> threads = new ArrayList<>();
        ids.forEach(id -> {
           Thread t = new Thread(() -> {
               try {
                   action.accept(id);
               } catch (StatusRuntimeException e) {
                   handleUnreachable(id, e);
               }
           });
           threads.add(t);
           t.start();
        });
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
