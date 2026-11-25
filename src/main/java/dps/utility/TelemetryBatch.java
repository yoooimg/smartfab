package dps.utility;

import java.util.List;

/** Represents every 10s measured telemetry data */
public record TelemetryBatch(int id, long timestamp, List<Double> averages, OperationalState state) {}
