package ru.hardcode.dashboard.configuration.filter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LoggerFilterTest.EchoController.class,
		properties = "request-logging.max-cached-body-size=64B")
@Import(LoggerFilterTest.EchoController.class)
@WithMockUser
class LoggerFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void jsonRequest_preservesBodyAndReturnsRequestId() throws Exception {
		var response = mockMvc.perform(post("/test/echo").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"value":"dashboard"}
								"""))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(jsonPath("$.value").value("dashboard"))
				.andReturn().getResponse();

		assertThat(response.getHeader("X-Request-Id")).isEqualTo(response.getHeader("X-Test-Mdc"));
		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void oversizedJsonRequest_returnsStandardApiError() throws Exception {
		mockMvc.perform(post("/test/echo").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"value\":\"" + "a".repeat(64) + "\"}"))
				.andExpect(status().is(413))
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"))
				.andExpect(jsonPath("$.message").value("Request body exceeds 64 bytes"));
	}

	@RestController
	static class EchoController {

		@PostMapping("/test/echo")
		org.springframework.http.ResponseEntity<EchoBody> echo(@RequestBody EchoBody body) {
			return org.springframework.http.ResponseEntity.ok()
					.header("X-Test-Mdc", MDC.get("requestId"))
					.body(body);
		}
	}

	record EchoBody(String value) {
	}
}
