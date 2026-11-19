package com.processing.apitests.simulator;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.Waits;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

@DisplayName("Merchant simulator: /run, /fee (api-spec section 4; SimulationController, SimulatorRequest, AcquirerFeeRequest)")
class MerchantSimulatorTest {

    private static final String RUN = "/api/simulator/merchant/run";
    private static final String FEE = "/api/simulator/merchant/fee";

    @BeforeEach
    void ensureActiveCard() {
        CardFactory.aCard().unlimited().create();
    }

    static Stream<Arguments> validRuns() {
        return Stream.of(
                Arguments.of("grocery", Map.of("count", 2, "scenario", "grocery")),
                Arguments.of("electronics", Map.of("count", 2, "scenario", "electronics")),
                Arguments.of("restaurant", Map.of("count", 2, "scenario", "restaurant")),
                Arguments.of("travel", Map.of("count", 2, "scenario", "travel")),
                Arguments.of("grocery + mccCodes [5812]",
                        Map.of("count", 2, "scenario", "grocery", "mccCodes", List.of("5812"))));
    }

    @ParameterizedTest(name = "{0}: 2 transactions")
    @MethodSource("validRuns")
    @DisplayName("run 200: approved + declined = totalSubmitted = count")
    void validRunIsAccounted(String description, Map<String, Object> body) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().post(RUN);

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/simulator-run.json"))
                .body("totalSubmitted", equalTo(2))
                .body("transactions", hasSize(2))
                .body("transactions.status", everyItem(oneOf("APPROVED", "DECLINED")));
        int approved = response.path("approved");
        int declined = response.path("declined");
        assertThat(approved + declined, equalTo(2));
    }

    static Stream<Arguments> invalidRuns() {
        return Stream.of(
                Arguments.of("count 0", Map.of("count", 0, "scenario", "grocery"), 400,
                        "Invalid request", "count : must be greater than or equal to 1"),
                Arguments.of("scenario absent", Map.of("count", 1), 400,
                        "Invalid request", "scenario : Scenario is required"),
                Arguments.of("tps 0", Map.of("count", 1, "scenario", "grocery", "tps", 0), 400,
                        "Invalid request", "tps : tps must be >= 1"),
                Arguments.of("unknown MCC", Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("0000")), 404,
                        "Resource not found", "Merchants with given mcc ([0000]) not found"));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("invalidRuns")
    @DisplayName("run rejected: status, error and message")
    void invalidRunIsRejected(String description, Map<String, Object> body, int status, String error, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().post(RUN);

        response.then()
                .statusCode(status)
                .body("error", equalTo(error))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/fee (called by Switch) 404 for a transaction it did not generate")
    void feeOfUnknownTransactionIs404() {
        String stan = TestData.stan();
        RequestSpecification request = given().spec(Specs.merchant()).body(Map.of(
                "transmissionDateTime", TestData.now().toString(), "stan", stan,
                "pan", TestData.unknownPanOfKnownBin(), "terminalId", TestData.terminalId(), "amount", 1_000));

        Response response = request.when().post(FEE);

        response.then()
                .statusCode(404)
                .body("error", equalTo("Resource not found"))
                .body("message", equalTo("Acquirer fee not found for stan = " + stan));
    }

    @ParameterizedTest(name = "{0} absent -> 400 \"{1}\"")
    @CsvSource(delimiter = '|', value = {
        "pan|pan : must not be blank",
        "stan|stan : must not be blank",
        "terminalId|terminalId : must not be blank",
        "transmissionDateTime|transmissionDateTime : must not be null"})
    @DisplayName("POST /api/simulator/merchant/fee 400: one required field of AcquirerFeeRequest absent per request")
    void feeRequiredFieldIsAbsent(String field, String message) {
        Map<String, Object> body = new HashMap<>(Map.of("transmissionDateTime", TestData.now().toString(),
                "stan", TestData.stan(), "pan", TestData.unknownPanOfKnownBin(), "terminalId", TestData.terminalId(),
                "amount", 1_000));
        body.remove(field);
        RequestSpecification request = given().spec(Specs.merchant()).body(body);

        Response response = request.when().post(FEE);

        response.then()
                .statusCode(400)
                .body("error", equalTo("Invalid request"))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    @DisplayName("POST /api/simulator/merchant/fee 200: the fee of a simulator transaction equals acquiringFee "
            + "stored by Switch (RouteService -> MerchantAcquirerClient -> Logger)")
    void feeOfSimulatorTransactionEqualsStoredFee() {
        Map<String, Object> answer = given().spec(Specs.gateway()).body(Map.of("count", 1, "scenario", "grocery"))
                .when().post(RUN).then().statusCode(200).extract().path("transactions[0]");
        String own = String.format("find { it.stan == '%s' && it.processingTimeMs == %s && it.rrn == %s }",
                answer.get("stan"), answer.get("processingTimeMs"),
                answer.get("rrn") == null ? "null" : "'" + answer.get("rrn") + "'");
        Map<String, Object> stored = await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL)
                .until(() -> given().spec(Specs.gateway()).queryParam("limit", 20).when().get("/api/dashboard/recent")
                        .then().statusCode(200).extract().path(own), notNullValue());
        RequestSpecification request = given().spec(Specs.merchant()).body(Map.of(
                "transmissionDateTime", stored.get("transmissionDateTime"), "stan", stored.get("stan"),
                "pan", stored.get("pan"), "terminalId", stored.get("terminalId"), "amount", stored.get("amount")));

        Response response = request.when().post(FEE);

        response.then()
                .statusCode(200)
                .body("acquirerFee", notNullValue())
                .body("acquirerFee", equalTo(stored.get("acquiringFee")));
    }
}
