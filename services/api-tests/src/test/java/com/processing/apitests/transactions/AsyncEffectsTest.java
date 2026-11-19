package com.processing.apitests.transactions;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.Waits;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.processing.apitests.support.TransactionSteps.searchByPan;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("Asynchronous effects: appearance and absence (Switch -> RabbitMQ -> Logger; bounds in support/Waits)")
class AsyncEffectsTest {

    @Test
    @DisplayName("Absence: a request rejected by Gateway validation never reaches the storage; "
            + "a valid request of the same card does (control)")
    void rejectedRequestLeavesNoRecord() {
        CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
        RequestSpecification invalid = given().spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.forPan(card.pan()).amount(0).build());

        Response rejected = invalid.when().post("/api/transactions");

        rejected.then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Field 'amount' must be > 0"));
        await().during(Waits.ABSENCE_WINDOW).atMost(Waits.ABSENCE_TIMEOUT).pollInterval(Waits.ABSENCE_POLL_INTERVAL)
                .untilAsserted(() -> searchByPan(card.pan()).then().statusCode(200).body("total", equalTo(0)));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(100_000)));
        TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()));
        await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL).untilAsserted(() ->
                searchByPan(card.pan()).then().body("total", equalTo(1)));
    }

    @Test
    @DisplayName("Absence on rollback: POST /api/internal/rollback 200 returns the money and adds NO record")
    void rollbackAddsNoRecord() {
        CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
        String rrn = TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(40_000));
        await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL).untilAsserted(() ->
                searchByPan(card.pan()).then().body("total", equalTo(1)));
        RequestSpecification reversal = given().spec(Specs.authorization())
                .body(Map.of("rrn", rrn, "pan", card.pan(), "amount", 40_000));

        Response response = reversal.when().post("/api/internal/rollback");

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/rollback-response.json"))
                .body("rrn", equalTo(rrn))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue());
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(100_000)));
        await().during(Waits.ABSENCE_WINDOW).atMost(Waits.ABSENCE_TIMEOUT).pollInterval(Waits.ABSENCE_POLL_INTERVAL)
                .untilAsserted(() -> searchByPan(card.pan()).then()
                        .body("total", equalTo(1))
                        .body("transactions[0].status", equalTo("APPROVED"))
                        .body("transactions[0].rrn", equalTo(rrn)));
    }
}
