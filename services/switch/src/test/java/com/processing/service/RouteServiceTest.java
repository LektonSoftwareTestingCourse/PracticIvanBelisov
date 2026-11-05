package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345000001";
    private static final String AUTH_CODE = "ABC123";
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal ACQUIRING_FEE = new BigDecimal("15.00");
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-06-01T10:30:00Z");

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldReturnAuthorizationResponseWhenBinKnownAndTransactionLogged() {
        // Arrange
        AuthorizationResponse approved = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(routedRequest())).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(TRANSMISSION_TIME, STAN, PAN, "TERM0001", AMOUNT))
                .thenReturn(ACQUIRING_FEE);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(validRequest());

        // Assert
        assertThat(response).isEqualTo(approved);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldPublishTransactionBuiltFromRequestAndResponseWhenApproved() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(ACQUIRING_FEE);
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        when(loggerClient.log(transaction.capture())).thenReturn(true);

        // Act
        routeService.route(validRequest());

        // Assert
        TransactionRequest logged = transaction.getValue();
        assertThat(logged.id()).isNotNull();
        assertThat(logged.mti()).isEqualTo("0100");
        assertThat(logged.stan()).isEqualTo(STAN);
        assertThat(logged.rrn()).isEqualTo(RRN);
        assertThat(logged.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(logged.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(logged.authCode()).isEqualTo(AUTH_CODE);
        assertThat(logged.acquiringFee()).isEqualByComparingTo(ACQUIRING_FEE);
        assertThat(logged.processingTimeMs()).isEqualTo(42);
        assertThat(logged.transmissionDateTime()).isEqualTo(TRANSMISSION_TIME);
    }

    @ParameterizedTest(name = "PAN=''{0}''")
    @NullSource
    @ValueSource(strings = {"4999991234567890", "4000"})
    void shouldDeclineWithCardNotFoundWhenBinIsUnknown(String pan) {
        // Arrange
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("499999"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(requestWithPan(pan));

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo("14");
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(authorizationClient, never()).authorize(any());
    }

    @Test
    void shouldDeclineWithDoNotHonorWhenAuthorizationServiceUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenThrow(new AuthorizationException(STAN, 3, "Read timed out"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(validRequest());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
    }

    @Test
    void shouldRollbackReservationAndReturnSystemErrorWhenApprovedTransactionNotLogged() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);

        // Act
        AuthorizationResponse response = routeService.route(validRequest());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.declineReason()).isEqualTo("System error");
        verify(authorizationClient).rollback(routedRequest(), RRN);
    }

    @ParameterizedTest(name = "rollback={0}")
    @MethodSource("rollbackOutcomes")
    void shouldReturnSystemErrorWhateverRollbackOutcomeWhenApprovedTransactionNotLogged(RollbackResponse rollback) {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(routedRequest(), RRN)).thenReturn(rollback);

        // Act
        AuthorizationResponse response = routeService.route(validRequest());

        // Assert
        assertThat(response.responseCode()).isEqualTo("96");
    }

    @Test
    void shouldReturnDeclineWithoutRollbackWhenDeclinedTransactionNotLogged() {
        // Arrange
        AuthorizationResponse declined = declinedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declined);
        when(loggerClient.log(any())).thenReturn(false);

        // Act
        AuthorizationResponse response = routeService.route(validRequest());

        // Assert
        assertThat(response).isEqualTo(declined);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    private static Stream<Arguments> rollbackOutcomes() {
        return Stream.of(
                Arguments.of((RollbackResponse) null),
                Arguments.of(new RollbackResponse(RRN, "00", RollbackResponse.STATUS_APPROVED, null, 10)),
                Arguments.of(new RollbackResponse(RRN, "14", RollbackResponse.STATUS_DECLINED,
                        "TRANSACTION_NOT_FOUND", 10)));
    }

    private static AuthorizationRequest validRequest() {
        return requestWithPan(PAN);
    }

    private static AuthorizationRequest routedRequest() {
        return validRequest().withIssuerId(ISSUER_ID);
    }

    private static AuthorizationRequest requestWithPan(String pan) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000", AMOUNT, "643", TRANSMISSION_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5411", "ACQ001", null);
    }

    private static AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse(
                "0110", STAN, RRN, AUTH_CODE, "00", AuthorizationResponse.STATUS_APPROVED, null, 42);
    }

    private static AuthorizationResponse declinedResponse() {
        return new AuthorizationResponse(
                "0110", STAN, null, null, "51", AuthorizationResponse.STATUS_DECLINED, "INSUFFICIENT_FUNDS", 15);
    }
}
