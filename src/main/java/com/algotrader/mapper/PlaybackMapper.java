package com.algotrader.mapper;

import com.algotrader.api.dto.response.PlaybackSessionResponse;
import com.algotrader.simulator.PlaybackSession;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for PlaybackSession domain model <-> response DTO.
 *
 * <p>Used by TickReplayController to convert the internal PlaybackSession to
 * the API response format, including computed fields like progressPercent.
 */
@Mapper
public interface PlaybackMapper {

    @Mapping(target = "status", expression = "java(session.getStatus().name())")
    @Mapping(
            target = "progressPercent",
            expression =
                    "java(session.getTotalTicks() > 0 ? (session.getTicksProcessed() * 100) / session.getTotalTicks() : 0)")
    PlaybackSessionResponse toResponse(PlaybackSession session);
}
