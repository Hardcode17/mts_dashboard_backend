package ru.hardcode.dashboard.configuration.evidence;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.util.stream.Stream;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** Opt-in evidence is exposed only for a complete incident and service mapping. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnEvidenceMapping.Mapping.class)
public @interface ConditionalOnEvidenceMapping {

	final class Mapping implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			var env = context.getEnvironment();
			return env.getProperty("mcp.evidence.enabled", Boolean.class, false)
					&& Stream.of("scenario-id", "incident-id", "scenario-run-id", "itsm-service-key",
							"log-service-key", "database-service-key")
					.allMatch(key -> StringUtils.hasText(env.getProperty("mcp.evidence.demo." + key)));
		}
	}
}
