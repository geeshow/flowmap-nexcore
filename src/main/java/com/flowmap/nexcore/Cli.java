package com.flowmap.nexcore;

import com.flowmap.nexcore.combine.CrossRun;
import com.flowmap.nexcore.impact.GitHub;
import com.flowmap.nexcore.impact.GitLog;
import com.flowmap.nexcore.impact.Impact;
import com.flowmap.nexcore.impact.PrImpact;
import com.flowmap.nexcore.model.CallGraph;
import com.flowmap.nexcore.model.MethodNode;
import com.flowmap.nexcore.nexcore.GraphBuilder;
import com.flowmap.nexcore.nexcore.SourceScanner;
import com.flowmap.nexcore.openapi.OpenApi;
import com.flowmap.nexcore.output.JsonIO;
import com.flowmap.nexcore.output.JsonOutput;
import com.flowmap.nexcore.output.Manifest;
import com.flowmap.nexcore.util.Args;
import com.flowmap.nexcore.util.Config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Command-line front end. Mirrors flowmap-spring's commands:
 * {@code analyze} · {@code combine} · {@code openapi} · {@code impact} · {@code refresh} · {@code sync}.
 * With no args, reads {@code flowmap.config}.
 *
 * <p>Per-project artifacts live under a nested per-service directory:
 * {@code <out-dir>/projects/<namespace>/<repo>/<perRoot>/graph.json} (+ {@code openapi.json} /
 * {@code impact.json}). The {@code _combined.json} / {@code _openapi.json} / {@code _manifest.json}
 * aggregates stay at the {@code out-dir} root. {@code sync} mirrors the nested per-service tree into
 * the web data dir, renaming inner files to {@code <perRoot>.json} / {@code .openapi.json} / etc.
 */
public final class Cli {

    private static final String DEFAULT_REPO = "../nexcore";
    private static final String DEFAULT_OUT_DIR = "./json";

    private Cli() {
    }

    public static void run(String[] argv) throws Exception {
        if (argv.length == 0) {
            String[] fromConfig = Config.toArgs(Path.of("."));
            if (fromConfig != null) argv = fromConfig;
            else argv = new String[]{"refresh"};
        }
        Args args = Args.parse(argv);
        switch (args.command) {
            case "analyze": analyze(args); break;
            case "combine": combine(args); break;
            case "openapi": openapi(args); break;
            case "impact": impact(args); break;
            case "refresh": refresh(args); break;
            case "sync": sync(args); break;
            default:
                System.err.println("Unknown command: " + args.command
                        + " (analyze|combine|openapi|impact|refresh|sync)");
        }
    }

    // ---------- analyze ----------

    private static void analyze(Args args) {
        Path repo = Path.of(args.get("repo", DEFAULT_REPO));
        String project = args.get("project");
        Path out = args.get("out") == null ? null : Path.of(args.get("out"));
        SourceScanner.Scan scan = SourceScanner.scan(repo, project);
        CallGraph graph = GraphBuilder.build(scan);
        Map<String, Object> meta = analyzeMeta(repo.toString(), project, scan, graph);
        JsonOutput.write(graph.toNodeLink(meta), out);
        if (out != null) System.out.println("analyze → " + out + " ("
                + graph.nodes().size() + " nodes, " + graph.edges().size() + " edges)");
    }

