package com.algotrader.mapper;

import com.algotrader.domain.model.Trade;
import com.algotrader.domain.vo.ChargeBreakdown;
import com.algotrader.entity.TradeEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between Trade domain model and TradeEntity.
 *
 * <p>The key complexity is ChargeBreakdown: the domain model holds a single ChargeBreakdown VO,
 * while the entity has 7 flat columns (brokerage, stt, exchangeCharges, sebiCharges, stampDuty,
 * gst, totalCharges). This mapper flattens/unflattens the charge breakdown.
 */
@Mapper
public interface TradeMapper {

    @Mapping(source = "charges.brokerage", target = "brokerage")
    @Mapping(source = "charges.stt", target = "stt")
    @Mapping(source = "charges.exchangeCharges", target = "exchangeCharges")
    @Mapping(source = "charges.sebiCharges", target = "sebiCharges")
    @Mapping(source = "charges.stampDuty", target = "stampDuty")
    @Mapping(source = "charges.gst", target = "gst")
    @Mapping(
            target = "totalCharges",
            expression = "java(trade.getCharges() != null ? trade.getCharges().getTotal() : null)")
    @Mapping(target = "createdAt", ignore = true)
    TradeEntity toEntity(Trade trade);

    @Mapping(target = "charges", expression = "java(toChargeBreakdown(entity))")
    Trade toDomain(TradeEntity entity);

    List<Trade> toDomainList(List<TradeEntity> entities);

    /** Reassemble ChargeBreakdown VO from flat entity columns. */
    default ChargeBreakdown toChargeBreakdown(TradeEntity entity) {
        if (entity.getBrokerage() == null) {
            return null;
        }
        return ChargeBreakdown.builder()
                .brokerage(entity.getBrokerage())
                .stt(entity.getStt())
                .exchangeCharges(entity.getExchangeCharges())
                .sebiCharges(entity.getSebiCharges())
                .stampDuty(entity.getStampDuty())
                .gst(entity.getGst())
                .build();
    }
}
