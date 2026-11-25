package dps.mqtt;

import dps.pline.ProductionLine;
import dps.utility.TelemetryStore;
import dps.utility.LineStateStore;
import dps.utility.TelemetryBatch;
import dps.utility.LineStateUpdate;
import dps.utility.Log;
import dps.utility.Telemetry;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;


/**
 * Subscribes to the MQTT topics used by {@link ProductionLine}s and applies
 * the received telemetry batches and state updates to the
 * administration server's stores.
 */
@Component
public class MqttSubscriber {

    private static final String TAG = "MqttSubscriber";

    private final MqttClient mqttClient;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TelemetryStore telemetryStore;
    private final LineStateStore lineStateStore;

    /**
     * The Broker address the public constructor uses.
     * Package-visible for testing.
     *
     * @return {@link Topics#BROKER_URL}
     */
    static String defaultBrokerUrl() {
        return Topics.BROKER_URL;
    }

    /**
     * Connects to the broker and registers the message callback.
     *
     * @param clientId unique MQTT client id, e.g. "admin-server"
     * @param telemetryStore store to write received telemetry into
     * @param lineStateStore store to write received state updates into
     * @throws MqttException if the connection to the broker fails
     */
    @Autowired
    public MqttSubscriber(@Value("${mqtt.admin-client-id}") String clientId, TelemetryStore telemetryStore, LineStateStore lineStateStore) throws MqttException {
        this(clientId, defaultBrokerUrl(), telemetryStore, lineStateStore);
    }

    /**
     * Package-visible constructor allowing a custom broker URL, used
     * only by tests to point at an embedded test broker instead of
     * {@link Topics#BROKER_URL} - avoids collisions with a real
     * Mosquitto broker that may already be running on the standard port.
     *
     * @param clientId unique MQTT client id
     * @param brokerUrl the broker to connect to
     * @param telemetryStore store to write received telemetry into
     * @param lineStateStore store to write received state updates into
     * @throws MqttException if the connection to the broker fails
     */
    MqttSubscriber(String clientId, String brokerUrl, TelemetryStore telemetryStore, LineStateStore lineStateStore) throws MqttException {
        this.telemetryStore = telemetryStore;
        this.lineStateStore = lineStateStore;
        this.mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        this.mqttClient.setCallback(new SubscriberCallback());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        this.mqttClient.connect(options);
        subscribeAll();
        Log.info(TAG, "Connected and subscribed");
    }

    /**
     * Subscribes to telemetry and state topics of every production line.
     *
     * @throws MqttException if the subscription fails
     */
    public void subscribeAll() throws MqttException {
        mqttClient.subscribe(Topics.TELEMETRY_WILDCARD);
        mqttClient.subscribe(Topics.STATE_WILDCARD);
    }

    /**
     * Routes an incoming message to the right handler based on its topic.
     * Package-visible for testing.
     *
     * @param topic the MQTT topic the message was published on
     * @param payload the raw JSON message body
     */
    void dispatch(String topic, byte[] payload) {
        try {
            if (topic.endsWith("/telemetry")) {
                callbackTelemetry(mapper.readValue(payload, TelemetryBatch.class));
            } else if (topic.endsWith("/state")) {
                callbackState(mapper.readValue(payload, LineStateUpdate.class));
            }
        } catch (tools.jackson.core.JacksonException e) {
            Log.error(TAG, "Failed to process message on " + topic + ": " + e.getMessage());
        }
    }

    /**
     * Applies a received telemetry batch to the telemetry store, one
     * {@link Telemetry} entry per average value, all sharing the
     * batch's single timestamp.
     *
     * @param payload the deserialized telemetry batch
     */
    private void callbackTelemetry(TelemetryBatch payload) {
        for (double avg : payload.averages()) {
            telemetryStore.add(payload.id(), new Telemetry(avg, payload.timestamp()));
        }
    }

    /**
     * Applies a received state update to the line state store.
     *
     * @param payload the deserialized state update
     */
    private void callbackState(LineStateUpdate payload) {
        Log.info(TAG, "Line " + payload.id() + " -> " + payload.state());
        lineStateStore.update(payload.id(), payload.state());
    }

    /**
     * Disconnects from the broker.
     * @throws MqttException if disconnection fails.
     */
    public void close() throws MqttException {
        mqttClient.disconnect();
    }

    /**
     * This client's MQTT callback.
     * Package-visible inner class for testing.
     */
    class SubscriberCallback implements MqttCallback {

        @Override
        public void connectionLost(Throwable cause) {
            Log.error(TAG, "MQTT connection lost: " + cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) throws Exception {
            dispatch(topic, message.getPayload());
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {}
    }
}