package dps.rest;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dps.utility.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link LineRegisterClient} - runs against a real (in-process)
 * {@link HttpServer} - the actual request/response wiring (method, path, body, status handling)
 * is verified end to end.
 */
class LineRegisterClientTest {

    private HttpServer httpServer;
    private LineRegisterClient lineRegisterClient;

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private volatile int nextStatus = 200;
    private volatile String nextBody = "[]";

   @BeforeEach
    void setup() throws IOException {
       httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
       httpServer.createContext("/line", exchange -> {
           lastMethod.set(exchange.getRequestMethod());
           lastPath.set(exchange.getRequestURI().getPath());
           lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
           byte[] responseBytes = nextBody.getBytes(StandardCharsets.UTF_8);
           exchange.sendResponseHeaders(nextStatus, responseBytes.length);
           try (OutputStream os = exchange.getResponseBody()) {
               os.write(responseBytes);
           }
       });
       httpServer.start();
       lineRegisterClient = new LineRegisterClient("localhost", httpServer.getAddress().getPort());
   }

   @AfterEach
    void teardown() {
       httpServer.stop(0);
   }

   @Test
    void register_sendAPostWithTheLineAsJson_andReturnsTheOtherLines() throws Exception {
       nextStatus = 200;
       nextBody = "[{\"id\":2,\"address\":\"localhost\",\"port\":9002}]";
       List<Line> otherLines = lineRegisterClient.register(new Line(1, "localhost", 9001));
       assertThat(lastMethod.get()).isEqualTo("POST");
       assertThat(lastPath.get()).isEqualTo("/line");
       assertThat(lastBody.get()).contains("\"id\":1", "\"address\":\"localhost\"", "\"port\":9001");
       assertThat(otherLines).containsExactly(new Line(2, "localhost", 9002));
   }

   @Test
    void register_emptyNetwork_returnsAnEmptyList() throws Exception {
       nextStatus = 200;
       nextBody = "[]";
       List<Line> otherLines = lineRegisterClient.register(new Line(1, "localhost", 9001));
       assertThat(otherLines).isEmpty();
   }

   @Test
    void register_nonOkStatus_throwsIOExceptionWithTheStatusAndBody() throws Exception {
       nextStatus = 409;
       nextBody = "\"id already in use\"";
       assertThatThrownBy(() -> lineRegisterClient.register(new Line(1, "localhost", 9001)))
               .isInstanceOf(IOException.class)
               .hasMessageContaining("409")
               .hasMessageContaining("id already in use");
   }

   @Test
    void deregister_sendsADeleteToTheLineSpecificPath() throws Exception {
       nextStatus = 200;
       nextBody = "";
       lineRegisterClient.deregister(1);
       assertThat(lastMethod.get()).isEqualTo("DELETE");
       assertThat(lastPath.get()).isEqualTo("/line/1");
   }

   @Test
    void deregister_nonOkStatus_throwsIOException() {
       nextStatus = 404;
       nextBody = "\"not found\"";
       assertThatThrownBy(() -> lineRegisterClient.deregister(1))
               .isInstanceOf(IOException.class)
               .hasMessageContaining("404");
   }
}
