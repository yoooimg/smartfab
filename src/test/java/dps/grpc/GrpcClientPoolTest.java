package dps.grpc;

import io.grpc.Server;

import dps.rest.LineRegisterClient;
import dps.utility.Line;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Unit tests for {@link GrpcClientPool} using a real gRPC {@link Server} backed by a
 * real {@link CoordinationServiceImpl}, {@link RicartAgrawalaCoordinator}, {@link PendingReplyMonitor}
 * and {@link LineRegisterClient} mocked via Mockito, listening on a real port.
 */
@ExtendWith(MockitoExtension.class)
class GrpcClientPoolTest {

    @Mock
    private RicartAgrawalaCoordinator l1RicartAgrawalaCoordinator;

    @Mock
    private LineRegisterClient lineRegisterClient;

    private GrpcClientPool l1GrpcClientPool;

    @Mock
    private PendingReplyMonitor l2PendingReplyMonitor;

    @Mock
    private RicartAgrawalaCoordinator l2RicartAgrawalaCoordinator;

    @Mock
    private GrpcClientPool l2GrpcClientPool;
    private Server l2Server;
    private int l2Port;

    @BeforeEach
    void setup() throws IOException {
        l1GrpcClientPool = new GrpcClientPool(1, l1RicartAgrawalaCoordinator, lineRegisterClient);
        l2Port = newPort();
        CoordinationServiceImpl l2CoordinationService = new CoordinationServiceImpl(2, l2RicartAgrawalaCoordinator, l2GrpcClientPool, l2PendingReplyMonitor);
        l2Server = ServerBuilder.forPort(l2Port).addService(l2CoordinationService).build().start();
        l1GrpcClientPool.register(new Line(2, "localhost", l2Port));
    }

    @AfterEach
    void teardown() {
        l1GrpcClientPool = null;
        l2Server.shutdownNow();
    }

