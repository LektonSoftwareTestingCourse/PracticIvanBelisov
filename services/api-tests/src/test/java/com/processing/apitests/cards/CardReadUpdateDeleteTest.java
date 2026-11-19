package com.processing.apitests.cards;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("GET / PATCH / DELETE /api/cards/{pan} (api-spec section 2; CardController, PatchCardRequest)")
class CardReadUpdateDeleteTest {

    private static final String CARD = "/api/cards/{pan}";

    @Test
    @DisplayName("GET 200: the card as created")
    void existingCardIsReturned() {
        CreatedCard card = CardFactory.aCard().balance(12_345).create();
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get(CARD, card.pan());

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(card.pan()))
                .body("availableBalance", equalTo(12_345))
                .body("expiryDate", equalTo(card.expiry().toString()));
    }

    @Test
    @DisplayName("GET 404: unknown PAN with a wrong Luhn digit (Luhn is not checked, practice-2 TC-21 expected 400); "
            + "the message masks the PAN")
    void unknownCardIs404() {
        String pan = TestData.unknownPanOfKnownBin();
        while (TestData.isLuhnValid(pan)) {
            pan = TestData.unknownPanOfKnownBin();
        }
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get(CARD, pan);

        response.then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"))
                .body("message", equalTo("Card with PAN " + pan.substring(0, 4) + "********" + pan.substring(12)
                        + " was not found"))
                .body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest(name = "GET /api/cards/{0} -> 400")
    @ValueSource(strings = {"400000123456789", "40000012345678901", "400000123456789A"})
    @DisplayName("GET 400: path PAN is not 16 digits (boundaries 15/17, a letter) (api-spec: pan - 16 цифр)")
    void malformedPanIs400(String pan) {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get(CARD, pan);

        response.then()
                .statusCode(400)
                .body("error", equalTo("ConstraintViolationException"))
                .body("message", equalTo("getCard.pan: PAN number must contain exactly 16 digits"));
    }

    @ParameterizedTest(name = "PATCH status {0} -> 200")
    @ValueSource(strings = {"EXPIRED", "ACTIVE"})
    @DisplayName("PATCH 200: status changes (to another inactive one and back to ACTIVE), other fields kept")
    void statusIsPatched(String status) {
        CreatedCard card = CardFactory.aCard().balance(7_000).status("BLOCKED").create();
        RequestSpecification request = given().spec(Specs.gateway()).body(Map.of("status", status));

        Response response = request.when().patch(CARD, card.pan());

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("status", equalTo(status))
                .body("availableBalance", equalTo(7_000))
                .body("dailyLimit", equalTo(15_000_000));
    }

    @Test
    @DisplayName("PATCH 200: limits updated, dailyLimit = monthlyLimit is the boundary that is allowed")
    void limitsArePatched() {
        CreatedCard card = CardFactory.aCard().create();
        RequestSpecification request = given().spec(Specs.gateway())
                .body(Map.of("dailyLimit", 50_000, "monthlyLimit", 50_000));

        Response response = request.when().patch(CARD, card.pan());

        response.then()
                .statusCode(200)
                .body("dailyLimit", equalTo(50_000))
                .body("monthlyLimit", equalTo(50_000));
    }

    static Stream<Arguments> rejectedPatches() {
        return Stream.of(
                Arguments.of("dailyLimit > monthlyLimit", Map.of("dailyLimit", 50_001, "monthlyLimit", 50_000), true,
                        400, "IllegalArgumentException", "Daily limit can not be larger than monthly limit"),
                Arguments.of("negative dailyLimit", Map.of("dailyLimit", -1), true,
                        400, "MethodArgumentNotValidException", "Value can not be negative"),
                Arguments.of("unknown card", Map.of("status", "BLOCKED"), false,
                        404, "CardNotFoundException", "Card with PAN "));
    }

    @ParameterizedTest(name = "{0} -> {3} {4}")
    @MethodSource("rejectedPatches")
    @DisplayName("PATCH rejected: limit rules (400) and unknown card (404)")
    void patchIsRejected(String description, Map<String, Object> body, boolean ownCard, int status, String error,
                         String message) {
        String pan = ownCard ? CardFactory.aCard().create().pan() : TestData.unknownPanOfKnownBin();
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().patch(CARD, pan);

        response.then()
                .statusCode(status)
                .body("error", equalTo(error))
                .body("message", startsWith(message))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("PATCH accepts a negative availableBalance (no constraint in PatchCardRequest) (D-13)")
    void negativeBalanceIsAccepted() {
        CreatedCard card = CardFactory.aCard().create();
        RequestSpecification request = given().spec(Specs.gateway()).body(Map.of("availableBalance", -5));

        Response response = request.when().patch(CARD, card.pan());

        response.then().statusCode(200).body("availableBalance", equalTo(-5));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("PATCH status DELETED (not in the enum) -> 500 instead of 400 (D-11)")
    void unsupportedStatusIs500() {
        CreatedCard card = CardFactory.aCard().create();
        RequestSpecification request = given().spec(Specs.cardManagement()).body(Map.of("status", "DELETED"));

        Response response = request.when().patch(CARD, card.pan());

        response.then()
                .statusCode(500)
                .body("error", equalTo("HttpMessageNotReadableException"))
                .body("message", startsWith("JSON parse error"));
    }

    @Test
    @DisplayName("DELETE 204 with an empty body; afterwards GET 404 and a second DELETE 404")
    void deleteIsSoftAndFinal() {
        CreatedCard card = CardFactory.aCard().create();
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().delete(CARD, card.pan());

        response.then().statusCode(204).body(emptyString());
        given().spec(Specs.cardManagement()).when().get(CARD, card.pan()).then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
        given().spec(Specs.gateway()).when().delete(CARD, card.pan()).then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }
}
