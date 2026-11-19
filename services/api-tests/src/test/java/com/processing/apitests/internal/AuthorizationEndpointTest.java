package com.processing.apitests.internal;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Patterns;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@DisplayName("POST /api/internal/authorize (api-spec section 7; AuthControllerImpl status mapping, AuthorizationRequest)")
class AuthorizationEndpointTest {

    private static final String PATH = "/api/internal/authorize";

    private static AuthorizationRequestBuilder requestFor(String pan) {
        return AuthorizationRequestBuilder.forPan(pan).issuerId("ISS001");
    }

    private static Response authorize(AuthorizationRequestBuilder body) {
        RequestSpecification request = given().spec(Specs.authorization()).body(body.build());
        return request.when().post(PATH);
    }

    @Test
    @DisplayName("200 APPROVED with RRN and authCode")
    void approvedIs200() {
        CreatedCard card = CardFactory.aCard().balance(100_000).unlimited().create();
        AuthorizationRequestBuilder body = requestFor(card.pan()).amount(1_000);

        Response response = authorize(body);

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern(Patterns.RRN))
                .body("authCode", matchesPattern(Patterns.AUTH_CODE))
                .body("declineReason", nullValue());
        assertThat(CardFactory.balanceOf(card.pan()), comparesEqualTo(new BigDecimal(99_000)));
    }

    static Stream<Arguments> declines() {
        Supplier<String> unknownCard = TestData::unknownPanOfKnownBin;
        Supplier<String> blocked = () -> CardFactory.aCard().unlimited().status("BLOCKED").create().pan();
        Supplier<String> inactive = () -> CardFactory.aCard().unlimited().status("INACTIVE").create().pan();
        Supplier<String> expired = () -> CardFactory.aCard().unlimited().status("EXPIRED").create().pan();
        Supplier<String> lowBalance = () -> CardFactory.aCard().balance(100).unlimited().create().pan();
        Supplier<String> lowLimit = () -> CardFactory.aCard().balance(100_000).dailyLimit(100).monthlyLimit(100)
                .create().pan();
        return Stream.of(
                Arguments.of("unknown card", unknownCard, 404, "14", "CARD_NOT_FOUND"),
                Arguments.of("BLOCKED card", blocked, 403, "05", "CARD_BLOCKED"),
                Arguments.of("INACTIVE card", inactive, 403, "05", "CARD_INACTIVE"),
                Arguments.of("EXPIRED card", expired, 403, "54", "CARD_EXPIRED"),
                Arguments.of("amount 101 > balance 100", lowBalance, 422, "51", "INSUFFICIENT_FUNDS"),
                Arguments.of("amount 101 > dailyLimit 100", lowLimit, 400, "61", "EXCEEDS_AMOUNT_LIMIT"));
    }

    @ParameterizedTest(name = "{0} -> HTTP {2}, {3} / {4}")
    @MethodSource("declines")
    @DisplayName("Decline is mapped to its own HTTP status, body keeps code and reason (Switch hides the status)")
    void declineHasOwnHttpStatus(String description, Supplier<String> pan, int httpStatus, String code, String reason) {
        AuthorizationRequestBuilder body = requestFor(pan.get()).amount(101);

        Response response = authorize(body);

        response.then()
                .statusCode(httpStatus)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason));
    }

    @ParameterizedTest(name = "{0} -> 400 Spring error body")
    @CsvSource({"issuerId absent (@NotBlank; set by Switch), ", "stan of 5 digits (@ExactSize(6)), 12345"})
    @DisplayName("Bean validation of AuthorizationRequest -> 400 Spring default body, not an AuthorizationResponse")
    void invalidRequestIs400(String description, String stan) {
        AuthorizationRequestBuilder body = stan == null
                ? AuthorizationRequestBuilder.forPan(TestData.unknownPanOfKnownBin())
                : requestFor(TestData.unknownPanOfKnownBin()).stan(stan);

        Response response = authorize(body);

        response.then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("error", equalTo("Bad Request"))
                .body("path", equalTo(PATH))
                .body("responseCode", nullValue());
    }

    @Test
    @Tag("known-defect")
    @DisplayName("amount 0 passes @PositiveOrZero, CMS rejects the reservation (400) and Authorization reports "
            + "CARD_NOT_FOUND (D-16)")
    void zeroAmountIsMisreportedAsCardNotFound() {
        CreatedCard card = CardFactory.aCard().unlimited().create();

        Response response = authorize(requestFor(card.pan()).amount(0));

        response.then()
                .statusCode(404)
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
        assertThat(CardFactory.statusOf(card.pan()), equalTo("ACTIVE"));
    }
}
