package com.meetodo.digest;

import java.time.LocalDate;
import com.meetodo.task.Task;
import com.meetodo.task.TaskRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DailyDigestConfigurationTests {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired DailyDigestService service;
    @Autowired DailyDigestRepository digests;
    @Autowired TaskRepository tasks;

    // 검증: local 프로필 밖에서는 수동 발송 API가 노출되지 않는다.
    @Test void manualApiIsAbsentOutsideLocal() throws Exception {
        mvc.perform(post("/api/dev/digest/run")).andExpect(status().isNotFound());
    }

    // 검증: 기본 설정에서는 예약 실행 빈과 스케줄 등록기가 생성되지 않는다.
    @Test void schedulingIsDisabledByDefault() {
        assertThat(context.getBeansOfType(DailyDigestScheduler.class)).isEmpty();
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
    }

    // 검증: 예약식은 서울 평일 9시 기본값과 환경 설정 치환을 지원한다.
    @Test void schedulingUsesConfigurableWeekdaySeoulCron() throws Exception {
        Scheduled schedule = DailyDigestScheduler.class.getMethod("send").getAnnotation(Scheduled.class);
        assertThat(schedule.cron()).isEqualTo("${meetodo.digest.cron:0 0 9 * * MON-FRI}");
        assertThat(schedule.zone()).isEqualTo("Asia/Seoul");
        var friday = LocalDate.of(2026, 11, 13).atTime(9, 0);
        assertThat(CronExpression.parse("0 0 9 * * MON-FRI").next(friday)).isEqualTo(friday.plusDays(3));
    }

    // 검증: 활성화 시 환경 설정의 cron이 실제 예약 작업에 적용된다.
    @Test void enabledScheduleUsesCronOverride() {
        new ApplicationContextRunner().withUserConfiguration(DailyDigestScheduler.class)
                .withBean(DailyDigestService.class, () -> mock(DailyDigestService.class))
                .withPropertyValues("meetodo.digest.enabled=true", "meetodo.digest.cron=0 15 10 * * TUE")
                .run(application -> {
                    assertThat(application).hasSingleBean(DailyDigestScheduler.class);
                    var processor = application.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(processor.getScheduledTasks()).singleElement().satisfies(task ->
                            assertThat(((CronTask) task.getTask()).getExpression()).isEqualTo("0 15 10 * * TUE"));
                });
    }

    // 검증: Notifier가 없으면 대상 유무와 무관하게 외부 전송 없이 SKIPPED다.
    @Test void absentNotifierIsSkipped() {
        LocalDate date = LocalDate.of(2099, 1, 1);
        digests.findByDigestDate(date).ifPresent(digests::delete);
        Task target = tasks.saveAndFlush(Task.create("no notifier target", null, date));
        try {
            var result = service.run(date, false);
            assertThat(result.status()).isEqualTo("SKIPPED");
            assertThat(result.todayCount()).isGreaterThanOrEqualTo(1);
        } finally { tasks.delete(target); }
    }
}
