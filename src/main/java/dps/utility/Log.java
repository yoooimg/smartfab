package dps.utility;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight, dependency-free logging utility shared across every
 * component (Admin Server, Production Line, gRPC, MQTT). Prints
 * timestamp, thread name, and a component tag, so concurrent activity
 * across threads/processes can be told apart when reading console
 * output side by side.
 */
public final class Log {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    /**
     * @param component tag identifying the source, e.g. "AdminServer",
     *                   "ProductionLine:3", "GrpcClientPool:3"
     * @param message the event description
     */
    public static void info(String component, String message) {
        System.out.println(format("INFO", component, message));
    }

    /**
     * @param component tag identifying the source
     * @param message the event description
     */
    public static void warn(String component, String message) {
        System.err.println(format("WARN", component, message));
    }

    /**
     * @param component tag identifying the source
     * @param message the event description
     */
    public static void error(String component, String message) {
        System.err.println(format("ERROR", component, message));
    }

    private static String format(String level, String component, String message) {
        return String.format("%s [%-5s] [%-15s] [%-22s] %s",
                LocalTime.now().format(TIME_FORMAT),
                level,
                Thread.currentThread().getName(),
                component,
                message);
    }
}
