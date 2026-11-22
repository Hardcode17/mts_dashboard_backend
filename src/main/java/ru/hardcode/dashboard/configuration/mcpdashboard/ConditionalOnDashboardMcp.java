package ru.hardcode.dashboard.configuration.mcpdashboard;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The MCP Dashboard adapter (KAN-86) is exposed only when explicitly enabled. Blank or
 * missing {@code mcp.dashboard.enabled} keeps every related bean out of the context, so
 * {@code /mcp/dashboard} stays fail-closed like the demo evidence transport.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnDashboardMcp.Enabled.class)
public @interface ConditionalOnDashboardMcp {

	final class Enabled implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return context.getEnvironment().getProperty("mcp.dashboard.enabled", Boolean.class, false);
		}
	}
}
