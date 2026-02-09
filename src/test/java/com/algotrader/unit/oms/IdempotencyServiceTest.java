package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.oms.IdempotencyService;
import com.algotrader.oms.OrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for IdempotencyService covering uniqueness checks,
 * dedup key generation, Redis TTL behavior, and edge cases.
 */
class IdempotencyServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private IdempotencyService idempotencyService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        idempotencyService = new IdempotencyService(redisTemplate);
    }

    private OrderRequest sampleRequest() {
        return OrderRequest.builder()
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .quantity(50)
                .strategyId("STR1")
                .correlationId("COR1")
                .build();
    }

    @Nested
    @DisplayName("Uniqueness Checks")
    class UniquenessChecks {

        @Test
        @DisplayName("isUnique returns true when key does not exist in Redis")
        void isUniqueReturnsTrueWhenKeyNotFound() {
            when(redisTemplate.hasKey(anyString())).thenReturn(false);

            assertThat(idempotencyService.isUnique(sampleRequest())).isTrue();
        }

        @Test
        @DisplayName("isUnique returns true when Redis returns null (key unknown)")
        void isUniqueReturnsTrueWhenRedisReturnsNull() {
            when(redisTemplate.hasKey(anyString())).thenReturn(null);

            assertThat(idempotencyService.isUnique(sampleRequest())).isTrue();
        }

        @Test
        @DisplayName("isUnique returns false when key exists (duplicate)")
        void isUniqueReturnsFalseWhenDuplicate() {
            when(redisTemplate.hasKey(anyString())).thenReturn(true);

            assertThat(idempotencyService.isUnique(sampleRequest())).isFalse();
        }
    }

    @Nested
    @DisplayName("Mark Processed")
    class MarkProcessed {

        @Test
        @DisplayName("markProcessed sets Redis key with 5-min TTL")
        void markProcessedSetsKeyWithTtl() {
            OrderRequest request = sampleRequest();

            idempotencyService.markProcessed(request);

            verify(valueOperations).set(anyString(), eq("1"), eq(IdempotencyService.DEDUP_WINDOW));
        }

        @Test
        @DisplayName("markProcessed uses correct key prefix")
        void markProcessedUsesCorrectPrefix() {
            OrderRequest request = sampleRequest();

            idempotencyService.markProcessed(request);

            verify(valueOperations)
                    .set(
                            org.mockito.ArgumentMatchers.startsWith(IdempotencyService.KEY_PREFIX),
                            eq("1"),
                            eq(IdempotencyService.DEDUP_WINDOW));
        }
    }

    @Nested
    @DisplayName("Hash Generation")
    class HashGeneration {

        @Test
        @DisplayName("Same request produces same hash")
        void sameRequestProducesSameHash() {
            OrderRequest request = sampleRequest();

            String hash1 = idempotencyService.generateHash(request);
            String hash2 = idempotencyService.generateHash(request);

            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("Different instrument produces different hash")
        void differentInstrumentProducesDifferentHash() {
            OrderRequest request1 = sampleRequest();
            OrderRequest request2 = OrderRequest.builder()
                    .instrumentToken(999999L)
                    .tradingSymbol("BANKNIFTY24FEB50000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.LIMIT)
                    .quantity(50)
                    .strategyId("STR1")
                    .build();

            assertThat(idempotencyService.generateHash(request1))
                    .isNotEqualTo(idempotencyService.generateHash(request2));
        }

        @Test
        @DisplayName("Different side produces different hash")
        void differentSideProducesDifferentHash() {
            OrderRequest buyRequest = sampleRequest();
            OrderRequest sellRequest = OrderRequest.builder()
                    .instrumentToken(256265L)
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.SELL)
                    .type(OrderType.LIMIT)
                    .quantity(50)
                    .strategyId("STR1")
                    .build();

            assertThat(idempotencyService.generateHash(buyRequest))
                    .isNotEqualTo(idempotencyService.generateHash(sellRequest));
        }

        @Test
        @DisplayName("Different quantity produces different hash")
        void differentQuantityProducesDifferentHash() {
            OrderRequest request1 = sampleRequest();
            OrderRequest request2 = OrderRequest.builder()
                    .instrumentToken(256265L)
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.LIMIT)
                    .quantity(100)
                    .strategyId("STR1")
                    .build();

            assertThat(idempotencyService.generateHash(request1))
                    .isNotEqualTo(idempotencyService.generateHash(request2));
        }

        @Test
        @DisplayName("Hash is 16 hex characters")
        void hashIs16HexChars() {
            String hash = idempotencyService.generateHash(sampleRequest());

            assertThat(hash).hasSize(16);
            assertThat(hash).matches("[0-9a-f]{16}");
        }

        @Test
        @DisplayName("Null strategyId uses 'manual' placeholder")
        void nullStrategyIdUsesManualPlaceholder() {
            OrderRequest manualRequest = OrderRequest.builder()
                    .instrumentToken(256265L)
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.LIMIT)
                    .quantity(50)
                    .strategyId(null)
                    .build();

            // Should not throw, and should produce a valid hash
            String hash = idempotencyService.generateHash(manualRequest);
            assertThat(hash).hasSize(16);
        }

        @Test
        @DisplayName("Null side uses UNKNOWN placeholder")
        void nullSideUsesUnknownPlaceholder() {
            OrderRequest requestWithNullSide = OrderRequest.builder()
                    .instrumentToken(256265L)
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(null)
                    .type(OrderType.LIMIT)
                    .quantity(50)
                    .build();

            String hash = idempotencyService.generateHash(requestWithNullSide);
            assertThat(hash).hasSize(16);
        }
    }
}
