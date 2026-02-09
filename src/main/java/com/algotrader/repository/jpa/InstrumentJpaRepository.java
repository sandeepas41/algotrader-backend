package com.algotrader.repository.jpa;

import com.algotrader.entity.InstrumentEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the instruments table.
 * Instruments are downloaded daily from Kite API and cached by download_date.
 * On startup, InstrumentService checks if today's instruments exist before re-downloading.
 */
@Repository
public interface InstrumentJpaRepository extends JpaRepository<InstrumentEntity, Long> {

    List<InstrumentEntity> findByDownloadDate(LocalDate downloadDate);

    Optional<InstrumentEntity> findByToken(Long token);

    List<InstrumentEntity> findByTradingSymbol(String tradingSymbol);

    List<InstrumentEntity> findByUnderlyingAndExpiryAndDownloadDate(
            String underlying, LocalDate expiry, LocalDate downloadDate);

    List<InstrumentEntity> findByUnderlyingAndDownloadDate(String underlying, LocalDate downloadDate);

    List<InstrumentEntity> findByExchangeAndDownloadDate(String exchange, LocalDate downloadDate);

    boolean existsByDownloadDate(LocalDate downloadDate);
}
