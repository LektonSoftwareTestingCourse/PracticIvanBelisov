package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCircuitBreakerTest {

    private static final String SERVICE = "switch";
    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);
    private static final int FAILURE_THRESHOLD = 3;

    private MutableClock clock;
    private InMemoryCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        circuitBreaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, FAILURE_THRESHOLD, clock);
    }

    @Test
    void shouldStayClosedWhenFailuresBelowThreshold() {
        // Arrange
        circuitBreaker.recordFailure(SERVICE);
        circuitBreaker.recordFailure(SERVICE);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldOpenAndRejectWhenFailuresReachThreshold() {
        // Arrange
        openCircuit();

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepRejectingWhenOpenDurationNotElapsed() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION.minusMillis(1));

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldAllowSingleProbeWhenOpenDurationElapsed() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldRejectSecondRequestWhenHalfOpenProbeInProgress() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION);
        circuitBreaker.allowRequest(SERVICE);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldCloseCircuitWhenHalfOpenProbeSucceeds() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION);
        circuitBreaker.allowRequest(SERVICE);
        circuitBreaker.recordSuccess(SERVICE);
        circuitBreaker.recordFailure(SERVICE);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldReopenImmediatelyWhenHalfOpenProbeFails() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION);
        circuitBreaker.allowRequest(SERVICE);
        circuitBreaker.recordFailure(SERVICE);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldFreeProbeSlotWhenHalfOpenRequestReleased() {
        // Arrange
        openCircuit();
        clock.advance(OPEN_DURATION);
        circuitBreaker.allowRequest(SERVICE);
        circuitBreaker.releaseRequest(SERVICE);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    private void openCircuit() {
        circuitBreaker.recordFailure(SERVICE);
        circuitBreaker.recordFailure(SERVICE);
        circuitBreaker.recordFailure(SERVICE);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
