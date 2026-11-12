package com.meetodo.digest;

import com.meetodo.notification.*;
import com.meetodo.task.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DailyDigestService {
    private final DailyDigestRepository digests;
    private final TaskRepository tasks;
    private final ObjectProvider<Notifier> notifiers;
    private final DailyDigestMessageFactory messages;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public DailyDigestService(DailyDigestRepository digests, TaskRepository tasks, ObjectProvider<Notifier> notifiers,
                              DailyDigestMessageFactory messages, Clock clock, PlatformTransactionManager manager) {
        this.digests = digests; this.tasks = tasks; this.notifiers = notifiers; this.messages = messages; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DailyDigestResponse run(LocalDate requestedDate, boolean force) {
        LocalDate date = requestedDate == null ? LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))) : requestedDate;
        // Claim commits before sending; a unique date also blocks concurrent instances.
        DailyDigest digest;
        try {
            digest = transaction.execute(status -> {
                DailyDigest existing = digests.findByDigestDate(date).orElse(null);
                if (existing != null && !force) return null;
                DailyDigest claim = existing == null ? new DailyDigest(date) : existing;
                return digests.saveAndFlush(claim);
            });
        } catch (DataIntegrityViolationException duplicate) {
            return existing(date);
        }
        if (digest == null) return existing(date);

        List<Task> selected = tasks.findDigestTasks(TaskStatus.TODO, date.plusDays(3));
        long overdue = selected.stream().filter(task -> task.getDueDate().isBefore(date)).count();
        long today = selected.stream().filter(task -> task.getDueDate().equals(date)).count();
        long upcoming = selected.size() - overdue - today;
        long noDueDate = tasks.countByStatusAndDueDateIsNull(TaskStatus.TODO);
        digest.start(overdue, today, upcoming, noDueDate);
        transaction.executeWithoutResult(status -> digests.saveAndFlush(digest));
        Notifier notifier = notifiers.getIfAvailable();
        NotificationStatus result = NotificationStatus.SKIPPED;
        if (!selected.isEmpty() && notifier != null) {
            try {
                notifier.send(messages.create(digest, selected));
                result = NotificationStatus.SENT;
            } catch (RuntimeException failure) {
                result = NotificationStatus.FAILED;
            }
        }
        digest.finish(result, result == NotificationStatus.SENT ? clock.instant() : null);
        transaction.executeWithoutResult(status -> digests.saveAndFlush(digest));
        return DailyDigestResponse.from(digest, result.name());
    }

    private DailyDigestResponse existing(LocalDate date) {
        return DailyDigestResponse.from(digests.findByDigestDate(date).orElseThrow(), "ALREADY_SENT");
    }
}
