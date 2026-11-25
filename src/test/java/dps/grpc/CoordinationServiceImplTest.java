package dps.grpc;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import proto.generated.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CoordinationServiceImpl}, with all three
 * collaborators ({@link RicartAgrawalaCoordinator}, {@link GrpcClientPool},
 * {@link PendingReplyMonitor}) and the gRPC {@link StreamObserver}
 * mocked via Mockito. Each handler is verified purely as a translation
 * layer: it must delegate to the right collaborator with the right
 * arguments and send back the expected response, nothing more.
 */
@ExtendWith(MockitoExtension.class)
class CoordinationServiceImplTest {

    private static final int OWN_ID = 1;

    private CoordinationServiceImpl coordinationService;

    @Mock
    private RicartAgrawalaCoordinator ricartAgrawalaCoordinator;

    @Mock
    private GrpcClientPool grpcClientPool;

    @Mock
    private PendingReplyMonitor pendingReplyMonitor;

    @BeforeEach
    void setup() {
        coordinationService = new CoordinationServiceImpl(OWN_ID, ricartAgrawalaCoordinator, grpcClientPool, pendingReplyMonitor);
    }

    @AfterEach
    void teardown() {
        coordinationService = null;
    }

    @Test
    void presentation_registersTheLine_inBothCoordinatorAndClientPool_andAcks() {
        Line protoLine = Line.newBuilder()
                .setId(2)
                .setAddress("localhost")
                .setPort(9002)
                .build();
        StreamObserver<Ack> responseObserver = mock(StreamObserver.class);
        coordinationService.presentation(protoLine, responseObserver);
        verify(ricartAgrawalaCoordinator).addLine(2);
        verify(grpcClientPool).register(LineMapper.fromProto(protoLine));
        verify(responseObserver).onNext(any(Ack.class));
        verify(responseObserver).onCompleted();
    }

    @Test
    void calibration_whenNotDeferred_sendsAckAndAnImmediateReply() {
        CalibrationRequest request = CalibrationRequest.newBuilder()
                .setRequesterId(2)
                .setCriticality(0.20)
                .build();
        StreamObserver<CalibrationAck> responseObserver = mock(StreamObserver.class);
        when(ricartAgrawalaCoordinator.onCalibrationRequestReceived(2, 0.20))
                .thenReturn(new CalibrationRequestOutcome(false, false, 0));
        coordinationService.calibration(request,responseObserver);
        ArgumentCaptor<CalibrationAck> calibrationAckCaptor = ArgumentCaptor.forClass(CalibrationAck.class);
        verify(responseObserver).onNext(calibrationAckCaptor.capture());
        verify(responseObserver).onCompleted();
        assertThat(calibrationAckCaptor.getValue().getDeferred()).isFalse();
        verify(grpcClientPool).sendReply(2, OWN_ID);
    }

    @Test
    void calibration_whenDeferred_sendsAckOnly_noImmediateReply() {
        CalibrationRequest request = CalibrationRequest.newBuilder()
                .setRequesterId(2)
                .setCriticality(0.20)
                .build();
        StreamObserver<CalibrationAck> responseObserver = mock(StreamObserver.class);
        when(ricartAgrawalaCoordinator.onCalibrationRequestReceived(2, 0.20))
                .thenReturn(new CalibrationRequestOutcome(true, true, 2000));
        coordinationService.calibration(request,responseObserver);
        ArgumentCaptor<CalibrationAck> calibrationAckCaptor = ArgumentCaptor.forClass(CalibrationAck.class);
        verify(responseObserver).onNext(calibrationAckCaptor.capture());
        verify(responseObserver).onCompleted();
        CalibrationAck calibrationAck = calibrationAckCaptor.getValue();
        List<Object> outcome = new ArrayList<>();
        outcome.add(calibrationAck.getDeferred());
        outcome.add(calibrationAck.getKnownDeadline());
        outcome.add(calibrationAck.getRemainingMs());
        assertThat(outcome).containsExactlyInAnyOrder(true, true, 2000L);
        verify(grpcClientPool, never()).sendReply(anyInt(), anyInt());
    }

