package dps.grpc;

/**
 * Outcome of receiving a calibration request from a line, telling the
 * caller how to build the {@code CalibrationAck}: whether the request
 * is deferred, and if so, whether a precise remaining time is known.
 *
 * @param deferred true if the response must be deferred (a Reply will
 *                 come later, not immediately)
 * @param knownDeadline true if remainingMs is a reliable estimate
 *                      (only when this line is UnderCalibration)
 * @param remainingMs milliseconds left in this line's own calibration,
 *                     meaningful only if knownDeadline is true
 */
public record CalibrationRequestOutcome(boolean deferred, boolean knownDeadline, long remainingMs) {
}
