package dps.utility;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Telemetry}.
 */
class TelemetryTest {
    @Test
    void toString_containsVibrationAndTimestamp() {
        Telemetry telemetry = new Telemetry(70.0, 1000L);
        assertThat(telemetry.toString()).isEqualTo("{vibration: 70.0, timestamp: 1000}");
    }
}
