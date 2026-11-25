package dps.utility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LineStateStore}.
 */
public class LineStateStoreTest {

    LineStateStore lineStateStore;

    @BeforeEach
    void setup() {
        lineStateStore = new LineStateStore();
    }

    @AfterEach
    void teardown() {
        lineStateStore = null;
    }

    @Test
    void whenUnknownId_thenGetReturnsNull() {
        assertThat(lineStateStore.get(1)).isNull();
    }

    @Test
    void whenKnownId_thenGetReturnsStoredState() {
        lineStateStore.update(1, OperationalState.WAITING_FOR_CALIBRATION);
        assertThat(lineStateStore.get(1)).isEqualTo(OperationalState.WAITING_FOR_CALIBRATION);
    }

    @Test
    void whenMultipleUpdates_thenOverwritesPreviousState() {
        lineStateStore.update(1, OperationalState.WAITING_FOR_CALIBRATION);
        lineStateStore.update(1, OperationalState.UNDER_CALIBRATION);
        assertThat(lineStateStore.get(1)).isEqualTo(OperationalState.UNDER_CALIBRATION);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                    "1 | FULLY_OPERATIONAL",
                    "2 | UNDER_CALIBRATION"
            })
    void whenMultipleIds_thenUpdateTracksIndependently(int id, OperationalState state) {
        lineStateStore.update(id, state);
        assertThat(lineStateStore.get(id)).isEqualTo(state);
    }
}
