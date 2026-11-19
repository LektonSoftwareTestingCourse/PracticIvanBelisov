package com.processing.apitests.cards;

import com.processing.apitests.support.Patterns;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.builders.CardRequestBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("POST /api/cards (api-spec section 2; CreateCardRequest, CardServiceImpl.createCard)")
class CardCreateTest {

    private static final String PATH = "/api/cards";
    private static final String NAME_RULE = "Cardholder name can contain only uppercase letters, spaces, ', dots and -";

    private static Response create(Object body) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);
        return request.when().post(PATH);
    }

    @Test
    @DisplayName("201: Card with the requested data, generated PAN of the BIN, ACTIVE, expiry +3 years")
    void cardIsCreated() {
        CardRequestBuilder body = CardRequestBuilder.aCard();
        YearMonth now = YearMonth.now(ZoneOffset.UTC);

        Response response = create(body.build());

        response.then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("id", matchesPattern(Patterns.UUID))
                .body("pan", matchesPattern(Patterns.panOfBin("400000")))
                .body("bin", equalTo("400000"))
                .body("cardholderName", equalTo("IVAN IVANOV"))
                .body("status", equalTo("ACTIVE"))
                .body("currencyCode", equalTo("643"))
                .body("dailyLimit", equalTo(15_000_000))
                .body("monthlyLimit", equalTo(300_000_000))
                .body("availableBalance", equalTo(100_000_000))
                .body("issuerId", equalTo("ISS001"))
                .body("expiryDate", matchesPattern(Patterns.YEAR_MONTH))
                .body("expiryDate", oneOf(now.plusYears(3).toString(), now.plusYears(3).plusMonths(1).toString()))
                .body("createdAt", matchesPattern(Patterns.ISO_INSTANT));
        assertThat("PAN passes Luhn (tz/05)", TestData.isLuhnValid(response.path("pan")), is(true));
    }

    @ParameterizedTest(name = "BIN {0} -> issuerId {1}")
    @CsvSource({"400000, ISS001", "400001, ISS002", "400002, ISS003", "400003, ISS004", "400004, ISS005"})
    @DisplayName("issuerId is taken from the BIN table (BinIssuerService)")
    void issuerIdFollowsBin(String bin, String issuerId) {
        CardRequestBuilder body = CardRequestBuilder.aCard().bin(bin);

        Response response = create(body.build());

        response.then()
                .statusCode(201)
                .body("bin", equalTo(bin))
                .body("issuerId", equalTo(issuerId))
                .body("pan", startsWith(bin));
    }

    @Test
    @DisplayName("Boundary values: zero balance and zero limits are accepted (@NotNegative)")
    void zeroValuesAreAccepted() {
        CardRequestBuilder body = CardRequestBuilder.aCard().balance(0).dailyLimit(0).monthlyLimit(0);

        Response response = create(body.build());

        response.then()
                .statusCode(201)
                .body("availableBalance", equalTo(0))
                .body("dailyLimit", equalTo(0))
                .body("monthlyLimit", equalTo(0));
    }

    @ParameterizedTest(name = "cardholderName \"{0}\" is accepted")
    @CsvSource(value = {"A", "ANNA-MARIA O'NEIL", "J. R. R. TOLKIEN"}, quoteCharacter = '"')
    @DisplayName("Cardholder name: uppercase letters, spaces, apostrophe, dot and hyphen are valid")
    void validCardholderNames(String name) {
        CardRequestBuilder body = CardRequestBuilder.aCard().with("cardholderName", name);

        Response response = create(body.build());

        response.then().statusCode(201).body("cardholderName", equalTo(name));
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("bin", "4000A0", "BIN number must contain exactly 6 digits"),
                Arguments.of("bin", "40000", "BIN number must contain exactly 6 digits"),
                Arguments.of("bin", "4000000", "BIN number must contain exactly 6 digits"),
                Arguments.of("cardholderName", "ivan ivanov", NAME_RULE),
                Arguments.of("cardholderName", "IVAN1", NAME_RULE),
                Arguments.of("currencyCode", "64", "The length must be exactly 3 symbols"),
                Arguments.of("currencyCode", "6430", "The length must be exactly 3 symbols"),
                Arguments.of("currencyCode", "64A", "Value can contain digits only"),
                Arguments.of("dailyLimit", -1, "Value can not be negative"),
                Arguments.of("monthlyLimit", -1, "Value can not be negative"),
                Arguments.of("bin", null, "must not be blank"),
                Arguments.of("cardholderName", null, "must not be blank"),
                Arguments.of("currencyCode", null, "must not be blank"));
    }

    @ParameterizedTest(name = "{0} = {1} -> 400 \"{2}\"")
    @MethodSource("invalidFields")
    @DisplayName("Required, format and range rules of CreateCardRequest -> 400 MethodArgumentNotValidException")
    void invalidFieldIsRejected(String field, Object value, String expectedMessage) {
        CardRequestBuilder body = CardRequestBuilder.aCard().with(field, value);

        Response response = create(body.build());

        response.then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", equalTo(expectedMessage))
                .body("serviceName", equalTo("card-management"))
                .body("retryAfterMs", nullValue());
    }

    @Test
    @DisplayName("BIN absent from the BIN table -> 404 BinNotFoundException (spec lists only 409, D-24)")
    void unknownBinIs404() {
        CardRequestBuilder body = CardRequestBuilder.aCard().bin(TestData.UNKNOWN_BIN);

        Response response = create(body.build());

        response.then()
                .statusCode(404)
                .body("error", equalTo("BinNotFoundException"))
                .body("message", equalTo("BIN not found: Unknown BIN: " + TestData.UNKNOWN_BIN))
                .body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest(name = "initialBalance={0}, dailyLimit={1}, monthlyLimit={2} -> 201")
    @CsvSource({
        "-1, 15000000, 300000000",
        "100, 5000, 1000"})
    @Tag("known-defect")
    @DisplayName("Invalid card data is accepted on create: negative balance, dailyLimit > monthlyLimit (D-13)")
    void invalidCardDataIsAcceptedOnCreate(long balance, long daily, long monthly) {
        CardRequestBuilder body = CardRequestBuilder.aCard().balance(balance).dailyLimit(daily).monthlyLimit(monthly);

        Response response = create(body.build());

        response.then().statusCode(201)
                .body("availableBalance", equalTo((int) balance))
                .body("dailyLimit", equalTo((int) daily))
                .body("monthlyLimit", equalTo((int) monthly));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Limits absent -> 400 OutOfRetriesException \"PAN already exists\": NOT NULL violation "
            + "is reported as a PAN collision (D-10)")
    void absentLimitsAreMisreportedAsPanCollision() {
        CardRequestBuilder body = CardRequestBuilder.aCard().without("dailyLimit").without("monthlyLimit")
                .without("initialBalance");

        Response response = create(body.build());

        response.then()
                .statusCode(400)
                .body("error", equalTo("OutOfRetriesException"))
                .body("message", matchesPattern("^Operation failed \\(retries > 3\\. Reason: .*PanCollisionException.*"));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Unreadable JSON -> 500 in Card Management (no handler for HttpMessageNotReadableException) (D-11)")
    void unreadableJsonIs500() {
        RequestSpecification request = given().spec(Specs.cardManagement()).body("{\"bin\":");

        Response response = request.when().post(PATH);

        response.then()
                .statusCode(500)
                .body("error", equalTo("HttpMessageNotReadableException"))
                .body("serviceName", equalTo("card-management"));
    }
}
