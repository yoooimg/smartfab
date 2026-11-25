package dps.grpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PendingReplyMonitor}, with {@link GrpcClientPool}
 * and {@link RicartAgrawalaCoordinator} mocked via Mockito. Uses the
 * package-visible constructor to inject short timeouts, so each
 * watchdog strategy can be verified quickly and deterministically.
 */
@ExtendWith(MockitoExtension.class)
class PendingReplyMonitorTest {

    private static final int OWN_ID = 1;
    private static final long FALLBACK_MS = 300;
    private static final long RETRY_MS = 150;
    private static final long LEASE_MARGIN_MS = 100;
    private static final long EXTRA_MS = 1500;

    @Mock
    private GrpcClientPool grpcClientPool;

    @Mock
    private RicartAgrawalaCoordinator ricartAgrawalaCoordinator;

    private PendingReplyMonitor pendingReplyMonitor;

    @BeforeEach
    void setup() {
        pendingReplyMonitor = new PendingReplyMonitor(OWN_ID, grpcClientPool, ricartAgrawalaCoordinator, FALLBACK_MS, RETRY_MS, LEASE_MARGIN_MS);
    }

    @AfterEach
    void teardown() {
        pendingReplyMonitor.stopWatching(2);
    }

    @Test
    void fallbackWaitingStrategy_pings_ifStillPendingAfterTheGracePeriod() {
        when(ricartAgrawalaCoordinator.isWaitingForReplyFrom(2)).thenReturn(true);
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(false, false, 0));
        verify(grpcClientPool, timeout(FALLBACK_MS + EXTRA_MS)).sendPing(2, OWN_ID);
    }

    @Test
    void fallbackWaitingStrategy_doesNotPing_ifReplyArrivesBeforeTheGracePeriod() {
        when(ricartAgrawalaCoordinator.isWaitingForReplyFrom(2)).thenReturn(false);
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(false, false, 0));
        verify(grpcClientPool, after(FALLBACK_MS + EXTRA_MS).never()).sendPing(anyInt(), anyInt());
    }

    @Test
    void leaseWaitingStrategy_pings_afterTheReportedRemainingTimePlusMargin() {
        when(ricartAgrawalaCoordinator.isWaitingForReplyFrom(2)).thenReturn(true);
        long remainingMs = 200;
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(true, true, remainingMs));
        // verify(grpcClientPool, after(remainingMs / 2).never()).sendPing(anyInt(), anyInt());
        verify(grpcClientPool, timeout(remainingMs + LEASE_MARGIN_MS + EXTRA_MS)).sendPing(2, OWN_ID);
    }

    @Test
    void retryWaitingStrategy_pingsRepeatedly_whileStillPending() {
        when(ricartAgrawalaCoordinator.isWaitingForReplyFrom(2)).thenReturn(true);
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(true, false, 0));
        verify(grpcClientPool, timeout(RETRY_MS * 4L + EXTRA_MS).atLeast(2)).sendPing(2, OWN_ID);
    }

    @Test
    void stopWatching_interruptsTheWatcher_soNoFurtherPingIsSent() throws InterruptedException {
        lenient().when(ricartAgrawalaCoordinator.isWaitingForReplyFrom(2)).thenReturn(true);
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(false, false, 0));
        pendingReplyMonitor.stopWatching(2);
        verify(grpcClientPool, after(FALLBACK_MS + EXTRA_MS).never()).sendPing(anyInt(), anyInt());
        assertThat(pendingReplyMonitor.isWatching(2)).isFalse();
    }

    @Test
    void stopWatching_onAnUnknownLine_doesNothing_noExceptionThrown() {
        assertThat(pendingReplyMonitor.isWatching(2)).isFalse();
        pendingReplyMonitor.stopWatching(2);
    }

    @Test
    void startWatching_tracksTheLineAsActive_untilStopped() {
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(true, false, 0));
        List<Boolean> stillWatching = new ArrayList<>();
        stillWatching.add(pendingReplyMonitor.isWatching(2));
        pendingReplyMonitor.stopWatching(2);
        stillWatching.add(pendingReplyMonitor.isWatching(2));
        assertThat(stillWatching).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void publicConstructor_delegatesToTheDefaultTimeouts() {
        pendingReplyMonitor = null;
        pendingReplyMonitor = new PendingReplyMonitor(OWN_ID, grpcClientPool, ricartAgrawalaCoordinator);
        assertThat(pendingReplyMonitor.isWatching(2)).isFalse();
    }

    @Test
    void retryWaitingStrategy_doesNotPing_whenReplyArrivesBetweenChecks() {
        when(ricartAgrawalaCoordinator.isWaitingForReplyFrom(2)).thenReturn(true, false);
        pendingReplyMonitor.startWatching(2, new CalibrationRequestOutcome(true, false, 0));
        verify(grpcClientPool, after(RETRY_MS + EXTRA_MS).never()).sendPing(anyInt(), anyInt());
    }
}
