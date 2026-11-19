package com.processing.apitests.simulator;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.Specs;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.oneOf;

@DisplayName("POST /api/simulator/terminal/* (api-spec section 4; TerminalSimulatorController, TerminalSimulatorService)")
class TerminalSimulatorTest {

    private static final String RUN = "/api/simulator/terminal/run";
    private static final String START_CONTINUOUS = "/api/simulator/terminal/start-continuous";
    private static final String STOP = "/api/simulator/terminal/stop";

    @BeforeEach
    void ensureCards() {
        CardFactory.aCard().unlimited().create();
        CardFactory.aCard().unlimited().status("BLOCKED").create();
    }

    private static Map<String, Object> runRequest(int count, String scenario, int tps) {
        Map<String, Object> body = new HashMap<>();
        body.put("count", count);
        body.put("scenario", scenario);
        body.put("tps", tps);
        return body;
    }

    @ParameterizedTest(name = "scenario {0}: 3 transactions submitted and accounted")
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    @DisplayName("run 200 for every scenario of the enum: approved + declined = totalSubmitted = count")
    void runEveryScenario(String scenario) {
        RequestSpecification request = given().spec(Specs.gateway()).body(runRequest(3, scenario, 50));

        Response response = request.when().post(RUN);

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/simulator-run.json"))
                .body("totalSubmitted", equalTo(3))
                .body("transactions", hasSize(3))
                .body("transactions.status", everyItem(oneOf("APPROVED", "DECLINED")));
        int approved = response.path("approved");
        int declined = response.path("declined");
        assertThat(approved + declined, equalTo(3));
        assertThat(response.jsonPath().getList("transactions.findAll { it.status == 'APPROVED' }").size(),
                equalTo(approved));
    }

    static Stream<Arguments> invalidRuns() {
        return Stream.of(
                Arguments.of("count 0", runRequest(0, "normal", 10), "Validation failed", "count"),
                Arguments.of("tps 0", runRequest(1, "normal", 0), "Validation failed", "tps"),
                Arguments.of("tps absent (not in the spec)", Map.of("count", 1, "scenario", "normal"),
                        "Validation failed", "tps"),
                Arguments.of("unknown scenario", runRequest(1, "weekend", 10), "Invalid request format", "weekend"));
    }

    @ParameterizedTest(name = "{0} -> 400")
    @MethodSource("invalidRuns")
    @DisplayName("run 400: one invalid field per request, the message mentions it")
    void invalidRunIsRejected(String description, Map<String, Object> body, String error, String mentioned) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().post(RUN);

        response.then()
                .statusCode(400)
                .body("error", equalTo(error))
                .body("message", containsString(mentioned))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @DisplayName("stop 200 with an empty body (idempotent when nothing runs)")
    void stopIsIdempotent() {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().post(STOP);

        response.then().statusCode(200).body(emptyString());
    }

    static Stream<Arguments> invalidContinuousStarts() {
        return Stream.of(
                Arguments.of("tps 0 (@Min(1))", Map.of("tps", 0, "transactionType", "INVALID_PAN"), "tps"),
                Arguments.of("transactionType absent (@NotNull)", Map.of("tps", 1), "transactionType"));
    }

    @ParameterizedTest(name = "{0} -> 400")
    @MethodSource("invalidContinuousStarts")
    @DisplayName("start-continuous 400: one invalid field of TerminalStartContinuousRequest per request, nothing started")
    void invalidContinuousStartIsRejected(String description, Map<String, Object> body, String field) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().post(START_CONTINUOUS);

        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation failed"))
                .body("message", containsString("on field '" + field + "'"))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @DisplayName("start-continuous 200 with an empty body; a second start while running -> 422 \"Illegal state\" "
            + "(TerminalSimulatorService.startContinuous)")
    void secondContinuousStartIsRejected() {
        Map<String, Object> body = Map.of("tps", 1, "transactionType", "INVALID_PAN");
        given().spec(Specs.gateway()).body(body).when().post(START_CONTINUOUS).then()
                .statusCode(200)
                .body(emptyString());

        Response response = given().spec(Specs.gateway()).body(body).when().post(START_CONTINUOUS);

        response.then()
                .statusCode(422)
                .body("error", equalTo("Illegal state"))
                .body("message", equalTo("Continuous simulation is already running!"))
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @AfterEach
    void stopContinuous() {
        given().spec(Specs.gateway()).when().post(STOP).then().statusCode(200);
    }
}
