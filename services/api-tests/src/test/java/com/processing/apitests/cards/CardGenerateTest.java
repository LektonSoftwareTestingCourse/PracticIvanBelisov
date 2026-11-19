package com.processing.apitests.cards;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

@DisplayName("POST /api/cards/generate (api-spec section 2; GenerateCardsRequest, app.card-generator.max-count)")
class CardGenerateTest {

    private static final String PATH = "/api/cards/generate";

    @ParameterizedTest(name = "count={0}, bins={1}")
    @CsvSource(value = {"1|400004", "3|400001,400002"}, delimiter = '|')
    @DisplayName("201: generated = count, cards hasSize(count), every card of a requested BIN with a Luhn-valid PAN")
    void cardsAreGenerated(int count, String binList) {
        List<String> bins = List.of(binList.split(","));
        RequestSpecification request = given().spec(Specs.gateway()).body(Map.of("count", count, "bins", bins));

        Response response = request.when().post(PATH);

        response.then()
                .statusCode(201)
                .body("generated", equalTo(count))
                .body("cards", hasSize(count))
                .body("cards.bin", everyItem(oneOf(bins.toArray())))
                .body("cards.status", everyItem(oneOf("ACTIVE", "INACTIVE", "BLOCKED")))
                .body("cards.findAll { !(it.pan ==~ /\\d{16}/) }", empty());
        List<String> pans = response.path("cards.pan");
        assertThat(pans.stream().allMatch(TestData::isLuhnValid), is(true));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("count 0", Map.of("count", 0, "bins", List.of("400000")), 400,
                        "MethodArgumentNotValidException", "must be greater than or equal to 1"),
                Arguments.of("count 10001", Map.of("count", 10_001, "bins", List.of("400000")), 400,
                        "CardGenerationLimitException", "Count exceeds maximum value: 10000"),
                Arguments.of("bins empty", Map.of("count", 1, "bins", List.of()), 400,
                        "MethodArgumentNotValidException", "must not be empty"),
                Arguments.of("bin of 2 digits", Map.of("count", 1, "bins", List.of("12")), 400,
                        "MethodArgumentNotValidException", "BIN number must contain exactly 6 digits"),
                Arguments.of("unknown bin", Map.of("count", 1, "bins", List.of(TestData.UNKNOWN_BIN)), 404,
                        "BinNotFoundException", "BIN not found: Unknown BIN: " + TestData.UNKNOWN_BIN));
    }

    @ParameterizedTest(name = "{0} -> {2} {3}")
    @MethodSource("invalidRequests")
    @DisplayName("Invalid generation request: status, error class and message")
    void invalidRequestIsRejected(String description, Map<String, Object> body, int status, String error, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).body(body);

        Response response = request.when().post(PATH);

        response.then()
                .statusCode(status)
                .body("error", equalTo(error))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("card-management"));
    }
}