    private static Map<String, Object> analyzeMeta(String repo, String project,
                                                   SourceScanner.Scan scan, CallGraph graph) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("command", "analyze");
        meta.put("repo", repo);
        meta.put("project", project);
        meta.put("files", scan.javaFiles.size());
        meta.put("nodes", graph.nodes().size());
        meta.put("edges", graph.edges().size());
        return meta;
    }

    // ---------- combine ----------

    private static void combine(Args args) throws IOException {
        Path dir = Path.of(args.get("dir", DEFAULT_OUT_DIR));
        Path out = Path.of(args.get("out", dir.resolve("_combined.json").toString()));
        List<Path> graphs = graphFiles(dir);
        LinkedHashMap<String, Object> combined = CrossRun.combine(graphs);
        JsonOutput.write(combined, out);
        System.out.println("combine → " + out + " (" + graphs.size() + " graphs)");
    }

    // ---------- openapi ----------

    private static void openapi(Args args) {
        Path repo = Path.of(args.get("repo", DEFAULT_REPO));
        String project = args.get("project");
        String title = args.get("title", project != null ? project : "flowmap-nexcore");
        String version = args.get("api-version", "1.0.0");
        Path out = args.get("out") == null ? null : Path.of(args.get("out"));
        SourceScanner.Scan scan = SourceScanner.scan(repo, project);
        LinkedHashMap<String, Object> doc = OpenApi.generate(scan, repo, title, version);
        JsonOutput.write(doc, out);
        if (out != null) System.out.println("openapi → " + out);
    }

    // ---------- impact ----------

    private static void impact(Args args) {
        Path gitRepo = Path.of(args.get("git", args.get("repo", DEFAULT_REPO)));
        String graph = args.get("graph");
        if (graph == null) {
            System.err.println("impact requires --graph <graph.json>");
            return;
        }
        Path out = args.get("out") == null ? null : Path.of(args.get("out"));
        GitLog git = new GitLog(gitRepo);
        if (!git.isGitRepo()) {
            System.err.println("impact: " + gitRepo + " is not a git repository — skipping");
            return;
        }
        LinkedHashMap<String, Object> result = Impact.run(git, Path.of(graph),
                args.get("branch"), args.getInt("max", 50), args.getInt("depth", 3), args.get("range"));
        JsonOutput.write(result, out);
        if (out != null) System.out.println("impact → " + out);
    }

    // ---------- refresh ----------

    private static void refresh(Args args) throws IOException {
        Path repo = Path.of(args.get("repo", DEFAULT_REPO));
        Path outDir = Path.of(args.get("out-dir", DEFAULT_OUT_DIR));
        Files.createDirectories(outDir);
        boolean doImpact = !args.has("no-impact");
        int impactMax = args.getInt("impact-max", 50);
        int impactDepth = args.getInt("impact-depth", 3);
        String allTitle = args.get("title", "flowmap-nexcore-all");

        // One repo-wide scan → global index, so cross-project shared calls resolve to real
        // s2s edges inside each per-project graph (the web merges graphs by node id).
        SourceScanner.Scan all = SourceScanner.scan(repo, null);
        com.flowmap.nexcore.nexcore.UnitIndex global = new com.flowmap.nexcore.nexcore.UnitIndex(all);

        // NEXCORE 는 한 work-tree(= git repo) 아래 bizunit 모듈이 모여 있는 모노레포다. 따라서 산출물
        //   계층은 projects/<namespace>/<repo>/<perRoot> = <namespace>/nexcore/<bizunit> 가 된다:
        //   repo 슬롯엔 실제 git repo 명(work-tree basename, 보통 "nexcore"), perRoot 슬롯엔 bizunit.
        //   pulls/impact 는 여전히 bizunit 그래프별 1벌(각 모듈에 닿는 PR 변경만 귀속).
        GitLog git = new GitLog(repo);
        boolean isGit = git.isGitRepo();
        // repo: 실제 git work-tree basename (예: "nexcore") — gitRepo 마커이자 경로의 repo 슬롯.
        String repoName = git.repoName();
        // namespace: --namespace(flowmap.config NAMESPACE) > origin owner > repoName 폴백.
        //   nexcore 샘플은 origin remote 가 없어 config 의 NAMESPACE 로 실제 소속을 지정한다.
        String namespace = args.get("namespace");
        if (namespace == null || namespace.isBlank()) namespace = git.namespace();
        if (namespace == null || namespace.isBlank()) namespace = repoName;
        List<String> projects = discoverProjects(repo);

        List<String> built = new ArrayList<>();
        Map<String, String[]> nsRepoOf = new LinkedHashMap<>();   // project → {namespace, repo}
        Map<String, Path> gitRootOf = new LinkedHashMap<>();      // project → pulls/impact git work-tree
        for (String project : projects) {
            SourceScanner.Scan scan = SourceScanner.scan(repo, project);
            if (scan.units.isEmpty()) continue; // no ghost graphs
            CallGraph graph = GraphBuilder.buildProject(global, project);

            // 개별 프로젝트 .git 최우선: repo/<project>/.git 이 그 프로젝트 자신의 git 이면 namespace/repo·
            //   파일경로·pulls/impact 를 그 git 기준으로 도출한다. 없으면 work-tree(모노레포) 폴백.
            Path projRoot = repo.resolve(project);
            boolean ownGit = Files.exists(projRoot.resolve(".git"));   // 부모 work-tree 가 아닌 "자신의" .git
            GitLog projGit = ownGit ? new GitLog(projRoot) : null;
            String pNs, pRepo; Path pGitRoot; boolean pIsGit;
            if (projGit != null && projGit.isGitRepo()) {
                pRepo = projGit.repoSlug();
                pNs = args.get("namespace");
                if (pNs == null || pNs.isBlank()) pNs = projGit.namespace();
                if (pNs == null || pNs.isBlank()) pNs = pRepo;
                pGitRoot = projRoot; pIsGit = true;
                // 파일 경로 재기준화: work-tree 상대("<project>/src/…") → 프로젝트 루트 상대("src/…")
                String prefix = project + "/";
                for (MethodNode n : graph.nodes())
                    if (n.file != null && n.file.startsWith(prefix)) n.file = n.file.substring(prefix.length());
            } else {
                pNs = namespace; pRepo = repoName; pGitRoot = repo; pIsGit = isGit;
            }

            Path svc = serviceDir(outDir, pNs, pRepo, project);
            Map<String, Object> meta = analyzeMeta(repo.toString(), project, scan, graph);
            if (pIsGit) { meta.put("gitNamespace", pNs); meta.put("gitRepo", pRepo); }   // → manifest.namespace/repo
            JsonOutput.write(graph.toNodeLink(meta), svc.resolve("graph.json"));
            LinkedHashMap<String, Object> doc = OpenApi.generate(scan, repo, project, "1.0.0");
            JsonOutput.write(doc, svc.resolve("openapi.json"));
            built.add(project);
            nsRepoOf.put(project, new String[]{pNs, pRepo});
            gitRootOf.put(project, pGitRoot);
            System.out.println("  analyzed " + project + " (" + graph.nodes().size()
                    + " nodes, " + graph.edges().size() + " edges) → " + pNs + "/" + pRepo);
        }

        // combined graph + repo-wide openapi (combine is now mostly a merge; s2s already in graphs)
        List<Path> graphs = graphFiles(outDir);
        JsonOutput.write(CrossRun.combine(graphs), outDir.resolve("_combined.json"));
        JsonOutput.write(OpenApi.generate(all, repo, allTitle, "1.0.0"), outDir.resolve("_openapi.json"));

        // impact + pulls (needs git history). PR-based, per-service — identical file paths and
        // schema to flowmap-spring: <svc>.pulls.json + <svc>.pulls/<n>.json and
        // <svc>.impact.json (bare impact.json staging name) + <svc>.impact/<n>.json shards.
        //   bizunit 모듈별 1벌로 집계한다. 한 work-tree 의 PR 이라도 변경 파일이 그 모듈 그래프 노드와
        //   닿는 것만 impact 로 귀속된다.
        if (doImpact) {
            int analyzed = 0;
            for (String project : built) {
                Path gr = gitRootOf.get(project);
                GitLog pg = gr.equals(repo) ? git : new GitLog(gr);   // 개별 프로젝트 git 또는 work-tree
                if (!pg.isGitRepo()) continue;
                String[] nsr = nsRepoOf.get(project);
                Path svc = serviceDir(outDir, nsr[0], nsr[1], project);
                generatePullsAndImpact(pg, gr.toFile(), project, svc, svc.resolve("graph.json"), impactMax);
                analyzed++;
            }
            if (analyzed > 0) System.out.println("  pulls/impact analyzed (" + analyzed + " projects)");
            else System.out.println("  impact skipped (no git repository)");
        }

        Manifest.write(outDir);

        // optional: sync web-consumed artifacts into flowmap/docs/web/data (manifest.json + per-project files)
        String syncDir = args.get("sync-dir");
        if (syncDir != null) syncToWeb(outDir, Path.of(syncDir), built);

        System.out.println("refresh complete → " + outDir + " (" + built.size() + " projects)");
    }

    // ---------- sync ----------

    /**
     * Flatten the per-service staging tree into the web data dir, without re-analyzing.
     * Used as a standalone pipeline stage so it can run AFTER the shared assembler has
     * written its manifest (this then merges the nexcore projects into it).
     */
    private static void sync(Args args) throws IOException {
        Path outDir = Path.of(args.get("out-dir", DEFAULT_OUT_DIR));
        String syncDir = args.get("sync-dir");
        if (syncDir == null) {
            System.err.println("sync requires --sync-dir <web-data-dir>");
            return;
        }
        syncToWeb(outDir, Path.of(syncDir), builtProjects(outDir));
    }

    /**
     * Copy the files the flowmap web app loads into its (flat) data dir: per-service
     * {@code service/<p>/graph.json}→{@code <p>.json}, {@code openapi.json}→{@code <p>.openapi.json},
     * {@code impact.json}→{@code <p>.impact.json}, then merge the nexcore projects from
     * {@code _manifest.json} into the web {@code manifest.json} (preserving entries written
     * by other analyzers). {@code _combined}/{@code _openapi} are analyzer staging only (the
     * web merges per-project graphs itself) and are not synced.
     */
    private static void syncToWeb(Path outDir, Path syncDir, List<String> built) throws IOException {
        Files.createDirectories(syncDir);
        Path projectsRoot = outDir.resolve("projects");
        for (Path leaf : leafProjectDirs(outDir)) {
            String perRoot = leaf.getFileName().toString();
            String rel = projectsRoot.relativize(leaf).toString().replace(File.separatorChar, '/');
            Path destPdir = syncDir.resolve("projects").resolve(rel);
            Files.createDirectories(destPdir);
            copyIfPresent(leaf.resolve("graph.json"), destPdir.resolve(perRoot + ".json"));
            copyIfPresent(leaf.resolve("openapi.json"), destPdir.resolve(perRoot + ".openapi.json"));
            copyIfPresent(leaf.resolve("impact.json"), destPdir.resolve(perRoot + ".impact.json"));
            copyIfPresent(leaf.resolve("pulls.json"), destPdir.resolve(perRoot + ".pulls.json"));
            // lazy-load shard dirs keep their <perRoot>.{impact,pulls}/ name (the index refs them so).
            copyDirIfPresent(leaf.resolve(perRoot + ".impact"), destPdir.resolve(perRoot + ".impact"));
            copyDirIfPresent(leaf.resolve(perRoot + ".pulls"), destPdir.resolve(perRoot + ".pulls"));
        }
        mergeManifest(outDir.resolve("_manifest.json"), syncDir.resolve("manifest.json"), built);
        System.out.println("  synced → " + syncDir + " (manifest.json + " + built.size() + " projects)");
    }

    private static void copyIfPresent(Path src, Path dst) throws IOException {
        if (Files.isRegularFile(src)) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Mirror a shard directory (drop the dest first so stale shards don't linger), if the source exists. */
    private static void copyDirIfPresent(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) return;
        deleteDirRecursive(dst);
        Files.createDirectories(dst);
        try (Stream<Path> s = Files.list(src)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(f)) {
                    Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Merge the {@code projects} of the nexcore {@code _manifest.json} into the web app's
     * {@code manifest.json}: drop any existing entries for the just-synced projects (by name)
     * and append the fresh nexcore ones, leaving entries from other analyzers untouched.
     * When the web manifest does not exist yet, the nexcore manifest is copied verbatim.
     */
    private static void mergeManifest(Path nexcoreManifest, Path webManifest, List<String> built)
            throws IOException {
        if (!Files.isRegularFile(nexcoreManifest)) return;
        if (!Files.isRegularFile(webManifest)) {
            Files.copy(nexcoreManifest, webManifest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode nexcore = JsonIO.read(nexcoreManifest);
        ObjectNode web = (ObjectNode) JsonIO.read(webManifest);
        Set<String> mine = new HashSet<>(built);

        ArrayNode merged = mapper.createArrayNode();
        for (JsonNode p : web.path("projects")) {
            if (!mine.contains(p.path("name").asText())) merged.add(p);
        }
        for (JsonNode p : nexcore.path("projects")) merged.add(p);
        web.set("projects", merged);
        JsonOutput.write(web, webManifest);
    }

    // ---------- helpers ----------

    /** Per-service staging directory: {@code <out-dir>/projects/<namespace>/<repo>/<perRoot>/}. */
    private static Path serviceDir(Path outDir, String namespace, String repo, String perRoot) {
        return outDir.resolve("projects").resolve(namespace).resolve(repo).resolve(perRoot);
    }

    /**
     * Leaf project dirs under {@code <outDir>/projects} (nested {@code <ns>/<repo>/<perRoot>}):
     * any dir directly holding a {@code .json} artifact. Skips {@code .pulls}/{@code .impact} shard dirs.
     */
    private static List<Path> leafProjectDirs(Path outDir) throws IOException {
        Path root = outDir.resolve("projects");
        List<Path> out = new ArrayList<>();
        collectLeaves(root, out);
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    private static void collectLeaves(Path d, List<Path> out) throws IOException {
        if (!Files.isDirectory(d)) return;
        List<Path> children;
        try (Stream<Path> s = Files.list(d)) {
            children = s.collect(java.util.stream.Collectors.toList());
        }
        boolean hasJson = children.stream()
                .anyMatch(c -> Files.isRegularFile(c) && c.getFileName().toString().endsWith(".json"));
        if (hasJson) out.add(d);
        for (Path c : children) {
            String n = c.getFileName().toString();
            if (Files.isDirectory(c) && !n.endsWith(".pulls") && !n.endsWith(".impact")) collectLeaves(c, out);
        }
    }

    /**
     * Generate the PR-based pulls + impact artifacts for one service into {@code svcDir},
     * matching flowmap-spring's layout/schema. {@code svcBase} is the service name used for the
     * shard-dir prefix and the pulls index {@code dir}; {@code graphFile} is the call graph the
     * impact reverse-walk runs against (the per-project graph). Writes bare
     * {@code pulls.json}/{@code impact.json} (the staging sibling names the
     * sync/manifest expect) plus {@code <svcBase>.pulls/}/{@code <svcBase>.impact/} shard dirs.
     */
    private static void generatePullsAndImpact(GitLog git, File repoFile, String svcBase,
                                               Path svcDir, Path graphFile, int max) throws IOException {
        Files.createDirectories(svcDir);
        String base = git.resolveBranch(null);
        List<GitHub.Pr> pulls = GitHub.mergedPulls(repoFile, base, max);
        if (pulls == null) pulls = new ArrayList<>();   // neither git nor gh yielded PRs → treat as none
        writePulls(repoFile, git, svcBase, svcDir, base, pulls);
        PrImpact.Result res = PrImpact.analyze(git, base, pulls, graphFile);
        JsonOutput.write(res.index, svcDir.resolve("impact.json"));
        writeImpactShards(svcDir, svcBase, res.shards);
    }

    /** Write the {@code pulls.json} index (bare) + {@code <svcBase>.pulls/<n>.json} shards, pruning stale. */
    private static void writePulls(File repoFile, GitLog git, String svcBase, Path svcDir,
                                   String base, List<GitHub.Pr> pulls) throws IOException {
        String webBase = git.webBaseUrl();
        String shardDir = svcBase + ".pulls";
        Path pdir = svcDir.resolve(shardDir);
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (GitHub.Pr pr : pulls) {
            List<GitHub.PrFile> files = GitHub.pullFiles(repoFile, pr);
            if (files == null) files = new ArrayList<>();
            Map<String, Object> shard = GitHub.buildShard(pr, files, webBase);
            JsonOutput.write(shard, pdir.resolve(pr.number + ".json"));
            keep.add(pr.number + ".json");
            entries.add(GitHub.indexEntry(shard, shardDir));
        }
        JsonOutput.write(GitHub.pullIndexDoc(base, webBase, shardDir, entries), svcDir.resolve("pulls.json"));
        pruneShardDir(pdir, keep);
    }

    /** Write the {@code <svcBase>.impact/<n>.json} shards, pruning ones not re-emitted this run. */
    private static void writeImpactShards(Path svcDir, String svcBase,
                                          Map<Integer, Map<String, Object>> shards) throws IOException {
        Path dir = svcDir.resolve(svcBase + ".impact");
        Set<String> keep = new HashSet<>();
        for (Map.Entry<Integer, Map<String, Object>> e : shards.entrySet()) {
            JsonOutput.write(e.getValue(), dir.resolve(e.getKey() + ".json"));
            keep.add(e.getKey() + ".json");
        }
        pruneShardDir(dir, keep);
    }

    /** Remove {@code *.json} shards in {@code dir} not in {@code keep}; delete {@code dir} if empty. */
    private static void pruneShardDir(Path dir, Set<String> keep) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String name = f.getFileName().toString();
                if (Files.isRegularFile(f) && name.endsWith(".json") && !keep.contains(name)) {
                    Files.deleteIfExists(f);
                }
            }
        }
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.deleteIfExists(dir);
        }
    }

    /** Recursively delete a directory tree if present (clears a stale shard dir before a rewrite). */
    private static void deleteDirRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort
                }
            });
        }
    }

    /**
     * Project names present in the staging tree — dirs with a {@code graph.json} (projects) OR
     * with only an {@code impact.json} (graph-less impact-only unit, defensive — not normally emitted).
     */
    private static List<String> builtProjects(Path outDir) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path leaf : leafProjectDirs(outDir)) {
            if (Files.isRegularFile(leaf.resolve("graph.json")) || Files.isRegularFile(leaf.resolve("impact.json")))
                out.add(leaf.getFileName().toString());
        }
        return out;
    }

    private static List<String> discoverProjects(Path repo) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(repo)) return out;
        try (Stream<Path> s = Files.list(repo)) {
            s.filter(Files::isDirectory)
                    .filter(d -> Files.isDirectory(d.resolve("src/main/java")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    private static List<Path> graphFiles(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path leaf : leafProjectDirs(dir)) {
            Path g = leaf.resolve("graph.json");
            if (Files.isRegularFile(g)) out.add(g);
        }
        return out;
    }
}
