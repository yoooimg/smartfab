package dps.rest;

import dps.utility.LineRegistry;
import dps.utility.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import dps.utility.Line;

/**
 * Service for production lines, provides by REST server.
 */
@RestController
@RequestMapping("/line")
public class LineRegisterController {

    private static final String TAG = "LineRegisterController";

    /** Represents lines registry */
    private final LineRegistry lineRegistry;

    /**
     * Creates a new Controller.
     *
     * @param lineRegistry the given {@link LineRegistry}.
     * <br>
     * With {@code Autowired} Spring injects the dependency when it creates the class instance.
     */
    @Autowired
    public LineRegisterController(LineRegistry lineRegistry) {
        this.lineRegistry = lineRegistry;
    }

    /**
     * Endpoint for line registration.
     *
     * @param line the given {@link Line};
     * @return a registry of all registered lines excepts {@code line}.
     */
    @PostMapping
    public ResponseEntity<?> register(@RequestBody Line line) {
        if (lineRegistry.register(line)) {
            Log.info(TAG, "Line " + line.id() + " registered (" + line.address() + ":" + line.port() + ")");
            return ResponseEntity.ok(lineRegistry.otherLines(line));
        }
        Log.warn(TAG,"Line " + line.id() + " registration rejected: id already in use");
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * Endpoint for line removal.
     *
     * @param id line id;
     * @return the remaining registered lines, or 404 if {@code id} was not found.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deregister(@PathVariable int id) {
        if (lineRegistry.remove(id)) {
            Log.info(TAG, "Line " + id + " deregistered");
            return ResponseEntity.ok(lineRegistry.all());
        }
        Log.warn(TAG, "Deregistration failed: line " + id + " not found");
        return ResponseEntity.notFound().build();
    }
}
