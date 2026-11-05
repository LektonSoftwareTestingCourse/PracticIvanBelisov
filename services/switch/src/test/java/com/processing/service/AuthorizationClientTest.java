package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthorizationClientTest {

    private static final String AUTH_URL = "http://authorization:8083";
    private static final String AUTHORIZE_URL = AUTH_URL + "/api/internal/authorize";
    private static final String ROLLBACK_URL = AUTH_URL + "/api/internal/rollback";
    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String RRN = "612345000001";
    private static final int MAX_ATTEMPTS = 3;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private AuthorizationClient authorizationClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SwitchProperties properties = properties();
        circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties);
        authorizationClient = new AuthorizationClient(
                properties,
                builder.build(),
                RetryFactory.authorizationRetry(properties),
                circuitBreaker);
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {AuthorizationResponse.STATUS_APPROVED, AuthorizationResponse.STATUS_DECLINED})
    void shouldReturnResponseWhenAuthorizationAnswersWithFinalStatus(String status) {
        // Arrange
        server.expect(requestTo(AUTHORIZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(authorizationJson(status), MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.stan()).isEqualTo(STAN);
    }

    @Test
    void shouldReturnDeclinedResponseWhenAuthorizationAnswersForbiddenWithBody() {
        // Arrange
        server.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(authorizationJson(AuthorizationResponse.STATUS_DECLINED)));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAuthorizationAnswers")
    void shouldRetryAndThrowWhenAuthorizationAnswerIsInvalid(String scenario, ResponseCreator answer) {
        // Arrange
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTHORIZE_URL)).andRespond(answer);

        // Act
        Throwable thrown = catchThrowable(() -> authorizationClient.authorize(request()));

        // Assert
        assertThat(thrown)
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("STAN=" + STAN)
                .hasMessageContaining("after 3 attempts")
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldReportNetworkErrorCauseWhenAuthorizationUnreachable() {
        // Arrange
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTHORIZE_URL))
                .andRespond(withException(new IOException("Connection refused")));

        // Act
        Throwable thrown = catchThrowable(() -> authorizationClient.authorize(request()));

        // Assert
        assertThat(thrown)
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Last error: Connection refused");
        server.verify();
    }

    @Test
    void shouldFailFastWithoutHttpCallWhenCircuitBreakerIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act
        Throwable thrown = catchThrowable(() -> authorizationClient.authorize(request()));

        // Assert
        assertThat(thrown)
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        server.verify();
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {RollbackResponse.STATUS_APPROVED, RollbackResponse.STATUS_DECLINED})
    void shouldReturnRollbackResponseWhenAuthorizationAnswersWithFinalStatus(String status) {
        // Arrange
        server.expect(requestTo(ROLLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"rrn\":\"" + RRN + "\",\"pan\":\"" + PAN + "\",\"amount\":1000.00}"))
                .andRespond(withSuccess(rollbackJson(status), MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.rrn()).isEqualTo(RRN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRollbackAnswers")
    void shouldReturnNullWhenRollbackFails(String scenario, ResponseCreator answer) {
        // Arrange
        server.expect(requestTo(ROLLBACK_URL)).andRespond(answer);

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response).isNull();
    }

    private static Stream<Arguments> invalidAuthorizationAnswers() {
        return Stream.of(
                Arguments.of("unknown status", withSuccess(authorizationJson("PENDING"), MediaType.APPLICATION_JSON)),
                Arguments.of("empty body", withSuccess()),
                Arguments.of("server error without body", withServerError()));
    }

    private static Stream<Arguments> invalidRollbackAnswers() {
        return Stream.of(
                Arguments.of("unknown status", withSuccess(rollbackJson("PENDING"), MediaType.APPLICATION_JSON)),
                Arguments.of("empty body", withSuccess()),
                Arguments.of("server error without body", withServerError()),
                Arguments.of("network error", withException(new IOException("Connection refused"))));
    }

    private static String authorizationJson(String status) {
        return "{\"mti\":\"0110\",\"stan\":\"" + STAN + "\",\"rrn\":\"" + RRN + "\",\"authCode\":\"ABC123\","
                + "\"responseCode\":\"00\",\"status\":\"" + status + "\",\"processingTimeMs\":12}";
    }

    private static String rollbackJson(String status) {
        return "{\"rrn\":\"" + RRN + "\",\"responseCode\":\"00\",\"status\":\"" + status + "\",\"processingTimeMs\":5}";
    }

    private static AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000", new BigDecimal("1000.00"), "643",
                Instant.parse("2026-06-01T10:30:00Z"), "TERM0001", "POS", "MERCH0000000002",
                "5411", "ACQ001", "ISS001");
    }

    private static SwitchProperties properties() {
        return new SwitchProperties(
                "1.0.0",
                Map.of(),
                AUTH_URL,
                null,
                null,
                new SwitchProperties.HttpProperties(100, 100, 100),
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of()),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000)));
    }
}
