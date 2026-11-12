package com.meetodo.digest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "meetodo.digest.enabled", havingValue = "true")
public class DailyDigestScheduler {
    private final DailyDigestService service;
    public DailyDigestScheduler(DailyDigestService service) { this.service = service; }

    @Scheduled(cron = "${meetodo.digest.cron:0 0 9 * * MON-FRI}", zone = "Asia/Seoul")
    public void send() { service.run(null, false); }
}
