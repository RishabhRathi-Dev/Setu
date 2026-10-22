package io.setu.security.quota;

public record QuotaDecision(
        boolean allowed,
        String reason
) {

    public static QuotaDecision allow() {
        return new QuotaDecision(
                true,
                "Quota available"
        );
    }

    public static QuotaDecision deny(
            String reason) {

        return new QuotaDecision(
                false,
                reason
        );
    }
}
