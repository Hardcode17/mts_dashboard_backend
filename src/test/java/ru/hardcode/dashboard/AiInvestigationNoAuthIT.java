package ru.hardcode.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"itsm.worker.enabled=false",
		"spring.security.user.name=demo-test",
		"spring.security.user.password=demo-test-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(ItsmPostgresTestcontainersConfiguration.class)
class AiInvestigationNoAuthIT {

	@Autowired private MockMvc mvc;

	@Test
	void requiresCoordinatorBearerEvenWhenJwtIsNotConfigured() throws Exception {
		String path = "/api/incidents/INC0000420001/investigations";
		mvc.perform(post(path)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
		mvc.perform(get(path + "/3f9c1d5e-2b47-49a1-8f6c-9d0e1a2b3c4d")
						.with(httpBasic("demo-test", "demo-test-password")))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"));
	}
}
