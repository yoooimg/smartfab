package dps.utility;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * History of average vibrations per line.
 */
@Component
public class TelemetryStore {

    /** Represents the store. */
    private final Map<Integer, List<Telemetry>> store = new HashMap<>();
    /** Explicit private lock. */
    private final Object lock = new Object();

    /**
     * Adds {@link Telemetry} to store.
     * @param id line id;
     * @param telemetry telemetry data;
     */
    public void add(int id, Telemetry telemetry) {
        synchronized (lock) {
            store.computeIfAbsent(id, k -> new ArrayList<>()).add(telemetry);
        }
    }

    /**
     * Gets a line telemetries.
     *
     * @param id line id.
     * @return a list of telemetries or null.
     */
    public List<Telemetry> get(int id) {
        synchronized (lock) {
            return new ArrayList<>(store.getOrDefault(id, List.of()));
        }
    }

    /**
     * Computes the average vibration between two timestamps.
     *
     * @param id line id;
     * @param from timestamp;
     * @param to timestamp.
     *
     * @return the average vibration or 0.0
     */
    public double average(int id, long from, long to) {
        synchronized (lock) {
            return get(id)
                    .stream()
                    .filter(t -> t.timestamp() >= from && t.timestamp() <= to)
                    .mapToDouble(Telemetry::vibration)
                    .average()
                    .orElse(0.0);
        }
    }
}
