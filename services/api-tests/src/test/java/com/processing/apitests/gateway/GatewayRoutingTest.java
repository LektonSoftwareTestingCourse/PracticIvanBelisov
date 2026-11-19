package com.processing.apitests.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@DisplayName("Gateway routing and ownership of errors (gateway application.yml routes vs api-spec and controllers)")
class GatewayRoutingTest {

    @ParameterizedTest(name = "{0} {1} -> 404 from Gateway ({2})")
    @CsvSource({
        "GET,  /api/unknown-route,         no route at all",
        "POST, /api/internal/route,        internal: Switch",
        "POST, /api/internal/authorize,    internal: Authorization",
        "POST, /api/internal/rollback,     internal: Authorization",
        "POST, /api/internal/log,          internal: Logger",
        "GET,  /api/simulator/merchants,   code without route",
        "GET,  /api/bins,                  code without route",
        "GET,  /api/notifications,         code without route",
        "GET,  /ws/transactions,           spec endpoint without route"})
    @DisplayName("Path without a route -> Gateway's own 404 (no serviceName, has requestId)")
    void pathWithoutRouteIsGateway404(String method, String path, String reason) {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().request(method, path);

        response.then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("error", equalTo("Not Found"))
                .body("path", equalTo(path))
                .body("requestId", notNullValue())
                .body("$", not(hasKey("serviceName")));
    }

    @ParameterizedTest(name = "{0} {1} -> {2} from transaction-logger")
    @CsvSource(delimiter = '|', value = {
        "GET|/api/transactions|404|Not found|Resource not found: api/transactions",
        "DELETE|/api/transactions/search|405|Method not allowed|Request method 'DELETE' is not supported"})
    @DisplayName("Wrong method is answered by Transaction Logger: 404 on /api/transactions (not 405), 405 elsewhere")
    void wrongMethodIsAnsweredByLogger(String method, String path, int status, String error, String message) {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().request(method, path);

        response.then()
                .statusCode(status)
                .body("error", equalTo(error))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    @DisplayName("POST /api/transactions with text/plain passes Gateway validation, Switch answers 415 "
            + "and exposes its internal path (D-21)")
    void unsupportedMediaTypeIsSwitch415() throws Exception {
        String validJson = new ObjectMapper().writeValueAsString(
                AuthorizationRequestBuilder.forPan(TestData.panOfUnknownBin()).build());
        RequestSpecification request = given().spec(Specs.gateway())
                .contentType("text/plain")
                .body(validJson);

        Response response = request.when().post("/api/transactions");

        response.then()
                .statusCode(415)
                .body("error", equalTo("Unsupported Media Type"))
                .body("path", equalTo("/api/internal/route"));
    }
}
