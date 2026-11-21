package ru.hardcode.dashboard.service;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DashboardItsmLink {

	private static final String PLACEHOLDER = "{incidentId}";

	private final String template;
	private final boolean demo;

	public DashboardItsmLink(@Value("${dashboard.itsm.link-template}") String template,
			@Value("${dashboard.itsm.link-demo}") boolean demo) {
		URI sample;
		int placeholderOffset = template.indexOf(PLACEHOLDER);
		try {
			sample = URI.create(template.replace(PLACEHOLDER, "INC000000"));
		} catch (IllegalArgumentException ignored) {
			throw invalidTemplate();
		}
		String scheme = sample.getScheme();
		if (placeholderOffset < 0 || placeholderOffset != template.lastIndexOf(PLACEHOLDER)
				|| !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
				|| sample.getHost() == null || sample.getRawUserInfo() != null
				|| placeholderOffset < scheme.length() + 3 + sample.getRawAuthority().length()) {
			throw invalidTemplate();
		}
		this.template = template;
		this.demo = demo;
	}

	URI forIncident(String validIncidentId) {
		// INC numbers contain only ASCII letters and digits; they are already URI-safe.
		return URI.create(template.replace(PLACEHOLDER, validIncidentId));
	}

	boolean demo() {
		return demo;
	}

	private static IllegalArgumentException invalidTemplate() {
		return new IllegalArgumentException(
				"dashboard.itsm.link-template must be an absolute HTTP(S) URL with one {incidentId} outside the authority");
	}
}
