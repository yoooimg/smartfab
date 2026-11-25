package dps.grpc;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RicartAgrawalaCoordinator}: priority
 * rule (criticality, then id), deferral while UnderCalibration, release
 * notifying deferred requesters, quorum-based access grant (including
 * crash-driven unblocking), and cancellation for a controlled exit.
 */
class RicartAgrawalaCoordinatorTest {

    RicartAgrawalaCoordinator l1;
    @BeforeEach
    void setup() {
        l1 = new RicartAgrawalaCoordinator(1);
    }

    @AfterEach
    void teardown() {
        l1 = null;
    }

    @Nested
    @DisplayName("priority between two competing requests")
    class PriorityTest {

        CalibrationRequestOutcome outcome;

        @BeforeEach
        void setup() {
            outcome = null;
        }

        @AfterEach
        void teardown() {
            outcome = null;
        }

        @Test
        @DisplayName("higher criticality wins -> immediate reply")
        void higherCriticalityWins() {
            l1.addLine(2);
            l1.requestCalibration(0.10);
            outcome = l1.onCalibrationRequestReceived(2, 0.20);
            assertThat(outcome.deferred()).isFalse();
        }

        @Test
        @DisplayName("lower criticality loses -> deferred")
        void lowerCriticalityLoses() {
            l1.addLine(2);
            l1.requestCalibration(0.20);
            outcome = l1.onCalibrationRequestReceived(2, 0.10);
            assertThat(outcome.deferred()).isTrue();
        }

        @Test
        @DisplayName("same criticality -> higher id wins (tie-break)")
        void tieBreakByHigherId() {
            l1.addLine(2);
            l1.requestCalibration(0.10);
            outcome = l1.onCalibrationRequestReceived(2, 0.10);
            assertThat(outcome.deferred()).isFalse();
        }

        @Test
        @DisplayName("same criticality -> lower id loses (tie-break)")
        void tieBreakByLowerId() {
            l1.addLine(0);
            l1.requestCalibration(0.10);
            outcome = l1.onCalibrationRequestReceived(0, 0.10);
            assertThat(outcome.deferred()).isTrue();
        }
    }

    @Nested
    @DisplayName("while under calibration")
    class UnderCalibrationTest {

        CalibrationRequestOutcome outcome;

        @BeforeEach
        void setup() {
            outcome = null;
        }

        @AfterEach
        void teardown() {
            outcome = null;
        }

        @ParameterizedTest
        @ValueSource(doubles = {10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0})
        @DisplayName("always defers, regardless of the requester's criticality")
        void alwaysDefersRegardlessOfCriticality(double requesterCriticality) {
            l1.addLine(2);
            l1.enterCalibration(5000);
            outcome = l1.onCalibrationRequestReceived(2, requesterCriticality);
            assertThat(outcome.deferred()).isTrue();
        }

        @Test
        @DisplayName("the remaining lease is always derivable")
        void alwaysKnownDeadLine() {
            l1.addLine(2);
            l1.enterCalibration(5000);
            outcome = l1.onCalibrationRequestReceived(2, 0.10);
            assertThat(outcome.knownDeadline()).isTrue();
        }

        @Test
        @DisplayName("reports a known, decreasing remaining lease")
        void reportsAPreciseDecreasingDeadline() throws InterruptedException {
            l1.addLine(2);
            l1.enterCalibration(3000);
            Thread.sleep(1000);
            outcome = l1.onCalibrationRequestReceived(2, 0.10);
            assertThat(outcome.remainingMs()).isPositive().isLessThan(3000);
        }
    }

    @Nested
    @DisplayName("release")
    class ReleaseTest {

        CalibrationRequestOutcome outcome;

        @BeforeEach
        void setup() {
            outcome = null;
        }

        @AfterEach
        void teardown() {
            outcome = null;
        }

        @Test
        @DisplayName("notifies exactly the deferred requesters")
        void notifiesOnlyDeferredRequesters() {
            l1.addLine(2);
            l1.addLine(3);
            l1.requestCalibration(0.80);
            l1.enterCalibration(5000);
            l1.onCalibrationRequestReceived(2, 0.20);
            l1.onCalibrationRequestReceived(3, 0.30);

            List<Integer> toNotify = l1.release();
            assertThat(toNotify).containsExactlyInAnyOrder(2, 3);
        }

