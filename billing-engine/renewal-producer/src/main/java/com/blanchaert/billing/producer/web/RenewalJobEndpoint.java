package com.blanchaert.billing.producer.web;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.converter.JobParametersConverter;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component
@Endpoint(id = "renewal-job")
public class RenewalJobEndpoint {
    private final JobLauncher launcher;
    private final Job renewalJob;
    private final ZoneId zone;

    public RenewalJobEndpoint(JobLauncher launcher, Job renewalJob,
                              @Value("${app.timezone:Europe/Brussels}") String tz) {
        this.launcher = launcher;
        this.renewalJob = renewalJob;
        this.zone = ZoneId.of(tz);
    }

    /**
     * POST /actuator/renewal-job
     * @param force if true, add a random run.id so you can re-run same-day
     */
    @WriteOperation
    public Map<String, Object> trigger(Boolean force) throws Exception {
        LocalDate d = LocalDate.now(zone);
        JobParametersBuilder b = new JobParametersBuilder()
                .addString("scheduleDate", d.toString());
        if (Boolean.TRUE.equals(force)) {
            b.addString("run.id", UUID.randomUUID().toString());
        }
        JobParameters params = b.toJobParameters();
        var exec = launcher.run(renewalJob, params);
        DefaultJobParametersConverter djpc = new DefaultJobParametersConverter();
        return Map.of(
                "job", exec.getJobInstance().getJobName(),
                "instanceId", exec.getJobInstance().getInstanceId(),
                "status", exec.getStatus().toString(),
                "parameters", djpc.getProperties(params)
        );
    }
}
