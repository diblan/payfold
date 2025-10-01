package com.blanchaert.billing.producer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.*;

@Configuration
@EnableBatchProcessing
public class RenewalJobConfig {
    @Bean
    public Job renewalJob(JobRepository repo, Step scanStep, Step publishStep) {
        return new JobBuilder("renewalJob", repo)
                .start(scanStep)
                .next(publishStep)
                .build();
    }

    @Bean
    public Step scanStep(JobRepository repo,
                         PlatformTransactionManager tx,
                         JdbcTemplate jdbc,
                         ObjectMapper om,
                         @Value("${app.chunkSize:1000}") int chunkSize,
                         @Value("${app.timezone:Europe/Brussels}") String tz) {
        return new StepBuilder("scanStep", repo)
                .tasklet((contribution, chunkContext) -> {
                    ZoneId zone = ZoneId.of(tz);
                    LocalDate today = LocalDate.now(zone);
                    LocalDateTime start = today.atStartOfDay();
                    LocalDateTime end = today.plusDays(1).atStartOfDay();
                    // Insert into outbox in the same tx; guard against duplicates via (subscription_id, due_date)
                    String sql = """
                            WITH due AS (
                                SELECT s.id AS subscription_id, s.customer_id, s.plan_id,
                                    p.interval, p.price_cents, p.currency,
                                (CASE WHEN p.interval = 'year'
                                    THEN (s.renewed_at + INTERVAL '1 year')
                                    ELSE (s.renewed_at + INTERVAL '1 month')
                                    END) AS due_ts
                                FROM subscription s
                                JOIN plan p ON p.id = s.plan_id
                                WHERE s.status = 'active'
                                AND s.renewed_at IS NOT NULL
                            ), win AS (
                                SELECT *, due_ts AT TIME ZONE ? AS due_local
                                FROM due
                            )
                            INSERT INTO renewal_outbox (subscription_id, due_date, payload)
                            SELECT subscription_id,
                                (due_local)::date AS due_date,
                                jsonb_build_object(
                                    'subscription_id', subscription_id,
                                    'customer_id', customer_id,
                                    'plan_id', plan_id,
                                    'interval', interval,
                                    'amount_cents', price_cents,
                                    'currency', currency
                                ) AS payload
                            FROM win
                            WHERE due_local >= ? AND due_local < ?
                            ON CONFLICT (subscription_id, due_date) DO NOTHING;
                            """;
                    int inserted = jdbc.update(con -> {
                        var ps = con.prepareStatement(sql);
                        ps.setString(1, zone.getId());
                        ps.setObject(2, start);
                        ps.setObject(3, end);
                        return ps;
                    });
                    System.out.println("Outbox rows inserted: " + inserted);
                    return RepeatStatus.FINISHED;
                }, tx).build();
    }

    @Bean
    public Step publishStep(JobRepository repo,
                            PlatformTransactionManager tx,
                            JdbcTemplate jdbc,
                            OutboxPublisher publisher) {
        return new StepBuilder("publishStep", repo)
                .tasklet((contribution, chunkContext) -> {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "SELECT id, payload FROM renewal_outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 5000");
                    int n = 0;
                    for (var r : rows) {
                        UUID id = (UUID) r.get("id");
                        String payload = r.get("payload").toString();
                        if (publisher.publish(payload)) {
                            jdbc.update("UPDATE renewal_outbox SET published_at = now() WHERE id = ? ", id);
                            n++;
                        }
                    }
                    System.out.println("Published messages: " + n);
                    return RepeatStatus.FINISHED;
                }, tx).build();
    }
}