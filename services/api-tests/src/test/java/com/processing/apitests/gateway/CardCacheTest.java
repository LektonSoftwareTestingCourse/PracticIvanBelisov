package com.processing.apitests.gateway;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("Gateway response cache for /api/cards/** (X-Cache; ResponseCachingGatewayFilterFactory, TTL 5 min)")
class CardCacheTest {

    private static final String CARD = "/api/cards/{pan}";

    @Test
    @DisplayName("First GET -> X-Cache: MISS, second identical GET -> X-Cache: HIT with the same body")
    void secondReadIsServedFromCache() {
        CreatedCard card = CardFactory.aCard().create();
        RequestSpecification first = given().spec(Specs.gateway());
        RequestSpecification second = given().spec(Specs.gateway());

        Response miss = first.when().get(CARD, card.pan());
        Response hit = second.when().get(CARD, card.pan());

        miss.then().statusCode(200).header("X-Cache", equalTo("MISS")).header("Content-Type", "application/json");
        hit.then().statusCode(200).header("X-Cache", equalTo("HIT")).header("Content-Type", "application/json");
        assertThat(hit.asString(), equalTo(miss.asString()));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Balance changed by an authorization is not visible through Gateway: stale HIT up to 5 min (D-09)")
    void authorizationDoesNotInvalidateCache() {
        CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
        given().spec(Specs.gateway()).when().get(CARD, card.pan()).then().header("X-Cache", "MISS");
        TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()).amount(1_000));
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get(CARD, card.pan());

        response.then()
                .statusCode(200)
                .header("X-Cache", equalTo("HIT"))
                .body("availableBalance", equalTo(100_000));
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(99_000)));
    }
}
