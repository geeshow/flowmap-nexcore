package com.flowmap.nexcore.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowmap.nexcore.output.JsonIO;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-PR change-impact analysis, emitted as a LEAN INDEX + per-PR SHARDS — a faithful Java
 * port of flowmap-spring's {@code Impact} so nexcore produces the identical
 * {@code <svc>.impact.json} index and {@code <svc>.impact/<number>.json} shards (file paths
 * and schema).
 *
 * <p>Join model: a method is "changed" in a PR when the merge commit's changed CODE line
 * ranges (comment/blank-only lines ignored) fall inside the method's range at that revision
 * (re-parsed from the blob with JavaParser). "Changed API methods" lift each changed PRIVATE
 * method to its nearest non-private caller(s), which seed a reverse walk over the call graph
 * to the impacted endpoints (entry-point nodes).</p>
 *
 * <p>Endpoint model adaptation: spring collects {@code CONTROLLER}-layer nodes; NEXCORE
 * endpoints are ProcessUnit transactions, so the reverse walk collects nodes carrying an
 * {@code entryPoint} (same predicate the legacy commit-based impact used). Deletion/breaking
 * tracking is best-effort: {@code deletedNodes} are method ids gone after the merge;
 * endpoint-level deletion is left empty (it cannot be derived from the Java blob alone in the
 * NEXCORE {@code .uio}/{@code .jmd} model), so the deletion counts stay 0 — same as before.</p>
 */
public final class PrImpact {

    private PrImpact() {
    }

    /** Split output: the lean {@link #index} plus per-PR-number heavy {@link #shards}. */
    public static final class Result {
        public final LinkedHashMap<String, Object> index;
        public final LinkedHashMap<Integer, Map<String, Object>> shards;

        Result(LinkedHashMap<String, Object> index, LinkedHashMap<Integer, Map<String, Object>> shards) {
            this.index = index;
            this.shards = shards;
        }
    }

