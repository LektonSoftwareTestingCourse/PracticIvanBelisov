package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String PAN = "4000001234567899";
    private static final String OTHER_PAN = "4000009876543210";
    private static final String RRN = "612345000001";
    private static final BigDecimal AMOUNT = new BigDecimal("400.00");

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                settings,
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                new RetryServiceImpl(),
                new TransactionRunnerImpl());
    }

    @Nested
    class CreateCard {

        @Test
        void shouldSaveActiveCardWithGeneratedPanAndIssuerWhenCreated() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(settings.cardValidityPeriod()).thenReturn(3);
            when(settings.maxCardCreationRetries()).thenReturn(2);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN);
            when(cardRepository.create(any(Card.class))).then(returnsFirstArg());

            // Act
            Card card = createDefaultCard();

            // Assert
            assertThat(card.pan()).isEqualTo(PAN);
            assertThat(card.issuerId()).isEqualTo(ISSUER_ID);
            assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
            assertThat(card.expiryDate()).isEqualTo(YearMonth.now().plusYears(3));
            assertThat(card.availableBalance()).isEqualByComparingTo("1000.00");
            verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
        }

        @Test
        void shouldRegeneratePanAndRetryWhenPanCollides() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(settings.cardValidityPeriod()).thenReturn(3);
            when(settings.maxCardCreationRetries()).thenReturn(2);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN, OTHER_PAN);
            when(cardRepository.create(any(Card.class)))
                    .thenThrow(new PanCollisionException(PAN))
                    .then(returnsFirstArg());

            // Act
            Card card = createDefaultCard();

            // Assert
            assertThat(card.pan()).isEqualTo(OTHER_PAN);
        }

        private Card createDefaultCard() {
            return cardService.createCard(BIN, "IVAN IVANOV", "643",
                    new BigDecimal("100.00"), new BigDecimal("3000.00"), new BigDecimal("1000.00"));
        }
    }

    @Nested
    class CreateCards {

        @Test
        void shouldSaveAllCardsInOneBatchWhenNoCollision() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(settings.cardValidityPeriod()).thenReturn(3);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN, OTHER_PAN);
            when(cardRepository.createAll(any())).then(returnsFirstArg());

            // Act
            List<Card> saved = cardService.createCards(List.of(draft(), draft()));

            // Assert
            assertThat(saved).extracting(Card::pan).containsExactly(PAN, OTHER_PAN);
            verify(cardRepository, never()).create(any());
            verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
        }

        @Test
        void shouldSaveCardsOneByOneWithFreshPansWhenBatchCollides() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(settings.cardValidityPeriod()).thenReturn(3);
            when(settings.maxCardCreationRetries()).thenReturn(2);
            when(panGenerator.generatePan(BIN)).thenReturn("4000000000000001", "4000000000000002", "4000000000000003", "4000000000000004");
            when(cardRepository.createAll(any())).thenThrow(new MassiveCardCreationCollisionException());
            when(cardRepository.create(any(Card.class))).then(returnsFirstArg());

            // Act
            List<Card> saved = cardService.createCards(List.of(draft(), draft()));

            // Assert
            assertThat(saved).extracting(Card::pan).containsExactly("4000000000000003", "4000000000000004");
            verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
        }

        private CardDraft draft() {
            return new CardDraft(BIN, "IVAN IVANOV", CardStatus.ACTIVE, "643",
                    new BigDecimal("100.00"), new BigDecimal("3000.00"), new BigDecimal("1000.00"));
        }
    }

    @Nested
    class ReadCards {

        @Test
        void shouldReturnCardWhenPanExists() {
            // Arrange
            Card card = activeCard(new BigDecimal("1000.00"));
            when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

            // Act
            Card found = cardService.getCard(PAN);

            // Assert
            assertThat(found).isEqualTo(card);
        }

        @Test
        void shouldThrowCardNotFoundWhenPanUnknown() {
            // Arrange
            when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

            // Act
            Throwable thrown = catchThrowable(() -> cardService.getCard(PAN));

            // Assert
            assertThat(thrown).isInstanceOf(CardNotFoundException.class);
        }

        @ParameterizedTest(name = "limit={0}, maxPageLimit=100")
        @CsvSource({"1", "100"})
        void shouldQueryRepositoryWithRequestedPageWhenLimitWithinMaximum(int limit) {
            // Arrange
            List<Card> page = List.of(activeCard(new BigDecimal("1000.00")));
            when(settings.maxPageLimit()).thenReturn(100);
            when(cardRepository.findCards(limit, 5L, CardStatus.ACTIVE, BIN, null, null, null)).thenReturn(page);

            // Act
            List<Card> cards = cardService.getCards(limit, 5L, CardStatus.ACTIVE, BIN, null, null, null);

            // Assert
            assertThat(cards).isEqualTo(page);
        }

        @Test
        void shouldRejectPageWhenLimitAboveMaximum() {
            // Arrange
            when(settings.maxPageLimit()).thenReturn(100);

            // Act
            Throwable thrown = catchThrowable(() -> cardService.getCards(101, 0L, null, null, null, null, null));

            // Assert
            assertThat(thrown)
                    .isInstanceOf(TooLargeLimitException.class)
                    .hasMessageContaining("101");
        }

        @Test
        void shouldUseDefaultPagingWhenLimitAndOffsetMissing() {
            // Arrange
            List<Card> page = List.of(activeCard(new BigDecimal("1000.00")));
            when(defaults.pageLimit()).thenReturn(20);
            when(defaults.pageOffset()).thenReturn(0L);
            when(cardRepository.findCards(20, 0L, null, null, null, null, null)).thenReturn(page);

            // Act
            List<Card> cards = cardService.getCards(null, null, null, null, null, null, null);

            // Assert
            assertThat(cards).isEqualTo(page);
        }
    }

    @Nested
    class PatchAndDelete {

        @Test
        void shouldKeepCurrentValuesWhenPatchFieldsMissing() {
            // Arrange
            Card card = activeCard(new BigDecimal("1000.00"));
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
            when(cardRepository.update(any(Card.class))).then(returnsFirstArg());

            // Act
            Card patched = cardService.patchCard(PAN, null, null, null, null);

            // Assert
            assertThat(patched).isEqualTo(card);
            verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
        }

        @Test
        void shouldApplyProvidedValuesWhenPatchFieldsPresent() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("1000.00"))));
            when(cardRepository.update(any(Card.class))).then(returnsFirstArg());

            // Act
            Card patched = cardService.patchCard(PAN, CardStatus.BLOCKED,
                    new BigDecimal("50.00"), new BigDecimal("500.00"), new BigDecimal("10.00"));

            // Assert
            assertThat(patched.status()).isEqualTo(CardStatus.BLOCKED);
            assertThat(patched.dailyLimit()).isEqualByComparingTo("50.00");
            assertThat(patched.monthlyLimit()).isEqualByComparingTo("500.00");
            assertThat(patched.availableBalance()).isEqualByComparingTo("10.00");
        }

        @Test
        void shouldStoreCardAsDeletedWhenDeleting() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("1000.00"))));
            ArgumentCaptor<Card> stored = ArgumentCaptor.forClass(Card.class);

            // Act
            cardService.deleteCard(PAN);

            // Assert
            verify(cardRepository).update(stored.capture());
            assertThat(stored.getValue().status()).isEqualTo(CardStatus.DELETED);
            verify(eventNotifier).notifyListeners(new CardServiceDeletionEvent(PAN));
        }
    }

    @Nested
    class ReserveFunds {

        @Test
        void shouldDecreaseBalanceAndPublishEventWhenReservationUnique() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("1000.00"))));
            when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
            when(reservationRepository.save(any(Reservation.class))).then(returnsFirstArg());
            when(cardRepository.update(any(Card.class))).then(returnsFirstArg());

            // Act
            Card card = cardService.reserve(PAN, AMOUNT, RRN);

            // Assert
            assertThat(card.availableBalance()).isEqualByComparingTo("600.00");
            verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, AMOUNT));
        }

        @Test
        void shouldRejectReservationWhenRrnAlreadyUsedForCard() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("1000.00"))));
            when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

            // Act
            Throwable thrown = catchThrowable(() -> cardService.reserve(PAN, AMOUNT, RRN));

            // Assert
            assertThat(thrown).isInstanceOf(ReservationAlreadyExistsException.class);
            verify(reservationRepository, never()).save(any());
            verify(cardRepository, never()).update(any());
        }
    }

    @Nested
    class RollbackFunds {

        @Test
        void shouldRestoreBalanceAndPublishEventWhenReservationFound() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("600.00"))));
            when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation()));
            when(reservationRollbackRepository.save(any())).then(returnsFirstArg());
            when(cardRepository.update(any(Card.class))).then(returnsFirstArg());

            // Act
            Card card = cardService.rollback(PAN, AMOUNT, RRN);

            // Assert
            assertThat(card.availableBalance()).isEqualByComparingTo("1000.00");
            verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, AMOUNT));
        }

        @Test
        void shouldStoreReservationAsRolledBackWhenRollingBack() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("600.00"))));
            when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation()));
            when(reservationRollbackRepository.save(any())).then(returnsFirstArg());

            // Act
            cardService.rollback(PAN, AMOUNT, RRN);

            // Assert
            verify(reservationRepository).save(argThat(r -> r.status() == ReservationStatus.ROLLED_BACK));
        }

        @Test
        void shouldThrowReservationNotFoundWhenNoReservationForRrn() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard(new BigDecimal("600.00"))));
            when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

            // Act
            Throwable thrown = catchThrowable(() -> cardService.rollback(PAN, AMOUNT, RRN));

            // Assert
            assertThat(thrown).isInstanceOf(ReservationNotFoundException.class);
            verify(cardRepository, never()).update(any());
        }

        private Reservation reservation() {
            Instant created = Instant.parse("2026-06-01T10:30:00Z");
            return new Reservation(UUID.randomUUID(), PAN, AMOUNT, RRN, ReservationStatus.RESERVED, created, created);
        }
    }

    @Nested
    class BulkUpdateStatus {

        @ParameterizedTest(name = "bins={0}, pans={1}")
        @MethodSource("com.processing.cardmanagement.services.CardServiceImplTest#invalidBulkSelectors")
        void shouldRejectBulkUpdateWhenSelectorAmbiguous(List<String> bins, List<String> pans, String message) {
            // Act
            Throwable thrown = catchThrowable(() -> cardService.bulkUpdateStatus(bins, pans, CardStatus.BLOCKED));

            // Assert
            assertThat(thrown)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(message);
        }

        @Test
        void shouldUpdateStatusOfEveryCardWithBinWhenSelectedByBins() {
            // Arrange
            when(cardRepository.findCardsByBinsForUpdate(List.of(BIN)))
                    .thenReturn(List.of(activeCard(BigDecimal.TEN), activeCard(BigDecimal.ONE)));

            // Act
            int updated = cardService.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

            // Assert
            assertThat(updated).isEqualTo(2);
            verify(cardRepository, times(2)).update(argThat(card -> card.status() == CardStatus.BLOCKED));
        }
    }

    static Stream<Arguments> invalidBulkSelectors() {
        return Stream.of(
                Arguments.of(null, null, "Either bin or pans must be provided"),
                Arguments.of(List.of(BIN), List.of(PAN), "Only one of bin or pans must be provided"));
    }

    private static Card activeCard(BigDecimal balance) {
        return new Card(UUID.randomUUID(), PAN, BIN, "IVAN IVANOV", YearMonth.of(2029, 12), CardStatus.ACTIVE,
                "643", new BigDecimal("100000"), new BigDecimal("300000"), balance, ISSUER_ID,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
