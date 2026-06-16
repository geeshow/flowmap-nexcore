package com.flowmap.nexcore.output;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Builds {@code _manifest.json} — one entry per per-service graph under
 * {@code <out-dir>/service/<service>/graph.json}, with node/edge counts, entry-point
 * tallies, per-module breakdown, and sibling artifact paths (openapi/impact). The graph
 * and sibling paths are recorded under their FLAT web names ({@code <service>.json} etc.)
 * so the manifest matches the flat layout {@code sync} writes into the web data dir.
 * Mirrors flowmap-spring's manifest shape.
 */
public final class Manifest {

    private Manifest() {
    }

    public static void write(Path outDir) {
        List<Map<String, Object>> projects = new ArrayList<>();
        List<Path> serviceDirs = new ArrayList<>();
        Path serviceRoot = outDir.resolve("service");
        if (Files.isDirectory(serviceRoot)) {
            try (Stream<Path> s = Files.list(serviceRoot)) {
                s.filter(Files::isDirectory)
                        .filter(d -> Files.isRegularFile(d.resolve("graph.json")))
                        .sorted()
                        .forEach(serviceDirs::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to list " + serviceRoot, e);
            }
        }

        for (Path dir : serviceDirs) {
            String name = dir.getFileName().toString();
            JsonNode root = JsonIO.read(dir.resolve("graph.json"));

            Map<String, Integer> entryPoints = new TreeMap<>();
            Map<String, int[]> moduleNodes = new LinkedHashMap<>();  // module → [nodes, entryPoints stored separately]
            Map<String, Map<String, Integer>> moduleEntry = new LinkedHashMap<>();
            int nodes = 0;
            for (JsonNode n : root.path("nodes")) {
                nodes++;
                String module = n.path("module").isNull() ? null : n.path("module").asText(null);
                if (module != null) moduleNodes.computeIfAbsent(module, k -> new int[]{0})[0]++;
                JsonNode ep = n.path("entryPoint");
                if (!ep.isNull() && !ep.isMissingNode()) {
                    entryPoints.merge(ep.asText(), 1, Integer::sum);
                    if (module != null) {
                        moduleEntry.computeIfAbsent(module, k -> new TreeMap<>())
                                .merge(ep.asText(), 1, Integer::sum);
                    }
                }
            }
            int edges = root.path("edges").size();

            Map<String, Object> proj = new LinkedHashMap<>();
            proj.put("name", name);
            proj.put("type", "backend");
            proj.put("graph", name + ".json");
            proj.put("openapi", sibling(dir, "openapi.json", name + ".openapi.json"));
            proj.put("impact", sibling(dir, "impact.json", name + ".impact.json"));
            proj.put("pulls", null);
            proj.put("join", null);
            proj.put("screens", null);
            proj.put("nodes", nodes);
            proj.put("edges", edges);
            proj.put("entryPoints", entryPoints);

            List<Map<String, Object>> modules = new ArrayList<>();
            for (Map.Entry<String, int[]> e : moduleNodes.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("nodes", e.getValue()[0]);
                m.put("entryPoints", moduleEntry.getOrDefault(e.getKey(), new TreeMap<>()));
                modules.add(m);
            }
            proj.put("modules", modules);
            proj.put("generated", Instant.now().toString());
            projects.add(proj);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1);
        manifest.put("generated", Instant.now().toString());
        manifest.put("projects", projects);
        JsonOutput.write(manifest, outDir.resolve("_manifest.json"));
    }

    /** Web name for a staging sibling, or null when the sibling file is absent. */
    private static String sibling(Path serviceDir, String stagingFile, String webName) {
        return Files.isRegularFile(serviceDir.resolve(stagingFile)) ? webName : null;
    }
}
