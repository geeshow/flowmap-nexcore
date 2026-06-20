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
 * Service-to-service combine. Merges per-project graphs and reconnects placeholders to the
 * real target node, re-labelling those edges {@code s2s}:
 * <ul>
 *   <li>{@code ext:SHARED#<comp>.<Unit>.<method>} — cross-project shared calls / batch→online,
 *       matched by {@code <Unit>#<method>} suffix;</li>
 *   <li>{@code ext:LINKED#<token>} — 연동거래 ({@code callService*}) whose token is a transaction
 *       id (possibly behind a {@code /std}/{@code /lng} prefix and/or {@code .jmd}), matched to the
 *       target ProcessUnit entry via its bare-token alias. Service-code targets stay external.</li>
 * </ul>
 * Shared resources ({@code db:table:*}, {@code kafka:*}) merge by id automatically.
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
            // nearest comp segment (e.g. `acgo0002` in `ac.acgo0002.FAC0011.fAC0011`), a hint to
            // disambiguate when several projects expose the same <Unit>#<method> suffix.
            String comp = parts.length >= 3 ? parts[parts.length - 3] : null;
            String realId = findReal(nodes.keySet(), unit, method, comp);
            if (realId != null) {
                edge.put("target", realId);
                edge.put("kind", "s2s");
            }
        }

        // reconnect ext:LINKED placeholders (연동거래 callService*) whose token resolves to a
        // ProcessUnit transaction in THIS run. A linked call carries a transaction id, often
        // behind a context prefix and/or .jmd extension (`/std/TACU0001.jmd`, `/lng/TACU0001`,
        // `TACU0001`); the bare token (`TACU0001`) is what each ProcessUnit entry declares as
        // an alias. Probe each path segment (sans .jmd) against the alias index — the prefix
        // segment (std/lng/…) misses, the token hits → reconnect to the real entry as s2s.
        // Linked targets that are service codes (e.g. `TXN_LIMIT_CHECK`, not a transaction)
        // miss entirely and stay external.
        Map<String, String> aliasToId = aliasIndex(nodes.values());
        for (Map<String, Object> edge : edges) {
            String target = (String) edge.get("target");
            if (target == null || !target.startsWith("ext:LINKED#")) continue;
            String realId = resolveJmdAlias(target.substring("ext:LINKED#".length()), aliasToId);
            if (realId != null) {
                edge.put("target", realId);
                edge.put("kind", "s2s");
            }
        }

        // drop ext:SHARED / ext:LINKED placeholder nodes no longer referenced (reconnected away)
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            referenced.add((String) edge.get("source"));
            referenced.add((String) edge.get("target"));
        }
        nodes.keySet().removeIf(id -> (id.startsWith("ext:SHARED#") || id.startsWith("ext:LINKED#"))
                && !referenced.contains(id));

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

    /**
     * Resolve a shared-call placeholder to its real provider node by {@code .<Unit>#<method>}
     * suffix. When the {@code comp} hint matches a candidate's id, that wins immediately. Without
     * a comp hit only an <em>unambiguous</em> single suffix match is accepted — multiple matches
     * with no comp tie-break stay external, avoiding a wrong cross-project {@code s2s} edge.
     */
    private static String findReal(Set<String> ids, String unit, String method, String comp) {
        String suffix = "." + unit + "#" + method;
        String picked = null;
        int matches = 0;
        for (String id : ids) {
            if (id.startsWith("ext:") || id.startsWith("db:") || id.startsWith("kafka:")) continue;
            if (!id.endsWith(suffix)) continue;
            if (comp != null && id.contains("." + comp + ".")) return id; // comp disambiguates
            if (picked == null) picked = id;
            matches++;
        }
        return matches == 1 ? picked : null;
    }

    /** Bare transaction token (a node's {@code aliases}) → real node id, for real (non-ext) nodes. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> aliasIndex(Iterable<Map<String, Object>> nodes) {
        Map<String, String> index = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = (String) node.get("id");
            if (id == null || id.startsWith("ext:") || id.startsWith("db:") || id.startsWith("kafka:")) continue;
            Object aliases = node.get("aliases");
            if (!(aliases instanceof List)) continue;
            for (Object a : (List<Object>) aliases) {
                if (a != null) index.putIfAbsent(String.valueOf(a), id);
            }
        }
        return index;
    }

    /**
     * Resolve a linked-call token to a real node via the alias index. Splits on {@code /},
     * strips a {@code .jmd} extension per segment, and returns the first segment that hits an
     * alias — so a context prefix ({@code std}/{@code lng}/…) is skipped and only the
     * transaction token ({@code TACU0001}) matches. Null when nothing matches (e.g. a service
     * code, not a transaction).
     */
    private static String resolveJmdAlias(String raw, Map<String, String> aliasToId) {
        if (raw == null || aliasToId.isEmpty()) return null;
        for (String seg : raw.split("/")) {
            if (seg.isEmpty()) continue;
            String token = seg.replaceAll("(?i)\\.jmd$", "");
            String id = aliasToId.get(token);
            if (id != null) return id;
        }
        return null;
    }
}
