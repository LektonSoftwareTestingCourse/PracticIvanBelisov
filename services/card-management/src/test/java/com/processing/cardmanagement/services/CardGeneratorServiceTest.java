package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.CardGenerationLimitException;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardGeneratorOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardGeneratorServiceTest {

    private static final List<String> BINS = List.of("400000", "400001", "400002");
    private static final int MAX_COUNT = 1000;

    @Mock
    private CardService cardService;

    @Mock
    private CardEventNotifier eventNotifier;

    private CardGeneratorService generatorService;

    @BeforeEach
    void setUp() {
        CardGeneratorOptions options = new CardGeneratorOptions(
                new BigDecimal("1000"), new BigDecimal("5000"),
                new BigDecimal("100"), new BigDecimal("500"),
                "643", MAX_COUNT);
        generatorService = new CardGeneratorService(cardService, options, eventNotifier);
    }

    @Test
    void shouldRejectGenerationWhenCountAboveMaximum() {
        // Act
        Throwable thrown = catchThrowable(() -> generatorService.generate(MAX_COUNT + 1, BINS));

        // Assert
        assertThat(thrown)
                .isInstanceOf(CardGenerationLimitException.class)
                .hasMessageContaining(String.valueOf(MAX_COUNT));
        verifyNoInteractions(cardService, eventNotifier);
    }

    @ParameterizedTest(name = "count={0} -> ACTIVE={1}, INACTIVE={2}, BLOCKED={3}")
    @CsvSource({
        "100,  95,  3, 2",
        "200, 190,  6, 4",
        "10,    9,  0, 1",
        "1,     0,  0, 1"
    })
    void shouldDistributeStatusesByPercentagesWhenGenerating(int count, long active, long inactive, long blocked) {
        // Arrange
        ArgumentCaptor<List<CardDraft>> drafts = draftsCaptor();
        when(cardService.createCards(drafts.capture())).thenReturn(List.of());

        // Act
        generatorService.generate(count, BINS);

        // Assert
        Map<CardStatus, Long> statuses = countBy(drafts.getValue(), CardDraft::status);
        assertThat(drafts.getValue()).hasSize(count);
        assertThat(statuses.getOrDefault(CardStatus.ACTIVE, 0L)).isEqualTo(active);
        assertThat(statuses.getOrDefault(CardStatus.INACTIVE, 0L)).isEqualTo(inactive);
        assertThat(statuses.getOrDefault(CardStatus.BLOCKED, 0L)).isEqualTo(blocked);
    }

    @Test
    void shouldAssignBinsRoundRobinWhenGenerating() {
        // Arrange
        ArgumentCaptor<List<CardDraft>> drafts = draftsCaptor();
        when(cardService.createCards(drafts.capture())).thenReturn(List.of());

        // Act
        generatorService.generate(5, BINS);

        // Assert
        assertThat(drafts.getValue())
                .extracting(CardDraft::bin)
                .containsExactly("400000", "400001", "400002", "400000", "400001");
    }

    @Test
    void shouldGenerateAmountsWithinConfiguredRangesWhenGenerating() {
        // Arrange
        ArgumentCaptor<List<CardDraft>> drafts = draftsCaptor();
        when(cardService.createCards(drafts.capture())).thenReturn(List.of());

        // Act
        generatorService.generate(50, BINS);

        // Assert
        assertThat(drafts.getValue()).allSatisfy(draft -> {
            assertThat(draft.initialBalance()).isBetween(new BigDecimal("1000"), new BigDecimal("4999"));
            assertThat(draft.dailyLimit()).isBetween(new BigDecimal("100"), new BigDecimal("499"));
            assertThat(draft.monthlyLimit()).isEqualByComparingTo(draft.dailyLimit().multiply(BigDecimal.valueOf(30)));
            assertThat(draft.currencyCode()).isEqualTo("643");
            assertThat(draft.cardholderName()).isNotBlank().isUpperCase();
        });
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<CardDraft>> draftsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static <K> Map<K, Long> countBy(List<CardDraft> drafts, Function<CardDraft, K> key) {
        return drafts.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }
}
