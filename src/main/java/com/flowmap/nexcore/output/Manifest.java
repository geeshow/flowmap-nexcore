package com.flowmap.nexcore.output;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds {@code _manifest.json} — one entry per per-service graph under the nested layout
 * {@code <out-dir>/projects/<namespace>/<repo>/<perRoot>/graph.json}, with node/edge counts,
 * entry-point tallies, per-module breakdown, and sibling artifact paths (openapi/impact).
 * Graph + sibling paths are recorded RELATIVE to the data dir
 * ({@code projects/<namespace>/<repo>/<perRoot>/<perRoot>.json}) so the manifest matches the
 * nested layout {@code sync} writes into the web data dir. Mirrors flowmap-spring's manifest shape.
 */
public final class Manifest {

    private Manifest() {
    }

    /** Leaf project dirs under {@code <outDir>/projects}: dirs directly holding a {@code .json} (skips shard dirs). */
    private static List<Path> leafProjectDirs(Path outDir) {
        Path root = outDir.resolve("projects");
        List<Path> out = new ArrayList<>();
        collectLeaves(root, out);
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    private static void collectLeaves(Path d, List<Path> out) {
        if (!Files.isDirectory(d)) return;
        List<Path> children;
        try (Stream<Path> s = Files.list(d)) {
            children = s.collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list " + d, e);
        }
        if (children.stream().anyMatch(c -> Files.isRegularFile(c) && c.getFileName().toString().endsWith(".json")))
            out.add(d);
        for (Path c : children) {
            String n = c.getFileName().toString();
            if (Files.isDirectory(c) && !n.endsWith(".pulls") && !n.endsWith(".impact")) collectLeaves(c, out);
        }
    }

    public static void write(Path outDir) {
        List<Map<String, Object>> projects = new ArrayList<>();
        List<Path> leaves = leafProjectDirs(outDir);

        for (Path dir : leaves) {
            if (!Files.isRegularFile(dir.resolve("graph.json"))) continue;   // graph-less → impact-only pass below
            String name = dir.getFileName().toString();
            String rel = "projects/" + outDir.resolve("projects").relativize(dir).toString().replace(File.separatorChar, '/');
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

            JsonNode meta = root.path("meta");
            JsonNode gitRepo = meta.path("gitRepo");
            String repo = (gitRepo.isNull() || gitRepo.isMissingNode()) ? null : gitRepo.asText(null);
            JsonNode gitNs = meta.path("gitNamespace");
            String namespace = (gitNs.isNull() || gitNs.isMissingNode()) ? null : gitNs.asText(null);

            Map<String, Object> proj = new LinkedHashMap<>();
            proj.put("name", name);
            proj.put("type", "backend");
            proj.put("namespace", namespace);
            proj.put("repo", repo);   // 모노레포 마커(gitRepo) — 모듈↔repo 연결
            proj.put("graph", rel + "/" + name + ".json");
            proj.put("openapi", sibling(dir, "openapi.json", rel + "/" + name + ".openapi.json"));
            proj.put("impact", sibling(dir, "impact.json", rel + "/" + name + ".impact.json"));
            proj.put("pulls", sibling(dir, "pulls.json", rel + "/" + name + ".pulls.json"));
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

        // graph 없는 repo 단위 impact 엔트리(모노레포 pulls/impact 단위) — impact.json 만 있고 graph.json 은
        // 없는 leaf. spring 의 graphlessEntries 와 같은 역할(웹 commit/PR 뷰에서만 사용).
        Path projectsRoot = outDir.resolve("projects");
        for (Path d : leaves) {
            if (Files.isRegularFile(d.resolve("impact.json")) && !Files.isRegularFile(d.resolve("graph.json")))
                projects.add(impactOnlyEntry(d, projectsRoot));
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

    /** graph 없는 repo 단위 impact 엔트리 — namespace/repo 는 nested 경로에서 도출. */
    private static Map<String, Object> impactOnlyEntry(Path dir, Path projectsRoot) {
        String name = dir.getFileName().toString();
        String rel = "projects/" + projectsRoot.relativize(dir).toString().replace(File.separatorChar, '/');
        String[] seg = projectsRoot.relativize(dir).toString().replace(File.separatorChar, '/').split("/");
        String namespace = seg.length >= 3 ? seg[seg.length - 3] : null;
        String repo = seg.length >= 2 ? seg[seg.length - 2] : name;
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("name", name);
        proj.put("type", "backend");
        proj.put("namespace", namespace);
        proj.put("repo", repo);
        proj.put("graph", null);
        proj.put("openapi", null);
        proj.put("impact", rel + "/" + name + ".impact.json");
        proj.put("pulls", sibling(dir, "pulls.json", rel + "/" + name + ".pulls.json"));
        proj.put("join", null);
        proj.put("screens", null);
        proj.put("nodes", 0);
        proj.put("edges", 0);
        proj.put("entryPoints", new TreeMap<>());
        proj.put("modules", new ArrayList<>());
        proj.put("generated", Instant.now().toString());
        return proj;
    }
}
