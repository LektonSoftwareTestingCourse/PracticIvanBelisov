package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private static final String KEY = "POST /api/transactions:10.0.0.1";
    private static final String OTHER_KEY = "POST /api/transactions:10.0.0.2";
    private static final Duration EXPIRE_AFTER = Duration.ofMinutes(10);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @Test
    void shouldRejectRequestWhenCapacityExhausted() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(2, 1);
        limiter.allowRequest(KEY);
        limiter.allowRequest(KEY);

        // Act
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldAllowRequestAgainWhenTokenRefilledAfterOneSecond() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1);
        limiter.allowRequest(KEY);
        advance(Duration.ofSeconds(1));

        // Act
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldStillRejectWhenOnlyPartOfTokenRefilled() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1);
        limiter.allowRequest(KEY);
        advance(Duration.ofMillis(500));

        // Act
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldNotAccumulateTokensAboveCapacityWhenIdleForLong() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 100);
        advance(Duration.ofSeconds(10));
        limiter.allowRequest(KEY);

        // Act
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepSeparateBucketsWhenKeysDiffer() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 0);
        limiter.allowRequest(KEY);

        // Act
        boolean allowed = limiter.allowRequest(OTHER_KEY);

        // Assert
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest(name = "capacity={0}")
    @ValueSource(ints = {0, -5})
    void shouldRejectEveryRequestWhenCapacityNotPositive(int capacity) {
        // Arrange
        InMemoryRateLimiter limiter = limiter(capacity, 100);

        // Act
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    private InMemoryRateLimiter limiter(int capacity, double refillPerSecond) {
        return InMemoryRateLimiter.forTesting(capacity, refillPerSecond, EXPIRE_AFTER, 100, nanos::get);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
