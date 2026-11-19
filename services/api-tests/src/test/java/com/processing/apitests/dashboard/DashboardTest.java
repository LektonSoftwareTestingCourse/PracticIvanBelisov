package com.processing.apitests.dashboard;

import com.processing.apitests.support.CardFactory;
import com.processing.apitests.support.CardFactory.CreatedCard;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TransactionSteps;
import com.processing.apitests.support.Waits;
import com.processing.apitests.support.builders.AuthorizationRequestBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

@DisplayName("/api/dashboard: stats, recent, charts (api-spec section 5; DashboardController, TransactionService)")
class DashboardTest {

    private static JsonPath stats() {
        return given().spec(Specs.gateway()).when().get("/api/dashboard/stats")
                .then().statusCode(200).extract().jsonPath();
    }

    @Test
    @DisplayName("stats 200: schema, integer transactionsPerMinute, approved + declined = total, rate, average")
    void statsAreConsistent() {
        RequestSpecification request = given().spec(Specs.gateway());

        Response response = request.when().get("/api/dashboard/stats");

        response.then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/dashboard-stats.json"))
                .body("transactionsPerMinute", instanceOf(Integer.class));
        JsonPath body = response.jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
        long total = body.getLong("totalTransactions");
        long approved = body.getLong("approvedCount");
        assertThat(approved + body.getLong("declinedCount"), equalTo(total));
        assertThat(body.getDouble("approvalRate"), closeTo(total == 0 ? 0 : (double) approved / total, 1e-9));
        BigDecimal totalAmount = new BigDecimal(body.getString("totalAmount"));
        BigDecimal expectedAverage = total == 0 ? BigDecimal.ZERO
                : totalAmount.divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP);
        assertThat(new BigDecimal(body.getString("averageAmount")), comparesEqualTo(expectedAverage));
    }

    @Test
    @DisplayName("stats grow with a new transaction: totalTransactions and approvedCount +1 (async, awaited)")
    void statsGrowAfterTransaction() {
        CreatedCard card = CardFactory.aCard().unlimited().create();
        JsonPath before = stats();

        TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()));

        await().atMost(Waits.EFFECT_TIMEOUT).pollInterval(Waits.POLL_INTERVAL).untilAsserted(() -> {
            JsonPath after = stats();
            assertThat(after.getLong("totalTransactions"), equalTo(before.getLong("totalTransactions") + 1));
            assertThat(after.getLong("approvedCount"), equalTo(before.getLong("approvedCount") + 1));
        });
    }

    @ParameterizedTest(name = "limit={0} -> at most {1} items, createdAt DESC")
    @CsvSource({", 20", "1, 1", "500, 500"})
    @DisplayName("recent: default and boundary limits, page sorted by createdAt descending")
    void recentRespectsLimitAndOrder(Integer limit, int maxSize) {
        CreatedCard card = CardFactory.aCard().unlimited().create();
        TransactionSteps.approve(AuthorizationRequestBuilder.forPan(card.pan()));
        RequestSpecification request = given().spec(Specs.gateway());
        if (limit != null) {
            request.queryParam("limit", limit);
        }

        Response response = request.when().get("/api/dashboard/recent");

        response.then().statusCode(200).body("size()", lessThanOrEqualTo(maxSize)).body("size()", greaterThan(0));
        List<Instant> createdAt = response.jsonPath().getList("createdAt", String.class).stream()
                .map(Instant::parse).toList();
        List<Instant> sorted = new ArrayList<>(createdAt);
        sorted.sort(Comparator.reverseOrder());
        assertThat(createdAt, equalTo(sorted));
    }

    @ParameterizedTest(name = "limit={0} -> 400 \"{1}\"")
    @CsvSource(delimiter = '|', value = {
        "0|getRecent.limit: limit must be positive",
        "-5|getRecent.limit: limit must be positive",
        "501|getRecent.limit: limit must not exceed 500"})
    @DisplayName("recent: invalid limit -> 400 naming the rule")
    void recentInvalidLimit(int limit, String message) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam("limit", limit);

        Response response = request.when().get("/api/dashboard/recent");

        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest(name = "granularity={0}")
    @ValueSource(strings = {"hour", "day"})
    @DisplayName("charts: buckets are consistent (total = approved + declined) and ordered by time")
    void chartsAreConsistent(String granularity) {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam("granularity", granularity);

        Response response = request.when().get("/api/dashboard/charts");

        response.then()
                .statusCode(200)
                .body("findAll { it.total != it.approved + it.declined }", empty());
        List<Instant> buckets = response.jsonPath().getList("timestamp", String.class).stream()
                .map(Instant::parse).toList();
        List<Instant> sorted = new ArrayList<>(buckets);
        sorted.sort(Comparator.naturalOrder());
        assertThat(buckets, equalTo(sorted));
    }

    @Test
    @DisplayName("charts: a window in the future gives an empty array")
    void chartsEmptyWindow() {
        Instant from = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        RequestSpecification request = given().spec(Specs.gateway())
                .queryParam("from", from.toString())
                .queryParam("to", from.plus(1, ChronoUnit.DAYS).toString());

        Response response = request.when().get("/api/dashboard/charts");

        response.then().statusCode(200).body("$", empty());
    }

    @Test
    @DisplayName("charts: unknown granularity -> 400 \"granularity: granularity must be hour or day\"")
    void chartsUnknownGranularity() {
        RequestSpecification request = given().spec(Specs.gateway()).queryParam("granularity", "week");

        Response response = request.when().get("/api/dashboard/charts");

        response.then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", equalTo("granularity: granularity must be hour or day"));
    }

    @Test
    @Tag("known-defect")
    @DisplayName("charts: from > to -> 400 with an EMPTY message (same class-level constraint issue, D-08)")
    void chartsReversedRangeHasEmptyMessage() {
        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        RequestSpecification request = given().spec(Specs.gateway())
                .queryParam("from", to.plus(1, ChronoUnit.DAYS).toString())
                .queryParam("to", to.toString());

        Response response = request.when().get("/api/dashboard/charts");

        response.then().statusCode(400).body("error", equalTo("Validation error")).body("message", equalTo(""));
    }
}
