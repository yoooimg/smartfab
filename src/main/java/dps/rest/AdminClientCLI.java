package dps.rest;

import dps.utility.LineSnapshot;
import dps.utility.OperationalState;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Scanner;

/**
 * Command-line client for the Administration Server: a simple
 * menu-driven console app that queries the REST interface exposed by
 * {@code AdminClientController}.
 */
public class AdminClientCLI {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    private final String adminServerUrl;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * @param adminServerHost Administration Server host
     * @param adminServerPort Administration Server port
     */
    public AdminClientCLI(String adminServerHost, int adminServerPort) {
        this.adminServerUrl = "http://" + adminServerHost + ":" + adminServerPort + "/line";
    }

    /**
     * Entry point: reads the Administration Server's address from CLI
     * args (falling back to {@code localhost:8080} if omitted), then
     * runs the interactive menu until the user quits.
     *
     * @param args optional adminServerHost, adminServerPort
     */
    public static void main(String[] args) {
        String adminServerHost = args.length > 0 ? args[0] : DEFAULT_HOST;
        int adminServerPort = DEFAULT_PORT;
        if (args.length > 1) {
            try {
                adminServerPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[1] + "', using default " + DEFAULT_PORT);
            }
        }
        new AdminClientCLI(adminServerHost, adminServerPort).menu();
    }

    /**
     * Runs the interactive menu loop until the user chooses to quit.
     * Every option is wrapped so that invalid input or a failed request
     * is reported to the user without terminating the application.
     * Package-visible so tests can drive it directly after redirecting {@code System.in}/{@code System.out}.
     */
    void menu() {
        Scanner input = new Scanner(System.in);
        int option;

        do {
            System.out.println();
            System.out.println("1. Get all active lines");
            System.out.println("2. Get a line state by id");
            System.out.println("3. Get the average vibration by id and between two timestamps");
            System.out.println("4. Quit");
            System.out.println();
            System.out.print("Pick a number between [1-4]: ");

            option = readInt(input, -1);
            if (option < 1 || option > 4) {
                System.out.println("Invalid option, please pick a number between 1 and 4.");
                continue;
            }

            try {
                dispatchOption(option, input);
            } catch (IOException e) {
                System.out.println("Request failed: " + e.getMessage());
            } catch (InterruptedException e) {
                System.out.println("Request interrupted.");
                Thread.currentThread().interrupt();
            }

        } while (option != 4);
    }

    /**
     * Runs the action for the given, already-validated menu option.
     * Extracted from {@link #menu()} purely so the switch's
     * "no case matched" branch — structurally unreachable, since
     * {@code menu()} only ever calls this with an {@code option}
     * already checked to be in [1,4] — can be excluded from coverage
     * metrics on its own, without also excluding {@code menu()}'s
     * own, meaningfully-tested logic.
     *
     * @param option the validated menu choice [1-4]
     * @param input the shared console scanner, forwarded to whichever
     *              option needs further input
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    @Generated
    private void dispatchOption(int option, Scanner input) throws IOException, InterruptedException {
        switch (option) {
            case 1 -> printActiveLines();
            case 2 -> printLineState(input);
            case 3 -> printAverageBetween(input);
            case 4 -> System.out.println("Bye!");
        }
    }

    /**
     * Handles menu option 2: reads a line id, queries its current
     * state, and prints it.
     *
     * @param input the shared console scanner
     * @throws IOException if the request fails (e.g. the id doesn't exist)
     * @throws InterruptedException if the request is interrupted
     */
    private void printLineState(Scanner input) throws IOException, InterruptedException {
        System.out.print("Insert the target line id: ");
        Integer targetId = readInt(input, null);
        if (targetId == null) {
            System.out.println("Invalid id, must be a number.");
            return;
        }
        OperationalState state = getLineState(targetId);
        System.out.println("Line-" + targetId + ": " + state);
    }

    /**
     * Handles menu option 3: reads a line id and a timestamp range
     * (inclusive on both ends), queries the average vibration, and
     * prints it.
     *
     * @param input the shared console scanner
     * @throws IOException if the request fails (e.g. the id doesn't exist)
     * @throws InterruptedException if the request is interrupted
     */
    private void printAverageBetween(Scanner input) throws IOException, InterruptedException {
        System.out.print("Insert the target line id: ");
        Integer targetId = readInt(input, null);
        if (targetId == null) {
            System.out.println("Invalid id, must be a number.");
            return;
        }

        System.out.print("from (timestamp, inclusive): ");
        Long from = readLong(input);
        System.out.print("to (timestamp, inclusive): ");
        Long to = readLong(input);

        if (from == null || to == null) {
            System.out.println("Invalid timestamp, must be a number.");
            return;
        }
        if (from > to) {
            System.out.println("'from' must not be greater than 'to'.");
            return;
        }

        double average = getAverageBetween(targetId, from, to);
        System.out.println("Line-" + targetId + " average vibration: " + average);
    }

    /**
     * Handles menu option 1: queries and prints every currently
     * registered line, with a short telemetry summary instead of the
     * full measurement list.
     *
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private void printActiveLines() throws IOException, InterruptedException {
        List<LineSnapshot> activeLines = getActiveLines();
        if (activeLines.isEmpty()) {
            System.out.println("No lines currently registered.");
            return;
        }
        for (LineSnapshot snapshot : activeLines) {
            int count = snapshot.telemetries().size();
            String last = count == 0 ? "n/a" : snapshot.telemetries().get(count - 1).toString();
            System.out.println("Line-" + snapshot.line().id()
                    + " (" + snapshot.line().address() + ":" + snapshot.line().port() + ")"
                    + " | state=" + snapshot.state()
                    + " | telemetry: " + count + " measurement(s), last=" + last);
        }
    }

    /**
     * Reads an integer from the console, tolerating non-numeric input.
     *
     * @param input the console scanner
     * @param fallback value returned (without changing the scanner
     *                 position further) if the next token is not a
     *                 valid integer
     * @return the parsed integer, or {@code fallback} if invalid
     */
    private int readInt(Scanner input, int fallback) {
        try {
            return input.nextInt();
        } catch (InputMismatchException e) {
            input.nextLine();
            return fallback;
        }
    }

    /**
     * @see #readInt(Scanner, int)
     */
    private Integer readInt(Scanner input, Integer fallback) {
        try {
            return input.nextInt();
        } catch (InputMismatchException e) {
            input.nextLine();
            return fallback;
        }
    }

    /**
     * Reads a long from the console, tolerating non-numeric input.
     *
     * @param input the console scanner
     * @return the parsed value, or {@code null} if invalid
     */
    private Long readLong(Scanner input) {
        try {
            return input.nextLong();
        } catch (InputMismatchException e) {
            input.nextLine();
            return null;
        }
    }

    /**
     * Queries the average vibration of a line between two timestamps
     * (both inclusive).
     *
     * @param targetId the line's id
     * @param from range start, inclusive
     * @param to range end, inclusive
     * @return the average vibration in that range
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private double getAverageBetween(int targetId, long from, long to) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminServerUrl + "/" + targetId + "/average/" + from + "/" + to))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("status " + response.statusCode() + " for line " + targetId);
        }
        return jsonMapper.readValue(response.body(), Double.class);
    }

    /**
     * Queries the current operational state of a line.
     *
     * @param targetId the line's id
     * @return the line's current state
     * @throws IOException if the request fails (e.g. the id doesn't exist)
     * @throws InterruptedException if the request is interrupted
     */
    private OperationalState getLineState(int targetId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminServerUrl + "/state/" + targetId))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            throw new IOException("line " + targetId + " not found");
        }
        if (response.statusCode() != 200) {
            throw new IOException("status " + response.statusCode() + " for line " + targetId);
        }
        return jsonMapper.readValue(response.body(), OperationalState.class);
    }

    /**
     * Queries every currently registered line.
     *
     * @return the list of line snapshots
     * @throws IOException if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private List<LineSnapshot> getActiveLines() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminServerUrl + "/all"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("status " + response.statusCode());
        }
        return List.of(jsonMapper.readValue(response.body(), LineSnapshot[].class));
    }
}