package com.flowmap.nexcore.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

/** Reads JSON files (graphs/openapi) for combine/manifest/impact stages. */
public final class JsonIO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIO() {
    }

    public static JsonNode read(Path p) {
        try {
            return MAPPER.readTree(p.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read JSON " + p, e);
        }
    }
}
