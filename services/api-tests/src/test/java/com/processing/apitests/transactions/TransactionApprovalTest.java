package com.processing.apitests.transactions;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Patterns;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("POST /api/transactions: approval (api-spec section 3; tz/04 step 7; AuthServiceImpl RRN and authCode)")
class TransactionApprovalTest {

    @Test
    @DisplayName("Approved: 200, full AuthorizationResponse, mti/RRN/authCode by code, funds reserved")
    void approvedTransactionReturnsFullResponseAndReservesFunds() {
        CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
        AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(15_000);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String rrnPrefix = "(" + prefix(today) + "|" + prefix(today.minusDays(1)) + ")";
        RequestSpecification request = given().spec(Specs.gateway()).body(body.build());

        Response response = request.when().post("/api/transactions");

        response.then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(body.get("stan")))
                .body("rrn", matchesPattern(Patterns.RRN))
                .body("rrn", matchesPattern("^" + rrnPrefix + "\\d{8}$"))
                .body("authCode", matchesPattern(Patterns.AUTH_CODE))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("$", hasKey("declineReason"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
        assertThat("balance after reservation (CMS, uncached)", CardFactory.balanceOf(card.pan()),
                comparesEqualTo(new BigDecimal(85_000)));
    }

    private static String prefix(LocalDate date) {
        return String.format("%1d%03d", date.getYear() % 10, date.getDayOfYear());
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Fractional amount is approved but nothing is reserved: CMS rounds the balance to integers (D-12)")
    void fractionalAmountIsApprovedWithoutReservation() {
        CreatedCard card = CardFactory.aCard().balance(1_000).unlimited().create();
        RequestSpecification request = given().spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.forPan(card.pan()).amount(new BigDecimal("0.4")).build());

        Response response = request.when().post("/api/transactions");

        response.then().statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("rrn", matchesPattern(Patterns.RRN));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(1_000)));
    }
}