        @Test
        @DisplayName("notifies exactly the deferred requesters, once")
        void notifiesDeferredRequestersOnce() {
            l1.addLine(2);
            l1.addLine(3);
            l1.requestCalibration(0.80);
            l1.enterCalibration(5000);
            l1.onCalibrationRequestReceived(2, 0.20);
            l1.onCalibrationRequestReceived(3, 0.30);

            l1.release();
            List<Integer> toNotifyTwice = l1.release();
            assertThat(toNotifyTwice).isEmpty();
        }
    }

    @Nested
    @DisplayName("access grant via reply / crash")
    class AccessGrantTest {

        @Test
        @DisplayName("grants access only on the last expected reply")
        void grantsOnlyOnLastExpectedReply() {
            l1.addLine(2);
            l1.addLine(3);
            l1.requestCalibration(0.10);
            List<Boolean> replies = new ArrayList<>();
            replies.add(l1.onReplyReceived(2));
            replies.add(l1.onReplyReceived(3));
            assertThat(replies).containsExactlyInAnyOrder(false, true);
        }

        @Test
        @DisplayName("a crashed line that was the last pending reply grants access")
        void crashOfLastPendingLineGrantsAccess() {
            l1.addLine(2);
            l1.addLine(3);
            l1.requestCalibration(0.10);
            l1.enterCalibration(5000);
            List<Boolean> replies = new ArrayList<>();
            replies.add(l1.onLineCrashed(2));
            replies.add(l1.onLineCrashed(3));
            assertThat(replies).containsExactlyInAnyOrder(false, true);
        }

        @Test
        @DisplayName("a reply or crash from a line not currently pending never grant access")
        void unrelateLineNeverGrantsAccess() {
            l1.addLine(2);
            l1.requestCalibration(0.10);
            List<Boolean> replies = new ArrayList<>();
            replies.add(l1.onReplyReceived(2));
            replies.add(l1.onReplyReceived(3));
            replies.add(l1.onLineCrashed(4));
            assertThat(replies).containsExactlyInAnyOrder(true, false, false);
        }

        @Test
        @DisplayName("awaitCalibrationAccess blocks until the last reply arrives")
        void awaitCalibrationAccessBlocksUntilLastReply() throws InterruptedException {
            l1.addLine(2);
            l1.addLine(3);
            l1.requestCalibration(0.10);

            Thread waiter = new Thread(() -> {
                try {
                    l1.awaitCalibrationAccess();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();

            Thread.sleep(200);
            assertThat(waiter.isAlive()).as("still blocked before any reply arrives").isTrue();

            l1.onReplyReceived(2);
            Thread.sleep(200);
            assertThat(waiter.isAlive()).as("still blocked before only one of two replies");

            l1.onReplyReceived(3);
            waiter.join();
            assertThat(waiter.isAlive()).as("unblocked after the last reply").isFalse();
        }
    }

    @Nested
    @DisplayName("controlled exit")
    class ControlledExitTest {

        @Test
        @DisplayName("cancelPendingRequest clears the own request and pending replies")
        void cancelPendingRepliesClearsState() {
            l1.addLine(2);
            l1.addLine(3);
            l1.requestCalibration(0.10);
            l1.cancelPendingRequest();
            List<Boolean> replies = new ArrayList<>();
            replies.add(l1.onReplyReceived(2));
            replies.add(l1.onLineCrashed(3));
            assertThat(replies).containsExactlyInAnyOrder(false, false);
        }
    }

    @Test
    void removeLine_removesFromKnownLines() {
        l1.addLine(2);
        l1.removeLine(2);
        l1.requestCalibration(0.5);
        assertThat(l1.isWaitingForReplyFrom(2)).isFalse();
    }

    @Test
    void isWaitingForReplyFrom_reflectsPendingReplies_directly() {
        l1.addLine(2);
        l1.requestCalibration(0.5);
        assertThat(l1.isWaitingForReplyFrom(2)).isTrue();
        l1.onReplyReceived(2);
        assertThat(l1.isWaitingForReplyFrom(2)).isFalse();
    }

    @Test
    void onCalibrationRequestReceived_withNoOwnPendingRequest_grantsImmediately() {
        l1.addLine(2);
        CalibrationRequestOutcome outcome = l1.onCalibrationRequestReceived(2, 0.99);
        assertThat(outcome.deferred()).isFalse();
    }
}
