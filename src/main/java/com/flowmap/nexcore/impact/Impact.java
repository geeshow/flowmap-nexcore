package com.flowmap.nexcore.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowmap.nexcore.output.JsonIO;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Change-impact analysis. Mines git commits → changed lines → changed methods (by
 * reparsing the blob at that revision) → joins to the current call graph and walks
 * callers (reverse BFS) to the affected ProcessUnit transactions. Same model as
 * flowmap-spring's {@code Impact} (recent-range → current-graph).
 */
public final class Impact {

    private Impact() {
    }

    public static LinkedHashMap<String, Object> run(GitLog git, Path graphFile,
                                                    String branch, int max, int depth, String range) {
        JsonNode graph = JsonIO.read(graphFile);

        // node metadata + callers adjacency
        Map<String, JsonNode> nodeById = new LinkedHashMap<>();
        for (JsonNode n : graph.path("nodes")) nodeById.put(n.path("id").asText(), n);
        Map<String, List<String>> callers = new LinkedHashMap<>();
        for (JsonNode e : graph.path("edges")) {
            String s = e.path("source").asText();
            String t = e.path("target").asText();
            callers.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
        }

        String resolvedBranch = git.resolveBranch(branch);
        List<GitLog.Commit> commits;
        try {
            commits = git.commits(resolvedBranch, max, range);
        } catch (Exception ex) {
            commits = new ArrayList<>();
        }

        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17).setAttributeComments(false));

        List<Map<String, Object>> commitJson = new ArrayList<>();
        Set<String> allChangedNodeIds = new LinkedHashSet<>();
        Set<String> subgraphIds = new LinkedHashSet<>();
        Map<String, Set<String>> endpointCommits = new LinkedHashMap<>();

        for (GitLog.Commit c : commits) {
            Set<String> changed = new LinkedHashSet<>();
            for (String file : c.changedFiles) {
                if (!file.endsWith(".java")) continue;
                List<int[]> hunks = c.hunks.getOrDefault(file, List.of());
                if (hunks.isEmpty()) continue;
                String src = git.blob(c.sha, file);
                if (src == null || src.isBlank()) continue;
                changed.addAll(changedMethods(parser, src, hunks));
            }
            allChangedNodeIds.addAll(changed);

            // reverse BFS to endpoints; a changed node that is itself an endpoint also counts
            Set<String> reached = bfsCallers(changed, callers, depth);
            Set<String> affected = new LinkedHashSet<>(changed);
            affected.addAll(reached);
            subgraphIds.addAll(affected);

            List<Map<String, Object>> changedNodes = new ArrayList<>();
            for (String id : changed) {
                Map<String, Object> cn = new LinkedHashMap<>();
                cn.put("id", id);
                cn.put("inGraph", nodeById.containsKey(id));
                changedNodes.add(cn);
            }

            List<Map<String, Object>> impactedEndpoints = new ArrayList<>();
            Set<String> impactedServices = new TreeSet<>();
            for (String id : affected) {
                JsonNode n = nodeById.get(id);
                if (n == null) continue;
                if (n.path("entryPoint").isNull() || n.path("entryPoint").isMissingNode()) continue;
                Map<String, Object> ep = endpointEntry(id, n);
                impactedEndpoints.add(ep);
                if (ep.get("service") != null) impactedServices.add((String) ep.get("service"));
                endpointCommits.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(c.shortSha);
            }

            Map<String, Object> cj = new LinkedHashMap<>();
            cj.put("sha", c.sha);
            cj.put("shortSha", c.shortSha);
            cj.put("author", c.author);
            cj.put("date", c.date);
            cj.put("subject", c.subject);
            cj.put("changedFiles", c.changedFiles);
            cj.put("changedNodes", changedNodes);
            cj.put("deletedNodes", new ArrayList<>());
            cj.put("deletedEndpoints", new ArrayList<>());
            cj.put("impactedEndpoints", impactedEndpoints);
            cj.put("impactedServices", new ArrayList<>(impactedServices));
            commitJson.add(cj);
        }

        // endpointImpact aggregate
        List<Map<String, Object>> endpointImpact = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : endpointCommits.entrySet()) {
            JsonNode n = nodeById.get(e.getKey());
            Map<String, Object> ep = endpointEntry(e.getKey(), n);
            ep.put("commits", new ArrayList<>(e.getValue()));
            endpointImpact.add(ep);
        }
        endpointImpact.sort((a, b) -> Integer.compare(
                ((List<?>) b.get("commits")).size(), ((List<?>) a.get("commits")).size()));

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("branch", resolvedBranch);
        out.put("commitCount", commits.size());
        out.put("depth", depth);
        out.put("changedNodeCount", allChangedNodeIds.size());
        out.put("deletedEndpointCount", 0);
        out.put("trulyDeletedEndpointCount", 0);
        out.put("breakingDeletionCount", 0);
        out.put("commits", commitJson);
        out.put("subgraph", subgraph(graph, subgraphIds));
        out.put("endpointImpact", endpointImpact);
        out.put("deletedEndpoints", new ArrayList<>());
        return out;
    }

    private static Set<String> changedMethods(JavaParser parser, String src, List<int[]> hunks) {
        Set<String> ids = new LinkedHashSet<>();
        ParseResult<CompilationUnit> res = parser.parse(src);
        if (!res.isSuccessful() || res.getResult().isEmpty()) return ids;
        CompilationUnit cu = res.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            for (MethodDeclaration m : cls.getMethods()) {
                if (m.getRange().isEmpty()) continue;
                int mb = m.getRange().get().begin.line;
                int me = m.getRange().get().end.line;
                for (int[] h : hunks) {
                    if (mb <= h[1] && h[0] <= me) {
                        ids.add(fqcn + "#" + m.getNameAsString());
                        break;
                    }
                }
            }
        }
        return ids;
    }

    private static Set<String> bfsCallers(Set<String> seeds, Map<String, List<String>> callers, int depth) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(seeds);
        Set<String> visited = new LinkedHashSet<>(seeds);
        int d = 0;
        while (!frontier.isEmpty() && d < depth) {
            int sz = frontier.size();
            for (int i = 0; i < sz; i++) {
                String cur = frontier.poll();
                for (String caller : callers.getOrDefault(cur, List.of())) {
                    if (visited.add(caller)) {
                        reached.add(caller);
                        frontier.add(caller);
                    }
                }
            }
            d++;
        }
        return reached;
    }

    private static Map<String, Object> endpointEntry(String id, JsonNode n) {
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("id", id);
        ep.put("httpMethod", n == null ? null : textOrNull(n, "httpMethod"));
        ep.put("endpoint", n == null ? null : textOrNull(n, "endpoint"));
        ep.put("service", n == null ? null : textOrNull(n, "project"));
        ep.put("description", n == null ? null : textOrNull(n, "description"));
        return ep;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static LinkedHashMap<String, Object> subgraph(JsonNode graph, Set<String> ids) {
        List<Object> nodes = new ArrayList<>();
        for (JsonNode n : graph.path("nodes")) {
            if (ids.contains(n.path("id").asText())) nodes.add(toMap(n));
        }
        List<Object> edges = new ArrayList<>();
        for (JsonNode e : graph.path("edges")) {
            if (ids.contains(e.path("source").asText()) && ids.contains(e.path("target").asText())) {
                edges.add(toMap(e));
            }
        }
        LinkedHashMap<String, Object> g = new LinkedHashMap<>();
        g.put("directed", true);
        g.put("multigraph", true);
        g.put("meta", new LinkedHashMap<>());
        g.put("nodes", nodes);
        g.put("edges", edges);
        return g;
    }

    @SuppressWarnings("unchecked")
    private static Object toMap(JsonNode n) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(n, LinkedHashMap.class);
    }
}
