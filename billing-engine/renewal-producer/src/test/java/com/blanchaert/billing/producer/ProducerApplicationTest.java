package com.blanchaert.billing.producer;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;

@SpringBootTest
@Testcontainers
class ProducerApplicationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsWithRenewalJob() {
        assertThat(applicationContext.getBean("renewalJob", Job.class)).isNotNull();
    }
}
