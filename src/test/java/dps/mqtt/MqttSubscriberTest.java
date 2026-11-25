package dps.mqtt;

import dps.utility.*;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;


/**
 * Integration test for {@link MqttSubscriber}: a fake "production
 * line" (a plain Paho client) publishes real telemetry/state
 * messages on an in-process broker (dedicated test port), and the
 * real {@link MqttSubscriber} - wired to real (not mocked)
 * {@link dps.utility.TelemetryStore} and {@link LineStateStore}
 * instances - is verified to actually apply them. Message delivery
 * is asynchronous (Paho's callback thread), so assertions poll
 * briefly instead of assuming instant delivery.
 */
class MqttSubscriberTest {

    private MqttClient mqttClient;
    private MqttSubscriber mqttSubscriber;
    private TelemetryStore telemetryStore;
    private LineStateStore lineStateStore;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeAll
    static void startTestBroker() throws Exception {
        TestBroker.start();
    }

    @BeforeEach
    void setup() throws Exception {
        telemetryStore = new TelemetryStore();
        lineStateStore = new LineStateStore();
        mqttSubscriber = new MqttSubscriber("test-admin-" + System.nanoTime(), TestBroker.TEST_BROKER_URL, telemetryStore, lineStateStore);
        mqttClient = new MqttClient(TestBroker.TEST_BROKER_URL, "mqtt-client-" + System.nanoTime(), new MemoryPersistence());
        mqttClient.connect();
    }

    @AfterEach
    void teardown() throws Exception {
        mqttClient.disconnect();
        mqttSubscriber.close();
    }

    /** Polls {@code condition} for up to 3 seconds, since delivery happens on Paho's own callback thread. */
    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                Assertions.fail("condition not met within the timeout");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void telemetryMessage_isSplitIntoOneEntryPerAverage_inTheTelemetryStore() throws Exception {
        TelemetryBatch telemetryBatch = new TelemetryBatch(1, 5000L, List.of(70.0, 80.0), OperationalState.FULLY_OPERATIONAL);
        mqttClient.publish(Topics.telemetry(1), new MqttMessage(jsonMapper.writeValueAsBytes(telemetryBatch)));
        awaitUntil(() -> telemetryStore.get(1).size() == 2);
        assertThat(telemetryStore.get(1))
                .extracting(Telemetry::vibration)
                .containsExactlyInAnyOrder(70.0, 80.0);
        assertThat(telemetryStore.get(1)).allMatch(telemetry -> telemetry.timestamp() == 5000L);
    }

    @Test
    void stateMessage_updatesTheLineStateStore() throws Exception {
        LineStateUpdate lineStateUpdate = new LineStateUpdate(1, OperationalState.WAITING_FOR_CALIBRATION, 6000L, 0.50);
        mqttClient.publish(Topics.state(1), new MqttMessage(jsonMapper.writeValueAsBytes(lineStateUpdate)));
        awaitUntil(() -> lineStateStore.get(1) != null);
        assertThat(lineStateStore.get(1)).isEqualTo(OperationalState.WAITING_FOR_CALIBRATION);
    }

    @Test
    void subscriber_receivesFromMultipleLines_independently() throws Exception {
        mqttClient.publish(Topics.state(1), new MqttMessage(jsonMapper.writeValueAsBytes(new LineStateUpdate(1, OperationalState.UNDER_CALIBRATION, 1L, null))));
        mqttClient.publish(Topics.state(2), new MqttMessage(jsonMapper.writeValueAsBytes(new LineStateUpdate(2, OperationalState.FULLY_OPERATIONAL, 1L, null))));
        awaitUntil(() -> lineStateStore.get(1) != null && lineStateStore.get(2) != null);
        assertThat(lineStateStore.get(1)).isEqualTo(OperationalState.UNDER_CALIBRATION);
        assertThat(lineStateStore.get(2)).isEqualTo(OperationalState.FULLY_OPERATIONAL);
    }

    @Test
    void malformedPayload_isLoggedAndIgnored_doesNotCrashTheSubscriber() throws Exception {
        mqttClient.publish(Topics.telemetry(1), new MqttMessage("malformed".getBytes()));
        TelemetryBatch telemetryBatch = new TelemetryBatch(1, 1L, List.of(30.0), OperationalState.FULLY_OPERATIONAL);
        mqttClient.publish(Topics.telemetry(1), new MqttMessage(jsonMapper.writeValueAsBytes(telemetryBatch)));
        awaitUntil(() -> !telemetryStore.get(1).isEmpty());
        assertThat(telemetryStore.get(1)).hasSize(1);
        assertThat(telemetryStore.get(1).get(0).vibration()).isEqualTo(30.0);
    }

    @Test
    void defaultBrokerUrl_isTopicsBrokerUrl() {
        assertThat(MqttSubscriber.defaultBrokerUrl()).isEqualTo(Topics.BROKER_URL);
    }

    @Test
    void publicConstructor_canBeInvoked_regardlessOfWhetherADefaultBrokerIsPresent() {
        MqttSubscriber subscriber = null;
        try {
            subscriber = new MqttSubscriber("test-ctor-" + System.nanoTime(), telemetryStore, lineStateStore);
        } catch (MqttException ignored) {} finally {
            if (subscriber != null) {
                try {
                    subscriber.close();
                } catch (MqttException ignored) {}
            }
        }
    }

    @Test
    void subscriberCallback_connectionLostAndDeliveryComplete_runWithoutError() {
        MqttSubscriber.SubscriberCallback callback = mqttSubscriber.new SubscriberCallback();
        callback.connectionLost(new RuntimeException("simulated disconnection"));
        callback.deliveryComplete(null);
    }

    @Test
    void connectionLost_logsTheCause() {
        PrintStream err = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        try {
            MqttSubscriber.SubscriberCallback callback = mqttSubscriber.new SubscriberCallback();
            callback.connectionLost(new RuntimeException("simulated disconnection"));
            String output = capturedErr.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("MQTT connection lost").contains("simulated disconnection");
        } finally {
            System.setErr(err);
        }
    }

    @Test
    void deliveryComplete_isANoOp_doesNotThrow() {
        MqttSubscriber.SubscriberCallback callback = mqttSubscriber.new SubscriberCallback();
        assertThatCode(() -> callback.deliveryComplete(null)).doesNotThrowAnyException();
    }

    @Test
    void dispatch_onATopicMatchingNeitherPattern_doesNothing() {
        mqttSubscriber.dispatch("smartfab/line/1/unrelated", "irrelevant".getBytes());
        assertThat(telemetryStore.get(1)).isEmpty();
        assertThat(lineStateStore.get(1)).isNull();
    }

}
