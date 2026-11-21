package ru.hardcode.dashboard.service.deadlinerisk;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * KAN-82 wiring: the model constants from {@code deadline-risk.*} and the pure scorer built from
 * them. Updating the trained KAN-81 coefficients is a config edit;
 * no code changes between trainings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeadlineRiskProperties.class)
public class DeadlineRiskConfiguration {

	@Bean
	public DeadlineRiskScorer deadlineRiskScorer(DeadlineRiskProperties properties) {
		return new DeadlineRiskScorer(properties);
	}
}
