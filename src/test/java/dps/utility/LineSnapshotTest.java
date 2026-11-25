package dps.utility;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LineSnapshot}.
 */
class LineSnapshotTest {

    @Test
    void toString_combinesLineStateAndTelemetries() {
        LineSnapshot snapshot = new LineSnapshot(
                new Line(1, "localhost", 9001),
                OperationalState.FULLY_OPERATIONAL,
                List.of(new Telemetry(70.0, 1000L))
        );

        assertThat(snapshot.toString()).isEqualTo(
                "{id=1, address=localhost, port=9001, state: FULLY_OPERATIONAL, "
                        + "telemetries: [{vibration: 70.0, timestamp: 1000}]}"
        );
    }

    @Test
    void toString_withNullState_showsUnknown() {
        LineSnapshot snapshot = new LineSnapshot(
                new Line(1, "localhost", 9001),
                null,
                List.of()
        );

        assertThat(snapshot.toString()).isEqualTo(
                "{id=1, address=localhost, port=9001, state: unknown, telemetries: []}"
        );
    }
}