package com.algotrader.mapper;

import com.algotrader.domain.model.NotificationPreference;
import com.algotrader.entity.NotificationPreferenceEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between NotificationPreference domain model and NotificationPreferenceEntity.
 * Straightforward 1:1 field mapping.
 */
@Mapper
public interface NotificationPreferenceMapper {

    NotificationPreferenceEntity toEntity(NotificationPreference notificationPreference);

    NotificationPreference toDomain(NotificationPreferenceEntity entity);

    List<NotificationPreference> toDomainList(List<NotificationPreferenceEntity> entities);
}
