package dps.pline;

import dps.grpc.*;
import dps.mqtt.MqttPublisher;
import dps.rest.LineRegisterClient;
import dps.sensor.Buffer;
import dps.sensor.Measurement;
import dps.sensor.MonitoringSensor;
import dps.sensor.SlidingWindow;
import dps.utility.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.IOException;
import java.util.*;

/**
 * A single production line's entire lifecycle, run as a standalone process.
 */
public class ProductionLine {

    private final Line self;

    private volatile OperationalState state;
    private volatile boolean exitRequested = false;
    private Thread stateThread;
    private Thread telemetryThread;

    private List<Line> otherLines;
    private final Buffer buffer;
    private final MonitoringSensor sensor;

    private final List<Double> pendingAverages = new ArrayList<>();

    private static final double THETA = 80;

    private final String adminServerAddress;
    private final int adminServerPort;

    private final LineRegisterClient lineRegisterClient;

    private RicartAgrawalaCoordinator ricartAgrawalaCoordinator;
    private GrpcClientPool grpcClientPool;
    private Server grpcServer;
    private PendingReplyMonitor pendingReplyMonitor;
    private CoordinationServiceImpl coordinationService;

    private MqttPublisher mqttPublisher;

    private final Random random = new Random();

    private final String tag;

    /**
     * Creates a new production line, wiring together all its collaborators
     * (coordination, gRPC connection pool, failure detection, MQTT
     * publisher) — none of them started or connected to the network yet.
     *
     * @param ownId this line's own id
     * @param ownAddress this line's own listening address
     * @param ownPort this line's own listening port (both REST registration and gRPC)
     * @param adminServerAddress Administration Server host
     * @param adminServerPort Administration Server port
     * @throws MqttException if the connection to the MQTT broker fails
     */
    public ProductionLine(int ownId, String ownAddress, int ownPort, String adminServerAddress, int adminServerPort) throws MqttException {
        this.self = new Line(ownId, ownAddress, ownPort);
        this.tag = "ProductionLine " + ownId;
        this.state = OperationalState.FULLY_OPERATIONAL;
        this.buffer = new SlidingWindow();
        this.sensor = new MonitoringSensor(buffer);
        this.adminServerAddress = adminServerAddress;
        this.adminServerPort = adminServerPort;
        this.lineRegisterClient = new LineRegisterClient(adminServerAddress, adminServerPort);
        this.ricartAgrawalaCoordinator = new RicartAgrawalaCoordinator(ownId);
        this.grpcClientPool = new GrpcClientPool(ownId, ricartAgrawalaCoordinator, lineRegisterClient);
        this.pendingReplyMonitor = new PendingReplyMonitor(ownId, grpcClientPool, ricartAgrawalaCoordinator);
        this.grpcClientPool.setPendingReplyMonitor(pendingReplyMonitor);
        this.coordinationService = new CoordinationServiceImpl(ownId, ricartAgrawalaCoordinator, grpcClientPool, pendingReplyMonitor);
        connectMqttPublisher(ownId);
        Log.info(tag, "Ready (address=" + ownAddress + ":" + ownPort + ", admin=" + adminServerAddress + ":" + adminServerPort + ")");
    }

    /**
     * Entry point for a single production line process: reads its own
     * identity and the Administration Server's address from CLI args,
     * then runs the full startup sequence — gRPC server, REST
     * registration, presentation to existing peers, sensor and monitoring
     * loop.
     *
     * @param args ownId, ownAddress, ownPort, adminServerAddress, adminServerPort
     */
    public static void main(String[] args) throws InterruptedException, IOException, MqttException {

        int ownId = Integer.parseInt(args[0]);
        String ownAddress = args[1];
        int ownPort = Integer.parseInt(args[2]);
        String serverAddress = args[3];
        int serverPort = Integer.parseInt(args[4]);

        // Initialization
        ProductionLine pl = new ProductionLine(ownId, ownAddress, ownPort, serverAddress, serverPort);

        // Starts gRPC server
        pl.startGrpcServer();

        // Registration
        pl.registerSelf();

        // Introduce itself to other lines.
        pl.introduceSelf();

        // Starts monitoring sensor
        pl.operate();
    }

