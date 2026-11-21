package ru.hardcode.dashboard;

import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import ru.hardcode.dashboard.client.ItsmMachineJwtTestConfiguration;
import ru.hardcode.dashboard.service.ItsmApiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "itsm.integration.auth.issuer="+ItsmMachineJwtTestConfiguration.ISSUER,
    "itsm.integration.auth.jwk-set-uri=https://keycloak.test/realms/itsm/protocol/openid-connect/certs",
    "itsm.integration.auth.audience="+ItsmMachineJwtTestConfiguration.AUDIENCE,
    "itsm.integration.auth.client-id="+ItsmMachineJwtTestConfiguration.CLIENT_ID,
    "itsm.integration.auth.import-role="+ItsmMachineJwtTestConfiguration.IMPORT_ROLE,
    "itsm.worker.poll-interval=PT0.05S"})
@Import({ItsmPostgresTestcontainersConfiguration.class,ItsmMachineJwtTestConfiguration.class,DashboardIncidentIT.FixedTime.class})
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Testcontainers
class DashboardIncidentIT extends ItsmWorkerTestSupport {
    @Container
    static final GenericContainer<?> mock = new GenericContainer<>("eclipse-temurin:21-jdk-jammy")
            .withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("itsm.test.mock-jar")),"/app.jar")
            .withCopyFileToContainer(MountableFile.forClasspathResource("itsm/character_problems.json"),"/rows.json")
            .withEnv("ITSM_MOCK_FIXTURE_FILE","/rows.json").withEnv("ITSM_MOCK_SCHEDULED","false")
            .withEnv("ITSM_MOCK_CLIENT_ID","test").withEnv("ITSM_MOCK_CLIENT_SECRET","test")
            .withEnv("ITSM_MOCK_TOKEN_URL","http://localhost/token")
            .withCommand("java","-jar","/app.jar").withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/user"));
    static String base() { return "http://"+mock.getHost()+":"+mock.getMappedPort(8080); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("dashboard.itsm.base-url",DashboardIncidentIT::base);
        r.add("dashboard.itsm.link-template",()->base()+"/itsm_incident/{incidentId}/page");
    }
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock cardClock() { return org.mockito.Mockito.spy(Clock.fixed(Instant.parse("2026-11-21T12:02:29Z"), ZoneOffset.UTC)); }
    }
    @Autowired ItsmApiClient itsm;
    @Autowired Clock clock;
    final RequestPostProcessor human=jwt().jwt(j->j.issuer("https://issuer.test").subject("operator").claim("azp","dashboard-web"));
    void publish(int version) throws Exception {
        UUID id=accept(postBatch(UUID.randomUUID(),"case",version).content(objectMapper.writeValueAsBytes(itsm.incidents())));
        awaitTerminal(id);
        assertStatus(id,"SUCCEEDED");
    }
    @Test void editIsPublishedOnceWithActorAndLiveDetails() throws Exception {
        resetDatabaseAndProcessor();
        publish(1);
        jdbcTemplate.update("INSERT INTO users(identity_issuer,identity_subject,login,display_name) VALUES ('https://issuer.test','operator','operator','Operator')");
        String id="INC000021931151";
        String path="/api/dashboard/incidents/"+id;
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/incidents/INC999999").with(human)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
        mockMvc.perform(get("/api/dashboard/users").with(human)).andExpect(status().isOk()).andExpect(jsonPath("$[0].login").isString());
        long changes=journalCount();
        mockMvc.perform(get(path).with(human)).andExpect(status().isOk())
                .andExpect(jsonPath("$.itsmUrl").value(base()+"/itsm_incident/"+id+"/page"))
                .andExpect(jsonPath("$.remainingSeconds").value(86400)).andExpect(jsonPath("$.overdue").value(false));
        Instant due=Instant.parse("2026-11-22T12:02:29Z");
        for (long offset : new long[]{-1,0,1}) {
            org.mockito.Mockito.doReturn(due.plusSeconds(offset)).when(clock).instant();
            mockMvc.perform(get(path).with(human)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingSeconds").value(-offset)).andExpect(jsonPath("$.overdue").value(offset>0));
        }
        org.mockito.Mockito.doReturn(Instant.parse("2026-11-21T12:02:29Z")).when(clock).instant();
        var http=HttpClient.newHttpClient();
        var page=HttpRequest.newBuilder(URI.create(base()+"/itsm_incident/"+id+"/page")).GET().build();
        var html=http.send(page,HttpResponse.BodyHandlers.ofString());
        assertThat(html.statusCode()).isEqualTo(200);
        assertThat(html.headers().firstValue("Content-Type")).hasValueSatisfying(v->assertThat(v).contains("text/html"));
        assertThat(html.body()).contains(id);
        assertThat(journalCount()).isEqualTo(changes);
        for(String rejected: new String[]{"{}","{\"assignedEmployeeLogin\":\"no-such-person\"}","{\"stateEnum\":\"Other\"}"})
            mockMvc.perform(patch(path).with(human).contentType("application/json").content(rejected))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        assertThat(journalCount()).isEqualTo(changes);
        jdbcTemplate.update("INSERT INTO coordination_tasks(incident_id) VALUES (?)",id);
        mockMvc.perform(patch(path).with(human).contentType("application/json").content("""
                {"assignedEmployeeLogin":"demo2","assignedGroupName":"Support","stateEnum":"Research","solution":"Fixed"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.assignedEmployeeLogin").value("demo2"))
                .andExpect(jsonPath("$.groupMemberships[0].group").value("research")).andExpect(jsonPath("$.overdue").value(false));
        mockMvc.perform(get("/api/dashboard/incidents").param("group","research").with(human)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '"+id+"')].assignedGroupName",org.hamcrest.Matchers.hasItem("Support")));
        assertThat(http.send(page,HttpResponse.BodyHandlers.ofString()).body()).contains("Support","Fixed");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM coordination_tasks WHERE incident_id=?",String.class,id)).isEqualTo("open");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_incident_change WHERE coordinator_id IS NOT NULL",Long.class)).isEqualTo(1);
        publish(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM coordination_tasks WHERE incident_id=?",String.class,id)).isEqualTo("completed");
        assertThat(journalCount()).isEqualTo(changes+1);
        var noDeadline=jdbcTemplate.queryForList("SELECT id FROM itsm_incident WHERE deadline IS NULL",String.class);
        assertThat(noDeadline).hasSize(3);
        for(String key:noDeadline)
            mockMvc.perform(get("/api/dashboard/incidents/"+key).with(human)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingSeconds").isEmpty()).andExpect(jsonPath("$.overdue").value(false));
        mockMvc.perform(patch(path).with(human).contentType("application/json").content("{\"stateEnum\":\"Closed\"}"))
                .andExpect(status().isOk());
        long prior=journalCount();
        mockMvc.perform(patch(path).with(human).contentType("application/json").content("{\"stateEnum\":\"Assigned\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCIDENT_CONFLICT"));
        mock.stop();
        mockMvc.perform(patch(path).with(human).contentType("application/json").content("{\"assignedGroupName\":\"Offline\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ITSM_OFFLINE"));
        assertThat(journalCount()).isEqualTo(prior);
        mockMvc.perform(get(path).with(human)).andExpect(jsonPath("$.stateEnum").value("Closed")).andExpect(jsonPath("$.assignedGroupName").value("Support"));
    }
    long journalCount() { return jdbcTemplate.queryForObject("SELECT count(*) FROM itsm_incident_change",Long.class); }
}
