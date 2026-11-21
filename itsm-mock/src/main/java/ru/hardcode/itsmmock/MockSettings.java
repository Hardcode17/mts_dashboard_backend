package ru.hardcode.itsmmock;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("itsm.mock")
record MockSettings(
        @NotNull URI targetUrl,
        @NotNull URI tokenUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]+") String sourceId,
        long seed,
        @Min(1) @Max(500) int batchSize,
        @NotNull Path stateFile,
        Path fixtureFile,
        @NotNull Duration timeout,
        @NotNull Duration retryDelay,
        @Min(1) @Max(5) int maxAttempts) {

    @AssertTrue(message = "target-url and token-url must use HTTP or HTTPS")
    public boolean isHttpConfiguration() {
        return isHttp(targetUrl) && isHttp(tokenUrl);
    }

    @AssertTrue(message = "timeout must be positive and retry-delay must be between zero and 5 seconds")
    public boolean isTimingConfiguration() {
        return timeout != null && !timeout.isZero() && !timeout.isNegative()
                && retryDelay != null && !retryDelay.isNegative()
                && retryDelay.compareTo(Duration.ofSeconds(5)) <= 0;
    }

    private static boolean isHttp(URI uri) {
        return uri != null && uri.getHost() != null
                && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
    }
}
