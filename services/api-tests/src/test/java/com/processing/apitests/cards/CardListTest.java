package com.processing.apitests.cards;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.builders.CardRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("GET /api/cards: filters and pagination (CardController.getCards, CardCriteriaBuilderJpaRepositoryImpl)")
class CardListTest {

    private static final String PATH = "/api/cards";
    private static final String BIN = "400003";
    private static final String ISSUER = "ISS004";

    private String startDate;
    private final List<Map<String, String>> created = new ArrayList<>();

    @BeforeEach
    void createOwnCards() {
        created.clear();
        String firstCreatedAt = null;
        for (String status : List.of("ACTIVE", "ACTIVE", "BLOCKED")) {
            Response card = given().spec(Specs.gateway())
                    .body(CardRequestBuilder.aCard().bin(BIN).build())
                    .when().post(PATH);
            card.then().statusCode(201);
            String pan = card.path("pan");
            if (!"ACTIVE".equals(status)) {
                given().spec(Specs.gateway()).body(Map.of("status", status))
                        .when().patch(PATH + "/{pan}", pan).then().statusCode(200);
            }
            if (firstCreatedAt == null) {
                firstCreatedAt = card.path("createdAt");
            }
            created.add(Map.of("pan", pan, "status", status));
        }
        startDate = Instant.parse(firstCreatedAt).truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private Response list(Map<String, ?> params) {
        RequestSpecification request = given().spec(Specs.gateway())
                .queryParam("startDate", startDate)
                .queryParams(params);
        return request.when().get(PATH);
    }

    @Test
    @DisplayName("startDate scopes the list to the cards of this test: total 3, all of BIN 400003 / ISS004")
    void startDateFilterReturnsOwnCards() {
        Map<String, Object> params = Map.of();

        Response response = list(params);

        response.then()
                .statusCode(200)
                .body("total", equalTo(3))
                .body("cards", hasSize(3))
                .body("cards.findAll { it.bin != '" + BIN + "' }", empty())
                .body("cards.issuerId", everyItem(equalTo(ISSUER)))
                .body("cards.pan", everyItem(startsWith(BIN)));
    }

    @ParameterizedTest(name = "status={0} -> {1} cards")
    @CsvSource({"ACTIVE, 2", "BLOCKED, 1"})
    @DisplayName("status filter: only cards with that status (a status without cards is the empty selection class)")
    void statusFilter(String status, int expected) {
        Map<String, Object> params = Map.of("status", status);

        Response response = list(params);

        response.then()
                .statusCode(200)
                .body("total", equalTo(expected))
                .body("cards", hasSize(expected))
                .body("cards.findAll { it.status != '" + status + "' }", empty());
    }

    static Stream<Arguments> pairwise() {
        return Stream.of(
                Arguments.of(null, null, null),
                Arguments.of(null, ISSUER, 1),
                Arguments.of(null, "ISS001", 2),
                Arguments.of("ACTIVE", null, 1),
                Arguments.of("ACTIVE", ISSUER, 2),
                Arguments.of("ACTIVE", "ISS001", null),
                Arguments.of("BLOCKED", null, 2),
                Arguments.of("BLOCKED", ISSUER, null),
                Arguments.of("BLOCKED", "ISS001", 1));
    }

    @ParameterizedTest(name = "status={0}, issuerId={1}, limit={2}")
    @MethodSource("pairwise")
    @DisplayName("Pairwise combinations of filters and limit: total and page size follow the known card set")
    void pairwiseCombination(String status, String issuerId, Integer limit) {
        long matching = created.stream()
                .filter(card -> status == null || status.equals(card.get("status")))
                .filter(card -> issuerId == null || ISSUER.equals(issuerId))
                .count();
        int pageSize = (int) Math.min(matching, limit == null ? 10 : limit);
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            params.put("status", status);
        }
        if (issuerId != null) {
            params.put("issuerId", issuerId);
        }
        if (limit != null) {
            params.put("limit", limit);
        }

        Response response = list(params);

        response.then()
                .statusCode(200)
                .body("total", equalTo((int) matching))
                .body("cards", hasSize(pageSize));
    }

    @Test
    @DisplayName("Pagination: limit 2 + offset 0/2/3 -> pages of 2, 1, 0 cards, total stays 3, no overlap")
    void pagination() {
        Map<String, Object> firstPage = Map.of("limit", 2, "offset", 0);
        Map<String, Object> secondPage = Map.of("limit", 2, "offset", 2);
        Map<String, Object> afterEnd = Map.of("limit", 2, "offset", 3);

        Response first = list(firstPage);
        Response second = list(secondPage);
        Response beyond = list(afterEnd);

        first.then().statusCode(200).body("total", equalTo(3)).body("cards", hasSize(2));
        second.then().statusCode(200).body("total", equalTo(3)).body("cards", hasSize(1));
        beyond.then().statusCode(200).body("total", equalTo(3)).body("cards", empty());
        List<String> pans = new ArrayList<>(first.path("cards.pan"));
        pans.addAll(second.path("cards.pan"));
        assertThat(pans.stream().distinct().count(), equalTo(3L));
    }

