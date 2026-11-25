**Stack**: Java 17, Spring Boot, gRPC/Protobuf, MQTT, REST, Gradle (Kotlin DSL).

**Testing**: JUnit 5, Mockito, AssertJ, MockMvc, JaCoCo (100% line & branch coverage).

**Highlights**: Ricart-Agrawala mutual exclusion over gRPC, SWIM-style failure detection, peer-to-peer coordination with no central controller.

---

### SmartFab - Distributed Smart Factory Simulation

SmartFab is a simulation of a small smart manufacturing plant. Each
production line is a **separate process**. All lines run at the same time
and coordinate with each other over the network, peer-to-peer, with no
central controller. An Administration Server keeps track of every line
and lets a client operator check the factory status.

This project was built for the **Distributed and Pervasive Systems
(DPS2026)** course. It also includes a full automated test suite,
built for the **Verification and Validation of Software (VEC2026)**
course.

### 1. Specification

Each production line has a monitoring sensor that measures vibration.
The sensor produces one measurement per second. Measurements are
grouped into a **sliding window of 8 measurements, with a 50% overlap**
between one window and the next (a new average is computed every 4
new measurements, using the 4 newest plus the 4 previous ones).

If the average vibration v̄ goes above a threshold (θ = 80, i.e.
v̄ > θ), the line must **calibrate itself**. Calibration is simulated
with `Thread.sleep()` for a random duration between 3 and 7 seconds.
While a line is calibrating, **no other line in the whole factory is
allowed to calibrate at the same time** - only one line can use the
"calibration resource" at once, and lines must coordinate *without*
the Administration Server acting as a coordinator. This is a classic
distributed mutual exclusion problem: the assignment requires
implementing the **Ricart-Agrawala algorithm** for it, over gRPC, with
priority given by the line whose data indicate the most critical
condition.

The two optional parts of the assignment are also implemented:
- **Controlled exit**: a line leaving the network on purpose
  must not abandon a calibration already in progress, and must warn
  every other line before disconnecting.
- **Uncontrolled exit (crash handling)**: if a line crashes
  without warning, the other lines must detect this and unblock the
  mutual exclusion, so the factory does not get stuck forever waiting
  for a line that will never reply.

The Administration Server collects telemetry and status from every
line (over MQTT) and handles line registration (over REST), and
answers queries from a client operator through a small command-line
Administration Client.

### 2. How it works (expected behavior)

#### 2.1 Production line lifecycle

Every line moves through three states:

```
FULLY_OPERATIONAL  ->  WAITING_FOR_CALIBRATION  ->  UNDER_CALIBRATION  ->  FULLY_OPERATIONAL  -> ...
```

- **FullyOperational**: the sensor is running. The line keeps sampling
  until the average vibration goes over `THETA`. While waiting or
  calibrating, newly computed averages do not trigger additional
  requests.
- **WaitingForCalibration**: the sensor is paused
  (`pauseMeasuring()`) and its buffer cleared. The line computes its
  criticality once, asks every other known line for permission to
  calibrate (a `Calibration` gRPC request), then waits for every
  needed reply.
- **UnderCalibration**: the line has permission. It "calibrates" for
  a random 3-7 second delay, then resumes the sensor
  (`startMeasuring()`), releases the resource, and notifies whoever
  it had made wait.

#### 2.2 Mutual exclusion: Ricart-Agrawala algorithm

Priority is based on **criticality**, computed once when a line enters
`WaitingForCalibration` and held fixed until it leaves
`UnderCalibration`:

```
criticality = (v̄ - θ) / θ
```

where v̄ is the latest sliding-window average and θ = 80. Higher
criticality wins over lower. If two lines have exactly the same
criticality, **the line with the higher id wins** (this is a
tie-break rule).

When line A asks line B for permission:
- If B is not calibrating and B's own request (if any) has lower
  priority than A's, B replies immediately ("go ahead").
- If B is currently `UnderCalibration`, B defers its answer and tells
  A exactly how many milliseconds are left (a **precise lease**),
  computed from B's own calibration start time and duration.
