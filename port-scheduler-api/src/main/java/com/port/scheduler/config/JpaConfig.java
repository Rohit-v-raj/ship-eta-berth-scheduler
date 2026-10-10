package com.port.scheduler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on the created/updated timestamps in {@code BaseEntity}. Kept out of
 * the application class so web-only test slices do not need a JPA context.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
