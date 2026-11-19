package com.processing.apitests.internal;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("GET /api/bin/{bin} of Bin Lookup (BinLookupController BIN_TABLE and query flags)")
class BinLookupTest {

    @ParameterizedTest(name = "BIN {0} -> {1}")
    @CsvSource({
        "400000, ISS001, Test Bank 1",
        "400001, ISS002, Test Bank 2",
        "400002, ISS003, Test Bank 3",
        "400003, ISS004, Test Bank 4",
        "400004, ISS005, Test Bank 5"})
    @DisplayName("Known BIN -> 200 with issuer; mapping equals the Switch routing table")
    void knownBinIsResolved(String bin, String issuerId, String issuerName) {
        RequestSpecification request = given().spec(Specs.binLookup());

        Response response = request.when().get("/api/bin/{bin}", bin);

        response.then()
                .statusCode(200)
                .body("bin", equalTo(bin))
                .body("issuerId", equalTo(issuerId))
                .body("issuerName", equalTo(issuerName));
    }

    @Test
    @DisplayName("Unknown BIN -> 404 with an empty body")
    void unknownBinIs404() {
        RequestSpecification request = given().spec(Specs.binLookup());

        Response response = request.when().get("/api/bin/{bin}", TestData.UNKNOWN_BIN);

        response.then().statusCode(404).body(emptyString());
    }

    @Test
    @DisplayName("fail=true -> 500 (fault injection flag of the stub, used by Authorization fallback)")
    void failFlagIs500() {
        RequestSpecification request = given().spec(Specs.binLookup()).queryParam("fail", true);

        Response response = request.when().get("/api/bin/{bin}", TestData.BIN_ISS001);

        response.then().statusCode(500).body(emptyString());
    }
}
