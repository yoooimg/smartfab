package dps.utility;

import java.util.List;

/** Snapshot of line all info. */
public record LineSnapshot(Line line, OperationalState state, List<Telemetry> telemetries) {

    @Override
    public String toString() {
        return line.toString().replace("}", ", state: ") + (state != null ? state.toString() : "unknown") + ", telemetries: " + telemetries + "}";
    }
}
