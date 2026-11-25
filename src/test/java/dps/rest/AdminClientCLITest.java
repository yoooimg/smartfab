package dps.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AdminClientCLI}, driven end-to-end: a real HTTP httpServer plays
 * the Administration Server's role, and {@code System.in}/{@code System.out} are
 * redirected so the interactive {@link AdminClientCLI#menu()} loop can
 * be scripted and its output asserted, the same way a real terminal
 * session would work.
 */
class AdminClientCLITest {

    private HttpServer httpServer;
    private AdminClientCLI adminClientCLI;

    private final InputStream in = System.in;
    private final PrintStream out = System.out;
    private ByteArrayOutputStream capturedOut;

    private volatile int allStatus = 200;
    private volatile String allBody = "[]";
    private volatile int stateStatus = 200;
    private volatile String stateBody = "\"FULLY_OPERATIONAL\"";
    private volatile int averageStatus = 200;
    private volatile String averageBody = "80.5";

    @BeforeEach
    void setup() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        httpServer.createContext("/line/all", exchange -> respond(exchange, allStatus, allBody));
        httpServer.createContext("/line/state", exchange -> respond(exchange, stateStatus, stateBody));
        httpServer.createContext("/line", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/average/")) {
                respond(exchange, averageStatus, averageBody);
            } else {
                respond(exchange, 404, "\"not found\"");
            }
        });
        httpServer.start();

        adminClientCLI = new AdminClientCLI("localhost", httpServer.getAddress().getPort());
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void teardown() {
        httpServer.stop(0);
        System.setIn(in);
        System.setOut(out);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void input(String script) {
        System.setIn(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
    }

    private String output() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    @Test
    void nonNumericMenuChoice_isReportedAndReAsked() {
        input("non-numeric\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Invalid option, please pick a number between 1 and 4.");
    }

    @Test
    void outOfRangeMenuChoice_isReportedAndReAsked() {
        input("9\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Invalid option, please pick a number between 1 and 4.");
    }

    @Test
    void quit_printsGoodbye() {
        input("4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Bye!");
    }

    @Test
    void listLines_withNoneRegistered_saysSo() {
        allBody = "[]";
        input("1\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("No lines currently registered.");
    }

    @Test
    void listLines_withLinesPresent_printsEachOne() {
        allBody = "[{\"line\":{\"id\":1,\"address\":\"localhost\",\"port\":9001},"
                + "\"state\":\"FULLY_OPERATIONAL\",\"telemetries\":[]},"
                + "{\"line\":{\"id\":2,\"address\":\"localhost\",\"port\":9002},"
                + "\"state\":\"UNDER_CALIBRATION\","
                + "\"telemetries\":[{\"vibration\":72.5,\"timestamp\":1000}]}]";
        input("1\n4\n");
        adminClientCLI.menu();
        String out = output();
        assertThat(out).contains("Line-1").contains("last=n/a");
        assertThat(out).contains("Line-2").contains("1 measurement(s)");
    }

    @Test
    void listLines_serverError_reportsFailure() {
        allStatus = 500;
        allBody = "\"error\"";
        input("1\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Request failed");
    }

    @Test
    void lineState_validId_printsState() {
        stateBody = "\"WAITING_FOR_CALIBRATION\"";
        input("2\n1\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Line-1: WAITING_FOR_CALIBRATION");
    }

    @Test
    void lineState_nonNumericId_isRejectedWithoutCallingTheServer() {
        input("2\nnon-numeric\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Invalid id, must be a number.");
    }

    @Test
    void lineState_unknownId_reportsNotFound() {
        stateStatus = 404;
        input("2\n8\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Request failed").contains("not found");
    }

    @Test
    void lineState_serverError_reportsFailure() {
        stateStatus = 500;
        input("2\n1\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Request failed");
    }

    @Test
    void average_validRange_printsResult() {
        averageBody = "70.0";
        input("3\n1\n1000\n2000\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Line-1 average vibration: 70.0");
    }

    @Test
    void average_nonNumericId_isRejectedWithoutFurtherPrompts() {
        input("3\nnon-numeric\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Invalid id, must be a number.");
    }

    @Test
    void average_nonNumericFrom_isRejected() {
        input("3\n1\nnon-numeric\n2000\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Invalid timestamp, must be a number.");
    }

    @Test
    void average_nonNumericTo_isRejected() {
        input("3\n1\n1000\nnonNumeric\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Invalid timestamp, must be a number.");
    }

    @Test
    void average_fromGreaterThanTo_isRejected() {
        input("3\n1\n2000\n1000\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("'from' must not be greater than 'to'.");
    }

    @Test
    void average_serverError_reportsFailure() {
        averageStatus = 500;
        input("3\n1\n1000\n2000\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Request failed");
    }

    @Test
    void main_noArgs_usesDefaultsAndQuitsCleanly() {
        input("4\n");
        AdminClientCLI.main(new String[]{});
        assertThat(output()).contains("Bye!");
    }

    @Test
    void main_hostOnly_usesDefaultPort() {
        input("4\n");
        AdminClientCLI.main(new String[]{"localhost"});
        assertThat(output()).contains("Bye!");
    }

    @Test
    void main_hostAndValidPort_usesBoth() {
        input("4\n");
        AdminClientCLI.main(new String[]{"localhost", String.valueOf(httpServer.getAddress().getPort())});
        assertThat(output()).contains("Bye!");
    }

    @Test
    void main_invalidPort_fallsBackToDefault_andStillWorks() {
        input("4\n");
        AdminClientCLI.main(new String[]{"localhost", "not-a-port"});
        assertThat(output()).contains("Bye!");
    }

    @Test
    void requestInterruptedMidCall_isReportedAndRestoresTheFlag() {
        Thread.currentThread().interrupt();
        input("1\n4\n");
        adminClientCLI.menu();
        assertThat(output()).contains("Request interrupted.");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }
}