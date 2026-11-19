package com.processing.apitests.internal;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.builders.TransactionLogBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static com.processing.apitests.support.TransactionSteps.searchByPan;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("POST /api/internal/log: redelivery (api-spec section 7; TransactionService.store, TransactionMapper.matches)")
class TransactionLogRedeliveryTest {

    private static final String PATH = "/api/internal/log";

    private static Response deliver(TransactionLogBuilder message) {
        RequestSpecification request = given().spec(Specs.logger()).body(message.build());
        return request.when().post(PATH);
    }

    @Test
    @DisplayName("First delivery -> 201 {id, status: stored}, record searchable")
    void firstDeliveryIsStored() {
        TransactionLogBuilder message = TransactionLogBuilder.aLoggedTransaction();

        Response response = deliver(message);

        response.then()
                .statusCode(201)
                .body("size()", equalTo(2))
                .body("id", equalTo(message.get("id")))
                .body("status", equalTo("stored"));
        searchByPan(message.get("pan")).then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions[0].id", equalTo(message.get("id")))
                .body("transactions[0].stan", equalTo(message.get("stan")));
    }

    @Test
    @DisplayName("Same message again -> 200 with the stored record, still one record")
    void identicalRedeliveryReturnsExistingRecord() {
        TransactionLogBuilder message = TransactionLogBuilder.aLoggedTransaction();
        deliver(message).then().statusCode(201);

        Response response = deliver(message.copy());

        response.then()
                .statusCode(200)
                .body("id", equalTo(message.get("id")))
                .body("rrn", equalTo(message.get("rrn")))
                .body("amount", equalTo(150_000))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());
        searchByPan(message.get("pan")).then()
                .body("total", equalTo(1))
                .body("transactions", hasSize(1));
    }

    static Stream<Arguments> sameMessageVariants() {
        return Stream.of(
                Arguments.of("createdAt changed (re-sent later)", "createdAt", "2020-01-01T00:00:00Z"),
                Arguments.of("amount in another scale", "amount", new BigDecimal("150000.00")),
                Arguments.of("acquiringFee in another scale", "acquiringFee", new BigDecimal("1500.0")));
    }

    @ParameterizedTest(name = "{0} -> 200, treated as the same message")
    @MethodSource("sameMessageVariants")
    @DisplayName("Repeated delivery that is equal by TransactionMapper.matches -> 200, record unchanged")
    void equivalentRedeliveryIsAccepted(String description, String field, Object value) {
        TransactionLogBuilder message = TransactionLogBuilder.aLoggedTransaction();
        deliver(message).then().statusCode(201);

        Response response = deliver(message.copy().with(field, value));

        response.then()
                .statusCode(200)
                .body("id", equalTo(message.get("id")))
                .body("createdAt", equalTo(message.get("createdAt")));
        searchByPan(message.get("pan")).then().body("total", equalTo(1));
    }

    static Stream<Arguments> conflictingVariants() {
        return Stream.of(
                Arguments.of("amount", 150_001),
                Arguments.of("status", "DECLINED"));
    }

    @ParameterizedTest(name = "same id, different {0} -> 409")
    @MethodSource("conflictingVariants")
    @DisplayName("Key matches, data differs -> 409 Transaction conflict, stored record not changed")
    void conflictingRedeliveryIsRejected(String field, Object value) {
        TransactionLogBuilder message = TransactionLogBuilder.aLoggedTransaction();
        deliver(message).then().statusCode(201);

        Response response = deliver(message.copy().with(field, value));

        response.then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Transaction conflict"))
                .body("message", equalTo("Transaction with id " + message.get("id") + " already exists with different data"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"));
        searchByPan(message.get("pan")).then()
                .body("total", equalTo(1))
                .body("transactions[0].amount", equalTo(150_000))
                .body("transactions[0].status", equalTo("APPROVED"))
                .body("transactions[0].rrn", equalTo(message.get("rrn")));
    }

    static Stream<Arguments> invalidMessages() {
        String validation = "Validation error";
        return Stream.of(
                Arguments.of("id", null, validation, "id: must not be null"),
                Arguments.of("stan", "12345", validation, "stan: stan must be exactly 6 digits"),
                Arguments.of("pan", "400000123456789", validation, "pan: PAN number must contain exactly 16 digits"),
                Arguments.of("amount", 0, validation, "amount: must be greater than 0"),
                Arguments.of("currencyCode", "64A", validation, "currencyCode: currencyCode must be exactly 3 digits"),
                Arguments.of("mcc", "541", validation, "mcc: mcc must be exactly 4 digits"),
                Arguments.of("merchantId", "M234567890123456", validation, "merchantId: size must be between 0 and 15"),
                Arguments.of("createdAt", null, validation, "createdAt: must not be null"),
                Arguments.of("status", "ROLLED_BACK", "Invalid request body",
                        "Request body is malformed or contains unsupported values"));
    }

    @ParameterizedTest(name = "{0} = {1} -> 400 \"{3}\"")
    @MethodSource("invalidMessages")
    @DisplayName("Invalid message -> 400 naming the field and the rule (Logger GlobalExceptionHandler)")
    void invalidMessageIsRejected(String field, Object value, String error, String expectedMessage) {
        TransactionLogBuilder message = TransactionLogBuilder.aLoggedTransaction().with(field, value);

        Response response = deliver(message);

        response.then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo(error))
                .body("message", equalTo(expectedMessage))
                .body("serviceName", equalTo("transaction-logger"));
    }
}