    @Test
    void reply_notifiesCoordinator_andCancelsTheWatchdog_regardlessOfWhetherItWasTheLastOne() {
        Grant grant = Grant.newBuilder()
                .setGranterId(2).build();
        StreamObserver<Ack> responseObserver = mock(StreamObserver.class);
        coordinationService.reply(grant, responseObserver);
        verify(ricartAgrawalaCoordinator).onReplyReceived(2);
        verify(pendingReplyMonitor).stopWatching(2);
        verify(responseObserver).onNext(any(Ack.class));
        verify(responseObserver).onCompleted();
    }

    @Test
    void leave_removesTheLine_fromBothCoordinatorAndClientPool() {
        Line protoLine = Line.newBuilder()
                .setId(2)
                .setAddress("localhost")
                .setPort(9002)
                .build();
        StreamObserver<Ack> responseObserver = mock(StreamObserver.class);
        coordinationService.leave(protoLine, responseObserver);
        verify(ricartAgrawalaCoordinator).removeLine(2);
        verify(grpcClientPool).removeChannel(2);
        verify(responseObserver).onNext(any(Ack.class));
        verify(responseObserver).onCompleted();
    }

    @Test
    void down_appliesTheSameRemovalAsACrash_andCancelsTheWatchdo() {
        Unreachable request = Unreachable.newBuilder()
                .setLineId(2)
                .build();
        StreamObserver<Ack> responseObserver = mock(StreamObserver.class);
        coordinationService.down(request,responseObserver);
        verify(ricartAgrawalaCoordinator).onLineCrashed(2);
        verify(grpcClientPool).removeChannel(2);
        verify(pendingReplyMonitor).stopWatching(2);
        verify(responseObserver).onNext(any(Ack.class));
        verify(responseObserver).onCompleted();
    }

    @Test
    void ping_onlyAcknowledges_touchesNoCollaborator() {
        PingRequest request = PingRequest.newBuilder()
                .setRequesterId(2)
                .build();
        StreamObserver<Ack> responseObserver = mock(StreamObserver.class);
        coordinationService.ping(request,responseObserver);
        verify(responseObserver).onNext(any(Ack.class));
        verify(responseObserver).onCompleted();
        verifyNoInteractions(ricartAgrawalaCoordinator, grpcClientPool, pendingReplyMonitor);
    }

    @Test
    void verifyReachability_reportsWhatTheClientPoolFound_reachable() {
        ReachableRequest request = ReachableRequest.newBuilder()
                .setTargetId(2)
                .build();
        StreamObserver<ReachableResult> responseObserver = mock(StreamObserver.class);
        when(grpcClientPool.tryPing(2, OWN_ID)).thenReturn(true);
        coordinationService.verifyReachability(request, responseObserver);
        ArgumentCaptor<ReachableResult> reachableResultCaptor = ArgumentCaptor.forClass(ReachableResult.class);
        verify(responseObserver).onNext(reachableResultCaptor.capture());
        assertThat(reachableResultCaptor.getValue().getReachable()).isTrue();
    }

    @Test
    void verifyReachability_reportsWhatTheClientPoolFound_unreachable() {
        ReachableRequest request = ReachableRequest.newBuilder()
                .setTargetId(2)
                .build();
        StreamObserver<ReachableResult> responseObserver = mock(StreamObserver.class);
        when(grpcClientPool.tryPing(2, OWN_ID)).thenReturn(false);
        coordinationService.verifyReachability(request, responseObserver);
        ArgumentCaptor<ReachableResult> reachableResultCaptor = ArgumentCaptor.forClass(ReachableResult.class);
        verify(responseObserver).onNext(reachableResultCaptor.capture());
        assertThat(reachableResultCaptor.getValue().getReachable()).isFalse();
    }
}
