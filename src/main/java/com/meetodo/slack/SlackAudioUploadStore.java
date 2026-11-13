package com.meetodo.slack;

import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SlackAudioUploadStore {
    private final SlackAudioUploadRepository uploads;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SlackAudioUploadStore(SlackAudioUploadRepository uploads, Clock clock, PlatformTransactionManager transactions) {
        this.uploads = uploads; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<SlackAudioUpload> find(String fileId) {
        validateFileId(fileId);
        return uploads.findByFileId(fileId);
    }

    // Call only after posting progress. Every first attempt stays claimed, including failures; no automatic retry.
    public boolean claim(String fileId) {
        validateFileId(fileId);
        try {
            transaction.executeWithoutResult(tx -> uploads.saveAndFlush(new SlackAudioUpload(fileId, clock.instant())));
            return true;
        } catch (DataIntegrityViolationException failure) {
            // The failed insert transaction has ended before checking whether another request claimed this file.
            if (uploads.findByFileId(fileId).isPresent()) return false;
            throw failure;
        }
    }

    public void finish(String fileId, Long meetingId, String errorCode) {
        validateFileId(fileId);
        if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,79}"))
            throw new IllegalArgumentException("Invalid audio upload error code");
        transaction.executeWithoutResult(tx -> uploads.findByFileId(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Audio upload not found"))
                .finish(meetingId, errorCode));
    }

    private static void validateFileId(String fileId) {
        if (fileId == null || fileId.length() > 80 || !fileId.matches("F[A-Z0-9]+"))
            throw new IllegalArgumentException("Invalid Slack file ID");
    }
}