- If B is itself `WaitingForCalibration` with a higher-priority
  request, B defers too, but this time it cannot give an exact time
  (it does not know how long its own wait will take).

A line only enters `UnderCalibration` once it has collected every
needed reply.

#### 2.3 Failure detection (crash handling)

A line waiting for a reply does not wait forever. A watchdog
(`PendingReplyMonitor`) is started for each pending reply, using one
of three strategies, depending on what the other line answered:

- **Lease strategy**: the other line gave a precise remaining time (it
  is `UnderCalibration`). The watchdog checks once, right after that
  time (plus a small safety margin).
- **Retry strategy**: the other line is itself waiting, with no known
  deadline. The watchdog pings it again and again, every few seconds.
- **Fallback strategy**: an immediate reply was expected. The watchdog
  checks once, after a fixed timeout.

If a ping to a line fails, the failure is not trusted right away. The
suspecting line asks every *other* known line to try reaching the
suspected one too (a small, SWIM-style indirect check). Only if nobody can reach it is the line declared
dead, avoiding false alarms from a single bad connection rather than a
real crash.

Once a line is confirmed dead:
- it is removed from the local view of the network,
- the mutual-exclusion algorithm is unblocked as if the missing reply
  had arrived (discarding its pending request if it was
  `WaitingForCalibration`, or letting another line into calibration if
  it was the one `UnderCalibration`),
- the Administration Server is told to remove it,
- the news is spread to every other known line (one-hop gossip is
  enough, since every line already knows every other line directly in
  this fully-connected topology).

#### 2.4 Controlled exit

A line can leave the network on purpose by typing `exit` in its
console. It first completes any calibration already in progress, or cancels its own pending request if
it was still waiting; then it notifies every other line; then it asks
the Administration Server to be removed. Concretely: if `FullyOperational`,
it leaves right away. If `WaitingForCalibration`, its wait is
interrupted and its own pending request is canceled. If
`UnderCalibration`, it **finishes the calibration first** - a resource
already in use is never abandoned midway.

#### 2.5 Administration Server

The Administration Server is a small Spring Boot application:

- **Production Line REST Interface** (`LineRegisterController`):
  a line registers itself when it starts (`POST /line`) and, if there
  is no other line with the same id, gets back the list of lines
  already in the network. It deregisters when it leaves or crashes
  (`DELETE /line/{id}`).
- **Production Line MQTT Interface** (`MqttSubscriber`): every
  line publishes its telemetry (every 10 seconds) and its state
  changes (immediately, on every transition) over MQTT. The server
  keeps the latest state and all telemetry per line in memory,
  synchronizing reads and writes as the assignment requires.
- **Administration Client REST Interface**
  (`AdminClientController`): read-only queries for the operator - the
  full list of lines, one line's current state, and the average
  vibration of a line between two given timestamps.

#### 2.6 Administration Client

A small interactive console application (`AdminClientCLI`) talks to
the Administration Server over REST, printing a menu with the three
queries above and looping until the user quits. Invalid input (a bad
menu choice, a non-numeric id, `from > to`) is reported with a clear
message - the application never crashes on bad user input.

### 3. Project structure

