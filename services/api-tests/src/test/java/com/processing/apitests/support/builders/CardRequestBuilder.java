package com.processing.apitests.support.builders;

import com.processing.apitests.support.TestData;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CardRequestBuilder {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private CardRequestBuilder() {
        fields.put("bin", TestData.BIN_ISS001);
        fields.put("cardholderName", "IVAN IVANOV");
        fields.put("currencyCode", TestData.DEFAULT_CURRENCY);
        fields.put("dailyLimit", 15_000_000L);
        fields.put("monthlyLimit", 300_000_000L);
        fields.put("initialBalance", 100_000_000L);
    }

    public static CardRequestBuilder aCard() {
        return new CardRequestBuilder();
    }

    public CardRequestBuilder bin(String bin) {
        return with("bin", bin);
    }

    public CardRequestBuilder balance(long balance) {
        return with("initialBalance", balance);
    }

    public CardRequestBuilder dailyLimit(long dailyLimit) {
        return with("dailyLimit", dailyLimit);
    }

    public CardRequestBuilder monthlyLimit(long monthlyLimit) {
        return with("monthlyLimit", monthlyLimit);
    }

    public CardRequestBuilder with(String field, Object value) {
        fields.put(field, value);
        return this;
    }

    public CardRequestBuilder without(String field) {
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
