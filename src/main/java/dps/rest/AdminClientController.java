package dps.rest;

import dps.utility.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server side service for administration client.
 */
@RestController
@RequestMapping("/line")
public class AdminClientController {

    /** Represents lines registry */
    private final LineRegistry lineRegistry;

    /** Represents lines state store */
    private final LineStateStore lineStateStore;

    /** Represents line telemetries store */
    private final TelemetryStore telemetryStore;

    /**
     * Creates a new Controller.
     *
     * @param lineRegistry the given {@link LineRegistry};
     * @param lineStateStore the given {@link LineStateStore};
     * @param telemetryStore the given {@link TelemetryStore}.
     *
     * <br>
     * With {@code Autowired} Spring injects the dependency when it creates the class instance.
     */
    @Autowired
    public AdminClientController(LineRegistry lineRegistry, LineStateStore lineStateStore, TelemetryStore telemetryStore) {
        this.lineRegistry = lineRegistry;
        this.lineStateStore = lineStateStore;
        this.telemetryStore = telemetryStore;
    }

    /**
     * Endpoint for lines registry
     * @return all registered lines
     */
    @GetMapping("/all")
    public ResponseEntity<?> all() {
        return ResponseEntity.ok(
                lineRegistry
                        .all()
                        .stream()
                        .map(l -> new LineSnapshot(l,  lineStateStore.get(l.id()), telemetryStore.get(l.id())))
                        .toList()
        );
    }

    /**
     * Endpoint for line current state.
     *
     * @param id {@link Line#id()}
     * @return {@link OperationalState}
     */
    @GetMapping("/state/{id}")
    public ResponseEntity<?> state(@PathVariable int id) {
        boolean currentlyRegistered = lineRegistry.all().stream().anyMatch(l -> l.id() == id);
        if (!currentlyRegistered) {
            return ResponseEntity.notFound().build();
        }
        OperationalState state = lineStateStore.get(id);
        return state != null ? ResponseEntity.ok(lineStateStore.get(id)) : ResponseEntity.notFound().build();
    }

    /**
     * Endpoint for line average vibration between two timestamps.
     *
     * @param id {@link Line#id()};
     * @param from timestamp
     * @param to timestamp
     * @return the average vibration between {@code from} and {@code to}.
     */
    @GetMapping("/{id}/average/{from}/{to}")
    public ResponseEntity<?> average(@PathVariable int id, @PathVariable long from, @PathVariable long to) {
        return ResponseEntity.ok(telemetryStore.average(id, from, to));
    }
}
