package io.github.headlesshq.mcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to the bridge of the hmc-mcp-bridge mod inside a running client, see {@code io.github.headlesshq.hmcmcp.bridge.Bridge}.
 */
public final class BridgeClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI base;
    private final String token;

    public BridgeClient(int port, String token) {
        this.base = URI.create("http://127.0.0.1:" + port);
        this.token = token;
    }

    /**
     * Executes an HMC-Specifics command inside the game.
     *
     * @param line           the command line, e.g. {@code gui} or {@code connect localhost 25565}.
     * @param timeoutSeconds how long the game may take to execute the command.
     */
    public Result command(String line, long timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/command?timeout=" + timeoutSeconds))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "text/plain; charset=utf-8")
            .timeout(Duration.ofSeconds(timeoutSeconds + 10))
            .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401) {
            throw new IOException("The hmc-mcp-bridge rejected our token");
        }

        JsonNode json = MAPPER.readTree(response.body());
        return new Result(
            json.path("output").asText(""),
            json.path("timedOut").asBoolean(false),
            json.path("error").isNull() || json.path("error").isMissingNode() ? null : json.path("error").asText()
        );
    }

    /**
     * @return whether the bridge answers.
     */
    public boolean isAlive() {
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/health")).timeout(Duration.ofSeconds(2)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @param output   what the command wrote.
     * @param timedOut whether the command was still running when the timeout expired.
     * @param error    why the command could not be executed, or {@code null}.
     */
    public record Result(String output, boolean timedOut, String error) {
    }

}
