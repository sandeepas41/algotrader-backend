package com.algotrader.mapper;

import com.algotrader.api.dto.response.CandleResponse;
import com.algotrader.timeseries.Candle;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between Candle domain model and CandleResponse DTO.
 *
 * <p>The Candle domain model includes instrumentToken and interval fields that are
 * omitted from the response DTO (the client already knows which instrument and
 * interval it requested).
 */
@Mapper
public interface CandleMapper {

    CandleResponse toResponse(Candle candle);

    List<CandleResponse> toResponseList(List<Candle> candles);
}
