package dps.utility;

/**
 * A single vibration measurement, at the granularity the
 * Administration Server stores and serves it: one sliding-window
 * average paired with the timestamp of the {@link TelemetryBatch} it came from — every
 * average in the same batch shares that one timestamp.
 *
 * @param vibration the average vibration value for this window
 * @param timestamp when the containing batch was published, in milliseconds
 */
public record Telemetry(double vibration, long timestamp){
    @Override
    public String toString(){
        return "{vibration: " +  vibration + ", timestamp: " + timestamp + "}";
    }
}
