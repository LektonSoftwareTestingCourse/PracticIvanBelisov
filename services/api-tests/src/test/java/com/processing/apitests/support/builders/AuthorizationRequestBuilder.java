package com.processing.apitests.support.builders;

import com.processing.apitests.support.TestData;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuthorizationRequestBuilder {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private AuthorizationRequestBuilder(String pan) {
        fields.put("mti", "0100");
        fields.put("stan", TestData.stan());
        fields.put("pan", pan);
        fields.put("processingCode", "000000");
        fields.put("amount", 1000);
        fields.put("currencyCode", TestData.DEFAULT_CURRENCY);
        fields.put("transmissionDateTime", TestData.now().toString());
        fields.put("terminalId", TestData.terminalId());
        fields.put("terminalType", "POS");
        fields.put("merchantId", TestData.merchantId());
        fields.put("mcc", "5411");
        fields.put("acquirerId", "ACQ001");
    }

    public static AuthorizationRequestBuilder forPan(String pan) {
        return new AuthorizationRequestBuilder(pan);
    }

    public AuthorizationRequestBuilder amount(Object amount) {
        return with("amount", amount);
    }

    public AuthorizationRequestBuilder stan(String stan) {
        return with("stan", stan);
    }

    public AuthorizationRequestBuilder merchantId(String merchantId) {
        return with("merchantId", merchantId);
    }

    public AuthorizationRequestBuilder terminalId(String terminalId) {
        return with("terminalId", terminalId);
    }

    public AuthorizationRequestBuilder mcc(String mcc) {
        return with("mcc", mcc);
    }

    public AuthorizationRequestBuilder transmissionDateTime(Instant instant) {
        return with("transmissionDateTime", instant.toString());
    }

    public AuthorizationRequestBuilder issuerId(String issuerId) {
        return with("issuerId", issuerId);
    }

    public AuthorizationRequestBuilder with(String field, Object value) {
        fields.put(field, value);
        return this;
    }

    public AuthorizationRequestBuilder without(String field) {
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
