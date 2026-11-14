package com.meetodo.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class UsagePricing {
    private final Rates chat;
    private final Rates stt;
    private final Rates embedding;
    public UsagePricing(Environment environment) {
        chat = rates(environment, "CHAT"); stt = rates(environment, "STT"); embedding = rates(environment, "EMBEDDING");
    }
    public UsageReport price(UsageReport report) {
        return new UsageReport(report.chatCalls().stream().map(call -> price(call, chat)).toList(),
                report.transcription() == null ? null : price(report.transcription(), stt),
                report.embeddingCalls().stream().map(call -> price(call, embedding)).toList());
    }
    private UsageCall price(UsageCall call, Rates rates) {
        if (!call.available()) return call.priced(null, "USAGE_UNAVAILABLE");
        if (call.model() == null || !call.model().equals(rates.model)) return call.priced(null, "PRICE_NOT_CONFIGURED");
        BigDecimal cost = null;
        if ("tokens".equals(call.usageType()) && rates.input != null && rates.output != null)
            cost = BigDecimal.valueOf(call.inputTokens()).multiply(rates.input)
                    .add(BigDecimal.valueOf(call.outputTokens()).multiply(rates.output))
                    .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
        if ("embedding".equals(call.usageType()) && rates.input != null)
            cost = BigDecimal.valueOf(call.inputTokens()).multiply(rates.input)
                    .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
        if ("duration".equals(call.usageType()) && rates.minute != null)
            cost = call.durationSeconds().multiply(rates.minute).divide(BigDecimal.valueOf(60), 12, RoundingMode.HALF_UP);
        return call.priced(cost == null ? null : cost.stripTrailingZeros(), cost == null ? "PRICE_NOT_CONFIGURED" : "ESTIMATED");
    }
    private Rates rates(Environment env, String type) {
        String prefix = "MEETODO_PRICE_" + type + "_";
        String model = env.getProperty(prefix + "MODEL", "").strip();
        if (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))
            throw new IllegalArgumentException("Invalid setting: " + prefix + "MODEL");
        return new Rates(model, decimal(env, prefix + "INPUT_PER_MILLION"), decimal(env, prefix + "OUTPUT_PER_MILLION"),
                decimal(env, prefix + "PER_MINUTE"));
    }
    private BigDecimal decimal(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal price = new BigDecimal(value.strip());
            if (price.signum() < 0 || price.precision() > 24 || Math.abs(price.scale()) > 12) throw new NumberFormatException();
            return price;
        } catch (NumberFormatException ignored) { throw new IllegalArgumentException("Invalid setting: " + name); }
    }
    private record Rates(String model, BigDecimal input, BigDecimal output, BigDecimal minute) { }
}
