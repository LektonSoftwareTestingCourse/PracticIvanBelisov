package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RoutingServiceTest {

    private static final Map<String, String> BIN_TABLE = Map.of(
            "400000", "ISS001",
            "400001", "ISS002",
            "400002", "ISS003",
            "400003", "ISS004",
            "400004", "ISS005");

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new RoutingService(propertiesWithBinTable(BIN_TABLE));
    }

    @ParameterizedTest(name = "PAN {0} -> {1}")
    @CsvSource({
        "4000001234567890, ISS001",
        "4000011234567890, ISS002",
        "4000021234567890, ISS003",
        "4000031234567890, ISS004",
        "4000041234567890, ISS005"
    })
    void shouldReturnIssuerIdWhenBinIsInRoutingTable(String pan, String expectedIssuerId) {
        // Act
        String issuerId = routingService.getIssuerIdByPan(pan);

        // Assert
        assertThat(issuerId).isEqualTo(expectedIssuerId);
    }

    @Test
    void shouldReturnIssuerIdWhenPanConsistsOfExactlySixDigits() {
        // Act
        String issuerId = routingService.getIssuerIdByPan("400000");

        // Assert
        assertThat(issuerId).isEqualTo("ISS001");
    }

    @ParameterizedTest(name = "PAN=''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"4", "40000"})
    void shouldThrowUnknownBinWhenPanIsNullOrShorterThanSixDigits(String pan) {
        // Act
        Throwable thrown = catchThrowable(() -> routingService.getIssuerIdByPan(pan));

        // Assert
        assertThat(thrown)
                .isInstanceOf(UnknownBinException.class)
                .hasMessage("Unknown BIN 'null-or-short'");
    }

    @Test
    void shouldThrowUnknownBinWithBinInMessageWhenBinIsNotInRoutingTable() {
        // Act
        Throwable thrown = catchThrowable(() -> routingService.getIssuerIdByPan("4999991234567890"));

        // Assert
        assertThat(thrown)
                .isInstanceOf(UnknownBinException.class)
                .hasMessage("Unknown BIN '499999'");
    }

    private static SwitchProperties propertiesWithBinTable(Map<String, String> binTable) {
        return new SwitchProperties(null, binTable, null, null, null, null, null, null);
    }
}
