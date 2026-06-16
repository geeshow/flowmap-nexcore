package com.flowmap.nexcore.combine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowmap.nexcore.output.JsonIO;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service-to-service combine. Merges per-project graphs and reconnects
 * {@code ext:SHARED#<comp>.<Unit>.<method>} placeholders (cross-project shared calls /
 * batch→online) to the real unit node, re-labelling those edges {@code s2s}. Shared
 * resources ({@code db:table:*}, {@code kafka:*}) merge by id automatically.
 */
public final class CrossRun {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CrossRun() {
    }

    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, Object> combine(List<Path> graphs) {
        LinkedHashMap<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<List<Object>> edgeKeys = new LinkedHashSet<>();

        for (Path g : graphs) {
            JsonNode root = JsonIO.read(g);
            for (JsonNode n : root.path("nodes")) {
                Map<String, Object> node = MAPPER.convertValue(n, LinkedHashMap.class);
                nodes.putIfAbsent((String) node.get("id"), node);
            }
            for (JsonNode e : root.path("edges")) {
                Map<String, Object> edge = MAPPER.convertValue(e, LinkedHashMap.class);
                List<Object> key = List.of(
                        String.valueOf(edge.get("source")), String.valueOf(edge.get("target")),
                        String.valueOf(edge.get("relation")), String.valueOf(edge.get("callSiteLine")));
                if (edgeKeys.add(key)) edges.add(edge);
            }
        }

        // reconnect ext:SHARED placeholders
        for (Map<String, Object> edge : edges) {
            String target = (String) edge.get("target");
            if (target == null || !target.startsWith("ext:SHARED#")) continue;
            String spec = target.substring("ext:SHARED#".length());
            String[] parts = spec.split("\\.");
            if (parts.length < 2) continue;
            String unit = parts[parts.length - 2];
            String method = parts[parts.length - 1];
            String suffix = "." + unit + "#" + method;
            String realId = findReal(nodes.keySet(), suffix);
            if (realId != null) {
                edge.put("target", realId);
                edge.put("kind", "s2s");
            }
        }

        // drop ext:SHARED nodes no longer referenced
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            referenced.add((String) edge.get("source"));
            referenced.add((String) edge.get("target"));
        }
        nodes.keySet().removeIf(id -> id.startsWith("ext:SHARED#") && !referenced.contains(id));

        long s2s = edges.stream().filter(e -> "s2s".equals(e.get("kind"))).count();

        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("command", "combine");
        meta.put("nodes", nodes.size());
        meta.put("edges", edges.size());
        meta.put("s2sEdges", (int) s2s);

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("directed", true);
        out.put("multigraph", true);
        out.put("meta", meta);
        out.put("nodes", new ArrayList<>(nodes.values()));
        out.put("edges", edges);
        return out;
    }

    private static String findReal(Set<String> ids, String suffix) {
        for (String id : ids) {
            if (id.startsWith("ext:") || id.startsWith("db:") || id.startsWith("kafka:")) continue;
            if (id.endsWith(suffix)) return id;
        }
        return null;
    }
}
