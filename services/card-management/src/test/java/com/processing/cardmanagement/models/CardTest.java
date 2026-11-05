package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CardTest {

    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PAN = "4000001234567899";
    private static final String RRN = "612345000001";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @ParameterizedTest(name = "balance={0}, amount={1}")
    @CsvSource({
        "1000.00, 999.99",
        "1000.00, 1000.00",
        "1000.00, 0.01"
    })
    void shouldStartReservationWhenCardActiveAndBalanceCoversAmount(BigDecimal balance, BigDecimal amount) {
        // Arrange
        Card card = activeCardWithBalance(balance);

        // Act
        Reservation reservation = card.startReservation(amount, RRN);

        // Assert
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest(name = "balance={0}, amount={1}")
    @CsvSource({
        "1000.00, 1000.01",
        "0.00,    0.01"
    })
    void shouldRejectReservationWhenAmountExceedsBalance(BigDecimal balance, BigDecimal amount) {
        // Arrange
        Card card = activeCardWithBalance(balance);

        // Act
        Throwable thrown = catchThrowable(() -> card.startReservation(amount, RRN));

        // Assert
        assertThat(thrown).isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        Card card = card(status, new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("3000.00"));

        // Act
        Throwable thrown = catchThrowable(() -> card.startReservation(new BigDecimal("1.00"), RRN));

        // Assert
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void shouldDecreaseBalanceWhenReservationApplied() {
        // Arrange
        Card card = activeCardWithBalance(new BigDecimal("1000.00"));
        Reservation reservation = new Reservation(PAN, new BigDecimal("400.00"), RRN);

        // Act
        Card reserved = card.withReservation(reservation);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo("600.00");
        assertThat(reserved).usingRecursiveComparison().ignoringFields("availableBalance").isEqualTo(card);
    }

    @Test
    void shouldIncreaseBalanceWhenRollbackApplied() {
        // Arrange
        Card card = activeCardWithBalance(new BigDecimal("600.00"));
        ReservationRollback rollback = rollback(PAN, new BigDecimal("400.00"));

        // Act
        Card restored = card.withRollback(rollback);

        // Assert
        assertThat(restored.availableBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void shouldMarkCardDeletedWhenDeleted() {
        // Arrange
        Card card = activeCardWithBalance(new BigDecimal("1000.00"));

        // Act
        Card deleted = card.deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted).usingRecursiveComparison().ignoringFields("status").isEqualTo(card);
    }

    @Test
    void shouldRejectDeletionWhenCardAlreadyDeleted() {
        // Arrange
        Card card = card(CardStatus.DELETED, new BigDecimal("1000.00"), new BigDecimal("100.00"),
                new BigDecimal("3000.00"));

        // Act
        Throwable thrown = catchThrowable(card::deleted);

        // Assert
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Card already deleted");
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({
        "100000, 300000",
        "100000, 100000",
        "0,      0"
    })
    void shouldUpdateDataWhenLimitsAreNonNegativeAndDailyNotAboveMonthly(BigDecimal daily, BigDecimal monthly) {
        // Arrange
        Card card = activeCardWithBalance(new BigDecimal("1000.00"));

        // Act
        Card updated = card.withData(CardStatus.BLOCKED, daily, monthly, new BigDecimal("50.00"));

        // Assert
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo(daily);
        assertThat(updated.monthlyLimit()).isEqualByComparingTo(monthly);
        assertThat(updated.availableBalance()).isEqualByComparingTo("50.00");
        assertThat(updated.id()).isEqualTo(card.id());
    }

    @ParameterizedTest(name = "daily={0}, monthly={1} -> ''{2}''")
    @CsvSource({
        "100000, 99999,  Daily limit can not be larger than monthly limit",
        "-1,     100000, Limit can not contain negative values",
        "100,    -1,     Limit can not contain negative values"
    })
    void shouldRejectDataWhenLimitsInvalid(BigDecimal daily, BigDecimal monthly, String expectedMessage) {
        // Arrange
        Card card = activeCardWithBalance(new BigDecimal("1000.00"));

        // Act
        Throwable thrown = catchThrowable(() -> card.withData(CardStatus.ACTIVE, daily, monthly, BigDecimal.TEN));

        // Assert
        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }

    @Test
    void shouldSetExpiryAfterValidityPeriodWhenCreatedFromDraft() {
        // Arrange
        CardDraft draft = new CardDraft("400000", "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("100.00"), new BigDecimal("3000.00"), new BigDecimal("1000.00"));
        YearMonth expectedExpiry = YearMonth.now().plusYears(3);

        // Act
        Card card = Card.fromDraft(PAN, "ISS001", 3, draft);

        // Assert
        assertThat(card.id()).isNotNull();
        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.bin()).isEqualTo("400000");
        assertThat(card.issuerId()).isEqualTo("ISS001");
        assertThat(card.expiryDate()).isEqualTo(expectedExpiry);
        assertThat(card.availableBalance()).isEqualByComparingTo("1000.00");
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
    }

    private static Card activeCardWithBalance(BigDecimal balance) {
        return card(CardStatus.ACTIVE, balance, new BigDecimal("100000"), new BigDecimal("300000"));
    }

    private static Card card(CardStatus status, BigDecimal balance, BigDecimal daily, BigDecimal monthly) {
        return new Card(CARD_ID, PAN, "400000", "IVAN IVANOV", YearMonth.of(2029, 12), status, "643",
                daily, monthly, balance, "ISS001", CREATED_AT);
    }

    private static ReservationRollback rollback(String pan, BigDecimal amount) {
        return new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(), pan, amount, RRN, CREATED_AT);
    }
}
