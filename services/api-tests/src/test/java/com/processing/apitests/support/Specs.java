package com.processing.apitests.support;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

public final class Specs {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 60_000;

    static {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    private Specs() {
    }

    public static RequestSpecification gateway() {
        return base(ApiConfig.GATEWAY);
    }

    public static RequestSpecification cardManagement() {
        return base(ApiConfig.CARD_MANAGEMENT);
    }

    public static RequestSpecification switchService() {
        return base(ApiConfig.SWITCH);
    }

    public static RequestSpecification authorization() {
        return base(ApiConfig.AUTHORIZATION);
    }

    public static RequestSpecification logger() {
        return base(ApiConfig.LOGGER);
    }

    public static RequestSpecification merchant() {
        return base(ApiConfig.MERCHANT);
    }

    public static RequestSpecification terminal() {
        return base(ApiConfig.TERMINAL);
    }

    public static RequestSpecification binLookup() {
        return base(ApiConfig.BIN_LOOKUP);
    }

    private static RequestSpecification base(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", CONNECT_TIMEOUT_MS)
                        .setParam("http.socket.timeout", SOCKET_TIMEOUT_MS)))
                .build();
    }
}
