package ru.hardcode.dashboard.exception;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

	@Test
	void appException_returnsStatusCodeAndFormattedMessage() throws Exception {
		var mockMvc = MockMvcBuilders.standaloneSetup(new MissingResourceController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();

		mockMvc.perform(get("/test/resources/42"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("Resource with id 42 not found"));
	}

	@RestController
	static class MissingResourceController {

		@GetMapping("/test/resources/{id}")
		public void getResource(@PathVariable("id") long id) {
			throw new AppException(AppErrors.RESOURCE_NOT_FOUND, Map.of("id", id));
		}
	}
}
