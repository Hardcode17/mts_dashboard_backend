package ru.hardcode.itsmmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "itsm.mock.batch-size=2")
@AutoConfigureMockMvc
@Import(ControlledScheduler.class)
@Testcontainers(disabledWithoutDocker = true)
class SnapshotContainerIT {
    private static final Path STATE = temporaryState();

    @Container
    static final GenericContainer<?> RECEIVER = new GenericContainer<>(DockerImageName.parse("python:3.13-alpine"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("receiver.py"), "/receiver.py")
            .withCommand("python", "/receiver.py")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/requests"));

    @Autowired TaskScheduler taskScheduler;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry props) {
        props.add("itsm.mock.target-url", () -> baseUrl() + "/api/integrations/itsm/batches");
        props.add("itsm.mock.token-url", () -> baseUrl() + "/token");
        props.add("itsm.mock.client-id", () -> "test-client");
        props.add("itsm.mock.client-secret", () -> "test-secret");
        props.add("itsm.mock.state-file", STATE::toString);
    }

    @AfterAll
    static void clean() throws IOException {
        Files.deleteIfExists(STATE);
        Files.deleteIfExists(STATE.getParent());
    }

    @Test
    void scheduledSnapshotsRetainBothRowsAndManualRequestSendsAnother() throws Exception {
        var callback = ControlledScheduler.callback(taskScheduler);
        for (int tick = 1; tick <= 3; tick++) {
            callback.run();
        }
        mvc.perform(post("/api/mock/emit")).andExpect(status().isOk());

        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/requests")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        var sent = json.readTree(response.body());
        assertThat(sent.size()).isEqualTo(4);
        var first = sent.get(0);
        var second = sent.get(1);
        var third = sent.get(2);
        assertThat(List.of(first.get("version").asString(), second.get("version").asString(),
                third.get("version").asString(), sent.get(3).get("version").asString()))
                .containsExactly("1", "2", "3", "4");
        assertThat(List.of(first.get("batchId"), second.get("batchId"), third.get("batchId")))
                .doesNotHaveDuplicates();
        assertThat(first.get("sourceId").asString()).isEqualTo("demo-itsm");
        assertThat(first.get("contentType").asString()).isEqualTo("application/json");
        assertThat(first.get("body").size()).isEqualTo(2);
        assertThat(second.get("body").size()).isEqualTo(2);
        assertThat(first.get("body")).isNotEqualTo(second.get("body"));
        assertThat(second.get("body")).isEqualTo(third.get("body"));
    }

    private static String baseUrl() {
        return "http://" + RECEIVER.getHost() + ":" + RECEIVER.getMappedPort(8080);
    }

    private static Path temporaryState() {
        try {
            return Files.createTempDirectory("itsm-mock-container-").resolve("state.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
