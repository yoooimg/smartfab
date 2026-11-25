package dps.utility;

/** Represents a snapshot of the line internal state. */
public record LineStateUpdate(int id, OperationalState state, long timestamp, Double criticality) {}