    /**
     * @return a TCP port on localhost guaranteed free at the moment of
     *         the call (the socket is opened then immediately closed),
     *         so a connection attempt to it fails fast with
     *         "connection refused" instead of hanging.
     */
    private int newPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void whenUnknownId_thenGetStubThrowsIllegalStateException() {
        assertThatThrownBy(() -> l1GrpcClientPool.getStub(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("line " + 3 + " not found");
    }

    @Test
    void whenKnownId_thenGetStubNoLongerThrowsIllegalStateException() {
        assertThat(l1GrpcClientPool.getStub(2)).isNotNull();
    }

    @Test
    void whenRemoveChannel_thenGetStubThrowsIllegalStateException() {
        l1GrpcClientPool.removeChannel(2);
        assertThatThrownBy(() -> l1GrpcClientPool.getStub(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("line " + 2 + " not found");
    }

    @Test
    void whenNoVerifierAgainstExists_thenIsDeadIsTrue() {
        assertThat(l1GrpcClientPool.isDead(2)).isTrue();
    }

    @Test
    void deregisterFromAdminServer_delegatesToLineRegisterClient() throws IOException, InterruptedException {
        l1GrpcClientPool.deregisterFromAdminServer(2);
        verify(lineRegisterClient).deregister(2);
    }

    @Test
    void deregisterFromAdminServer_swallowsIOException_withoutPropagating() throws IOException, InterruptedException {
        doThrow(new IOException("mocked")).when(lineRegisterClient).deregister(2);
        assertThatCode(() -> l1GrpcClientPool.deregisterFromAdminServer(2)).doesNotThrowAnyException();
    }

    @Test
    void deregisterFromAdminServer_restoresInterruptFlag_onInterruptedException() throws IOException, InterruptedException {
        doThrow(new InterruptedException("mocked")).when(lineRegisterClient).deregister(2);
        l1GrpcClientPool.deregisterFromAdminServer(2);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void sendPing_toARespondingLine_doesNotTriggerCrashHandling() {
        l1GrpcClientPool.sendPing(2, 1);
        verify(l1RicartAgrawalaCoordinator, never()).onLineCrashed(2);
    }

    @Test
    void sendPing_toAnUnreachableLine_triggersCrashHandling() throws IOException {
        l1GrpcClientPool.removeChannel(2);
        l1GrpcClientPool.register(new Line(2, "localhost", newPort()));
        l1GrpcClientPool.sendPing(2, 1);
        verify(l1RicartAgrawalaCoordinator).onLineCrashed(2);
    }

    @Test
    void tryPing_toARespondingLine_returnsTrue() {
        assertThat(l1GrpcClientPool.tryPing(2, 1)).isTrue();
    }

    @Test
    void tryPing_toAnUnreachableLine_returnsFalse_withNoSideEffects() throws IOException {
        l1GrpcClientPool.removeChannel(2);
        l1GrpcClientPool.register(new Line(2, "localhost", newPort()));
        assertThat(l1GrpcClientPool.tryPing(2, 1)).isFalse();
        verify(l1RicartAgrawalaCoordinator, never()).onLineCrashed(2);
    }

    @Test
    void setPendingReplyMonitor_thenCrashDetected_stopsWatchingOnIt() throws IOException {
        PendingReplyMonitor l1PendingReplyMonitor = mock(PendingReplyMonitor.class);
        l1GrpcClientPool.setPendingReplyMonitor(l1PendingReplyMonitor);
        l1GrpcClientPool.removeChannel(2);
        l1GrpcClientPool.register(new Line(2, "localhost", newPort()));
        l1GrpcClientPool.sendReply(2, 1);
        verify(l1PendingReplyMonitor).stopWatching(2);
    }

    @Test
    void sendPresentation_toARespondingLine_completesWithoutCrashHandling() {
        Line target = new Line(2, "localhost", l2Port);
        Line self = new Line(1, "localhost", 9001);
        l1GrpcClientPool.sendPresentation(target, self);
        verify(l1RicartAgrawalaCoordinator).addLine(2);
        verify(l1RicartAgrawalaCoordinator, never()).onLineCrashed(2);
        verify(l2RicartAgrawalaCoordinator).addLine(1);
    }

    @Test
    void sendPresentation_toUnreachableLine_confirmsCrashAndDeregistersIt() throws IOException, InterruptedException {
        Line target = new Line(2, "localhost", newPort());
        Line self = new Line(1, "localhost", 9001);
        l1GrpcClientPool.sendPresentation(target, self);
        verify(l1RicartAgrawalaCoordinator).onLineCrashed(2);
        verify(lineRegisterClient).deregister(2);
        assertThatThrownBy(() -> l1GrpcClientPool.getStub(2)).as("the crashed line should hoave been removed from known lines")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendReply_toARespondingLine_doesNotTriggerCrashHandling() {
        l1GrpcClientPool.sendReply(2, 1);
        verify(l1RicartAgrawalaCoordinator, never()).onLineCrashed(2);
    }

    @Test
    void sendReply_toUnreachableLine_triggersCrashHandling() throws IOException {
        l1GrpcClientPool.removeChannel(2);
        l1GrpcClientPool.register(new Line(2, "localhost", newPort()));
        l1GrpcClientPool.sendReply(2, 1);
        verify(l1RicartAgrawalaCoordinator).onLineCrashed(2);
    }

    @Test
    void broadcastCalibrationRequest_toARespondingLine_returnsItsRealOutcome() {
        when(l2RicartAgrawalaCoordinator.onCalibrationRequestReceived(1, 0.5))
                .thenReturn(new CalibrationRequestOutcome(true, true, 1000));
        var outcomes = l1GrpcClientPool.broadcastCalibrationRequest(1, 0.5);
        assertThat(outcomes.containsKey(2));
        assertThat(outcomes.get(2).deferred()).isTrue();
        assertThat(outcomes.get(2).knownDeadline()).isTrue();
        assertThat(outcomes.get(2).remainingMs()).isEqualTo(1000);
    }

    @Test
    void broadcastLeave_notifiesTheRespondingLine() {
        l1GrpcClientPool.broadcastLeave(new Line(1, "localhost", 9001));
        verify(l2RicartAgrawalaCoordinator).removeLine(1);
        verify(l2GrpcClientPool).removeChannel(1);
    }

    @Test
    void broadcastLeave_toAGroupWithUnreachableLine_stillNotifiesTheRespondingOneAndDetectsTheCrash() throws IOException {
        l1GrpcClientPool.register(new Line(3, "localhost", newPort()));
        l1GrpcClientPool.broadcastLeave(new Line(1, "localhost", 9001));
        verify(l2RicartAgrawalaCoordinator).removeLine(1);
        verify(l1RicartAgrawalaCoordinator).onLineCrashed(3);
    }

    @Test
    void broadcastLeave_restoresInterruptFlag_whenInterruptedWhileWaitingForBroadcastThreads() {
        Thread.currentThread().interrupt();
        l1GrpcClientPool.broadcastLeave(new Line(1, "localhost", 9001));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void reachable_reportsTrue_whenTheLineCanReachTheTarget() {
        when(l2GrpcClientPool.tryPing(3, 2)).thenReturn(true);
        var isReachable = l1GrpcClientPool.reachable(2, 3);
        assertThat(isReachable).isTrue();
    }

    @Test
    void reachable_reportsFalse_whenTheLineCannotReachTheTarget() {
        when(l2GrpcClientPool.tryPing(3, 2)).thenReturn(false);
        var isReachable = l1GrpcClientPool.reachable(2, 3);
        assertThat(isReachable).isFalse();
    }

    @Test
    void isDead_returnsTrue_whenAVerifierReportsTheTargetUnreachable() {
        when(l2GrpcClientPool.tryPing(3, 2)).thenReturn(false);
        var isDead = l1GrpcClientPool.isDead(3);
        assertThat(isDead).isTrue();
    }


    @Test
    void isDead_returnsFalse_whenAVerifierReportsTheTargetReachable() {
        when(l2GrpcClientPool.tryPing(3, 2)).thenReturn(true);
        var isDead = l1GrpcClientPool.isDead(3);
        assertThat(isDead).isFalse();
    }

    @Test
    void isDead_restoresInterruptFlag_whenInterruptedWhileWaitingForAVerifier() throws IOException {
        l1GrpcClientPool.register(new Line(3, "localhost", newPort()));
        Thread.currentThread().interrupt();
        l1GrpcClientPool.isDead(3);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void getStub_reusesTheSameChannel_onRepeatedCalls() {
        var stub1 = l1GrpcClientPool.getStub(2);
        var stub2 = l1GrpcClientPool.getStub(2);
        assertThat(stub1.getChannel()).isSameAs(stub2.getChannel());
    }

    @Test
    void handleUnreachable_onUnavailable_triggersCrashHandling() {
        l1GrpcClientPool.handleUnreachable(2, Status.UNAVAILABLE.asRuntimeException());
        verify(l1RicartAgrawalaCoordinator).onLineCrashed(2);
    }

    @Test
    void handleUnreachable_onDeadlineExceeded_alsoTriggersCrashHandling() {
        l1GrpcClientPool.handleUnreachable(2, Status.DEADLINE_EXCEEDED.asRuntimeException());
        verify(l1RicartAgrawalaCoordinator).onLineCrashed(2);
    }

    @Test
    void handleUnreachable_onAnUnrelatedStatusCode_doesNothing() {
        l1GrpcClientPool.handleUnreachable(2, Status.PERMISSION_DENIED.asRuntimeException());
        verify(l1RicartAgrawalaCoordinator, never()).onLineCrashed(2);
    }

    @Test
    void handleUnreachable_whenAVerifierConfirmsReachability_doesNothing() {
        when(l2GrpcClientPool.tryPing(3, 2)).thenReturn(true);
        l1GrpcClientPool.handleUnreachable(3, Status.UNAVAILABLE.asRuntimeException());
        verify(l1RicartAgrawalaCoordinator, never()).onLineCrashed(3);
    }

    @Test
    void broadcastDown_excludesTheDeadLineItself_fromTheNotifiedSet() {
        l1GrpcClientPool.broadcastDown(2);
        verifyNoInteractions(l2RicartAgrawalaCoordinator);
    }
}

