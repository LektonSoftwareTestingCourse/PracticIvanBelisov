package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TransactionRequestValidatorTest {

    private TransactionRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TransactionRequestValidator();
    }

    @Test
    void shouldAcceptRequestWhenAllFieldsValid() {
        // Arrange
        AuthorizationRequest request = validRequest().build();

        // Act
        Throwable thrown = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(thrown).isNull();
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0.01", "1", "999999999.99"})
    void shouldAcceptRequestWhenAmountPositive(BigDecimal amount) {
        // Arrange
        AuthorizationRequest request = validRequest().amount(amount).build();

        // Act
        Throwable thrown = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(thrown).isNull();
    }

    @Test
    void shouldRejectRequestWhenBodyMissing() {
        // Act
        Throwable thrown = catchThrowable(() -> validator.validate(null));

        // Assert
        assertThat(thrown)
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void shouldRejectRequestWithFieldMessageWhenFieldInvalid(
            String scenario, AuthorizationRequest request, String expectedMessage) {
        // Act
        Throwable thrown = catchThrowable(() -> validator.validate(request));

        // Assert
        assertThat(thrown)
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage(expectedMessage);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("mti null", validRequest().mti(null).build(), "Field 'mti' is required"),
                Arguments.of("mti blank", validRequest().mti("   ").build(), "Field 'mti' is required"),
                Arguments.of("stan null", validRequest().stan(null).build(), "Field 'stan' is required"),
                Arguments.of("pan empty", validRequest().pan("").build(), "Field 'pan' is required"),
                Arguments.of("processingCode null", validRequest().processingCode(null).build(),
                        "Field 'processingCode' is required"),
                Arguments.of("currencyCode blank", validRequest().currencyCode(" ").build(),
                        "Field 'currencyCode' is required"),
                Arguments.of("transmissionDateTime null", validRequest().transmissionDateTime(null).build(),
                        "Field 'transmissionDateTime' is required"),
                Arguments.of("terminalId null", validRequest().terminalId(null).build(),
                        "Field 'terminalId' is required"),
                Arguments.of("merchantId empty", validRequest().merchantId("").build(),
                        "Field 'merchantId' is required"),
                Arguments.of("mcc null", validRequest().mcc(null).build(), "Field 'mcc' is required"),
                Arguments.of("acquirerId blank", validRequest().acquirerId("  ").build(),
                        "Field 'acquirerId' is required"),
                Arguments.of("mti 0200", validRequest().mti("0200").build(), "Field 'mti' must be '0100'"),
                Arguments.of("pan 15 digits", validRequest().pan("400000123456789").build(),
                        "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan 17 digits", validRequest().pan("40000012345678901").build(),
                        "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan with letters", validRequest().pan("40000012345678AB").build(),
                        "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount null", validRequest().amount(null).build(), "Field 'amount' is required"),
                Arguments.of("amount zero", validRequest().amount(BigDecimal.ZERO).build(),
                        "Field 'amount' must be > 0"),
                Arguments.of("amount negative", validRequest().amount(new BigDecimal("-0.01")).build(),
                        "Field 'amount' must be > 0"),
                Arguments.of("currencyCode 2 chars", validRequest().currencyCode("64").build(),
                        "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("currencyCode 4 chars", validRequest().currencyCode("6430").build(),
                        "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc 3 digits", validRequest().mcc("504").build(), "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc with letter", validRequest().mcc("50A5").build(),
                        "Field 'mcc' must be exactly 4 digits"));
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567890")
                .processingCode("000000")
                .amount(new BigDecimal("1000.00"))
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-06-01T10:30:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000002")
                .mcc("5411")
                .acquirerId("ACQ001");
    }
}
