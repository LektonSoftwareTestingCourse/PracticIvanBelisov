package com.processing.apitests.cards;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("POST /api/cards/bulk-update (CardController.bulkUpdate, BulkUpdateRequest; not in api-spec)")
class CardBulkUpdateTest {

    private static final String PATH = "/api/cards/bulk-update";

    @Test
    @DisplayName("200 {updated: 2}: both own cards get the new status")
    void cardsSelectedByPanAreUpdated() {
        String first = CardFactory.aCard().create().pan();
        String second = CardFactory.aCard().create().pan();
        RequestSpecification request = given().spec(Specs.gateway())
                .body(Map.of("pans", List.of(first, second), "status", "INACTIVE"));

        Response response = request.when().post(PATH);

        response.then().statusCode(200).body("updated", equalTo(2));
        assertThat(CardFactory.statusOf(first), equalTo("INACTIVE"));
        assertThat(CardFactory.statusOf(second), equalTo("INACTIVE"));
    }

    @Test
    @DisplayName("200 {updated: 0} for a PAN that does not exist (empty selection is not an error)")
    void unknownPanUpdatesNothing() {
        RequestSpecification request = given().spec(Specs.gateway())
                .body(Map.of("pans", List.of(TestData.unknownPanOfKnownBin()), "status", "BLOCKED"));

        Response response = request.when().post(PATH);

        response.then().statusCode(200).body("updated", equalTo(0));
    }

    static Stream<Arguments> invalidRequests() {
        String pan = TestData.unknownPanOfKnownBin();
        return Stream.of(
                Arguments.of("neither bins nor pans", Map.of("status", "BLOCKED"),
                        "IllegalArgumentException", "Either bin or pans must be provided"),
                Arguments.of("bins and pans together", Map.of("bins", List.of("400000"), "pans", List.of(pan),
                        "status", "BLOCKED"), "IllegalArgumentException", "Only one of bin or pans must be provided"),
                Arguments.of("status absent", Map.of("pans", List.of(pan)),
                        "MethodArgumentNotValidException", "must not be null"),
                Arguments.of("PAN of 3 digits", Map.of("pans", List.of("123"), "status", "BLOCKED"),
                        "MethodArgumentNotValidException", "PAN number must contain exactly 16 digits"));
    }

    @ParameterizedTest(name = "{0} -> 400 {2}")
    @MethodSource("invalidRequests")
    @DisplayName("400: selector rules and field validation, nothing is updated")
    void invalidRequestIsRejected(String description, Map<String, Object> body, String error, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().post(PATH);

        response.then()
                .statusCode(400)
                .body("error", equalTo(error))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("card-management"));
    }
}
