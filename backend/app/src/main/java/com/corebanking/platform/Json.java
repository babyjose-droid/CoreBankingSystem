package com.corebanking.platform;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON for jsonb columns (approval payloads, audit detail). Uses the application's configured mapper. */
@Component
public class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public Map<String, Object> readMap(String json) {
        return json == null ? null : mapper.readValue(json, MAP);
    }

    public <T> T read(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public Map<String, Object> toMap(Object value) {
        return value == null ? null : mapper.convertValue(value, MAP);
    }
}
