package com.idm.test.engine.spi;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where every engine JSON mapper starts. Jackson 3 changed some defaults; these settings keep
 * the results file, the report and the evidence exactly as they were under Jackson 2, which
 * their readers depend on.
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /** A builder with the engine's shared settings; callers add their own features and build. */
    public static JsonMapper.Builder builder() {
        return JsonMapper.builder()
                // Jackson 3 sorts properties alphabetically by default; ours keep declaration order.
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                // Jackson 3 rejects content after the first value; Jackson 2 read the first value and
                // ignored the rest, and bodies are parsed that way. Strict callers re-enable it.
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
