package com.processing.apitests.gateway;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("Gateway circuit breaker: 503 with Retry-After (CircuitBreakerFilter; failure-threshold 3, open-duration 10s)")
class GatewayCircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;

    @Test
    @DisplayName("Three 5xx from Card Management -> next /api/cards/** call is 503 from Gateway, other routes work, "
            + "the route recovers after open-duration")
    void circuitOpensAfterThreeFailuresAndRecovers() {
        CreatedCard card = CardFactory.aCard().create();
        given().spec(Specs.gateway()).when().get("/api/cards/{pan}", card.pan()).then().statusCode(200);
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            given().spec(Specs.gateway()).body("{\"bin\":").when().post("/api/cards").then()
                    .statusCode(500)
                    .body("serviceName", equalTo("card-management"));
        }
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get("/api/cards/{pan}", card.pan());

        response.then()
                .statusCode(503)
                .header("Retry-After", equalTo("10"))
                .body("error", equalTo("SERVICE_UNAVAILABLE"))
                .body("message", equalTo("cardManagement service is temporarily unavailable"))
                .body("serviceName", equalTo("cardManagement"));
        given().spec(Specs.gateway()).queryParam("limit", 1).when().get("/api/transactions/search").then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                given().spec(Specs.gateway()).when().get("/api/cards/{pan}", card.pan()).then()
                        .statusCode(200)
                        .body("pan", equalTo(card.pan())));
    }
}
