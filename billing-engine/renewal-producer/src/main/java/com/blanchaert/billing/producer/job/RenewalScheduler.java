package com.blanchaert.billing.producer.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class RenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(RenewalScheduler.class);
    private static final long RENEWAL_JOB_LOCK_KEY = 0x504159464F4C4437L;

    private final JobLauncher launcher;
    private final Job renewalJob;
    private final ZoneId zone;
    private final DataSource dataSource;

    // Explicitly the SYNC launcher (Batch's default "jobLauncher" bean): runDaily()
    // releases the advisory lock when run() returns, so the launch must not outlive
    // the lock. The async launcher (asyncJobLauncher) is endpoint-only — see D9.
    public RenewalScheduler(@Qualifier("jobLauncher") JobLauncher launcher, Job renewalJob,
                            @Value("${app.timezone:Europe/Brussels}") String tz,
                            DataSource dataSource) {
        this.launcher = launcher;
        this.renewalJob = renewalJob;
        this.zone = ZoneId.of(tz);
        this.dataSource = dataSource;
    }

    // Fire daily at the configured cron (default 03:00 local). Declared in application.yml
    @Scheduled(cron = "${app.scheduleCron}", zone = "${app.timezone:Europe/Brussels}")
    public void runDaily() throws Exception {
        // Session-level advisory locks belong to the DB session, and close() on a
        // pooled connection returns it to the pool without ending the session — an
        // unreleased lock would leak to the next borrower. So: hold ONE dedicated
        // connection for the whole job, acquire and release on it explicitly.
        try (Connection session = dataSource.getConnection()) {
            if (!tryAdvisoryLock(session)) {
                log.info("Renewal job skipped: another producer instance holds the scheduler lock.");
                return;
            }
            try {
                LocalDate d = LocalDate.now(zone);
                JobParameters params = new JobParametersBuilder()
                        .addString("scheduleDate", d.toString())
                        .toJobParameters();
                launcher.run(renewalJob, params);
            } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException e) {
                // Sequential-duplicate window: a peer already ran today's instance
                // before we acquired the lock (e.g. clock skew between instances).
                log.info("Renewal job skipped: scheduleDate already run or running ({}).",
                        e.getClass().getSimpleName());
            } finally {
                releaseAdvisoryLock(session);
            }
        }
    }

    private boolean tryAdvisoryLock(Connection session) throws SQLException {
        try (var statement = session.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, RENEWAL_JOB_LOCK_KEY);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void releaseAdvisoryLock(Connection session) throws SQLException {
        try (var statement = session.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, RENEWAL_JOB_LOCK_KEY);
            try (var result = statement.executeQuery()) {
                result.next();
            }
        }
    }
}
