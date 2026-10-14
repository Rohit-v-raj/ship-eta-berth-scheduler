package com.port.scheduler.support;

import com.port.scheduler.dcsa.PortCallEventPublisher;
import com.port.scheduler.dcsa.PortCallEventReceiver;
import com.port.scheduler.config.JpaConfig;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.PortViewService;
import com.port.scheduler.service.VesselVisitService;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test of the persistence layer against real MySQL: the migrations are
 * applied to an empty test schema, Hibernate validates the entities against
 * the result, and each test runs in a transaction that is rolled back.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, CleanMigrateConfig.class, BerthService.class, VesselVisitService.class,
        PortCallEventPublisher.class, PortCallEventReceiver.class, PortViewService.class})
public @interface PersistenceTest {
}
