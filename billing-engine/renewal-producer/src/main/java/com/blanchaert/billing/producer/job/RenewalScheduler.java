package com.blanchaert.billing.producer.job;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class RenewalScheduler {
    private final JobLauncher launcher;
    private final Job renewalJob;
    private final ZoneId zone;

    public RenewalScheduler(JobLauncher launcher, Job renewalJob,
                            @Value("${app.timezone:Europe/Brussels}") String tz) {
        this.launcher = launcher;
        this.renewalJob = renewalJob;
        this.zone = ZoneId.of(tz);
    }

    // Fire daily at the configured cron (default 03:00 local). Declared in application.yml
    @Scheduled(cron = "${app.scheduleCron}", zone = "${app.timezone:Europe/Brussels}")
    public void runDaily() throws Exception {
        LocalDate d = LocalDate.now(zone);
        JobParameters params = new JobParametersBuilder()
                .addString("scheduleDate", d.toString())
                .toJobParameters();
        launcher.run(renewalJob, params);
    }
}