    /**
     * Starts a background thread that reads "exit" from stdin and signals
     * this line's intention to leave the network. Interrupts the
     * state thread only if it is currently blocked waiting for calibration
     * access — a calibration already in progress is left to complete
     * naturally.
     */
    private void onExitRequest() {
        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNext()) {
                if ("exit".equals(scanner.nextLine().trim())) {
                    exitRequested = true;
                    if (state == OperationalState.WAITING_FOR_CALIBRATION || state == OperationalState.FULLY_OPERATIONAL) {
                        stateThread.interrupt();
                    }
                    break;
                }
            }
        }, "exit-listener-" + self.id()).start();
    }

    /**
     * Leaves the network in a controlled way: notifies every known
     * peer, stops the sensor, shuts down the gRPC server, deregisters from
     * the Administration Server, and disconnects MQTT.
     *
     * @throws MqttException if disconnection fails
     * @throws IOException if deregistration fails
     * @throws InterruptedException if the deregistration request is interrupted
     */
    private void leaveNetwork() throws MqttException, IOException, InterruptedException {
        Log.info(tag, "Leaving the network...");
        grpcClientPool.broadcastLeave(self);
        stopMonitoring();
        telemetryThread.interrupt();
        grpcServer.shutdown();
        lineRegisterClient.deregister(self.id());
        mqttPublisher.close();
        Log.info(tag, "Left the network");
    }

    /**
     * Runs this line's entire operational lifecycle: repeatedly
     * samples telemetry while FullyOperational, requests and awaits
     * calibration access once a critical condition is detected, performs
     * the simulated calibration, then returns to FullyOperational and
     * starts the cycle again. All three transitions publish a
     * {@code LineStateUpdate} over MQTT. Runs forever, on the calling
     * thread.
     *
     * @throws InterruptedException if interrupted while sampling or
     *                               sleeping through calibration
     * @throws MqttException if a state update fails to publish
     */
    private void operate() throws InterruptedException, MqttException, IOException {
        stateThread = Thread.currentThread();
        onExitRequest();
        telemetryThread = new Thread(() -> {
            try {
                telemetryOperation();
            } catch (InterruptedException e) {
                Log.info(tag, "Telemetry thread stopping (line is leaving)");
            } catch (MqttException e) {
                Log.error(tag, "Telemetry thread stopped: " + e.getMessage());
            }
        }, "telemetry-" + self.id());
        telemetryThread.start();
        stateOperation();
    }

    /**
     * Periodically drains and publishes the telemetry averages accumulated
     * by {@link #startMonitoring()} since the last publication, every 10
     * seconds. Runs forever, on its own thread, independently of
     * {@link #stateOperation()}.
     *
     * @throws InterruptedException if interrupted while sleeping
     * @throws MqttException if a telemetry batch fails to publish
     */
    private void telemetryOperation() throws InterruptedException, MqttException {
        while (true) {
            Thread.sleep(10_000);
            List<Double> averages = readAndClearPendingAverages();
            if (!averages.isEmpty()) {
                Log.info(tag, "Publishing telemetry batch: " + averages.size() + " averages");
                mqttPublisher.publishTelemetry(new TelemetryBatch(self.id(), System.currentTimeMillis(), averages, state));
            }
        }
    }

    /**
     * Accumulates a newly computed sliding-window average, to be included
     * in the next telemetry batch published by {@link #telemetryOperation()}.
     *
     * @param average the computed average to accumulate
     */
    private synchronized void addPendingAverage(double average) {
        pendingAverages.add(average);
    }

    /**
     * Returns a copy of the averages accumulated since the last call, and
     * clears the pending list so the next call starts fresh.
     *
     * @return the averages computed since the last drain (possibly empty)
     */
    private synchronized List<Double> readAndClearPendingAverages() {
        List<Double> averages = new ArrayList<>(pendingAverages);
        pendingAverages.clear();
        return averages;
    }

    /**
     * Drives this line's operational state machine: sample while
     * FullyOperational, request and await calibration access once a
     * critical condition is detected, perform the simulated calibration,
     * then return to FullyOperational. Runs forever, on the calling thread.
     *
     * @throws InterruptedException if interrupted while sampling or
     *                               sleeping through calibration
     * @throws MqttException if a state update fails to publish
     */
    private void stateOperation() throws InterruptedException, MqttException, IOException {

        double criticality = 0.0;

        while (true) {

            if (exitRequested && OperationalState.FULLY_OPERATIONAL.equals(state)) {
                leaveNetwork();
                break;
            }

            if (OperationalState.FULLY_OPERATIONAL.equals(state)) {
                double average;
                try {
                    average = startMonitoring();
                } catch (InterruptedException e) {
                    if (exitRequested) {
                        leaveNetwork();
                        break;
                    }
                    throw e;
                }
                pauseMonitoring();
                criticality = computeCriticality(average);
                Log.info(tag, "Critical condition detected (criticality=" + String.format("%.3f", criticality) + ") — requesting calibration");
                publishState(OperationalState.WAITING_FOR_CALIBRATION, criticality);
                state = OperationalState.WAITING_FOR_CALIBRATION;
            }

            if (OperationalState.WAITING_FOR_CALIBRATION.equals(state)) {
                Map<Integer, CalibrationRequestOutcome> outcomes = broadcastCalibrationRequest(criticality);
                monitorPendingReplies(outcomes);
                try {
                    ricartAgrawalaCoordinator.awaitCalibrationAccess();
                } catch (InterruptedException e) {
                    if (exitRequested) {
                        ricartAgrawalaCoordinator.cancelPendingRequest();
                        leaveNetwork();
                        break;
                    }
                    throw e;
                }
                state = OperationalState.UNDER_CALIBRATION;
            }

            if (OperationalState.UNDER_CALIBRATION.equals(state)) {
                // long durationMs = 1000L * (3 + random.nextInt(5));
                long durationMs = 15000L;
                ricartAgrawalaCoordinator.enterCalibration(durationMs);
                Log.info(tag, "Calibration access granted — entering UnderCalibration (duration=" + durationMs + "ms)");
                publishState(OperationalState.UNDER_CALIBRATION, null);
                Thread.sleep(durationMs);

                List<Integer> toNotify = ricartAgrawalaCoordinator.release();
                broadcastReply(toNotify);

                sensor.startMeasuring();
                publishState(OperationalState.FULLY_OPERATIONAL, null);
                state = OperationalState.FULLY_OPERATIONAL;
                Log.info(tag, "Calibration complete — back to FullyOperational");

                if (exitRequested) {
                    leaveNetwork();
                    break;
                }
            }
        }
    }

    /**
     * Publishes this line's current operational state over MQTT.
     *
     * @param state the new operational state
     * @param criticality the criticality that triggered the request, only
     *                     meaningful for the FullyOperational to
     *                     WaitingForCalibration transition; {@code null}
     *                     otherwise
     * @throws MqttException if the publishing fails
     */
    private void publishState(OperationalState state, Double criticality) throws MqttException {
        mqttPublisher.publishState(new LineStateUpdate(self.id(), state, System.currentTimeMillis(), criticality));
    }

    /**
     * Notifies every line whose calibration request was deferred while
     * this line was UnderCalibration, granting them access now that this
     * line has released the resource.
     *
     * @param toNotify the ids to notify, as already returned by
     *                 {@link RicartAgrawalaCoordinator#release()} — not
     *                 re-fetched here, since {@code release()} is not
     *                 idempotent (a second call would find the deferred
     *                 set already cleared and return nothing)
     */
    private void broadcastReply(List<Integer> toNotify) {
        toNotify.forEach(id -> grpcClientPool.sendReply(id, self.id()));
    }

    /**
     * Starts a failure-detection watchdog for each line that responded to
     * this line's calibration request, so a crashed line doesn't leave
     * this line waiting forever for a Reply that will never arrive.
     *
     * @param outcomes each responding line's id mapped to its calibration
     *                 outcome, as returned by
     *                 {@link GrpcClientPool#broadcastCalibrationRequest}
     */
    private void monitorPendingReplies(Map<Integer, CalibrationRequestOutcome> outcomes) {
        outcomes.forEach((id, outcome) -> pendingReplyMonitor.startWatching(id, outcome));
    }

    /**
     * Registers this line's calibration request with the local
     * coordinator and broadcasts it to every known line.
     *
     * @param criticality this line's computed criticality for the request
     * @return each responding line's id mapped to its calibration outcome
     */
    private Map<Integer, CalibrationRequestOutcome> broadcastCalibrationRequest(double criticality) {
        ricartAgrawalaCoordinator.requestCalibration(criticality);
        return grpcClientPool.broadcastCalibrationRequest(self.id(), criticality);
    }

    /**
     * Registers this line with the Administration Server and stores the
     * lines already present in the network.
     *
     * @throws IOException if the registration request fails
     * @throws InterruptedException if the request is interrupted
     */
    private void registerSelf() throws IOException, InterruptedException{
        this.otherLines = new ArrayList<>(lineRegisterClient.register(self));
        Log.info(tag, "Registered with Administration Server — " + otherLines.size() + " existing line(s)");
    }

    /**
     * Starts this line's gRPC server, exposing {@link CoordinationServiceImpl}
     * on this line's own port so other lines can reach it.
     *
     * @throws IOException if the port cannot be bound.
     */
    private void startGrpcServer() throws IOException {
        grpcServer = ServerBuilder.forPort(self.port())
                .addService(coordinationService)
                .build()
                .start();
        Log.info(tag, "gRPC server started on port " + self.port());
    }

    /**
     * Sends a Presentation to every line already known from registration,
     * so they register this line too and both sides can reach each other.
     */
    private void introduceSelf() {
        otherLines.forEach(otherLine -> grpcClientPool.sendPresentation(otherLine, self));
        if (!otherLines.isEmpty()) {
            Log.info(tag, "Presented to " + otherLines.size() + " line(s)");
        }
    }

    /**
     * Connects MQTT publisher
     * @param ownId line id
     * @throws MqttException if an error occurs communicating with the server.
     */
    private void connectMqttPublisher(int ownId) throws MqttException {
        this.mqttPublisher = new MqttPublisher("line-" + ownId);
    }

    /**
     * Computes the criticality of vibration measurements - used in the Ricart-Agrawala
     * mutual exclusion request - a more critical line wins over a less critical one requesting
     * at the same time.
     *
     * @param average the measured average vibration
     * @return the computed criticality
     */
    static double computeCriticality(double average) {
        return (average - THETA) / THETA;
    }

    /**
     * Repeatedly samples the sliding window, accumulating every computed
     * average for telemetry publication, until a critical condition is
     * detected. Starts the sensor before sampling.
     *
     * @return the average that triggered the critical condition
     * @throws InterruptedException if interrupted while sampling
     */
    private double startMonitoring() throws InterruptedException {

        sensor.startMeasuring();

        boolean isCriticalCondition = false;

        double average = 0.0;

        while (!isCriticalCondition) {

            List<Measurement> window = buffer.readAllAndClear();

            if (window.size() == 8) {
                average = window.stream()
                        .mapToDouble(Measurement::value)
                        .average()
                        .orElse(0.0);

                addPendingAverage(average);
                isCriticalCondition = average > THETA;
            }

            Thread.sleep(1000);
        }

        return average;
    }

    /**
     * Pauses the sensor and discards any measurements accumulated so far,
     * called when this line stops being FullyOperational.
     */
    private void pauseMonitoring() {
        sensor.pauseMeasuring();
        buffer.clear();
    }

    /**
     * Stops the sensor thread entirely. Not currently called anywhere in
     * the normal lifecycle — reserved for a controlled shutdown.
     */
    private void stopMonitoring() {
        sensor.stopMeasuring();
    }
}