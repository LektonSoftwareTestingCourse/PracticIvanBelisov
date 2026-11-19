package com.processing.apitests.health;

import com.processing.apitests.support.Specs;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("GET /health of Gateway (api-spec section 1; HealthController, HealthService)")
class HealthTest {

    @Test
    @DisplayName("Gateway /health: 200, status ok and every downstream ok (api-spec section 1)")
    void gatewayHealthReportsAllDownstreamServices() {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get("/health");

        response.then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/gateway-health.json"))
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("version", equalTo("1.0.0"))
                .body("services.size()", equalTo(4))
                .body("services.switch", equalTo("ok"))
                .body("services.authorization", equalTo("ok"))
                .body("services.cardManagement", equalTo("ok"))
                .body("services.logger", equalTo("ok"));
    }
}
