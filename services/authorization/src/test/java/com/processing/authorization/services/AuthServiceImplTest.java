package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclineNoCardEvent;
import com.processing.authorization.events.AuthServiceAuthDeclineNoServiceEvent;
import com.processing.authorization.events.AuthServiceAuthDeclineUnknownEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardExpiryDateEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardStatusEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedFundsEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedLimitsEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceDuplicateKeyEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineConflictEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineNoCardEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineNoServiceEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineUnknownEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345000001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal BALANCE = new BigDecimal("100000.00");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("150000.00");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("3000000.00");
    private static final YearMonth VALID_EXPIRY = YearMonth.of(2027, 12);
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-06-01T10:30:00Z");
    private static final Instant REQUEST_TIME = Instant.now();

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    @Nested
    class ApprovedAuthorization {

        @Test
        void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
            assertThat(response.responseCode()).isEqualTo("00");
            assertThat(response.declineReason()).isNull();
            assertThat(response.rrn()).matches("\\d{12}");
            assertThat(response.authCode()).matches("[0-9A-Z]{6}");
            verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
        }

        @Test
        void shouldReserveRequestedAmountUnderGeneratedRrnWhenApproved() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        }

        @ParameterizedTest(name = "bin-lookup -> {0}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#binLookupResults")
        void shouldApproveWhenBinLookupReturnsIssuerOrNothing(Optional<String> lookupResult) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(lookupResult);
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }
    }

    @Nested
    class CardLookup {

        @Test
        void shouldDeclineWhenCardNotFound() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
            verify(eventNotifier).notify(new AuthServiceAuthDeclineNoCardEvent(PAN));
        }

        @ParameterizedTest(name = "{0} -> {1}/{2}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#getCardFailures")
        void shouldDeclineWithMappedReasonWhenCardManagementFails(
                RuntimeException failure, String expectedReason, String expectedCode,
                Class<? extends AuthorizationEvent> expectedEvent) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(failure);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            assertThat(response.rrn()).isNull();
            verify(eventNotifier).notify(isA(expectedEvent));
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }
    }

    @Nested
    class CardStatusCheck {

        @ParameterizedTest(name = "status={0}")
        @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
        void shouldDeclineWhenCardStatusIsNotActive(CardModelStatus status) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            verify(eventNotifier).notify(new AuthServiceAuthDeclinedCardStatusEvent(PAN, status.name()));
            verifyNoInteractions(limitUsageRepository);
        }

        @ParameterizedTest(name = "status={0} -> {1}/{2}")
        @CsvSource({
            "INACTIVE, CARD_INACTIVE, 05",
            "BLOCKED,  CARD_BLOCKED,  05",
            "EXPIRED,  CARD_EXPIRED,  54"
        })
        void shouldReturnStatusSpecificReasonAndCodeWhenCardIsNotActive(
                CardModelStatus status, String expectedReason, String expectedCode) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
        }

        @Test
        void shouldDeclineWithUnknownReasonWhenCardStatusIsMissing() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(null));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
            assertThat(response.responseCode()).isEqualTo("05");
            verify(eventNotifier).notify(isA(AuthServiceAuthDeclineUnknownEvent.class));
        }
    }

    @Nested
    class ExpiryDateCheck {

        @ParameterizedTest(name = "expiry={0}, transmitted={1}")
        @CsvSource({
            "2026-06, 2026-06-30T23:59:59Z",
            "2026-06, 2026-06-01T00:00:00Z",
            "2026-07, 2026-06-15T10:00:00Z"
        })
        void shouldApproveWhenTransactionDateIsNotAfterExpiryMonth(YearMonth expiry, Instant transmittedAt) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, expiry, BALANCE));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(requestAt(transmittedAt), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }

        @ParameterizedTest(name = "expiry={0}, transmitted={1}")
        @CsvSource({
            "2026-06, 2026-07-01T00:00:00Z",
            "2026-05, 2026-06-15T10:00:00Z"
        })
        void shouldDeclineAsExpiredWhenTransactionDateIsAfterExpiryMonth(YearMonth expiry, Instant transmittedAt) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, expiry, BALANCE));

            // Act
            AuthorizationResponse response = authService.authorize(requestAt(transmittedAt), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
            assertThat(response.responseCode()).isEqualTo("54");
            verify(eventNotifier).notify(
                    new AuthServiceAuthDeclinedCardExpiryDateEvent(PAN, expiry.atEndOfMonth().toString()));
            verifyNoInteractions(limitUsageRepository);
        }
    }

    @Nested
    class BalanceCheck {

        @ParameterizedTest(name = "amount={0}, balance=1000.00")
        @CsvSource({"999.99", "1000.00"})
        void shouldApproveWhenAmountDoesNotExceedAvailableBalance(BigDecimal amount) {
            // Arrange
            when(cardManagementClient.getCard(PAN))
                    .thenReturn(card(CardModelStatus.ACTIVE, VALID_EXPIRY, new BigDecimal("1000.00")));
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(requestWithAmount(amount), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }

        @ParameterizedTest(name = "amount={0}, balance=1000.00")
        @CsvSource({"1000.01", "1500.00"})
        void shouldDeclineWithInsufficientFundsBeforeLimitCheckWhenAmountExceedsBalance(BigDecimal amount) {
            // Arrange
            when(cardManagementClient.getCard(PAN))
                    .thenReturn(card(CardModelStatus.ACTIVE, VALID_EXPIRY, new BigDecimal("1000.00")));

            // Act
            AuthorizationResponse response = authService.authorize(requestWithAmount(amount), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(response.responseCode()).isEqualTo("51");
            verify(eventNotifier).notify(new AuthServiceAuthDeclinedFundsEvent(PAN));
            verifyNoInteractions(limitUsageRepository);
        }
    }

    @Nested
    class LimitsCheck {

        @Test
        void shouldUpdateLimitUsageForTransactionUtcDateWhenCardPassesChecks() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(PAN, LocalDate.of(2026, 6, 1), AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                    .thenReturn(1);

            // Act
            AuthorizationResponse response =
                    authService.authorize(requestAt(Instant.parse("2026-06-01T23:30:00Z")), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }

        @Test
        void shouldDeclineWithExceedsLimitWhenLimitUsageNotUpdated() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
            assertThat(response.responseCode()).isEqualTo("61");
            verify(eventNotifier).notify(new AuthServiceAuthDeclinedLimitsEvent(PAN));
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#concurrentInsertConflicts")
        void shouldRetryLimitUpdateAndApproveWhenConcurrentInsertConflicts(RuntimeException conflict) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(conflict)
                    .thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
            verify(eventNotifier).notify(isA(AuthServiceDuplicateKeyEvent.class));
        }

        @Test
        void shouldDeclineWithDbUnavailableWhenRetryAfterConflictFails() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("duplicate key"))
                    .thenThrow(new DataAccessResourceFailureException("db down"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
            assertThat(response.responseCode()).isEqualTo("96");
            verify(eventNotifier).notify(new AuthServiceAuthDeclineNoServiceEvent(PAN, "limit_usage table", "db down"));
        }

        @Test
        void shouldDeclineWithDbUnavailableWhenLimitStorageFails() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DataAccessResourceFailureException("db down"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
            assertThat(response.responseCode()).isEqualTo("96");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }
    }

    @Nested
    class Reservation {

        @ParameterizedTest(name = "{0} -> {1}/{2}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#reserveFailures")
        void shouldDeclineWithMappedReasonWhenReservationFails(
                RuntimeException failure, String expectedReason, String expectedCode,
                Class<? extends AuthorizationEvent> expectedEvent) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
            doThrow(failure).when(cardManagementClient).reserve(any(), any(), any());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            assertThat(response.authCode()).isNull();
            verify(eventNotifier).notify(isA(expectedEvent));
        }
    }

    @Nested
    class Rollback {

        @Test
        void shouldApproveRollbackWhenCardManagementReleasesReservation() {
            // Arrange
            RollbackRequest request = rollbackRequest();

            // Act
            RollbackResponse response = authService.rollback(request, REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
            assertThat(response.responseCode()).isEqualTo("00");
            assertThat(response.rrn()).isEqualTo(RRN);
            verify(cardManagementClient).rollback(request);
            verify(eventNotifier).notify(new AuthServiceRollbackEvent(PAN));
        }

        @ParameterizedTest(name = "{0} -> {1}/{2}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#rollbackFailures")
        void shouldDeclineRollbackWithMappedReasonWhenCardManagementFails(
                RuntimeException failure, String expectedReason, String expectedCode,
                Class<? extends AuthorizationEvent> expectedEvent) {
            // Arrange
            RollbackRequest request = rollbackRequest();
            doThrow(failure).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            verify(eventNotifier).notify(isA(expectedEvent));
        }
    }

    @Nested
    class Identifiers {

        @Test
        void shouldBuildRrnFromDayOfYearAndReservedSequenceBlockWhenFirstGenerated() {
            // Arrange
            when(limitUsageRepository.fetchRrnBlock()).thenReturn(5L);
            LocalDate today = LocalDate.now();
            String expectedPrefix = String.format("%1d%03d", today.getYear() % 10, today.getDayOfYear());

            // Act
            String rrn = authService.generateRRN();

            // Assert
            assertThat(rrn).isEqualTo(expectedPrefix + "00005000");
        }

        @Test
        void shouldReuseReservedBlockAndIncrementSequenceWhenGeneratedAgain() {
            // Arrange
            when(limitUsageRepository.fetchRrnBlock()).thenReturn(5L);
            String first = authService.generateRRN();

            // Act
            String second = authService.generateRRN();

            // Assert
            assertThat(first).endsWith("00005000");
            assertThat(second).endsWith("00005001");
        }
    }

    static Stream<Arguments> getCardFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14",
                        AuthServiceAuthDeclineNoCardEvent.class),
                Arguments.of(new InvalidGetCardRequestException("Invalid pan"), "CARD_NOT_FOUND", "14",
                        AuthServiceAuthDeclineNoCardEvent.class),
                Arguments.of(new ServiceUnavailableException("CMS down"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new ResourceAccessException("Read timed out"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new InternalCardManagerException("CMS 500"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new GetCardException("Status: 418"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new PaymentRequiredException("402"), "CARD_BLOCKED", "05",
                        AuthServiceAuthDeclinedCardStatusEvent.class),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05",
                        AuthServiceAuthDeclineUnknownEvent.class));
    }

    static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND", "14",
                        AuthServiceAuthDeclineNoCardEvent.class),
                Arguments.of(new InvalidReserveRequestException("400"), "CARD_NOT_FOUND", "14",
                        AuthServiceAuthDeclineNoCardEvent.class),
                Arguments.of(new ServiceUnavailableException("CMS down"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new ResourceAccessException("Read timed out"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new InternalCardManagerException("CMS 500"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new InsufficientFundsException("402"), "INSUFFICIENT_FUNDS", "51",
                        AuthServiceAuthDeclinedFundsEvent.class),
                Arguments.of(new ReserveException("Status: 409"), "RESERVATION_FAILED", "96",
                        AuthServiceAuthDeclineNoServiceEvent.class),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05",
                        AuthServiceAuthDeclineUnknownEvent.class));
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "TRANSACTION_NOT_FOUND", "14",
                        AuthServiceRollbackDeclineNoCardEvent.class),
                Arguments.of(new InvalidRollbackRequestException("400"), "TRANSACTION_NOT_FOUND", "14",
                        AuthServiceRollbackDeclineNoCardEvent.class),
                Arguments.of(new ServiceUnavailableException("CMS down"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceRollbackDeclineNoServiceEvent.class),
                Arguments.of(new ResourceAccessException("Read timed out"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceRollbackDeclineNoServiceEvent.class),
                Arguments.of(new InternalCardManagerException("CMS 500"), "SERVICE_UNAVAILABLE", "96",
                        AuthServiceRollbackDeclineNoServiceEvent.class),
                Arguments.of(new RollbackConflictException("409"), "ALREADY_ROLLED_BACK", "05",
                        AuthServiceRollbackDeclineConflictEvent.class),
                Arguments.of(new RollbackFailureException("Status: 422"), "ROLLBACK_FAILED", "96",
                        AuthServiceRollbackDeclineNoServiceEvent.class),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05",
                        AuthServiceRollbackDeclineUnknownEvent.class));
    }

    static Stream<Optional<String>> binLookupResults() {
        return Stream.of(Optional.of(ISSUER_ID), Optional.empty());
    }

    static Stream<Arguments> concurrentInsertConflicts() {
        return Stream.of(
                Arguments.of(new DuplicateKeyException("duplicate key")),
                Arguments.of(new ConstraintViolationException(
                        "unique violation", new SQLException("23505"), "limit_usage_pan_usage_date_key")));
    }

    private static AuthorizationRequest validRequest() {
        return request(AMOUNT, TRANSMISSION_TIME);
    }

    private static AuthorizationRequest requestWithAmount(BigDecimal amount) {
        return request(amount, TRANSMISSION_TIME);
    }

    private static AuthorizationRequest requestAt(Instant transmissionTime) {
        return request(AMOUNT, transmissionTime);
    }

    private static AuthorizationRequest request(BigDecimal amount, Instant transmissionTime) {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                amount, "643", transmissionTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID);
    }

    private static RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private static CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private static CardModel cardWithStatus(CardModelStatus status) {
        return card(status, VALID_EXPIRY, BALANCE);
    }

    private static CardModel card(CardModelStatus status, YearMonth expiry, BigDecimal availableBalance) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiry, status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, availableBalance,
                ISSUER_ID, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
