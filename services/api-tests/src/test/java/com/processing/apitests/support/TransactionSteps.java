package com.processing.apitests.support;

import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

public final class TransactionSteps {

    private TransactionSteps() {
    }

    public static JsonPath send(AuthorizationRequestBuilder request) {
        return given().spec(Specs.gateway())
                .body(request.build())
                .when().post("/api/transactions")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    public static Response searchByPan(Object pan) {
        return given().spec(Specs.gateway()).queryParam("pan", pan).when().get("/api/transactions/search");
    }

    public static String approve(AuthorizationRequestBuilder request) {
        return given().spec(Specs.gateway())
                .body(request.build())
                .when().post("/api/transactions")
                .then().statusCode(200)
                .body("status", equalTo("APPROVED"))
                .extract().path("rrn");
    }
}
