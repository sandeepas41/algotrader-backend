package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.OrderSide;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for a new leg definition within a MorphTargetDto.
 *
 * <p>Specifies the strike, option type, side, and lots for a new position
 * to open as part of a morph operation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewLegDefinitionDto {
    private BigDecimal strike;
    private InstrumentType optionType;
    private OrderSide side;
    private Integer lots;
}
