package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the instruments table.
 * Instruments are downloaded daily from Kite API and cached in H2 by download_date.
 */
@Entity
@Table(name = "instruments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstrumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long token;

    @Column(name = "trading_symbol", length = 50)
    private String tradingSymbol;

    @Column(length = 100)
    private String name;

    @Column(length = 20)
    private String underlying;

    @Column(name = "instrument_type", columnDefinition = "varchar(10)")
    private String instrumentType;

    @Column(length = 10)
    private String exchange;

    @Column(length = 20)
    private String segment;

    @Column(precision = 10, scale = 2)
    private BigDecimal strike;

    private LocalDate expiry;

    @Column(name = "lot_size")
    private int lotSize;

    @Column(name = "tick_size", precision = 10, scale = 4)
    private BigDecimal tickSize;

    @Column(name = "download_date")
    private LocalDate downloadDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
