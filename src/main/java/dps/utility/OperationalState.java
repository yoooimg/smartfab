package dps.utility;

/**
 * The three states a production line can be in:
 *
 * <p> {@code FullyOperational} is the starting
 * state and the only one where the sensor actively measures towards
 * a new critical-condition check;
 *
 * <p> {@code WaitingForCalibration} is
 * entered once the average vibration exceeds the threshold, while the
 * line requests calibration access from the others;
 *
 * <p> {@code UnderCalibration} is entered once access is granted, and is the
 * only state in which the line actually holds the mutual-exclusion
 * resource.
 */
public enum OperationalState {FULLY_OPERATIONAL, WAITING_FOR_CALIBRATION, UNDER_CALIBRATION}
