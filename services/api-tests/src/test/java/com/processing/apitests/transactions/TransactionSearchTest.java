package com.processing.apitests.transactions;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.Waits;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("GET /api/transactions/search and /export (api-spec section 3; TransactionFilter, TransactionSpecification)")
class TransactionSearchTest {

    private static final String PATH = "/api/transactions/search";

    private final String merchantId = TestData.merchantId();
    private final LocalDate dayOne = LocalDate.now(ZoneOffset.UTC).minusDays(2);
    private final LocalDate dayTwo = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    private final List<Tx> fixture = new ArrayList<>();
    private String panOne;
    private String panTwo;

    private record Tx(String pan, String issuerId, String status, String reason, String mcc, LocalDate date) {
    }

    @BeforeAll
    void createFixture() {
        CreatedCard active = CardFactory.aCard().bin(TestData.BIN_ISS001).balance(100_000).unlimited().create();
        CreatedCard blocked = CardFactory.aCard().bin(TestData.BIN_ISS002).unlimited().status("BLOCKED").create();
        panOne = active.pan();
        panTwo = blocked.pan();
        send(new Tx(panOne, "ISS001", "APPROVED", null, "5411", dayOne), 1_000);
        send(new Tx(panOne, "ISS001", "APPROVED", null, "5812", dayTwo), 2_000);
        send(new Tx(panTwo, "ISS002", "DECLINED", "CARD_BLOCKED", "5411", dayTwo), 1_000);
        send(new Tx(panTwo, "ISS002", "DECLINED", "CARD_BLOCKED", "5812", dayOne), 1_000);
        send(new Tx(panOne, "ISS001", "DECLINED", "INSUFFICIENT_FUNDS", "5411", dayOne), 5_000_000);
        await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL).untilAsserted(() ->
                search(Map.of()).then().body("total", equalTo(fixture.size())));
    }

    private void send(Tx tx, long amount) {
        String status = TransactionSteps.send(AuthorizationRequestBuilder.forPan(tx.pan())
                .merchantId(merchantId)
                .mcc(tx.mcc())
                .amount(amount)
                .transmissionDateTime(tx.date().atTime(12, 0).toInstant(ZoneOffset.UTC))).getString("status");
        assertThat("fixture transaction outcome", status, equalTo(tx.status()));
        fixture.add(tx);
    }

    private Response search(Map<String, ?> params) {
        RequestSpecification request = given().spec(Specs.gateway())
                .queryParam("merchantId", merchantId)
                .queryParams(params);
        return request.when().get(PATH);
    }

    @Test
    @DisplayName("merchantId filter: all five, schema of every item, nothing of another merchant")
    void merchantFilterReturnsOwnTransactions() {
        Map<String, Object> params = Map.of();

        Response response = search(params);

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("total", equalTo(5))
                .body("transactions", hasSize(5))
                .body("transactions.findAll { it.merchantId != '" + merchantId + "' }", empty());
    }

    Stream<Arguments> singleFilters() {
        return Stream.of(
                Arguments.of("pan", panOne),
                Arguments.of("status", "APPROVED"),
                Arguments.of("status", "DECLINED"),
                Arguments.of("issuerId", "ISS002"),
                Arguments.of("mcc", "5812"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("singleFilters")
    @DisplayName("Single filter: total equals the oracle, every returned item has the filtered value")
    void singleFilter(String param, String value) {
        long expected = fixture.stream().filter(tx -> value.equals(attribute(tx, param))).count();
        Map<String, Object> params = Map.of(param, value);

        Response response = search(params);

        response.then()
                .statusCode(200)
                .body("total", equalTo((int) expected))
                .body("transactions", hasSize((int) expected))
                .body("transactions." + param, everyItem(equalTo(value)));
    }

    private static String attribute(Tx tx, String param) {
        return switch (param) {
            case "pan" -> tx.pan();
            case "status" -> tx.status();
            case "issuerId" -> tx.issuerId();
            case "mcc" -> tx.mcc();
            default -> throw new IllegalArgumentException(param);
        };
    }

    static Stream<Arguments> dateRanges() {
        LocalDate one = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        LocalDate two = one.plusDays(1);
        return Stream.of(
                Arguments.of(one.toString(), one.toString(), 3),
                Arguments.of(two.toString(), two.toString(), 2),
                Arguments.of(one.toString(), two.toString(), 5),
                Arguments.of(two.toString(), null, 2),
                Arguments.of(null, one.toString(), 3),
                Arguments.of(one.minusDays(1).toString(), one.minusDays(1).toString(), 0),
                Arguments.of(two.plusDays(1).toString(), null, 0));
    }

    @ParameterizedTest(name = "dateFrom={0}, dateTo={1} -> {2}")
    @MethodSource("dateRanges")
    @DisplayName("dateFrom/dateTo by transmission date: inclusive day boundaries, empty outside")
    void dateRangeFilter(String from, String to, int expected) {
        Map<String, Object> params = new HashMap<>();
        if (from != null) {
            params.put("dateFrom", from);
        }
        if (to != null) {
            params.put("dateTo", to);
        }

        Response response = search(params);

        response.then().statusCode(200).body("total", equalTo(expected)).body("transactions", hasSize(expected));
    }

    @ParameterizedTest(name = "declineReason \"{0}\" -> {1}")
    @CsvSource({"insufficient, 1", "INSUFFICIENT_FUNDS, 1", "card blocked, 2", "blocked, 2", "funds, 1", "expired, 0"})
    @DisplayName("declineReason is a full-text search: words, any case, '_' treated as a space (V2 migration)")
    void declineReasonFullText(String query, int expected) {
        Map<String, Object> params = Map.of("declineReason", query);

        Response response = search(params);

        response.then()
                .statusCode(200)
                .body("total", equalTo(expected))
                .body("transactions.status", everyItem(equalTo("DECLINED")));
    }

    static Stream<Arguments> pairwise() {
        int[][] rows = {{0, 0, 0, 0}, {0, 1, 1, 1}, {0, 2, 2, 2}, {1, 0, 1, 2}, {1, 1, 2, 0}, {1, 2, 0, 1},
            {2, 0, 2, 1}, {2, 1, 0, 2}, {2, 2, 1, 0}};
        return Stream.of(rows).map(row -> Arguments.of(row[0], row[1], row[2], row[3]));
    }

    @ParameterizedTest(name = "status#{0} mcc#{1} date#{2} pan#{3}")
    @MethodSource("pairwise")
    @DisplayName("Pairwise combinations of status, mcc, date and pan: total equals the oracle over the fixture")
    void pairwiseCombination(int statusLevel, int mccLevel, int dateLevel, int panLevel) {
        String status = new String[] {null, "APPROVED", "DECLINED"}[statusLevel];
        String mcc = new String[] {null, "5411", "5812"}[mccLevel];
        LocalDate date = new LocalDate[] {null, dayOne, dayTwo}[dateLevel];
        String pan = new String[] {null, panOne, panTwo}[panLevel];
        long expected = fixture.stream()
                .filter(tx -> status == null || status.equals(tx.status()))
                .filter(tx -> mcc == null || mcc.equals(tx.mcc()))
                .filter(tx -> date == null || date.equals(tx.date()))
                .filter(tx -> pan == null || pan.equals(tx.pan()))
                .count();
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            params.put("status", status);
        }
        if (mcc != null) {
            params.put("mcc", mcc);
        }
        if (date != null) {
            params.put("dateFrom", date.toString());
            params.put("dateTo", date.toString());
        }
        if (pan != null) {
            params.put("pan", pan);
        }

        Response response = search(params);

        response.then().statusCode(200)
                .body("total", equalTo((int) expected))
                .body("transactions", hasSize((int) expected));
    }

    @Test
    @DisplayName("Pagination: limit 2, offset 0/2/4 -> 2, 2, 1 items, total 5 on every page, pages do not overlap, "
            + "createdAt DESC across pages (OffsetBasedPageRequest, D-31)")
    void pagination() {
        Map<String, Object> first = Map.of("limit", 2, "offset", 0);
        Map<String, Object> second = Map.of("limit", 2, "offset", 2);
        Map<String, Object> third = Map.of("limit", 2, "offset", 4);

        Response page1 = search(first);
        Response page2 = search(second);
        Response page3 = search(third);

        page1.then().statusCode(200).body("total", equalTo(5)).body("transactions", hasSize(2));
        page2.then().statusCode(200).body("total", equalTo(5)).body("transactions", hasSize(2));
        page3.then().statusCode(200).body("total", equalTo(5)).body("transactions", hasSize(1));
        List<String> ids = new ArrayList<>(page1.path("transactions.id"));
        ids.addAll(page2.path("transactions.id"));
        ids.addAll(page3.path("transactions.id"));
        assertThat(ids.stream().distinct().count(), equalTo(5L));
        List<Instant> createdAt = Stream.of(page1, page2, page3)
                .flatMap(page -> page.jsonPath().getList("transactions.createdAt", String.class).stream())
                .map(Instant::parse)
                .toList();
        List<Instant> descending = new ArrayList<>(createdAt);
        descending.sort(Comparator.reverseOrder());
        assertThat(createdAt, equalTo(descending));
    }

    @ParameterizedTest(name = "limit={0}, offset={1} -> {2} items")
    @CsvSource({"1, 0, 1", "500, 0, 5"})
    @DisplayName("Valid limit boundaries 1 and 500 (offsets at the end are covered by the pagination test)")
    void validPaginationBoundaries(int limit, int offset, int expected) {
        Map<String, Object> params = Map.of("limit", limit, "offset", offset);

        Response response = search(params);

        response.then().statusCode(200).body("total", equalTo(5)).body("transactions", hasSize(expected));
    }

    @Test
    @DisplayName("Default limit is 50 (api-spec): without limit a page never exceeds 50")
    void defaultLimitIs50() {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get(PATH);

        int total = response.then().statusCode(200).extract().path("total");
        response.then().body("transactions", hasSize(Math.min(total, 50)))
                .body("transactions.size()", lessThanOrEqualTo(50));
    }

    @Test
    @DisplayName("Empty selection: unknown merchant -> 200, total 0, empty array")
    void emptySelection() {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam("merchantId", TestData.merchantId());

        Response response = request.when().get(PATH);

        response.then().statusCode(200).body("total", equalTo(0)).body("transactions", empty());
    }

    @ParameterizedTest(name = "{0}={1} -> 400 \"{2}\"")
    @CsvSource(delimiter = '|', value = {
        "limit|0|limit: must be greater than 0",
        "limit|501|limit: limit must not exceed 500",
        "offset|-1|offset: must be greater than or equal to 0"})
    @DisplayName("Invalid pagination value -> 400 naming the parameter and the rule")
    void invalidPagination(String param, String value, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam(param, value);

        Response response = request.when().get(PATH);

        response.then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Validation error"))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest(name = "{0}={1} -> 400 conversion error of {0}")
    @CsvSource({"status, FOO", "status, approved", "dateFrom, 2026-13-01", "dateTo, 19.11.2026", "limit, abc"})
    @DisplayName("Value of a wrong type -> 400 naming the parameter (enum is case-sensitive)")
    void wrongTypeIsRejected(String param, String value) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam(param, value);

        Response response = request.when().get(PATH);

        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", startsWith(param + ": Failed to convert"))
                .body("message", containsString(value));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("Incorrect combination dateFrom > dateTo -> 400 with an EMPTY message: the rule is not named (D-08)")
    void reversedDateRangeHasEmptyMessage() {
        RequestSpecification request = given().spec(Specs.gateway())
                .queryParam("dateFrom", dayTwo.toString())
                .queryParam("dateTo", dayOne.toString());

        Response response = request.when().get(PATH);

        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo(""));
    }

    @Test
    @DisplayName("GET /api/transactions/export: text/csv attachment with the same filter, limit is not applied")
    void exportUsesTheSameFilter() {
        RequestSpecification request = given().spec(Specs.gateway())
                .accept("text/csv")
                .queryParam("merchantId", merchantId)
                .queryParam("limit", 1);

        Response response = request.when().get("/api/transactions/export");

        response.then()
                .statusCode(200)
                .contentType("text/csv")
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""));
        String[] lines = response.asString().strip().split("\\R");
        assertThat(lines[0], startsWith("id,mti,stan,rrn,pan,"));
        assertThat("header + all 5 rows despite limit=1", lines.length, equalTo(6));
    }

    @ParameterizedTest(name = "{0}={1} -> 400 \"{2}...\"")
    @CsvSource(delimiter = '|', value = {
        "status|FOO|status: Failed to convert",
        "limit|0|limit: must be greater than 0"})
    @DisplayName("GET /api/transactions/export 400: the filter is validated like search, "
            + "even limit that export does not apply (D-35)")
    void exportRejectsInvalidFilter(String param, String value, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam(param, value);

        Response response = request.when().get("/api/transactions/export");

        response.then()
                .statusCode(400)
                .contentType("application/json")
                .body("error", equalTo("Validation error"))
                .body("message", startsWith(message))
                .body("serviceName", equalTo("transaction-logger"));
    }
}
