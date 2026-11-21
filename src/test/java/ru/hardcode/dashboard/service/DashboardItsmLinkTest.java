package ru.hardcode.dashboard.service;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardItsmLinkTest {

	@Test
	void buildsTheCardLinkForExactlyTheGivenIncident() {
		var link = new DashboardItsmLink("https://itsm.example.org/incidents/{incidentId}", true);

		assertThat(link.forIncident("INC000021095531"))
				.isEqualTo(URI.create("https://itsm.example.org/incidents/INC000021095531"));
		assertThat(link.demo()).isTrue();
		assertThat(new DashboardItsmLink("http://127.0.0.1:8082/api/mock/incidents/{incidentId}", false).demo()).isFalse();
	}

	@Test
	void rejectsTemplatesThatCannotAddressOneIncidentSafely() {
		for (String template : List.of(
				"https://itsm.example.org/incidents",
				"https://itsm.example.org/{incidentId}/{incidentId}",
				"ftp://itsm.example.org/{incidentId}",
				"https://{incidentId}@itsm.example.org/incidents",
				"https://itsm.example.org/incidents?incident={incidentId}&other={incidentId}")) {
			assertThatThrownBy(() -> new DashboardItsmLink(template, true))
					.as("template %s", template)
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
