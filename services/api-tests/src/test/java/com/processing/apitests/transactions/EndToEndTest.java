package com.processing.apitests.transactions;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.Waits;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.processing.apitests.support.TransactionSteps.searchByPan;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("End-to-end: Gateway -> Switch -> RabbitMQ -> Logger -> search (api-spec sections 3 and 7)")
class EndToEndTest {

    @Test
    @DisplayName("Approved transaction reaches the storage: every field of the request and of the answer is kept")
    void approvedTransactionIsStoredAndSearchable() {
        CreatedCard card = CardFactory.aCard().bin(TestData.BIN_ISS003).balance(100_000).unlimited().create();
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.forPan(card.pan())
                .amount(12_345).issuerId("FAKE01");

        JsonPath answer = TransactionSteps.send(request);

        String rrn = answer.getString("rrn");
        String byRrn = "transactions.find { it.rrn == '" + rrn + "' }";
        await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL).untilAsserted(() ->
                searchByPan(card.pan()).then()
                        .statusCode(200)
                        .body("total", equalTo(1))
                        .body(byRrn, notNullValue()));
        searchByPan(card.pan()).then()
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body(byRrn + ".status", equalTo("APPROVED"))
                .body(byRrn + ".authCode", equalTo(answer.getString("authCode")))
                .body(byRrn + ".stan", equalTo(request.get("stan")))
                .body(byRrn + ".amount", equalTo(12_345))
                .body(byRrn + ".mti", equalTo("0100"))
                .body(byRrn + ".terminalId", equalTo(request.get("terminalId")))
                .body(byRrn + ".merchantId", equalTo(request.get("merchantId")))
                .body(byRrn + ".mcc", equalTo("5411"))
                .body(byRrn + ".transmissionDateTime", equalTo(request.get("transmissionDateTime")))
                .body(byRrn + ".issuerId", equalTo("ISS003"))
                .body(byRrn + ".declineReason", nullValue())
                .body(byRrn + ".acquiringFee", nullValue())
                .body(byRrn + ".createdAt", notNullValue());
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Queue path skips Logger validation: stan \"ABCDEF\" is stored, the same message over HTTP is 400 (D-20)")
    void queuePathBypassesLoggerValidation() {
        CreatedCard card = CardFactory.aCard().unlimited().create();
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.forPan(card.pan()).stan("ABCDEF");

        TransactionSteps.send(request);

        await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL).untilAsserted(() ->
                searchByPan(card.pan()).then()
                        .body("total", equalTo(1))
                        .body("transactions[0].stan", equalTo("ABCDEF")));
    }
}
