package com.meetodo.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class UsageReportConverter implements AttributeConverter<UsageReport, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Override public String convertToDatabaseColumn(UsageReport report) {
        if (report == null) return null;
        try { return MAPPER.writeValueAsString(report); }
        catch (JsonProcessingException ignored) { throw new IllegalStateException("Cannot encode usage report"); }
    }
    @Override public UsageReport convertToEntityAttribute(String json) {
        if (json == null) return null;
        try { return MAPPER.readValue(json, UsageReport.class); }
        catch (JsonProcessingException ignored) { throw new IllegalStateException("Cannot decode usage report"); }
    }
}
