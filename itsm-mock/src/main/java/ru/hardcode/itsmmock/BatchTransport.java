package ru.hardcode.itsmmock;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class BatchTransport {
    private final MockSettings settings;
    private final ObjectMapper json;
    private final HttpClient client;

    BatchTransport(MockSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    }

    Result send(SnapshotStore.Package pending) throws InterruptedException {
        try {
            String form = "grant_type=client_credentials&client_id=" + formEncode(settings.clientId())
                    + "&client_secret=" + formEncode(settings.clientSecret());
            var tokenRequest = HttpRequest.newBuilder(settings.tokenUrl())
                    .timeout(settings.timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            var tokenResponse = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (!successful(tokenResponse.statusCode())) {
                return Result.rejected("OAuth", tokenResponse.statusCode());
            }
            var token = json.readTree(tokenResponse.body()).path("access_token");
            if (!token.isString() || !token.asString().matches("[A-Za-z0-9._~+/=-]+")) {
                return new Result(false, false, "OAuth response has no valid access token");
            }
            var request = HttpRequest.newBuilder(settings.targetUrl())
                    .timeout(settings.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token.asString())
                    .header("Idempotency-Key", pending.batchId())
                    .header("X-Source-Id", settings.sourceId())
                    .header("X-Source-Version", Long.toString(pending.version()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(pending.body()))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 202
                    ? new Result(true, false, "Ingest HTTP 202")
                    : Result.rejected("Ingest", response.statusCode());
        } catch (IOException failure) {
            return new Result(false, true, "HTTP transport failed: " + failure.getClass().getSimpleName());
        } catch (tools.jackson.core.JacksonException failure) {
            return new Result(false, false, "OAuth returned invalid JSON");
        }
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean successful(int code) {
        return code >= 200 && code < 300;
    }

    record Result(boolean delivered, boolean retryable, String reason) {
        static Result rejected(String stage, int code) {
            return new Result(false, code == 429 || code >= 500 && code < 600, stage + " HTTP " + code);
        }
    }
}
