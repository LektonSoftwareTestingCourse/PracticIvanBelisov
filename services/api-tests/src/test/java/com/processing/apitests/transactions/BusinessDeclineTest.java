package com.processing.apitests.transactions;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("POST /api/transactions: business declines (AuthServiceImpl.authorize, DeclineOutcome; tz/04, api-spec codes)")
class BusinessDeclineTest {

    private static final String PATH = "/api/transactions";

    private static Response send(AuthorizationRequestBuilder body) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body.build());
        return request.when().post(PATH);
    }

    private static void assertAuthorizationDecline(Response response, AuthorizationRequestBuilder body,
                                                   String code, String reason) {
        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(body.get("stan")))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason))
                .body("rrn", nullValue())
                .body("authCode", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    private static void assertBalanceUnchanged(CreatedCard card) {
        assertThat("a decline must not reserve funds", CardFactory.balanceOf(card.pan()),
                comparesEqualTo(card.initialBalance()));
    }

    @Nested
    @DisplayName("CARD_NOT_FOUND, code 14")
    class CardNotFound {

        @Test
        @DisplayName("Path 1, request data: BIN is routed, PAN does not exist -> Authorization declines")
        void unknownPanOfKnownBinIsDeclinedByAuthorization() {
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(TestData.unknownPanOfKnownBin());

            Response response = send(body);

            assertAuthorizationDecline(response, body, "14", "CARD_NOT_FOUND");
        }

        @Test
        @DisplayName("Path 2, request data: BIN absent from routing table -> Switch declines, Authorization not called")
        void unknownBinIsDeclinedBySwitch() {
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(TestData.panOfUnknownBin());

            Response response = send(body);

            response.then()
                    .statusCode(200)
                    .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                    .body("mti", equalTo("0110"))
                    .body("stan", equalTo(body.get("stan")))
                    .body("status", equalTo("DECLINED"))
                    .body("responseCode", equalTo("14"))
                    .body("declineReason", equalTo("CARD_NOT_FOUND"))
                    .body("processingTimeMs", equalTo(0))
                    .body("rrn", nullValue())
                    .body("authCode", nullValue());
        }

        @Test
        @DisplayName("Path 3, card state: card deleted (soft delete) -> CMS hides it -> 14")
        void deletedCardIsDeclinedAsNotFound() {
            CreatedCard card = CardFactory.aCard().unlimited().create();
            given().spec(Specs.gateway()).when().delete("/api/cards/{pan}", card.pan()).then().statusCode(204);
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan());

            Response response = send(body);

            assertAuthorizationDecline(response, body, "14", "CARD_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("Card status declines (tz/04 step 2)")
    class CardStatus {

        @ParameterizedTest(name = "status {0} -> {1} / {2}")
        @CsvSource({
            "BLOCKED,  05, CARD_BLOCKED",
            "INACTIVE, 05, CARD_INACTIVE",
            "EXPIRED,  54, CARD_EXPIRED"})
        @DisplayName("Not ACTIVE card is declined with its own reason; BLOCKED and INACTIVE share code 05")
        void nonActiveCardIsDeclined(String status, String code, String reason) {
            CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().status(status).create();
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(1_000);

            Response response = send(body);

            assertAuthorizationDecline(response, body, code, reason);
            assertBalanceUnchanged(card);
        }

        @Test
        @DisplayName("Status is checked before funds: BLOCKED card with an amount above balance -> CARD_BLOCKED")
        void statusHasPriorityOverFunds() {
            CreatedCard card = CardFactory.aCard().balance(100).unlimited().status("BLOCKED").create();
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(1_000_000);

            Response response = send(body);

            assertAuthorizationDecline(response, body, "05", "CARD_BLOCKED");
        }
    }

    @Nested
    @DisplayName("CARD_EXPIRED, code 54: two paths")
    class CardExpired {

        @Test
        @DisplayName("Path 1, card state: status EXPIRED (see CardStatus); path 2, request data: date after expiry")
        void transmissionDateAfterExpiryMonthIsDeclined() {
            CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
            Instant firstDayAfterExpiry = card.expiry().plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan())
                    .transmissionDateTime(firstDayAfterExpiry);

            Response response = send(body);

            assertAuthorizationDecline(response, body, "54", "CARD_EXPIRED");
            assertThat(CardFactory.statusOf(card.pan()), equalTo("ACTIVE"));
            assertBalanceUnchanged(card);
        }

        @Test
        @DisplayName("Boundary ON: last second of the expiry month is still approved")
        void lastSecondOfExpiryMonthIsApproved() {
            CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
            YearMonth expiry = card.expiry();
            Instant lastSecond = expiry.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan())
                    .transmissionDateTime(lastSecond).amount(1_000);

            Response response = send(body);

            response.then().statusCode(200)
                    .body("status", equalTo("APPROVED"))
                    .body("responseCode", equalTo("00"));
        }
    }

    @Nested
    @DisplayName("Path 1, request data: the amount alone causes the decline (51 / 61)")
    class AmountInRequest {

        @ParameterizedTest(name = "balance {0}, limits {1}/{2}, amount {3} -> {4} {5}")
        @CsvSource({
            "50000,   1000000000, 1000000000, 50001, 51, INSUFFICIENT_FUNDS",
            "1000000, 10000,      1000000,    10001, 61, EXCEEDS_AMOUNT_LIMIT",
            "1000,    500,        500,        2000,  51, INSUFFICIENT_FUNDS"})
        @DisplayName("Amount above balance or above dailyLimit is declined, nothing is reserved")
        void amountAboveBalanceOrLimitIsDeclined(long balance, long daily, long monthly, long amount,
                                                 String code, String reason) {
            CreatedCard card = CardFactory.aCard().balance(balance).dailyLimit(daily).monthlyLimit(monthly).create();
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(amount);

            Response response = send(body);

            assertAuthorizationDecline(response, body, code, reason);
            assertBalanceUnchanged(card);
        }
    }

    @Nested
    @DisplayName("INSUFFICIENT_FUNDS, code 51: boundary and path 2 (card state)")
    class InsufficientFunds {

        @Test
        @DisplayName("Boundary ON: amount = balance is approved and the balance becomes 0")
        void amountEqualToBalanceIsApproved() {
            CreatedCard card = CardFactory.aCard().balance(50_000).unlimited().create();
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(50_000);

            Response response = send(body);

            response.then().statusCode(200).body("status", equalTo("APPROVED"));
            assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("Path 2, card state: balance lowered after creation (PATCH) -> the same amount is declined")
        void lowBalanceStateIsDeclined() {
            CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
            CardFactory.setBalance(card.pan(), 999);
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(1_000);

            Response response = send(body);

            assertAuthorizationDecline(response, body, "51", "INSUFFICIENT_FUNDS");
            assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(999)));
        }
    }

    @Nested
    @DisplayName("EXCEEDS_AMOUNT_LIMIT, code 61: path 2 (accumulated usage) and boundaries")
    class ExceedsLimit {

        @Test
        @DisplayName("Path 2, card state: daily usage exhausted by an approved amount = dailyLimit, next 1 -> 61")
        void accumulatedDailyUsageIsDeclined() {
            CreatedCard card = CardFactory.aCard().balance(1_000_000).dailyLimit(10_000).monthlyLimit(1_000_000)
                    .create();
            Instant day = TestData.now();
            TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(10_000)
                    .transmissionDateTime(day));
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(1)
                    .transmissionDateTime(day);

            Response response = send(body);

            assertAuthorizationDecline(response, body, "61", "EXCEEDS_AMOUNT_LIMIT");
            assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(990_000)));
        }

        @Test
        @DisplayName("Monthly limit: two days of one month, each under dailyLimit, together above monthlyLimit -> 61")
        void accumulatedMonthlyUsageIsDeclined() {
            CreatedCard card = CardFactory.aCard().balance(1_000_000).dailyLimit(10_000).monthlyLimit(15_000)
                    .create();
            LocalDate dayOne = YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(10);
            TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(10_000)
                    .transmissionDateTime(noonOf(dayOne)));
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(5_001)
                    .transmissionDateTime(noonOf(dayOne.plusDays(1)));

            Response response = send(body);

            assertAuthorizationDecline(response, body, "61", "EXCEEDS_AMOUNT_LIMIT");
        }

        @Test
        @DisplayName("Monthly limit boundary ON: the remaining 5 000 of the month is approved")
        void monthlyRemainderIsApproved() {
            CreatedCard card = CardFactory.aCard().balance(1_000_000).dailyLimit(10_000).monthlyLimit(15_000)
                    .create();
            LocalDate dayOne = YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(10);
            TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(10_000)
                    .transmissionDateTime(noonOf(dayOne)));
            AuthorizationRequestBuilder body = AuthorizationRequestBuilder.forPan(card.pan()).amount(5_000)
                    .transmissionDateTime(noonOf(dayOne.plusDays(1)));

            Response response = send(body);

            response.then().statusCode(200).body("status", equalTo("APPROVED"));
        }
    }

    private static Instant noonOf(LocalDate date) {
        return date.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }
}
