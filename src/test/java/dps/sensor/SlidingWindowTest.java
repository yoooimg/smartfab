package dps.sensor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlidingWindow}: verifies the "8 measurements,
 * 50% overlap" behavior — the window returns nothing until it has 
 * 8 measurements, and clears only the 4 oldest ones after each 
 * read, keeping the other 4 for the next window.
 */
public class SlidingWindowTest {


    SlidingWindow slidingWindow = new SlidingWindow();

    private Measurement measurement(double value, long timestamp) {
        return new Measurement("sensor-1", "vibration", value, timestamp);
    }

    @BeforeEach
    void setup() {
        slidingWindow = new SlidingWindow();
    }

    @AfterEach
    void teardown() {
        slidingWindow = null;
    }

    @Test
    void whenFewerThan8Measurements_thenReadAllAndClearReturnsEmpty() {
        for (int i = 0; i < 7; i++) {
            slidingWindow.addMeasurement(measurement(i, i));
        }
        List<Measurement> result = slidingWindow.readAllAndClear();
        assertThat(result).isEmpty();
    }

    @Test
    void whenExactly8Measurements_thenReadAllAndClearReturnsExactly8() {
        for (int i = 0; i < 8; i++) {
            slidingWindow.addMeasurement(measurement(i, i));
        }
        List<Measurement> result = slidingWindow.readAllAndClear();
        assertThat(result).extracting(Measurement::value).containsExactly(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0);
    }

    @Test
    void whenReadAllAndClear_then50PercentOverlap() {
        for (int i = 0; i < 8; i++) {
            slidingWindow.addMeasurement(measurement(i, i));
        }
        slidingWindow.readAllAndClear();

        for (int i = 8; i < 12; i++) {
            slidingWindow.addMeasurement(measurement(i, i));
        }

        List<Measurement> secondWindow = slidingWindow.readAllAndClear();
        assertThat(secondWindow).extracting(Measurement::value).containsExactly(4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0);
    }

    @Test
    void whenWindowNotYetRefilled_thenReadAllAndClearReturnsEmpty() {
        for (int i = 0; i < 8; i++) {
            slidingWindow.addMeasurement(measurement(i, i));
        }
        slidingWindow.readAllAndClear();
        List<Measurement> tooEarly = slidingWindow.readAllAndClear();
        assertThat(tooEarly).isEmpty();
    }

    @Test
    void whenClear_thenRemovesAllPendingMeasurements() {
        for (int i = 0; i < 8; i++) {
            slidingWindow.addMeasurement(measurement(i, i));
        }
        slidingWindow.clear();
        assertThat(slidingWindow.readAllAndClear()).isEmpty();
    }
}
