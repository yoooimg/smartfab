package dps.sensor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Buffer} implementation using a sliding window of 8
 * measurements with 50% overlap between consecutive windows: once
 * at least 8 measurements have accumulated, {@link #readAllAndClear()}
 * returns the oldest 8 as one window, then discards only the oldest 4
 * — the remaining 4 stay buffered and become the first half of the
 * next window, together with the 4 new measurements collected before
 * the following read.
 */
public class SlidingWindow implements Buffer {

    List<Measurement> measurements = new ArrayList<>();

    /**
     * Adds a new measurement to the buffer.
     *
     * @param m the measurement to add
     */
    @Override
    public synchronized void addMeasurement(Measurement m) {
        measurements.add(m);
    }

    /**
     * Returns the next full window of 8 measurements and slides the
     * buffer forward by 4 (50% overlap), or an empty list if fewer
     * than 8 measurements are currently buffered.
     *
     * @return the next window (8 measurements), or an empty list if not enough are buffered yet
     */
    @Override
    public synchronized List<Measurement> readAllAndClear() {
        if (measurements.size() < 8) {
            return List.of();
        }

        List<Measurement> window = new ArrayList<>(measurements.subList(0, 8));
        measurements.subList(0, 4).clear();
        return window;
    }

    /**
     * Discards every buffered measurement, without returning them.
     * Used when a line stops monitoring (e.g. entering
     * {@code WaitingForCalibration}), so stale measurements from
     * before the pause never end up in a later window.
     */
    @Override
    public synchronized void clear() {
        measurements.clear();
    }
}
