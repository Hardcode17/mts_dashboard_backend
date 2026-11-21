package ru.hardcode.dashboard.worker;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * Starts the KAN-54 worker only when the application provides a real {@link ItsmBatchProcessor}
 * bean (KAN-55). The conditions are evaluated by Spring Boot's condition infrastructure against
 * the bean definitions known before regular beans are created, so a processor registered by any
 * configuration, module or test enables the worker regardless of evaluation order.
 *
 * <p>Without a processor the worker never starts and HTTP intake keeps
 * storing batches as {@code RECEIVED}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "itsm.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ItsmBatchProcessor.class)
@Import(ItsmWorkerConfiguration.class)
public class ItsmWorkerAutoConfiguration {
}
