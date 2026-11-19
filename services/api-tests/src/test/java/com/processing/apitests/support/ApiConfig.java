package com.processing.apitests.support;

public final class ApiConfig {

    public static final String GATEWAY = resolve("gateway.url", "API_TESTS_GATEWAY_URL", "http://localhost:8080");
    public static final String CARD_MANAGEMENT = resolve("cards.url", "API_TESTS_CARD_MGMT_URL", "http://localhost:8081");
    public static final String SWITCH = resolve("switch.url", "API_TESTS_SWITCH_URL", "http://localhost:8082");
    public static final String AUTHORIZATION = resolve("auth.url", "API_TESTS_AUTH_URL", "http://localhost:8083");
    public static final String MERCHANT = resolve("merchant.url", "API_TESTS_MERCHANT_URL", "http://localhost:8084");
    public static final String TERMINAL = resolve("terminal.url", "API_TESTS_TERMINAL_URL", "http://localhost:8085");
    public static final String LOGGER = resolve("logger.url", "API_TESTS_LOGGER_URL", "http://localhost:8088");
    public static final String BIN_LOOKUP = resolve("binlookup.url", "API_TESTS_BIN_LOOKUP_URL", "http://localhost:8096");

    private ApiConfig() {
    }

    private static String resolve(String property, String env, String defaultValue) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return defaultValue;
    }
}
