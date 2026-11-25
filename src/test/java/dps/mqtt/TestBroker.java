package dps.mqtt;

import io.moquette.broker.Server;
import java.util.Properties;

/**
 * Starts a single in-process Moquette broker, shared by every MQTT
 * integration test class in the same test JVM, listening on a
 * dedicated test-only port — deliberately NOT {@link Topics#BROKER_URL}'s
 * port (1883), since a real Mosquitto broker may already be running
 * there on the development machine, which would make binding fail.
 * {@link MqttPublisher} and {@link MqttSubscriber} have a
 * package-visible constructor overload accepting this broker's URL
 * for exactly this purpose.
 *
 * <p>Deliberately a JVM-wide singleton, started lazily on first use
 * and never explicitly stopped: starting/stopping a real network
 * server per test class is fragile — the OS may not release the port
 * instantly after {@code stopServer()} returns. Since the whole test
 * JVM is short-lived (one Gradle test run) and exits right after the
 * suite finishes, letting it hold the port for the run's duration is
 * simpler and reliable.
 */
public final class TestBroker {

    /** Test-only broker address, distinct from {@link Topics#BROKER_URL}. */
    public static final String TEST_BROKER_URL = "tcp://localhost:18830";
    private static final String TEST_PORT = "18830";

    private static volatile boolean started = false;
    private static final Object LOCK = new Object();

    private TestBroker(){}

    /**
     * Starts the shared broker if it isn't already running. Safe to
     * call from every test class's {@code @BeforeAll} — only the
     * first call actually starts anything.
     *
     * @throws Exception if the broker fails to start
     */
    public static void start() throws Exception{
        if (started) {
            return;
        }
        synchronized (LOCK) {
            if (started) {
                return;
            }
            Server server = new Server();
            Properties confProperties = new Properties();
            confProperties.setProperty("port", TEST_PORT);
            confProperties.setProperty("host", "0.0.0.0");
            confProperties.setProperty("allow_anonymous", "true");
            server.startServer(confProperties);
            started = true;
        }
    }
}
