package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ReservationTest {

    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String PAN = "4000001234567899";
    private static final String RRN = "612345000001";
    private static final Instant CREATED_AT = Instant.parse("2026-06-01T10:30:00Z");

    @Test
    void shouldStartRollbackLinkedToReservationWhenReserved() {
        // Arrange
        Reservation reservation = reservationWithStatus(ReservationStatus.RESERVED);

        // Act
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("400.00"));

        // Assert
        assertThat(rollback.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(rollback.pan()).isEqualTo(PAN);
        assertThat(rollback.rrn()).isEqualTo(RRN);
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void shouldRejectRollbackWhenReservationAlreadyRolledBack() {
        // Arrange
        Reservation reservation = reservationWithStatus(ReservationStatus.ROLLED_BACK);

        // Act
        Throwable thrown = catchThrowable(() -> reservation.startRollback(new BigDecimal("400.00")));

        // Assert
        assertThat(thrown)
                .isInstanceOf(RollbackAlreadySatisfiedException.class)
                .hasMessageContaining(RRN);
    }

    @Test
    void shouldMarkReservationRolledBackWhenRollbackBelongsToIt() {
        // Arrange
        Reservation reservation = reservationWithStatus(ReservationStatus.RESERVED);
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("400.00"));

        // Act
        Reservation rolledBack = reservation.rolledBack(rollback);

        // Assert
        assertThat(rolledBack.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(rolledBack.id()).isEqualTo(RESERVATION_ID);
        assertThat(rolledBack.createdAt()).isEqualTo(CREATED_AT);
    }

    private static Reservation reservationWithStatus(ReservationStatus status) {
        return new Reservation(RESERVATION_ID, PAN, new BigDecimal("400.00"), RRN, status, CREATED_AT, CREATED_AT);
    }
}