    public static Result analyze(GitLog git, String base, List<GitHub.Pr> pulls, Path graphFile) {
        JsonNode graph = JsonIO.read(graphFile);
        Map<String, JsonNode> nodeById = new LinkedHashMap<>();
        for (JsonNode n : graph.path("nodes")) nodeById.put(n.path("id").asText(), n);
        Map<String, List<String>> callers = new LinkedHashMap<>();   // target id -> source ids
        for (JsonNode e : graph.path("edges")) {
            callers.computeIfAbsent(e.path("target").asText(), k -> new ArrayList<>())
                    .add(e.path("source").asText());
        }
        String webBase = git.webBaseUrl();

        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17).setAttributeComments(false));

        List<Map<String, Object>> perPullIndex = new ArrayList<>();
        LinkedHashMap<Integer, Map<String, Object>> shards = new LinkedHashMap<>();
        Set<String> allChangedInGraph = new LinkedHashSet<>();
        Set<String> allImpacted = new LinkedHashSet<>();

        for (GitHub.Pr pr : pulls) {
            // Analyzed revision: merged PR → its merge/squash commit; open PR → its (fetched) head.
            String sha = pr.analyzedCommit();
            if (sha == null) continue;
            // Base side of the net diff: open PR → merge-base(base branch, head) so commits already on
            //   the base branch are excluded; merged PR → first parent (the pre-merge base). When the
            //   open PR head isn't available locally (fetch failed / no remote), the diff comes back
            //   empty and the PR still appears in the list with zero impacted endpoints.
            String parent;
            List<GitLog.FileChange> changes;
            if (pr.isOpen()) {
                parent = git.mergeBase(base, sha);
                changes = git.changesBetween(parent != null ? parent : base, sha);
            } else {
                parent = git.firstParent(sha);
                changes = git.changesIn(sha);
            }

            LinkedHashMap<String, Fn> changedFns = new LinkedHashMap<>();   // id -> first-seen range
            LinkedHashSet<String> deletedIds = new LinkedHashSet<>();

            for (GitLog.FileChange ch : changes) {
                if (!ch.path.endsWith(".java")) continue;
                String newText = "DELETE".equals(ch.changeType) ? null : git.fileAt(sha, ch.path);
                Parsed newParsed = newText == null ? Parsed.EMPTY : parseUnit(parser, newText);

                // changed methods: CODE hunks ∩ new-revision method ranges (comment/blank lines ignored).
                if (!"DELETE".equals(ch.changeType) && !ch.newRanges.isEmpty() && newText != null) {
                    Set<Integer> code = newParsed.codeLines;
                    Set<Integer> changedCode = new HashSet<>();
                    for (int[] r : ch.newRanges) {
                        for (int ln = r[0]; ln <= r[1]; ln++) if (code.contains(ln)) changedCode.add(ln);
                    }
                    if (!changedCode.isEmpty()) {
                        for (Fn fn : newParsed.fns) {
                            for (int ln : changedCode) {
                                if (ln >= fn.start && ln <= fn.end) {
                                    changedFns.putIfAbsent(fn.nodeId, fn);
                                    break;
                                }
                            }
                        }
                    }
                }

                // deleted methods: present in the PR's base blob, gone after the merge.
                // Guard: only trust the "gone" set when the file is an outright DELETE or the new
                // blob PARSED — else a new blob that failed to parse (e.g. a syntactically broken
                // commit) would yield an empty new-method set and falsely flag every old method as
                // deleted. (A genuinely valid file with zero methods still parses, so real deletions
                // are still detected.)
                if (parent != null && ("DELETE".equals(ch.changeType) || newParsed.parsed)) {
                    String oldPath = ch.oldPath != null ? ch.oldPath : ch.path;
                    String oldText = git.fileAt(parent, oldPath);
                    List<Fn> oldFns = oldText == null ? List.of() : parseUnit(parser, oldText).fns;
                    Set<String> newIds = new HashSet<>();
                    for (Fn fn : newParsed.fns) newIds.add(fn.nodeId);
                    for (Fn fn : oldFns) if (!newIds.contains(fn.nodeId)) deletedIds.add(fn.nodeId);
                }
            }

            for (String id : changedFns.keySet()) if (nodeById.containsKey(id)) allChangedInGraph.add(id);

            List<Map<String, Object>> changedNodes = new ArrayList<>();
            List<String> directApi = new ArrayList<>();
            List<String> privateChanged = new ArrayList<>();
            for (Fn fn : changedFns.values()) {
                String vis = visOf(fn, nodeById);
                Map<String, Object> cn = new LinkedHashMap<>();
                cn.put("id", fn.nodeId);
                cn.put("inGraph", nodeById.containsKey(fn.nodeId));
                cn.put("visibility", vis);
                changedNodes.add(cn);
                if (!"private".equals(vis)) directApi.add(fn.nodeId);
                else if (nodeById.containsKey(fn.nodeId)) privateChanged.add(fn.nodeId);
            }

            LinkedHashSet<String> changedApi = new LinkedHashSet<>(directApi);
            changedApi.addAll(liftToApi(privateChanged, callers, nodeById));
            List<String> seeds = new ArrayList<>();
            for (String id : changedApi) if (nodeById.containsKey(id)) seeds.add(id);
            List<JsonNode> impacted = impactedEndpoints(seeds, callers, nodeById);
            for (JsonNode n : impacted) allImpacted.add(n.path("id").asText());

            // LEAN index row: list/overview data only.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", pr.number);
            row.put("title", pr.title);
            row.put("author", pr.author);
            row.put("mergedAt", pr.mergedAt);
            row.put("updatedAt", pr.updatedAt);
            row.put("status", pr.status);
            row.put("mergeCommit", sha);
            row.put("changedNodeCount", changedFns.size());
            row.put("changedFileCount", changes.size());
            List<Map<String, Object>> eps = new ArrayList<>();
            for (JsonNode n : impacted) eps.add(endpointRef(n));
            row.put("impactedEndpoints", eps);
            perPullIndex.add(row);

            // HEAVY shard: full per-PR detail, lazy-loaded on commit open.
            List<String> changedFiles = new ArrayList<>();
            for (GitLog.FileChange ch : changes) changedFiles.add(ch.path);
            Map<String, Object> shard = new LinkedHashMap<>();
            shard.put("number", pr.number);
            shard.put("status", pr.status);
            shard.put("mergeCommit", sha);
            shard.put("changedFiles", changedFiles);
            shard.put("changedNodes", changedNodes);
            shard.put("changedApiMethods", new ArrayList<>(changedApi));
            shard.put("deletedNodes", new ArrayList<>(deletedIds));
            shard.put("deletedEndpoints", new ArrayList<>());
            shards.put(pr.number, shard);
        }

        LinkedHashMap<String, Object> index = new LinkedHashMap<>();
        index.put("base", base);
        index.put("repoUrl", webBase);
        index.put("pullCount", pulls.size());
        index.put("changedNodeCount", allChangedInGraph.size());
        index.put("impactedEndpointCount", allImpacted.size());
        index.put("deletedEndpointCount", 0);
        index.put("trulyDeletedEndpointCount", 0);
        index.put("breakingDeletionCount", 0);
        index.put("pulls", perPullIndex);
        index.put("deletedEndpoints", new ArrayList<>());
        return new Result(index, shards);
    }

    /**
     * Lift each changed PRIVATE method to the nearest NON-PRIVATE method(s) that (transitively)
     * call it: walk UP the callers graph, stopping at the first non-private node on each branch
     * and continuing through private callers. Cycle-safe via the visited set.
     */
    private static List<String> liftToApi(List<String> privateSeeds,
                                          Map<String, List<String>> callers, Map<String, JsonNode> nodeById) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>(privateSeeds);
        Deque<String> stack = new ArrayDeque<>(privateSeeds);
        while (!stack.isEmpty()) {
            String cur = stack.removeLast();
            for (String src : callers.getOrDefault(cur, List.of())) {
                if (!seen.add(src)) continue;
                JsonNode n = nodeById.get(src);
                if (n == null) continue;
                if (!"private".equals(n.path("visibility").asText("public"))) out.add(src);
                else stack.addLast(src);
            }
        }
        return new ArrayList<>(out);
    }

    /** Reverse-walk callers from {@code seeds}; collect the endpoint nodes reached (seed included). */
    private static List<JsonNode> impactedEndpoints(List<String> seeds,
                                                    Map<String, List<String>> callers, Map<String, JsonNode> nodeById) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String s : seeds) if (nodeById.containsKey(s) && seen.add(s)) stack.addLast(s);
        while (!stack.isEmpty()) {
            String cur = stack.removeLast();
            JsonNode n = nodeById.get(cur);
            if (n != null && isEndpoint(n)) found.add(cur);
            for (String src : callers.getOrDefault(cur, List.of())) if (seen.add(src)) stack.addLast(src);
        }
        List<JsonNode> out = new ArrayList<>();
        for (String id : found) out.add(nodeById.get(id));
        return out;
    }

    private static boolean isEndpoint(JsonNode n) {
        JsonNode ep = n.path("entryPoint");
        return !ep.isNull() && !ep.isMissingNode();
    }

    private static Map<String, Object> endpointRef(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.path("id").asText());
        m.put("httpMethod", textOrNull(n, "httpMethod"));
        m.put("endpoint", textOrNull(n, "endpoint"));
        String svc = textOrNull(n, "project");
        if (svc == null) svc = textOrNull(n, "externalService");
        m.put("service", svc);
        return m;
    }

    private static String visOf(Fn fn, Map<String, JsonNode> nodeById) {
        JsonNode n = nodeById.get(fn.nodeId);
        if (n != null) {
            String v = textOrNull(n, "visibility");
            if (v != null) return v;
        }
        return fn.visibility;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    // ---------- Java method-range + code-line parsing ----------

    private static final class Fn {
        final String nodeId;
        final String visibility;
        final int start;
        final int end;

        Fn(String nodeId, String visibility, int start, int end) {
            this.nodeId = nodeId;
            this.visibility = visibility;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Parsed {
        static final Parsed EMPTY = new Parsed(List.of(), Set.of(), false);
        final List<Fn> fns;
        final Set<Integer> codeLines;
        final boolean parsed;   // true only when JavaParser succeeded (distinguishes "no methods" from "parse failed")

        Parsed(List<Fn> fns, Set<Integer> codeLines, boolean parsed) {
            this.fns = fns;
            this.codeLines = codeLines;
            this.parsed = parsed;
        }
    }

    /**
     * Parse Java source once: collect method ranges (id {@code <fqcn>#<method>}, visibility,
     * begin/end line) and the set of CODE lines (non-blank, minus comment-only lines), so a
     * comment- or whitespace-only edit does not count as a changed method.
     */
    private static Parsed parseUnit(JavaParser parser, String src) {
        ParseResult<CompilationUnit> res = parser.parse(src);
        if (!res.isSuccessful() || res.getResult().isEmpty()) return Parsed.EMPTY;
        CompilationUnit cu = res.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<Fn> fns = new ArrayList<>();
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            for (MethodDeclaration m : cls.getMethods()) {
                if (m.getRange().isEmpty()) continue;
                fns.add(new Fn(fqcn + "#" + m.getNameAsString(),
                        m.isPrivate() ? "private" : "public",
                        m.getRange().get().begin.line, m.getRange().get().end.line));
            }
        }
        Set<Integer> code = new HashSet<>();
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) if (!lines[i].isBlank()) code.add(i + 1);
        for (Comment c : cu.getAllContainedComments()) {
            if (c.getRange().isEmpty()) continue;
            int s = c.getRange().get().begin.line;
            int e = c.getRange().get().end.line;
            for (int ln = s; ln <= e; ln++) {
                if (ln > s && ln < e) {
                    code.remove(ln);   // interior comment lines never carry code
                } else {
                    String t = (ln - 1 < lines.length) ? lines[ln - 1].trim() : "";
                    // boundary line: drop only when it is purely a comment (no code before/after)
                    if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.equals("*/")) {
                        code.remove(ln);
                    }
                }
            }
        }
        return new Parsed(fns, code, true);
    }
}
