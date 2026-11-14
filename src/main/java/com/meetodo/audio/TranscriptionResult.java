package com.meetodo.audio;

import com.meetodo.usage.UsageCall;

public record TranscriptionResult(String text, UsageCall usage) {
}
