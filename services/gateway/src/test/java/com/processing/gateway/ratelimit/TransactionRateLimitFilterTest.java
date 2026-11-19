package com.processing.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRateLimitFilterTest {

    private static final String CLIENT_KEY = "POST /api/transactions:10.0.0.1";

    @Mock
    private InMemoryRateLimiter rateLimiter;

    private SimpleMeterRegistry meterRegistry;
    private RecordingChain chain;
    private TransactionRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        chain = new RecordingChain();
        filter = new TransactionRateLimitFilter(
                rateLimiter, new ClientIpResolver(), new ObjectMapper(), new GatewayMetrics(meterRegistry));
    }

    @Test
    void shouldPassTransactionToChainWhenWithinLimit() {
        // Arrange
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/transactions");
        when(rateLimiter.allowRequest(CLIENT_KEY)).thenReturn(true);

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        assertThat(chain.passedExchange()).isSameAs(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldRejectWithTooManyRequestsWhenLimitExceeded() {
        // Arrange
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/transactions");
        when(rateLimiter.allowRequest(CLIENT_KEY)).thenReturn(false);

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"error\":\"RATE_LIMIT_EXCEEDED\"")
                .contains("\"retryAfterMs\":1000");
        assertThat(chain.passedExchange()).isNull();
        assertThat(meterRegistry.get("gateway.requests.rejected").tag("reason", "rate_limit").counter().count())
                .isEqualTo(1.0);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "GET,  /api/transactions",
        "POST, /api/cards",
        "POST, /api/transactions/123"
    })
    void shouldNotConsultLimiterWhenRequestIsNotTransactionPost(HttpMethod method, String path) {
        // Arrange
        MockServerWebExchange exchange = exchange(method, path);

        // Act
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Assert
        assertThat(chain.passedExchange()).isSameAs(exchange);
        verifyNoInteractions(rateLimiter);
    }

    private static MockServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path)
                .remoteAddress(new InetSocketAddress("10.0.0.1", 50_000)));
    }

    private static final class RecordingChain implements GatewayFilterChain {

        private final AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(exchange);
            return Mono.empty();
        }

        ServerWebExchange passedExchange() {
            return passed.get();
        }
    }
}
