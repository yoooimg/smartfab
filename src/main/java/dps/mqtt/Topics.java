package dps.mqtt;

/**
 * MQTT topic names and formatting used by production lines and by the
 * administration server. Kept as a single shared source to avoid
 * mismatched topic strings between publisher and subscriber.
 */
public final class Topics {

    private Topics() {}

    /** @param id line id
     *  @return the topic a line publishes its telemetry batches to */
    public static String telemetry(int id) {
        return "smartfab/line/" + id + "/telemetry";
    }

    /** @param id line id
     *  @return the topic a line publishes its state updates to */
    public static String state(int id) {
        return "smartfab/line/" + id + "/state";
    }

    /** */
    public static final String BROKER_URL = "tcp://localhost:1883";

    /** Wildcard subscription used by the admin server to receive
     *  telemetry from every currently and future registered line. */
    public static final String TELEMETRY_WILDCARD = "smartfab/line/+/telemetry";

    /** QoS for telemetry: tolerant to occasional loss (frequent, low-value-per-message data). */
    public static final int TELEMETRY_QOS = 0;

    /** Wildcard subscription for state updates from every line. */
    public static final String STATE_WILDCARD = "smartfab/line/+/state";

    /** QoS for state updates: must not be silently lost, they drive the plant's visible state. */
    public static final int STATE_QOS = 1;
}
