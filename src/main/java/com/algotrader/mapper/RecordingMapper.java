package com.algotrader.mapper;

import com.algotrader.api.dto.response.RecordingInfoResponse;
import com.algotrader.api.dto.response.RecordingStatsResponse;
import com.algotrader.domain.model.RecordingInfo;
import com.algotrader.domain.model.RecordingStats;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for tick recording domain models <-> response DTOs.
 *
 * <p>Used by TickRecordingController to convert RecordingInfo and RecordingStats
 * domain models to their response DTO counterparts.
 */
@Mapper
public interface RecordingMapper {

    RecordingInfoResponse toResponse(RecordingInfo recordingInfo);

    List<RecordingInfoResponse> toResponseList(List<RecordingInfo> recordings);

    RecordingStatsResponse toResponse(RecordingStats recordingStats);
}
