package com.processing.apitests.gateway;

import com.processing.apitests.support.ApiConfig;
import com.processing.apitests.support.Specs;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("Gateway: OpenAPI routes and actuator (gateway application.yml *-docs routes, OpenApiGatewayFilterFactory)")
class GatewayDocsAndActuatorTest {

    @ParameterizedTest(name = "GET {0} -> has {1}, hides {2}")
    @CsvSource({
        "/switch-docs,   /api/transactions,             /api/internal/route",
        "/logger-docs,   /api/transactions/search,      /api/internal/log",
        "/cards-docs,    /api/cards/{pan},              ''",
        "/merchant-docs, /api/simulator/merchant/run,   ''",
        "/terminal-docs, /api/simulator/terminal/run,   ''"})
    @DisplayName("Docs route serves the document of its own service with public paths only "
            + "(OpenApiGatewayFilterFactory: /api/internal/route -> /api/transactions, other internal paths dropped)")
    void docsRouteServesOwnDocument(String route, String publicPath, String hiddenPath) {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get(route);

        response.then()
                .statusCode(200)
                .contentType("application/json")
                .body("openapi", startsWith("3."))
                .body("servers[0].url", equalTo(ApiConfig.GATEWAY))
                .body("paths", hasKey(publicPath));
        if (!hiddenPath.isEmpty()) {
            response.then().body("paths", not(hasKey(hiddenPath)));
        }
    }

    @Test
    @DisplayName("GET /actuator/health -> 200 UP (Spring actuator media type)")
    void actuatorHealthIsUp() {
        RequestSpecification request = given().spec(Specs.gateway()).accept("*/*");

        Response response = request.when().get("/actuator/health");

        response.then()
                .statusCode(200)
                .contentType(startsWith("application/vnd.spring-boot.actuator.v3+json"))
                .body("status", equalTo("UP"));
    }

    @Test
    @DisplayName("GET /actuator/prometheus -> 200 text/plain with http_server_requests metrics")
    void prometheusMetricsAreExposed() {
        RequestSpecification request = given().spec(Specs.gateway()).accept("*/*");

        Response response = request.when().get("/actuator/prometheus");

        response.then()
                .statusCode(200)
                .contentType(startsWith("text/plain"))
                .body(containsString("http_server_requests_seconds"));
    }
}
