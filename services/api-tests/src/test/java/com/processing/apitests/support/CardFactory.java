package com.processing.apitests.support;

import com.processing.apitests.support.builders.CardRequestBuilder;
import io.restassured.path.json.JsonPath;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;

import static io.restassured.RestAssured.given;

public final class CardFactory {

    private CardFactory() {
    }

    public static CardSpec aCard() {
        return new CardSpec();
    }

    public static BigDecimal balanceOf(String pan) {
        return new BigDecimal(given().spec(Specs.cardManagement())
                .when().get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .extract().path("availableBalance").toString());
    }

    public static String statusOf(String pan) {
        return given().spec(Specs.cardManagement())
                .when().get("/api/cards/{pan}", pan)
                .then().statusCode(200)
                .extract().path("status");
    }

    public static void setStatus(String pan, String status) {
        given().spec(Specs.gateway())
                .body(Map.of("status", status))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);
    }

    public static void setBalance(String pan, long balance) {
        given().spec(Specs.gateway())
                .body(Map.of("availableBalance", balance))
                .when().patch("/api/cards/{pan}", pan)
                .then().statusCode(200);
    }

    public static final class CardSpec {
        private final CardRequestBuilder request = CardRequestBuilder.aCard();
        private String status = "ACTIVE";

        private CardSpec() {
        }

        public CardSpec bin(String bin) {
            request.bin(bin);
            return this;
        }

        public CardSpec balance(long balance) {
            request.balance(balance);
            return this;
        }

        public CardSpec dailyLimit(long dailyLimit) {
            request.dailyLimit(dailyLimit);
            return this;
        }

        public CardSpec monthlyLimit(long monthlyLimit) {
            request.monthlyLimit(monthlyLimit);
            return this;
        }

        public CardSpec unlimited() {
            request.dailyLimit(1_000_000_000L).monthlyLimit(1_000_000_000L);
            return this;
        }

        public CardSpec status(String cardStatus) {
            this.status = cardStatus;
            return this;
        }

        public CreatedCard create() {
            JsonPath card = given().spec(Specs.gateway())
                    .body(request.build())
                    .when().post("/api/cards")
                    .then().statusCode(201)
                    .extract().jsonPath();
            String pan = card.getString("pan");
            if (!"ACTIVE".equals(status)) {
                setStatus(pan, status);
            }
            return new CreatedCard(
                    pan,
                    card.getString("bin"),
                    card.getString("issuerId"),
                    YearMonth.parse(card.getString("expiryDate")),
                    new BigDecimal(card.getString("availableBalance")));
        }
    }

    public record CreatedCard(String pan, String bin, String issuerId, YearMonth expiry, BigDecimal initialBalance) {
    }
}
