package dps.mqtt;

import dps.utility.LineStateUpdate;
import dps.utility.OperationalState;
import dps.utility.TelemetryBatch;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link MqttPublisher}: runs against a real
 * (in-process) MQTT broker, and verifies what actually arrives on
 * the wire by subscribing with an independent Paho client, rather
 * than asserting on {@link MqttPublisher}'s internals.
 */
class MqttPublisherTest {

    private static TestBroker testBroker;
    private MqttClient mqttClient;
    private MqttPublisher mqttPublisher;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeAll
    static void startTestBroker() throws Exception {
        TestBroker.start();
    }

    @BeforeEach
    void setup() throws Exception {
        mqttClient = new MqttClient(TestBroker.TEST_BROKER_URL, "test-spy-" + System.nanoTime(), new MemoryPersistence());
        mqttClient.connect();
        mqttPublisher = new MqttPublisher("test-publisher-" + System.nanoTime(), TestBroker.TEST_BROKER_URL);
    }

    @AfterEach
    void teardown() throws Exception {
        mqttPublisher.close();
        mqttClient.disconnect();
    }

    /**
     * Subscribes the client and waits briefly before returning:
     * some lightweight embedded brokers (Moquette included) can
     * acknowledge a SUBSCRIBE (returning control to the caller)
     * a moment before their internal routing table is fully updated -
     * a publishing that lands in that narrow window can be silently
     * dropped, with no error on either side. The small pause below
     * eliminates that race without affecting production code (
     * MqttPublisher/MqttSubscriber are untouched - this is test-only
     * synchronization).
     */
    private void subscribeAndSettle(String topic, IMqttMessageListener messageListener) throws Exception {
        mqttClient.subscribe(topic, messageListener);
        Thread.sleep(200);
    }

    @Test
    void publishTelemetry_arrivesOnTheLineSpecificTelemetryTopic() throws Exception {
        BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(1);
        subscribeAndSettle(Topics.telemetry(1), (topic, message) -> received.add(message));

        TelemetryBatch telemetryBatch = new TelemetryBatch(1, 100000L, List.of(70.0, 80.0), OperationalState.FULLY_OPERATIONAL);
        mqttPublisher.publishTelemetry(telemetryBatch);

        MqttMessage message = received.poll(1, TimeUnit.SECONDS);
        assertThat(message).as("no telemetry message arrived within the timeout").isNotNull();
        assertThat(message.getQos()).isEqualTo(Topics.TELEMETRY_QOS);

        TelemetryBatch receivedBatch = mapper.readValue(message.getPayload(), TelemetryBatch.class);
        assertThat(receivedBatch.id()).isEqualTo(1);
        assertThat(receivedBatch.timestamp()).isEqualTo(100000L);
        assertThat(receivedBatch.averages()).containsExactly(70.0, 80.0);
        assertThat(receivedBatch.state()).isEqualTo(OperationalState.FULLY_OPERATIONAL);
    }

    @Test
    void publishState_arrivesOnTheLineSpecificStateTopic() throws Exception {
        BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(1);
        subscribeAndSettle(Topics.state(1), (topic, message) -> received.add(message));

        LineStateUpdate lineStateUpdate = new LineStateUpdate(1, OperationalState.UNDER_CALIBRATION, 1000L, null);
        mqttPublisher.publishState(lineStateUpdate);

        MqttMessage message = received.poll(1, TimeUnit.SECONDS);
        assertThat(message).as("no state message arrived within the timeout").isNotNull();
        assertThat(message.getQos()).isEqualTo(Topics.STATE_QOS);

        LineStateUpdate receivedUpdate = mapper.readValue(message.getPayload(), LineStateUpdate.class);
        assertThat(receivedUpdate.id()).isEqualTo(1);
        assertThat(receivedUpdate.state()).isEqualTo(OperationalState.UNDER_CALIBRATION);
        assertThat(receivedUpdate.timestamp()).isEqualTo(1000L);
        assertThat(receivedUpdate.criticality()).isNull();
    }

    @Test
    void publishTelemetry_doesNotLeakOnToTheStateTopic() throws Exception {
        BlockingQueue<MqttMessage> stateMessages = new ArrayBlockingQueue<>(1);
        subscribeAndSettle(Topics.state(1), (topic, message) -> stateMessages.add(message));
        mqttPublisher.publishTelemetry(new TelemetryBatch(1, 1L, List.of(1.0), OperationalState.FULLY_OPERATIONAL));
        MqttMessage message = stateMessages.poll(500, TimeUnit.MILLISECONDS);
        assertThat(message).as("a telemetry publish must not appear on the state topic").isNull();
    }

    @Test
    void defaultBrokerUrl_isTopicsBrokerUrl() {
        assertThat(MqttPublisher.defaultBrokerUrl()).isEqualTo(Topics.BROKER_URL);
    }

    @Test
    void publicConstructor_canBeInvoked_regardlessOfWhetherADefaultBrokerIsPresent() {
        MqttPublisher publisher = null;
        try {
            publisher = new MqttPublisher("test-ctor-" + System.nanoTime());
        } catch (MqttException ignored) {} finally {
            if (publisher != null) {
                try {
                    publisher.close();
                } catch (MqttException ignored) {
                }
            }
        }
    }

    @Test
    void publish_wrapsASerializationFailure_intoAnIllegalStateException() {
        class Unserializable {
            @SuppressWarnings("unsed")
            public String getValue() {
                throw new RuntimeException("error");
            }
        }
        assertThatThrownBy(() -> mqttPublisher.publish("topic", new Unserializable(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic");
    }
}
