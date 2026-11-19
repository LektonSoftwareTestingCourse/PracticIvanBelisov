package com.processing.apitests.gateway;

import com.processing.apitests.support.ApiConfig;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("Gateway rate limit for POST /api/transactions (TransactionRateLimitFilter; capacity 100, refill 100/s)")
class GatewayRateLimitTest {

    private static final int BURST = 400;
    private static final int THREADS = 32;
    private static final int CAPACITY = 100;

    private static List<HttpResponse<String>> burst(String clientIp) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ApiConfig.GATEWAY + "/api/transactions"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", clientIp)
                .POST(HttpRequest.BodyPublishers.ofString("{"))
                .build();
        ExecutorService workers = Executors.newFixedThreadPool(THREADS);
        try {
            List<CompletableFuture<HttpResponse<String>>> futures = IntStream.range(0, BURST)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> send(client, request), workers))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            workers.shutdown();
        }
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Burst above capacity: at least 100 pass, the rest get 429 with Retry-After: 1 and a JSON body")
    void burstAboveCapacityIsLimited() {
        String clientIp = TestData.clientIp();

        List<HttpResponse<String>> responses = burst(clientIp);

        long passed = responses.stream().filter(r -> r.statusCode() == 400).count();
        List<HttpResponse<String>> limited = responses.stream().filter(r -> r.statusCode() == 429).toList();
        assertThat("requests within the bucket capacity are not limited", passed,
                greaterThanOrEqualTo((long) CAPACITY));
        assertThat("requests above the capacity are limited", limited.size(), greaterThan(0));
        assertThat(passed + limited.size(), equalTo((long) BURST));
        HttpResponse<String> rejected = limited.get(0);
        assertThat(rejected.headers().firstValue("Retry-After").orElse(null), equalTo("1"));
        assertThat(rejected.headers().firstValue("Content-Type").orElse(""), startsWith("application/json"));
        JsonPath body = new JsonPath(rejected.body());
        assertThat(body.getString("error"), equalTo("RATE_LIMIT_EXCEEDED"));
        assertThat(body.getString("message"), equalTo("Too many requests. Try again later."));
        assertThat(body.getInt("retryAfterMs"), equalTo(1000));
    }

    @Test
    @DisplayName("Buckets are per client: another IP is not limited while the first one is, the first recovers")
    void bucketIsPerClientAndRecovers() {
        String exhausted = TestData.clientIp();
        String other = TestData.clientIp();
        long limited = burst(exhausted).stream().filter(r -> r.statusCode() == 429).count();
        assertThat("precondition: the first client is limited", limited, greaterThan(0L));
        RequestSpecification otherClient = given().spec(Specs.gateway())
                .header("X-Forwarded-For", other).body("{");

        Response response = otherClient.when().post("/api/transactions");

        response.then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                given().spec(Specs.gateway()).header("X-Forwarded-For", exhausted).body("{")
                        .when().post("/api/transactions")
                        .then().statusCode(400));
    }
}
