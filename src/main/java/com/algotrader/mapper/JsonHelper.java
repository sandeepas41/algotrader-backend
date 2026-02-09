package com.algotrader.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility for JSON serialization/deserialization used by MapStruct mappers.
 *
 * <p>MapStruct mappers use this to convert between domain objects (StrikeSelection,
 * AdjustmentTrigger, AdjustmentAction, List&lt;PnLSegment&gt;) and their JSON string
 * representation stored in entity columns marked with columnDefinition = "JSON".
 *
 * <p>Uses a shared ObjectMapper instance configured for ISO-8601 dates (Jackson 3 default).
 */
public final class JsonHelper {

    private static final Logger log = LoggerFactory.getLogger(JsonHelper.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.findAndRegisterModules();
    }

    private JsonHelper() {}

    /** Serialize an object to JSON string. Returns null if input is null. */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize to JSON: {}", value.getClass().getSimpleName(), e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    /** Deserialize a JSON string to an object. Returns null if input is null or empty. */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to {}: {}", type.getSimpleName(), json, e);
            throw new IllegalStateException("JSON deserialization failed", e);
        }
    }

    /** Deserialize a JSON string to a generic type (e.g., List&lt;PnLSegment&gt;). */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to {}: {}", typeRef.getType(), json, e);
            throw new IllegalStateException("JSON deserialization failed", e);
        }
    }

    /** Deserialize a JSON array string to a List. Returns empty list if input is null. */
    public static <T> List<T> fromJsonList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(
                    json, OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON list of {}: {}", elementType.getSimpleName(), json, e);
            throw new IllegalStateException("JSON list deserialization failed", e);
        }
    }
}