```
src/main/proto/smartfab.proto      gRPC service and message definitions

src/main/java/dps/
├── Main.java                      Spring Boot entry point (Administration Server)
├── pline/
│   └── ProductionLine.java        a single production line's whole lifecycle
├── grpc/
│   ├── RicartAgrawalaCoordinator  the mutual exclusion algorithm (pure logic)
│   ├── GrpcClientPool             outbound gRPC calls + failure detection
│   ├── CoordinationServiceImpl    inbound gRPC handlers (server side)
│   ├── PendingReplyMonitor        watchdogs for replies that may never arrive
│   ├── LineMapper                 converts between domain and protobuf types
│   └── CalibrationRequestOutcome  small value type (deferred / lease info)
├── mqtt/
│   ├── MqttPublisher               publishes telemetry and state (line side)
│   ├── MqttSubscriber              receives and stores them (server side)
│   └── Topics                      topic names, QoS levels, broker address
├── rest/
│   ├── LineRegisterController      registration REST endpoint
│   ├── LineRegisterClient          registration REST client (line side)
│   ├── AdminClientController       read-only query REST endpoint
│   ├── AdminClientCLI              interactive console client
│   └── Generated                   marker annotation, see (JaCoCo exclusions)
├── sensor/                         course-provided, unmodified 
│   ├── Simulator                   generates fake vibration measurements
│   ├── MonitoringSensor            runs the simulator on its own thread
│   ├── SlidingWindow                8-measurement, 50%-overlap buffer (implemented)
│   ├── Buffer                       interface implemented by SlidingWindow
│   └── Measurement                  one sensor reading
└── utility/
    ├── Line, OperationalState,
    │   Telemetry, TelemetryBatch,
    │   LineStateUpdate, LineSnapshot  plain domain types (mostly records)
    ├── LineRegistry                   in-memory list of registered lines
    ├── LineStateStore                 in-memory latest state per line
    ├── TelemetryStore                 in-memory telemetry history per line
    └── Log                            small shared logging helper

src/test/java/dps/...               mirrors the main package structure,
                                     one test class per tested component
```

### 4. Gradle configuration

The build uses **Gradle with the Kotlin DSL** (`build.gradle.kts`).

**Plugins**
- `java`, `application` - a normal, runnable Java project.
- `com.google.protobuf` - generates Java classes from `smartfab.proto`
  (messages and gRPC stubs) into `proto.generated`.
- `org.springframework.boot` + `io.spring.dependency-management` - the
  Administration Server is a Spring Boot app; this also manages
  consistent versions for every Spring-related dependency.
- `jvm-test-suite` - the modern Gradle way to declare a test suite. It
  lets Gradle pick a matching, correctly-paired version of
  `junit-jupiter` and `junit-platform-launcher` automatically, instead
  of declaring them by hand (this avoided a real version-mismatch
  problem during development).
- `jacoco` - test coverage reports.

**Main dependencies**: `protobuf-java` and the gRPC libraries (client
and server), `spring-boot-starter-web` (REST), `spring-boot-starter-webflux`
(used for its `WebClient`, for simple async HTTP calls),
`org.eclipse.paho:...mqttv3` (MQTT client, chosen because the Mosquitto/MQTT as the broker).

**Test dependencies** (declared inside the `test` suite, not as plain
`testImplementation`, following the `jvm-test-suite` style): AssertJ
(fluent assertions), Mockito + `mockito-junit-jupiter` (mocking),
`spring-boot-starter-test` and `spring-boot-starter-webmvc-test` (Spring
Boot 4 moved `@WebMvcTest`/MockMvc support into its own starter - it is
no longer inside `spring-boot-starter-test`), and
`io.moquette:moquette-broker` (a real MQTT broker that runs inside the
test process itself, so MQTT tests do not need a real Mosquitto broker
running on the machine).

**JaCoCo report**: generated automatically after `./gradlew test`
(attached with `finalizedBy`). Coverage is currently **100%** (line and
branch) across every included package. A handful of classes are
deliberately excluded from the coverage numbers, listed below with the
reason for each - none of them are excluded because they were "hard to
test and skipped"; each has a specific, stated reason:

