package com.processing.apitests.transactions;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("POST /api/transactions: validation in Gateway (TransactionRequestValidator vs api-spec AuthorizationRequest)")
class TransactionValidationTest {

    private static final String PATH = "/api/transactions";

    private static AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequestBuilder.forPan(TestData.panOfUnknownBin());
    }

    private static void assertGatewayValidationError(Response response, String expectedMessage) {
        response.then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(expectedMessage))
                .body("serviceName", equalTo("gateway"))
                .body("retryAfterMs", nullValue());
    }

    @ParameterizedTest(name = "field {0} absent -> 400 \"Field ''{0}'' is required\"")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode",
        "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId"})
    @DisplayName("Required field absent (api-spec: Обязательное = Да; TransactionRequestValidator.requireValue)")
    void absentRequiredFieldIsRejected(String field) {
        RequestSpecification request = given().spec(Specs.gateway()).body(validRequest().without(field).build());

        Response response = request.when().post(PATH);

        assertGatewayValidationError(response, "Field '" + field + "' is required");
    }

    @ParameterizedTest(name = "field {0} = \"{1}\" -> 400 required")
    @CsvSource(value = {"pan|", "stan|''", "merchantId|'   '", "transmissionDateTime|''"}, delimiter = '|')
    @DisplayName("Explicit null, empty and whitespace-only are treated as absent (isBlank; empty Instant -> null)")
    void blankStringIsRejected(String field, String blank) {
        RequestSpecification request = given().spec(Specs.gateway()).body(validRequest().with(field, blank).build());

        Response response = request.when().post(PATH);

        assertGatewayValidationError(response, "Field '" + field + "' is required");
    }

    static Stream<Arguments> formatViolations() {
        return Stream.of(
                Arguments.of("mti", "0110", "Field 'mti' must be '0100'"),
                Arguments.of("mti", "100", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000123456789", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "40000012345678901", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "400000123456789A", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("amount", new BigDecimal("-0.01"), "Field 'amount' must be > 0"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("currencyCode", "6430", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "541", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54111", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54A1", "Field 'mcc' must be exactly 4 digits"));
    }

    @ParameterizedTest(name = "{0} = {1} -> 400 \"{2}\"")
    @MethodSource("formatViolations")
    @DisplayName("Format, length and numeric rules (TransactionRequestValidator.validate)")
    void formatViolationIsRejected(String field, Object value, String expectedMessage) {
        RequestSpecification request = given().spec(Specs.gateway()).body(validRequest().with(field, value).build());

        Response response = request.when().post(PATH);

        assertGatewayValidationError(response, expectedMessage);
    }

    static Stream<Arguments> unreadableBodies() {
        String invalidJson = "Request body must be valid JSON";
        return Stream.of(
                Arguments.of("broken JSON", "{\"mti\": \"0100\"", invalidJson),
                Arguments.of("empty body", "", invalidJson),
                Arguments.of("JSON array instead of object", "[]", invalidJson),
                Arguments.of("amount of a wrong type", validRequest().amount("abc").build(), invalidJson),
                Arguments.of("transmissionDateTime not ISO 8601",
                        validRequest().with("transmissionDateTime", "not-a-date").build(), invalidJson),
                Arguments.of("literal null", "null", "Request body is required"));
    }

    @ParameterizedTest(name = "{0} -> 400 \"{2}\"")
    @MethodSource("unreadableBodies")
    @DisplayName("Body that is not an AuthorizationRequest object (TransactionValidationFilter IOException branch, null)")
    void unreadableBodyIsRejected(String description, Object rawBody, String expectedMessage) {
        RequestSpecification request = given().spec(Specs.gateway()).body(rawBody);

        Response response = request.when().post(PATH);

        assertGatewayValidationError(response, expectedMessage);
    }

    static Stream<Arguments> validBoundaries() {
        return Stream.of(
                Arguments.of("amount", new BigDecimal("0.01")),
                Arguments.of("amount", 1),
                Arguments.of("mcc", "0000"),
                Arguments.of("mcc", "9999"),
                Arguments.of("currencyCode", "ABC"),
                Arguments.of("stan", "ABCDEF"),
                Arguments.of("terminalType", null),
                Arguments.of("issuerId", null));
    }

    @ParameterizedTest(name = "{0} = {1} passes validation and reaches Switch")
    @MethodSource("validBoundaries")
    @DisplayName("Valid boundary values pass Gateway: answer comes from Switch (unknown BIN decline 14)")
    void validBoundaryPassesValidation(String field, Object value) {
        AuthorizationRequestBuilder body = validRequest().with(field, value);
        RequestSpecification request = given().spec(Specs.gateway()).body(body.build());

        Response response = request.when().post(PATH);

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("stan", equalTo(body.get("stan")))
                .body("mti", equalTo("0110"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("processingTimeMs", equalTo(0));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Validation gap: stan of 5 digits passes Gateway, Authorization rejects it and Switch reports "
            + "05 \"Authorization service unavailable\" (D-07)")
    void shortStanPassesGatewayAndIsMisreportedBySwitch() {
        AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(TestData.unknownPanOfKnownBin())
                .stan("12345");
        RequestSpecification request = given().spec(Specs.gateway()).body(body.build());

        Response response = request.when().post(PATH);

        response.then()
                .statusCode(200)
                .body("mti", equalTo("0110"))
                .body("stan", equalTo("12345"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("Authorization service unavailable"))
                .body("rrn", nullValue())
                .body("authCode", nullValue());
    }
}
