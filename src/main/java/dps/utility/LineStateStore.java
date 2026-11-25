package dps.utility;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Stores the current operational state of each line. */
@Component
public class LineStateStore {

    /** Represents the store. */
    private final Map<Integer, OperationalState> store = new HashMap<>();
    /** Explicit private lock. */
    private final Object lock = new Object();

    /**
     * Updates the {@link OperationalState} of a {@link Line}.
     *
     * @param id line id;
     * @param state line {@link OperationalState};
     */
    public void update(int id, OperationalState state) {
        synchronized (lock) {
            store.put(id, state);
        }
    }

    /**
     * Gets the {@link OperationalState} of a {@link Line}.
     *
     * @param id line id
     * @return {@link OperationalState} of given line id.
     */
    public OperationalState get(int id) {
        synchronized (lock) {
            return store.get(id);
        }
    }
}
