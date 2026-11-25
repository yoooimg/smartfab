package dps.mqtt;

import dps.pline.ProductionLine;
import dps.utility.LineStateUpdate;
import dps.utility.TelemetryBatch;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a production line's telemetry and state updates over MQTT,
 * as JSON, on that line's own topics (see {@link Topics}). Used by
 * {@link ProductionLine} to report to the Administration
 * Server, which subscribes to every line's topics via {@link MqttSubscriber}.
 */
public class MqttPublisher {

    private final MqttClient mqttClient;
    private final JsonMapper mapper = JsonMapper.builder().build();

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
     * Connects to the MQTT broker.
     *
     * @param clientId unique MQTT client id, e.g. "line-3"
     * @throws MqttException if the connection to the broker fails
     */
    public MqttPublisher(String clientId) throws MqttException {
        this(clientId, defaultBrokerUrl());
    }

    /**
     * Package-visible constructor allowing a custom broker URL, used
     * only by tests to point at an embedded test broker instead of
     * {@link Topics#BROKER_URL} - avoids collisions with a real
     * Mosquitto broker that may already be running on the standard
     * port on the development machine.
     *
     * @param clientId unique MQTT client id
     * @param brokerUrl the broker to connect to
     * @throws MqttException if the connection to the broker fails
     */
    MqttPublisher(String clientId, String brokerUrl) throws MqttException {
        this.mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        this.mqttClient.connect(options);
    }

    /**
     * Publishes a telemetry batch on this line's telemetry topic.
     *
     * @param payload the telemetry batch to publish
     * @throws MqttException if the publishing fails
     */
    public void publishTelemetry(TelemetryBatch payload) throws MqttException {
        publish(Topics.telemetry(payload.id()), payload, Topics.TELEMETRY_QOS);
    }

    /**
     * Publishes a state update on this line's state topic.
     *
     * @param payload the state update to publish
     * @throws MqttException if the publishing fails
     */
    public void publishState(LineStateUpdate payload) throws MqttException {
        publish(Topics.state(payload.id()), payload, Topics.STATE_QOS);
    }

    /**
     * Serializes {@code payload} to JSON and publishes it on {@code topic}.
     * Package-visible for testing.
     *
     * @param topic MQTT topic to publish to
     * @param payload the object to serialize as the message body
     * @param qos MQTT quality of service level
     * @throws MqttException if the publishing fails
     */
    void publish(String topic, Object payload, int qos) throws MqttException {
        try {
            byte[] json = mapper.writeValueAsBytes(payload);
            MqttMessage message = new MqttMessage(json);
            message.setQos(qos);
            mqttClient.publish(topic, message);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Failed to serialize MQTT payload for topic " + topic, e);
        }
    }

    /**
     * Disconnects from the broker.
     *
     * @throws MqttException if disconnection fails
     */
    public void close() throws MqttException {
        mqttClient.disconnect();
    }
}
