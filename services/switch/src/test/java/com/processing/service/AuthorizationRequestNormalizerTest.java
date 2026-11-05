package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "terminalId=''{0}'' -> ''{1}''")
    @CsvSource(nullValues = "NULL", value = {
        "NULL,      NULL",
        "'',        ''",
        "'   ',     '   '",
        "T1,        T1000000",
        "TERM001,   TERM0010",
        "TERM0001,  TERM0001",
        "TERM00012, TERM0001"
    })
    void shouldNormalizeTerminalIdToEightCharactersWhenNotBlank(String terminalId, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(terminalId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @ParameterizedTest(name = "merchantId=''{0}'' -> ''{1}''")
    @CsvSource(nullValues = "NULL", value = {
        "NULL,              NULL",
        "'',                ''",
        "'   ',             '   '",

        "MERCH0000000002,   MERCH0000000002",
        "MERCH00000000002,  MERCH0000000002",
        "MERCH10000000002,  MERCH1000000000",
        "SHOP000000000002,  SHOP00000000000",
        "MERCH000000000002, MERCH0000000000",
        "MERCH42,           MERCH4200000000"
    })
    void shouldNormalizeMerchantIdToFifteenCharactersWhenNotBlank(String merchantId, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(merchantId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    void shouldKeepOtherFieldsWhenNormalizingRequest() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000.00"), "643", Instant.parse("2026-06-01T10:30:00Z"),
                "TERM1", "POS", "MERCH42", "5411", "ACQ001", "ISS001");

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(normalized.terminalId()).isEqualTo("TERM1000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH4200000000");
        assertThat(normalized)
                .usingRecursiveComparison()
                .ignoringFields("terminalId", "merchantId")
                .isEqualTo(request);
    }
}