| Excluded                                                                                             | Why                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `proto.generated.**`                                                                                 | protoc/protoc-gen-grpc-java output - never hand-written.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `dps.sensor.Simulator`, `dps.sensor.MonitoringSensor`, `dps.sensor.Measurement`, `dps.sensor.Buffer` | Course-provided code.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `dps.Main`                                                                                           | The Spring Boot entry point - a single `SpringApplication.run(...)` call, with no logic of its own to verify.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `dps.pline.ProductionLine`                                                                           | Orchestrates real threads, a real gRPC server, and a real MQTT connection in a state machine driven by real-time delays (1s sampling, 10s telemetry - a full cycle takes tens of seconds). Verified instead via manual multi-process end-to-end testing (starting several lines and the Administration Server together, killing one on purpose, issuing `exit` on another, and checking the logs). Its one pure-logic method, `computeCriticality`, is unit-tested (`ProductionLineTest`) but excluded here along with the rest of the class for simplicity.                                                                                                                                                                                                                                                                                                     |
| `dps.rest.AdminClientCLI#dispatchOption` only                              | A tiny extracted method containing only the option-dispatch `switch`. Its "no case matched" branch is provably unreachable - `menu()` only ever calls it with an option already validated to be in `[1,4]` - so forcing a test through it would mean deliberately bypassing that validation, testing a state the code guarantees can't happen. Marked with a small local `@Generated` annotation (`dps.rest.Generated`, retention `CLASS` so JaCoCo - which only recognizes annotations that actually survive into the `.class` file - picks it up; the JDK's own `javax.annotation.processing.Generated` would **not** work here, since it is source-only and never reaches the bytecode at all). Excluding only this one small method, rather than the whole class, keeps the rest of `AdminClientCLI` - which has real, meaningful behaviour - fully counted. |

### 5. Design choices

A few decisions are worth explaining, because they are not the only
possible choice:

- **Criticality instead of a logical clock.** The standard
  Ricart-Agrawala algorithm uses a Lamport timestamp for priority.
  Here, priority is the computed criticality instead, per the
  assignment's own rule - "priority is given to the line
  whose telemetry data indicate the most critical condition" - with
  line id as a tie-break only when two criticalities are exactly
  equal.

- **Three separate watchdog strategies**, instead of one fixed timeout
  for every case. A line that is already `UnderCalibration` can report
  an exact remaining time, so waiting exactly that long (plus a small
  margin) detects a real problem faster than a generic timeout would,
  without raising false alarms too early.

- **Indirect confirmation before declaring a crash** (a small
  SWIM-style check), instead of trusting a single failed call. A
  single network hiccup should not remove a perfectly healthy line
  from the factory.

- **Broadcasts run in parallel, not sequentially** (one thread per
  target line in `GrpcClientPool.broadcast`), per the assignment's
  explicit requirement ("If broadcast communications are
  required among nodes, these must be carried out in parallel and not
  sequentially").

- **Synchronization implemented from scratch**, using only the
  primitives covered in the lab (`synchronized`, `wait`/`notify`,
  plain `Thread`), per the assignment's constraint that
  synchronization structures must be self-built rather than taken from
  `java.util.concurrent`'s higher-level utilities, for educational
  purposes.

- **Package-visible "testing" constructors**, used in a few classes
  (`PendingReplyMonitor`, `MqttPublisher`, `MqttSubscriber`) that need
  a real network resource (a timeout, a broker address) which cannot
  be mocked easily. The public constructor keeps the real, production
  value; a second, package-visible constructor lets tests use a
  different value (a much shorter timeout, or a local test broker) -
  production behavior never changes, only what a test in the same
  package can configure.

- **Real, lightweight servers instead of mocks for integration
  tests.** MQTT tests run against a real embedded broker (Moquette);
  REST client tests run against the JDK's own built-in `HttpServer`;
  some gRPC tests run against a real gRPC server on a real port. This
  checks the actual wire behavior (serialization, status codes, real
  network calls), not just that some method was called - closer to
  what could really go wrong.

### 6. Running the project

Start the Administration Server first:

```
./gradlew bootRun
```
(or run `dps.Main` directly from the IDE)

Then start one or more production lines - each **as its own process**, each
needing its own id, address, port, and the Administration Server's
address and port. The simplest way is a dedicated Run Configuration
per line in the IDE, passing those arguments.

A Mosquitto (or any MQTT) broker must be running locally on
port 1883 for the real system to work — this is separate from the
test suite, which uses its own embedded broker and does not need
Mosquitto.

Run the Administration Client the same way, from the IDE.

### 7. Running the tests

```
./gradlew test
```

This runs the whole automated suite (unit tests for the pure logic,
Mockito-based tests for gRPC components, Spring MockMvc tests for the
REST layer, and real-broker/real-server integration tests for MQTT and
gRPC), and generates a JaCoCo coverage report at
`build/reports/jacoco/test/html/index.html` - currently 100% line and
branch coverage across every included package.