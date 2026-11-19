package com.processing.apitests.internal;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("POST /api/internal/route of Switch (api-spec section 7; RouteController, RouteService)")
class SwitchRouteTest {

    private static final String PATH = "/api/internal/route";

    private static Response route(Object body) {
        RequestSpecification request = given().spec(Specs.switchService()).body(body);
        return request.when().post(PATH);
    }

    @ParameterizedTest(name = "pan \"{0}\" -> 14 CARD_NOT_FOUND from Switch")
    @ValueSource(strings = {"123", "40000"})
    @DisplayName("PAN shorter than a BIN is declined by Switch itself (unreachable through Gateway validation)")
    void unroutablePanIsDeclinedBySwitch(String pan) {
        AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(pan);

        Response response = route(body.build());

        response.then()
                .statusCode(200)
                .body("mti", equalTo("0110"))
                .body("stan", equalTo(body.get("stan")))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("processingTimeMs", equalTo(0));
    }

    @Test
    @DisplayName("400 Spring error body for unreadable JSON (before RouteController)")
    void unreadableJsonIs400() {
        String body = "{\"mti\":";

        Response response = route(body);

        response.then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("error", equalTo("Bad Request"))
                .body("path", equalTo(PATH));
    }
}
