package com.port.scheduler.support;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Rebuilds the test schema from an empty database before the tests run, so
 * every run proves the migrations work from scratch.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CleanMigrateConfig {

    static final String TEST_SCHEMA = "port_core_test";

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            // clean drops everything in the schema: make sure it is the test one.
            try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
                if (!TEST_SCHEMA.equals(c.getCatalog())) {
                    throw new IllegalStateException("Refusing to clean schema '" + c.getCatalog()
                            + "': persistence tests may only wipe " + TEST_SCHEMA);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not verify the test schema before cleaning", e);
            }
            flyway.clean();
            flyway.migrate();
        };
    }
}