    @Test
    @DisplayName("Boundary ON: limit 10000 (app.card-service.max-page-limit) is accepted; limit 1 is in the pairwise set")
    void maxLimitIsAccepted() {
        Map<String, Object> max = Map.of("limit", 10_000);

        Response response = list(max);

        response.then().statusCode(200).body("total", equalTo(3)).body("cards", hasSize(3));
    }

    static Stream<Arguments> windowFilters() {
        Function<Instant, Map<String, Object>> bin = stored -> Map.of("bin", BIN);
        Function<Instant, Map<String, Object>> endOn = stored -> Map.of("endDate", stored.toString());
        Function<Instant, Map<String, Object>> endOff = stored -> Map.of("endDate", stored.minusNanos(1_000).toString());
        return Stream.of(
                Arguments.of("bin " + BIN, bin, 3),
                Arguments.of("endDate = stored createdAt of the 2nd card (ON)", endOn, 2),
                Arguments.of("endDate 1 microsecond earlier (OFF)", endOff, 1));
    }

    @ParameterizedTest(name = "{0} -> {2} cards")
    @MethodSource("windowFilters")
    @DisplayName("bin and endDate filters: endDate is inclusive (createdAt <= endDate), "
            + "createdAt is stored with microsecond precision")
    void binAndEndDateFilters(String description, Function<Instant, Map<String, Object>> query, int expected) {
        String secondPan = created.get(1).get("pan");
        Instant stored = Instant.parse(given().spec(Specs.cardManagement()).when().get(PATH + "/{pan}", secondPan)
                .then().statusCode(200).extract().path("createdAt"));

        Response response = list(query.apply(stored));

        response.then().statusCode(200).body("total", equalTo(expected)).body("cards", hasSize(expected));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Without limit a page has 10 cards (@RequestParam defaultValue \"10\"), "
            + "app.card-service.defaults.page-limit = 50 is never applied (D-32)")
    void defaultPageSizeIs10() {
        given().spec(Specs.gateway()).body(Map.of("count", 11, "bins", List.of(BIN)))
                .when().post(PATH + "/generate").then().statusCode(201);
        Map<String, Object> noLimit = Map.of();

        Response response = list(noLimit);

        response.then().statusCode(200).body("total", equalTo(14)).body("cards", hasSize(10));
    }

    static Stream<Arguments> emptySelections() {
        Function<String, Map<String, Object>> future = own ->
                Map.of("startDate", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        Function<String, Map<String, Object>> reversed = own ->
                Map.of("startDate", own, "endDate", Instant.parse(own).minus(1, ChronoUnit.DAYS).toString());
        Function<String, Map<String, Object>> contradicting = own ->
                Map.of("startDate", own, "bin", BIN, "issuerId", "ISS001");
        return Stream.of(
                Arguments.of("startDate after all cards", future),
                Arguments.of("startDate > endDate", reversed),
                Arguments.of("bin 400003 + issuerId ISS001", contradicting));
    }

    @ParameterizedTest(name = "{0} -> 200, total 0")
    @MethodSource("emptySelections")
    @DisplayName("Empty selection (no match, reversed range, contradicting filters) -> 200, total 0, empty array")
    void emptySelection(String description, Function<String, Map<String, Object>> query) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParams(query.apply(startDate));

        Response response = request.when().get(PATH);

        response.then().statusCode(200).body("total", equalTo(0)).body("cards", empty());
    }

    @ParameterizedTest(name = "{0}={1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "limit|0|400|getCards.limit: must be greater than 0",
        "limit|-1|400|getCards.limit: must be greater than 0",
        "offset|-1|400|getCards.offset: Value can not be negative",
        "bin|12|400|getCards.bin: BIN number must contain exactly 6 digits",
        "issuerId|iss004|400|getCards.issuerId: must match \"^[A-Z0-9]+$\"",
        "issuerId|ISS00000004|400|getCards.issuerId: size must be between 1 and 10",
        "limit|10001|413|Page limit (10001 received) can not be more than 10000"})
    @DisplayName("Invalid query parameter -> 400 (413 above max-page-limit), one invalid parameter per request")
    void invalidParameter(String param, String value, int status, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam(param, value);

        Response response = request.when().get(PATH);

        response.then()
                .statusCode(status)
                .body("message", equalTo(message))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Unknown status value -> 500 MethodArgumentTypeMismatchException instead of 400 (D-11)")
    void unknownStatusIs500() {
        RequestSpecification request = given().spec(Specs.cardManagement()).queryParam("status", "UNKNOWN");

        Response response = request.when().get(PATH);

        response.then().statusCode(500).body("error", equalTo("MethodArgumentTypeMismatchException"));
    }
}
