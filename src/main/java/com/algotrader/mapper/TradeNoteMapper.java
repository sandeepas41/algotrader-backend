package com.algotrader.mapper;

import com.algotrader.domain.model.TradeNote;
import com.algotrader.entity.TradeNoteEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between TradeNote domain model and TradeNoteEntity.
 * Straightforward 1:1 field mapping.
 */
@Mapper
public interface TradeNoteMapper {

    TradeNoteEntity toEntity(TradeNote tradeNote);

    TradeNote toDomain(TradeNoteEntity entity);

    List<TradeNote> toDomainList(List<TradeNoteEntity> entities);
}
