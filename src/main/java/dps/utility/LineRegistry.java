package dps.utility;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks production lines.
 */
@Component
public class LineRegistry {

    /**
     * List of all successfully registered production line.
     */
    List<Line> registry;

    /**
     * Creates a new registry
     */
    public LineRegistry() {
        this.registry = new ArrayList<>();
    }

    /**
     * Registers a line. Two lines are considered the same only if they
     * share the same id (address/port are ignored for this check).
     *
     * @param line the given {@link Line};
     * @return true if {@code line} is successfully registered.
     * */
    public synchronized boolean register(Line line) {
        if (registry.stream().anyMatch(l -> l.id() == line.id()))
            return false;
        registry.add(line);
        return true;
    }

    /**
     * @param line the given {@link Line};
     * @return a list of all registered line excepts the {@code line}.
     * */
    public synchronized List<Line> otherLines(Line line) {
        return registry.stream().filter(l -> l.id() != line.id()).toList();
    }

    /**
     * Removes the line with the given id, if present.
     *
     * @param id line id;
     * @return true if a line with {@code id} was found and removed,
     *         false if no such line was present.
     */
    public synchronized boolean remove(int id) {
        return registry.removeIf(l -> l.id() == id);
    }

    /**
     * @return an immutable snapshot of all currently registered lines.
     */
    public synchronized List<Line> all() {
        return List.copyOf(registry);
    }
}
