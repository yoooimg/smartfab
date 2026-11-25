package dps.utility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link TelemetryStore}: verifies storage per line and
 * the range-average computation used by the Administration Client
 * query, including the boundary (inclusive) behavior of the
 * timestamp range.
 */
public class TelemetryStoreTest {

    TelemetryStore telemetryStore;

    @BeforeEach
    void setup() {
        telemetryStore = new TelemetryStore();
    }

    @AfterEach
    void teardown() {
        telemetryStore = null;
    }

    @Test
    void whenUnknownId_thenGetReturnsEmptyList() {
        assertThat(telemetryStore.get(1)).isEmpty();
    }

    @Test
    void whenMultipleIdsTelemetries_thenAddIndependently1() {
        telemetryStore.add(1, new Telemetry(70.0, 1000));
        telemetryStore.add(1, new Telemetry(80.0, 2000));
        telemetryStore.add(2, new Telemetry(50.0, 1000));
        assertThat(telemetryStore.get(1)).hasSize(2);
    }

    @Test
    void whenMultipleIdsTelemetries_thenAddIndependently2() {
        telemetryStore.add(1, new Telemetry(70.0, 1000));
        telemetryStore.add(1, new Telemetry(80.0, 2000));
        telemetryStore.add(2, new Telemetry(50.0, 1000));
        assertThat(telemetryStore.get(2)).hasSize(1);
    }

    @Test
    void whenKnownId_thenGetReturnsACopy() {
        telemetryStore.add(1, new Telemetry(70.0, 1000));
        List<Telemetry> telemetries = telemetryStore.get(1);
        telemetries.clear();
        assertThat(telemetryStore.get(1)).hasSize(1);
    }

    @Test
    void whenAverage_thenBothRangeBoundariesAreIncluded() {
        telemetryStore.add(1, new Telemetry(70.0, 1000));
        telemetryStore.add(1, new Telemetry(90.0, 2000));
        telemetryStore.add(1, new Telemetry(50.0, 5000));

        double avg = telemetryStore.average(1, 1000, 3000);
        assertThat(avg).isCloseTo(80.0, within(0.001));
    }

    @Test
    void whenNoTelemetryInRange_thenAverageReturnsZero() {
        telemetryStore.add(1, new Telemetry(70.0, 1000));
        double avg = telemetryStore.average(1, 1001, 2000);
        assertThat(avg).isZero();
    }

    @Test
    void whenUnknownId_thenAverageReturnsZero() {
        assertThat(telemetryStore.average(1, 1000, 2000)).isZero();
    }
}
