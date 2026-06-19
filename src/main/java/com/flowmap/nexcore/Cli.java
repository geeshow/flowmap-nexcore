package com.flowmap.nexcore;

import com.flowmap.nexcore.combine.CrossRun;
import com.flowmap.nexcore.impact.GitHub;
import com.flowmap.nexcore.impact.GitLog;
import com.flowmap.nexcore.impact.Impact;
import com.flowmap.nexcore.impact.PrImpact;
import com.flowmap.nexcore.model.CallGraph;
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
 * <p>Per-project artifacts live under a per-service directory:
 * {@code <out-dir>/service/<service>/graph.json} (+ {@code openapi.json} / {@code impact.json}).
 * The {@code _combined.json} / {@code _openapi.json} / {@code _manifest.json} aggregates stay at
 * the {@code out-dir} root. {@code sync} flattens the per-service tree into the web data dir
 * (one flat {@code <service>.json} / {@code .openapi.json} / {@code .impact.json} per project).
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

        // 모노레포 판정: 한 git repo 아래 모듈(=project) 2개 이상이면 pulls/impact 를 repo 단위 1벌로 둔다.
        //   gitRepoMark(=git work-tree 이름)를 각 모듈 그래프 meta.gitRepo 에 찍어 두면, sync(spring/nexcore)
        //   가 manifest 의 repo 필드로 출력하고 웹이 모듈↔repo 를 잇는다. (단일 모듈이면 standalone 유지)
        GitLog git = new GitLog(repo);
        boolean isGit = git.isGitRepo();
        List<String> projects = discoverProjects(repo);
        String gitRepoMark = (isGit && projects.size() >= 2) ? git.repoName() : null;

        List<String> built = new ArrayList<>();
        for (String project : projects) {
            SourceScanner.Scan scan = SourceScanner.scan(repo, project);
            if (scan.units.isEmpty()) continue; // no ghost graphs
            CallGraph graph = GraphBuilder.buildProject(global, project);
            Path svc = serviceDir(outDir, project);
            Map<String, Object> meta = analyzeMeta(repo.toString(), project, scan, graph);
            if (gitRepoMark != null) meta.put("gitRepo", gitRepoMark);   // 모노레포 마커
            JsonOutput.write(graph.toNodeLink(meta), svc.resolve("graph.json"));
            LinkedHashMap<String, Object> doc = OpenApi.generate(scan, repo, project, "1.0.0");
            JsonOutput.write(doc, svc.resolve("openapi.json"));
            built.add(project);
            System.out.println("  analyzed " + project + " (" + graph.nodes().size()
                    + " nodes, " + graph.edges().size() + " edges)");
        }

        // combined graph + repo-wide openapi (combine is now mostly a merge; s2s already in graphs)
        List<Path> graphs = graphFiles(outDir);
        JsonOutput.write(CrossRun.combine(graphs), outDir.resolve("_combined.json"));
        JsonOutput.write(OpenApi.generate(all, repo, allTitle, "1.0.0"), outDir.resolve("_openapi.json"));

        // impact + pulls (needs git history). PR-based, per-service — identical file paths and
        // schema to flowmap-spring: <svc>.pulls.json + <svc>.pulls/<n>.json and
        // <svc>.impact.json (bare impact.json staging name) + <svc>.impact/<n>.json shards.
        boolean monorepo = gitRepoMark != null && built.size() >= 2 && !built.contains(gitRepoMark);
        if (doImpact && isGit && monorepo) {
            // 모노레포: 합쳐진 그래프(_combined.json)로 repo 단위 pulls/impact 1벌 → service/<repo>/.
            //   (graph 없는 repo 엔트리). PR 은 repo 전체에서 1번씩만 집계되고, impactedEndpoints 의
            //   service 는 노드 project(=모듈)라 웹이 모듈 단위로 귀속한다. (spring 모노레포와 동일)
            Path repoSvc = serviceDir(outDir, gitRepoMark);
            Files.createDirectories(repoSvc);
            // 이전(모듈별) 실행이 남긴 모듈 pulls/impact 는 제거 — repo 단위 1벌만 남긴다.
            for (String project : built) {
                Path p = serviceDir(outDir, project);
                Files.deleteIfExists(p.resolve("impact.json"));
                Files.deleteIfExists(p.resolve("pulls.json"));
                deleteDirRecursive(p.resolve(project + ".impact"));
                deleteDirRecursive(p.resolve(project + ".pulls"));
            }
            generatePullsAndImpact(git, repo.toFile(), gitRepoMark, repoSvc,
                    outDir.resolve("_combined.json"), impactMax);
            built.add(gitRepoMark);   // sync/manifest 가 repo 엔트리(pulls/impact 전용)를 포함하도록
            System.out.println("  pulls/impact analyzed (repo-level: " + gitRepoMark + ")");
        } else if (doImpact && isGit) {
            for (String project : built) {
                Path svc = serviceDir(outDir, project);
                generatePullsAndImpact(git, repo.toFile(), project, svc, svc.resolve("graph.json"), impactMax);
            }
            System.out.println("  pulls/impact analyzed (" + built.size() + " projects)");
        } else if (doImpact) {
            System.out.println("  impact skipped (" + repo + " is not a git repository)");
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
        for (String project : built) {
            Path svc = serviceDir(outDir, project);
            copyIfPresent(svc.resolve("graph.json"), syncDir.resolve(project + ".json"));
            copyIfPresent(svc.resolve("openapi.json"), syncDir.resolve(project + ".openapi.json"));
            copyIfPresent(svc.resolve("impact.json"), syncDir.resolve(project + ".impact.json"));
            copyIfPresent(svc.resolve("pulls.json"), syncDir.resolve(project + ".pulls.json"));
            // lazy-load shard dirs keep their <project>.{impact,pulls}/ name (the index refs them so).
            copyDirIfPresent(svc.resolve(project + ".impact"), syncDir.resolve(project + ".impact"));
            copyDirIfPresent(svc.resolve(project + ".pulls"), syncDir.resolve(project + ".pulls"));
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

    /** Per-service staging directory: {@code <out-dir>/service/<project>/}. */
    private static Path serviceDir(Path outDir, String project) {
        return outDir.resolve("service").resolve(project);
    }

    /**
     * Generate the PR-based pulls + impact artifacts for one service into {@code svcDir},
     * matching flowmap-spring's layout/schema. {@code svcBase} is the service name used for the
     * shard-dir prefix and the pulls index {@code dir}; {@code graphFile} is the call graph the
     * impact reverse-walk runs against (the per-module graph, or {@code _combined.json} for a
     * monorepo). Writes bare {@code pulls.json}/{@code impact.json} (the staging sibling names the
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
     * Project names present in the staging tree — dirs with a {@code graph.json} (modules) OR
     * with only an {@code impact.json} (graph-less repo-level impact unit for a monorepo).
     */
    private static List<String> builtProjects(Path outDir) throws IOException {
        List<String> out = new ArrayList<>();
        Path serviceRoot = outDir.resolve("service");
        if (!Files.isDirectory(serviceRoot)) return out;
        try (Stream<Path> s = Files.list(serviceRoot)) {
            s.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("graph.json"))
                            || Files.isRegularFile(d.resolve("impact.json")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
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
        Path serviceRoot = dir.resolve("service");
        if (!Files.isDirectory(serviceRoot)) return out;
        try (Stream<Path> s = Files.list(serviceRoot)) {
            s.filter(Files::isDirectory)
                    .map(d -> d.resolve("graph.json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }
}
