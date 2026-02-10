package com.algotrader.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.ExpiryType;
import com.algotrader.domain.model.WatchlistConfig;
import com.algotrader.entity.WatchlistConfigEntity;
import com.algotrader.mapper.WatchlistConfigMapper;
import com.algotrader.repository.jpa.WatchlistConfigJpaRepository;
import com.algotrader.service.WatchlistConfigService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WatchlistConfigService}.
 * Tests CRUD operations and duplicate underlying prevention.
 */
@ExtendWith(MockitoExtension.class)
class WatchlistConfigServiceTest {

    @Mock
    private WatchlistConfigJpaRepository watchlistConfigJpaRepository;

    @Mock
    private WatchlistConfigMapper watchlistConfigMapper;

    private WatchlistConfigService watchlistConfigService;

    @BeforeEach
    void setUp() {
        watchlistConfigService = new WatchlistConfigService(watchlistConfigJpaRepository, watchlistConfigMapper);
    }

    @Test
    @DisplayName("getAll: returns all configs")
    void getAllReturnsAll() {
        WatchlistConfigEntity entity = buildEntity(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true);
        WatchlistConfig domain = buildDomain(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true);

        when(watchlistConfigJpaRepository.findAll()).thenReturn(List.of(entity));
        when(watchlistConfigMapper.toDomainList(List.of(entity))).thenReturn(List.of(domain));

        List<WatchlistConfig> result = watchlistConfigService.getAll();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getUnderlying()).isEqualTo("NIFTY");
    }

    @Test
    @DisplayName("getEnabledConfigs: returns only enabled configs")
    void getEnabledConfigsFilters() {
        WatchlistConfigEntity enabled = buildEntity(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true);
        WatchlistConfig domain = buildDomain(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true);

        when(watchlistConfigJpaRepository.findByEnabled(true)).thenReturn(List.of(enabled));
        when(watchlistConfigMapper.toDomainList(List.of(enabled))).thenReturn(List.of(domain));

        List<WatchlistConfig> result = watchlistConfigService.getEnabledConfigs();
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("create: saves and returns new config")
    void createSavesConfig() {
        WatchlistConfig input = buildDomain(null, "BANKNIFTY", 15, ExpiryType.NEAREST_MONTHLY, true);
        WatchlistConfigEntity entity = buildEntity(null, "BANKNIFTY", 15, ExpiryType.NEAREST_MONTHLY, true);
        WatchlistConfigEntity saved = buildEntity(2L, "BANKNIFTY", 15, ExpiryType.NEAREST_MONTHLY, true);
        WatchlistConfig result = buildDomain(2L, "BANKNIFTY", 15, ExpiryType.NEAREST_MONTHLY, true);

        when(watchlistConfigJpaRepository.existsByUnderlying("BANKNIFTY")).thenReturn(false);
        when(watchlistConfigMapper.toEntity(any(WatchlistConfig.class))).thenReturn(entity);
        when(watchlistConfigJpaRepository.save(entity)).thenReturn(saved);
        when(watchlistConfigMapper.toDomain(saved)).thenReturn(result);

        WatchlistConfig created = watchlistConfigService.create(input);
        assertThat(created.getId()).isEqualTo(2L);
        assertThat(created.getUnderlying()).isEqualTo("BANKNIFTY");
    }

    @Test
    @DisplayName("create: throws on duplicate underlying")
    void createThrowsOnDuplicate() {
        WatchlistConfig input = buildDomain(null, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true);
        when(watchlistConfigJpaRepository.existsByUnderlying("NIFTY")).thenReturn(true);

        assertThatThrownBy(() -> watchlistConfigService.create(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("toggle: flips enabled state")
    void toggleFlipsEnabled() {
        WatchlistConfigEntity entity = buildEntity(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true);
        WatchlistConfigEntity toggled = buildEntity(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, false);
        WatchlistConfig result = buildDomain(1L, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, false);

        when(watchlistConfigJpaRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(watchlistConfigJpaRepository.save(entity)).thenReturn(toggled);
        when(watchlistConfigMapper.toDomain(toggled)).thenReturn(result);

        WatchlistConfig toggleResult = watchlistConfigService.toggle(1L);
        assertThat(toggleResult.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("delete: removes config")
    void deleteRemovesConfig() {
        when(watchlistConfigJpaRepository.existsById(1L)).thenReturn(true);

        watchlistConfigService.delete(1L);
        verify(watchlistConfigJpaRepository).deleteById(1L);
    }

    @Test
    @DisplayName("delete: throws when not found")
    void deleteThrowsWhenNotFound() {
        when(watchlistConfigJpaRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> watchlistConfigService.delete(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("seedDefaults: seeds 3 defaults when table is empty")
    void seedDefaultsSeedsWhenEmpty() {
        when(watchlistConfigJpaRepository.count()).thenReturn(0L);
        when(watchlistConfigMapper.toEntity(any(WatchlistConfig.class)))
                .thenReturn(buildEntity(null, "NIFTY", 10, ExpiryType.NEAREST_WEEKLY, true));
        when(watchlistConfigJpaRepository.save(any(WatchlistConfigEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        watchlistConfigService.seedDefaults();

        // Should save exactly 3 configs: NIFTY, BANKNIFTY, SENSEX
        verify(watchlistConfigJpaRepository, times(3)).save(any(WatchlistConfigEntity.class));
    }

    @Test
    @DisplayName("seedDefaults: skips when configs already exist")
    void seedDefaultsSkipsWhenNotEmpty() {
        when(watchlistConfigJpaRepository.count()).thenReturn(2L);

        watchlistConfigService.seedDefaults();

        verify(watchlistConfigJpaRepository, never()).save(any());
    }

    // ---- Helpers ----

    private WatchlistConfigEntity buildEntity(
            Long id, String underlying, int strikesFromAtm, ExpiryType expiryType, boolean enabled) {
        return WatchlistConfigEntity.builder()
                .id(id)
                .underlying(underlying)
                .strikesFromAtm(strikesFromAtm)
                .expiryType(expiryType)
                .enabled(enabled)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private WatchlistConfig buildDomain(
            Long id, String underlying, int strikesFromAtm, ExpiryType expiryType, boolean enabled) {
        return WatchlistConfig.builder()
                .id(id)
                .underlying(underlying)
                .strikesFromAtm(strikesFromAtm)
                .expiryType(expiryType)
                .enabled(enabled)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
