package dps.grpc;

import io.grpc.stub.StreamObserver;
import proto.generated.*;

/**
 * Implements the coordination services defined in {@code smartfab.proto}.
 * Delegates all decision-making to {@link RicartAgrawalaCoordinator} (the
 * pure algorithm), {@link GrpcClientPool} (outbound calls to lines) and
 * {@link PendingReplyMonitor} (failure-detection watchdogs) — this class
 * only translates gRPC calls into calls on those collaborators.
 */
public class CoordinationServiceImpl extends CoordinationServiceGrpc.CoordinationServiceImplBase {

    private final int ownId;
    private final String tag;
    private final RicartAgrawalaCoordinator ricartAgrawalaCoordinator;
    private final GrpcClientPool grpcClientPool;
    private final PendingReplyMonitor pendingReplyMonitor;

    /**
     * @param ownId this line's own id, needed when this line acts as
     *              an indirect verifier for another line's suspicion
     * @param ricartAgrawalaCoordinator this line's Ricart-Agrawala state
     * @param grpcClientPool this line's outbound gRPC connections
     * @param pendingReplyMonitor this line's pending replies state
     */
    public CoordinationServiceImpl(int ownId, RicartAgrawalaCoordinator ricartAgrawalaCoordinator, GrpcClientPool grpcClientPool, PendingReplyMonitor pendingReplyMonitor) {
        this.ownId = ownId;
        this.tag = "CoordinationService " + ownId;
        this.ricartAgrawalaCoordinator = ricartAgrawalaCoordinator;
        this.grpcClientPool = grpcClientPool;
        this.pendingReplyMonitor = pendingReplyMonitor;
    }

    /**
     * Handles a new line introducing itself: registers it both for the
     * mutual exclusion quorum ({@link RicartAgrawalaCoordinator}) and for
     * future outbound calls ({@link GrpcClientPool}).
     *
     * @param protoLine the presenting line's network identity
     * @param responseObserver used to send back a plain acknowledgement
     */
    @Override
    public void presentation(Line protoLine, StreamObserver<Ack> responseObserver) {
        dps.utility.Line line = LineMapper.fromProto(protoLine);
        ricartAgrawalaCoordinator.addLine(line.id());
        grpcClientPool.register(line);
        responseObserver.onNext(Ack.newBuilder().build());
        responseObserver.onCompleted();
    }

    /**
     * Handles an incoming calibration access request from a line:
     * delegates the decision (grant now, or defer and - if this
     * line is itself UnderCalibration - report a precise remaining
     * lease) to {@link RicartAgrawalaCoordinator}, and reports the
     * outcome back.
     *
     * @param request the line's requester id and computed criticality
     * @param responseObserver used to send back whether the request
     *                         is deferred and, if so, whether a
     *                         reliable remaining time is known.
     */
    @Override
    public void calibration(CalibrationRequest request, StreamObserver<CalibrationAck> responseObserver) {
        int requesterId = request.getRequesterId();
        double requesterCriticality = request.getCriticality();
        CalibrationRequestOutcome outcome = ricartAgrawalaCoordinator.onCalibrationRequestReceived(requesterId, requesterCriticality);
        responseObserver.onNext(
                CalibrationAck
                        .newBuilder()
                        .setDeferred(outcome.deferred())
                        .setKnownDeadline(outcome.knownDeadline())
                        .setRemainingMs(outcome.remainingMs())
                        .build()
        );
        responseObserver.onCompleted();

        if (!outcome.deferred()) {
            grpcClientPool.sendReply(requesterId, ownId);
        }
    }

    /**
     * Handles a Reply (calibration grant) from a line this line
     * had previously requested calibration to. Delegates to
     * {@link RicartAgrawalaCoordinator#onReplyReceived(int)}, which
     * returns true once this was the last pending reply — meaning
     * this line now has full access to enter calibration (signaled
     * to the waiting thread via wait/notify inside the coordinator).
     * Cancels this line's pending-reply watchdog regardless of
     * whether this was the last reply.
     *
     * @param request the granter's id
     * @param responseObserver used to send back a plain acknowledgement
     */
    @Override
    public void reply(Grant request, StreamObserver<Ack> responseObserver) {
        ricartAgrawalaCoordinator.onReplyReceived(request.getGranterId());
        pendingReplyMonitor.stopWatching(request.getGranterId());
        responseObserver.onNext(Ack.newBuilder().build());
        responseObserver.onCompleted();
    }

    /**
     * Handles a controlled exit notification from a line: removes it
     * from both the mutual exclusion quorum and the outbound connection
     * pool, closing its channel.
     *
     * @param protoLine the leaving line's network identity
     * @param responseObserver used to send back a plain acknowledgement
     */
    @Override
    public void leave(Line protoLine, StreamObserver<Ack> responseObserver) {
        dps.utility.Line line = LineMapper.fromProto(protoLine);
        ricartAgrawalaCoordinator.removeLine(line.id());
        grpcClientPool.removeChannel(line.id());
        responseObserver.onNext(Ack.newBuilder().build());
        responseObserver.onCompleted();
    }

    /**
     * Handles a gossip notification that a line has been confirmed
     * crashed by another line: applies the same removal as a direct
     * crash detection, without re-propagating (single-hop gossip is
     * sufficient in this fully connected mesh), and cancels this
     * line's pending-reply watchdog if one was active.
     *
     * @param request the confirmed-dead line's id
     * @param responseObserver used to send back a plain acknowledgement
     */
    @Override
    public void down(Unreachable request, StreamObserver<Ack> responseObserver) {
        ricartAgrawalaCoordinator.onLineCrashed(request.getLineId());
        grpcClientPool.removeChannel(request.getLineId());
        pendingReplyMonitor.stopWatching(request.getLineId());
        responseObserver.onNext(Ack.newBuilder().build());
        responseObserver.onCompleted();
    }

    /**
     * Plain liveness check: responding at all is the only signal —
     * no algorithm state is touched.
     *
     * @param pingRequest the sender's id (unused, informational only)
     * @param responseObserver used to send back a plain acknowledgement
     */
    @Override
    public void ping(PingRequest pingRequest, StreamObserver<Ack> responseObserver) {
        responseObserver.onNext(Ack.newBuilder().build());
        responseObserver.onCompleted();
    }

    /**
     * Handles an indirect verification request: another line suspects
     * {@code targetId} is crashed and asks this line to try reaching
     * it. Uses {@link GrpcClientPool#tryPing}, which has no side
     * effects on failure — this line is only reporting what it
     * observes, not investigating a crash of its own.
     *
     * @param reachableRequest the suspected line's id to verify
     * @param responseObserver used to send back whether this line
     *                         could reach the suspected line
     */
    @Override
    public void verifyReachability(ReachableRequest reachableRequest, StreamObserver<ReachableResult> responseObserver) {
        boolean pingSucceeds = grpcClientPool.tryPing(reachableRequest.getTargetId(), ownId);
        responseObserver.onNext(ReachableResult.newBuilder().setReachable(pingSucceeds).build());
        responseObserver.onCompleted();
    }
}

