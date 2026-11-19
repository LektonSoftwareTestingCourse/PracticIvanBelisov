package com.processing.apitests.internal;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("POST /api/cards/{pan}/reserve and /rollback (tz/04 section 3; CardController, CMS GlobalExceptionHandler)")
class CardReservationTest {

    private static Response reserve(String pan, Object amount, String rrn) {
        RequestSpecification request = given().spec(Specs.cardManagement())
                .body(Map.of("amount", amount, "rrn", rrn));
        return request.when().post("/api/cards/{pan}/reserve", pan);
    }

    private static Response rollback(String pan, Object amount, String rrn) {
        RequestSpecification request = given().spec(Specs.cardManagement())
                .body(Map.of("amount", amount, "rrn", rrn, "pan", pan));
        return request.when().post("/api/cards/{pan}/rollback", pan);
    }

    @Test
    @DisplayName("Reserve 200: card returned with the reduced balance")
    void reserveReducesBalance() {
        CreatedCard card = CardFactory.aCard().balance(50_000).unlimited().create();

        Response response = reserve(card.pan(), 1_000, TestData.rrn());

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(card.pan()))
                .body("availableBalance", equalTo(49_000));
    }

    @Test
    @DisplayName("Reserve with an RRN already used for this card -> 409, balance reduced only once")
    void duplicateRrnIsConflict() {
        CreatedCard card = CardFactory.aCard().balance(50_000).unlimited().create();
        String rrn = TestData.rrn();
        reserve(card.pan(), 1_000, rrn).then().statusCode(200);

        Response response = reserve(card.pan(), 1_000, rrn);

        response.then()
                .statusCode(409)
                .body("error", equalTo("ReservationAlreadyExistsException"))
                .body("serviceName", equalTo("card-management"));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(49_000)));
    }

    @Test
    @DisplayName("Reserve above balance -> 402 InsufficientFundsException")
    void reserveAboveBalanceIs402() {
        CreatedCard card = CardFactory.aCard().balance(500).unlimited().create();

        Response response = reserve(card.pan(), 501, TestData.rrn());

        response.then()
                .statusCode(402)
                .body("error", equalTo("InsufficientFundsException"))
                .body("message", equalTo("Not enough funds on this account"));
    }

    @Test
    @DisplayName("Reserve on a card that is not ACTIVE -> 409 IllegalStateException (Card.checkStatusOrThrow)")
    void reserveOnInactiveCardIsConflict() {
        String status = "BLOCKED";
        CreatedCard card = CardFactory.aCard().unlimited().status(status).create();

        Response response = reserve(card.pan(), 100, TestData.rrn());

        response.then()
                .statusCode(409)
                .body("error", equalTo("IllegalStateException"))
                .body("message", equalTo("Can't perform operation: card status is \"" + status + "\""));
    }

    @Test
    @DisplayName("Reserve with RRN of 11 digits -> 400 naming the RRN rule")
    void invalidRrnIs400() {
        CreatedCard card = CardFactory.aCard().unlimited().create();

        Response response = reserve(card.pan(), 100, "12345678901");

        response.then()
                .statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", equalTo("RRN number must contain exactly 12 digits"));
    }

    @Test
    @DisplayName("Reserve on an unknown PAN -> 404 CardNotFoundException with a masked PAN")
    void reserveOnUnknownCardIs404() {
        String pan = TestData.unknownPanOfKnownBin();

        Response response = reserve(pan, 100, TestData.rrn());

        response.then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"))
                .body("message", equalTo("Card with PAN " + pan.substring(0, 4) + "********" + pan.substring(12)
                        + " was not found"));
    }

    @Test
    @DisplayName("Rollback 200 returns the reserved amount; a repeated rollback -> 409, money not returned twice")
    void rollbackIsAppliedOnce() {
        CreatedCard card = CardFactory.aCard().balance(50_000).unlimited().create();
        String rrn = TestData.rrn();
        reserve(card.pan(), 1_000, rrn).then().statusCode(200);
        rollback(card.pan(), 1_000, rrn).then().statusCode(200).body("availableBalance", equalTo(50_000));

        Response response = rollback(card.pan(), 1_000, rrn);

        response.then()
                .statusCode(409)
                .body("error", equalTo("RollbackAlreadySatisfiedException"))
                .body("message", startsWith("Rollback with RRN \"" + rrn + "\""));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(50_000)));
    }

    @Test
    @DisplayName("Rollback of an unknown reservation -> 404 ReservationNotFoundException")
    void rollbackOfUnknownReservationIs404() {
        CreatedCard card = CardFactory.aCard().unlimited().create();

        Response response = rollback(card.pan(), 100, TestData.rrn());

        response.then()
                .statusCode(404)
                .body("error", equalTo("ReservationNotFoundException"));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Partial rollback closes the reservation: the remainder can never be returned (D-19)")
    void partialRollbackClosesReservation() {
        CreatedCard card = CardFactory.aCard().balance(50_000).unlimited().create();
        String rrn = TestData.rrn();
        reserve(card.pan(), 1_000, rrn).then().statusCode(200);
        rollback(card.pan(), 400, rrn).then().statusCode(200);

        Response response = rollback(card.pan(), 600, rrn);

        response.then().statusCode(409).body("error", equalTo("RollbackAlreadySatisfiedException"));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(49_400)));
    }
}
