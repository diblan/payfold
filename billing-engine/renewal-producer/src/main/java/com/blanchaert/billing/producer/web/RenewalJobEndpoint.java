package com.blanchaert.billing.producer.web;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Endpoint(id = "renewal-job")
public class RenewalJobEndpoint {
    private final JobLauncher launcher;
    private final JobExplorer explorer;
    private final Job renewalJob;
    private final ZoneId zone;

    // Launches via the endpoint-only asyncJobLauncher so the POST returns as soon as
    // the JobExecution exists, regardless of scale; the cron scheduler stays on the
    // synchronous default launcher because its advisory lock must span the whole run
    // (see D9 in docs/decisions.md).
    public RenewalJobEndpoint(@Qualifier("asyncJobLauncher") JobLauncher launcher,
                              JobExplorer explorer, Job renewalJob,
                              @Value("${app.timezone:Europe/Brussels}") String tz) {
        this.launcher = launcher;
        this.explorer = explorer;
        this.renewalJob = renewalJob;
        this.zone = ZoneId.of(tz);
    }

    /**
     * POST /actuator/renewal-job — returns immediately with the execution id.
     * Poll status via GET /actuator/renewal-job/{executionId}.
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
                "parameters", djpc.getProperties(params),
                "executionId", exec.getId()
        );
    }

    /**
     * GET /actuator/renewal-job/{executionId} — live status from the JobExplorer.
     * Returns null for an unknown id, which actuator renders as HTTP 404.
     */
    @ReadOperation
    public Map<String, Object> status(@Selector Long executionId) {
        JobExecution execution = explorer.getJobExecution(executionId);
        if (execution == null) {
            return null;
        }
        // LinkedHashMap, not Map.of: startTime/endTime are null while starting/running
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", execution.getId());
        body.put("job", execution.getJobInstance().getJobName());
        body.put("status", execution.getStatus().toString());
        body.put("exitStatus", execution.getExitStatus().getExitCode());
        body.put("startTime", execution.getStartTime());
        body.put("endTime", execution.getEndTime());
        return body;
    }
}
