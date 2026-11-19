package com.processing.apitests.support.builders;

import com.processing.apitests.support.TestData;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TransactionLogBuilder {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private TransactionLogBuilder() {
        Instant now = TestData.now();
        fields.put("id", TestData.uuid());
        fields.put("mti", "0100");
        fields.put("stan", TestData.stan());
        fields.put("rrn", TestData.rrn());
        fields.put("pan", TestData.unknownPanOfKnownBin());
        fields.put("processingCode", "000000");
        fields.put("amount", 150_000);
        fields.put("currencyCode", TestData.DEFAULT_CURRENCY);
        fields.put("terminalId", TestData.terminalId());
        fields.put("terminalType", "POS");
        fields.put("merchantId", TestData.merchantId());
        fields.put("mcc", "5411");
        fields.put("acquirerId", "ACQ001");
        fields.put("issuerId", "ISS001");
        fields.put("acquiringFee", 1_500);
        fields.put("status", "APPROVED");
        fields.put("declineReason", null);
        fields.put("authCode", "AB12CD");
        fields.put("processingTimeMs", 42);
        fields.put("transmissionDateTime", now.toString());
        fields.put("createdAt", now.toString());
    }

    private TransactionLogBuilder(Map<String, Object> source) {
        fields.putAll(source);
    }

    public static TransactionLogBuilder aLoggedTransaction() {
        return new TransactionLogBuilder();
    }

    public TransactionLogBuilder copy() {
        return new TransactionLogBuilder(fields);
    }

    public TransactionLogBuilder with(String field, Object value) {
        fields.put(field, value);
        return this;
    }

    public TransactionLogBuilder without(String field) {
        fields.remove(field);
        return this;
    }

    public Object get(String field) {
        return fields.get(field);
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(fields);
    }
}
