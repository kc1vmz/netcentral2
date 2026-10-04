package com.kc1vmz.netcentral.communication;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoutingServiceTest {
    @Test
    void routeAssignmentsLoadWithOnlyOneDatabaseConnection() {
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:route-single-connection");
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(500);
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE TABLE communication_route(owner_key VARCHAR PRIMARY KEY, version BIGINT, mode VARCHAR)");
            jdbc.execute("CREATE TABLE communication_route_instance(owner_key VARCHAR, instance_id VARCHAR)");
            jdbc.update("INSERT INTO communication_route VALUES('DEFAULT',0,'SELECTED')");
            jdbc.update("INSERT INTO communication_route_instance VALUES('DEFAULT','radio-1')");
            var routing = new RoutingService(jdbc,mock(CommunicationRepository.class),mock(CommunicationService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
            assertThat(routing.load("DEFAULT").instanceIds()).containsExactly("radio-1");
            assertThat(routing.load("OBJECT:EXAMPLE").instanceIds()).containsExactly("radio-1");
        }
    }
}
