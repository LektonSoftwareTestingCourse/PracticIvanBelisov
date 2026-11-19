package com.processing.apitests.internal;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("POST /api/internal/rollback (AuthControllerImpl.rollback, DeclineOutcome; tz/04 Reversal)")
class AuthorizationRollbackTest {

    private static final String PATH = "/api/internal/rollback";

    private static Response rollback(String rrn, String pan, Object amount) {
        RequestSpecification request = given().spec(Specs.authorization())
                .body(Map.of("rrn", rrn, "pan", pan, "amount", amount));
        return request.when().post(PATH);
    }

    @Test
    @DisplayName("409 ALREADY_ROLLED_BACK / 05 on a repeated rollback, balance is not credited twice")
    void secondRollbackIsConflict() {
        CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
        String rrn = TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(30_000));
        rollback(rrn, card.pan(), 30_000).then().statusCode(200);

        Response response = rollback(rrn, card.pan(), 30_000);

        response.then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath("schemas/rollback-response.json"))
                .body("rrn", equalTo(rrn))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(100_000)));
    }

    @Test
    @DisplayName("404 TRANSACTION_NOT_FOUND / 14 for an RRN without reservation")
    void unknownRrnIsNotFound() {
        CreatedCard card = CardFactory.aCard().unlimited().create();
        String rrn = TestData.rrn();

        Response response = rollback(rrn, card.pan(), 100);

        response.then()
                .statusCode(404)
                .body("rrn", equalTo(rrn))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("400 Spring error body for an invalid request: RRN not 12 digits (bean validation class)")
    void invalidRrnIs400() {
        CreatedCard card = CardFactory.aCard().unlimited().create();

        Response response = rollback("12345", card.pan(), 100);

        response.then()
                .statusCode(400)
                .body("error", equalTo("Bad Request"))
                .body("path", equalTo(PATH));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Rollback returns money but not the daily limit usage: the same amount is then declined with 61 (D-18)")
    void rollbackDoesNotReleaseLimitUsage() {
        CreatedCard card = CardFactory.aCard().balance(100_000).dailyLimit(10_000).monthlyLimit(1_000_000).create();
        Instant day = TestData.now();
        String rrn = TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(10_000)
                .transmissionDateTime(day));
        rollback(rrn, card.pan(), 10_000).then().statusCode(200);
        RequestSpecification repeat = given().spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.forPan(card.pan()).amount(10_000).transmissionDateTime(day).build());

        Response response = repeat.when().post("/api/transactions");

        response.then()
                .statusCode(200)
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(100_000)));
    }
}
