package com.processing.cardmanagement.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private LuhnValidator luhnValidator;

    @BeforeEach
    void setUp() {
        luhnValidator = new LuhnValidator();
    }

    @ParameterizedTest(name = "PAN {0}")
    @ValueSource(strings = {"4111111111111111", "5555555555554444", "4012888888881881", "4000000000000002"})
    void shouldAcceptPanWhenCheckDigitMatches(String pan) {
        // Act
        boolean valid = luhnValidator.isValid(pan);

        // Assert
        assertThat(valid).isTrue();
    }

    @ParameterizedTest(name = "PAN {0}")
    @ValueSource(strings = {"4111111111111112", "5555555555554440", "4000000000000001"})
    void shouldRejectPanWhenCheckDigitBroken(String pan) {
        // Act
        boolean valid = luhnValidator.isValid(pan);

        // Assert
        assertThat(valid).isFalse();
    }

    @ParameterizedTest(name = "BIN {0}")
    @ValueSource(strings = {"400000", "400004", "555555"})
    void shouldGenerateLuhnValidPanStartingWithBinWhenBinGiven(String bin) {
        // Act
        String pan = luhnValidator.generatePan(bin);

        // Assert
        assertThat(pan).hasSize(16).containsOnlyDigits().startsWith(bin);
        assertThat(luhnValidator.isValid(pan)).isTrue();
    }
}
