package com.meetodo.rag.index;

import java.time.Instant;

public record RagSourceSnapshot(String documentId, String kind, Long meetingId, Long taskId,
        int chunkIndex, String text, Instant registeredAt, String channelId, String namespace) { }
