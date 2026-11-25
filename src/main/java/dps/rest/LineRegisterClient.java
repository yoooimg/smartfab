package dps.rest;

import dps.utility.Line;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * REST client used by a Production Line to register itself with the
 * Administration Server ({@code LineRegisterController}) at startup.
 */
public class LineRegisterClient {

    private final String adminServerUrl;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * @param adminServerHost Administration Server host
     * @param adminServerPort Administration Server port
     */
    public LineRegisterClient(String adminServerHost, int adminServerPort) {
        this.adminServerUrl = "http://" + adminServerHost + ":" + adminServerPort + "/line";
    }

    /**
     * Registers a line with the Administration Server.
     *
     * @param self this line's own network identity.
     * @return the lines already present in the network.
     * @throws IOException if the registration fails (network error
     *                     or non-2xx response, e.g. duplicate id).
     * @throws InterruptedException if the request is interrupted.
     */
    public List<Line> register(Line self) throws IOException, InterruptedException {
        String body =  jsonMapper.writeValueAsString(self);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminServerUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Registration failed, status " + response.statusCode() + ": " + response.body());
        }

        return List.of(jsonMapper.readValue(response.body(), Line[].class));
    }

    /**
     * Deregisters this line from the Administration Server.
     *
     * @param id this line's own id
     * @throws IOException if the deregistration fails (network error or
     *                      non-2xx response, e.g. id not found)
     * @throws InterruptedException if the request is interrupted
     */
    public void deregister(int id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminServerUrl + "/" + id))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Deregistration failed, status " + response.statusCode() + ": " + response.body());
        }
    }
}
