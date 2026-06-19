package com.flowmap.nexcore;

import com.flowmap.nexcore.combine.CrossRun;
import com.flowmap.nexcore.impact.GitLog;
import com.flowmap.nexcore.impact.Impact;
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

        // impact (needs git history)
        boolean monorepo = gitRepoMark != null && built.size() >= 2 && !built.contains(gitRepoMark);
        if (doImpact && isGit && monorepo) {
            // 모노레포: 합쳐진 그래프(_combined.json)로 repo 단위 impact 1벌 → service/<repo>/impact.json
            //   (graph 없는 repo 엔트리). PR 은 repo 전체에서 1번씩만 집계되고, impactedEndpoints 의
            //   service 는 노드 project(=모듈)라 웹이 모듈 단위로 귀속한다.
            Path repoSvc = serviceDir(outDir, gitRepoMark);
            Files.createDirectories(repoSvc);
            LinkedHashMap<String, Object> result = Impact.run(git,
                    outDir.resolve("_combined.json"), null, impactMax, impactDepth, null);
            JsonOutput.write(result, repoSvc.resolve("impact.json"));
            built.add(gitRepoMark);   // sync/manifest 가 repo 엔트리(impact 전용)를 포함하도록
            System.out.println("  impact analyzed (repo-level: " + gitRepoMark + ")");
        } else if (doImpact && isGit) {
            for (String project : built) {
                Path svc = serviceDir(outDir, project);
                LinkedHashMap<String, Object> result = Impact.run(git,
                        svc.resolve("graph.json"), null, impactMax, impactDepth, null);
                JsonOutput.write(result, svc.resolve("impact.json"));
            }
            System.out.println("  impact analyzed (" + built.size() + " projects)");
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
        }
        mergeManifest(outDir.resolve("_manifest.json"), syncDir.resolve("manifest.json"), built);
        System.out.println("  synced → " + syncDir + " (manifest.json + " + built.size() + " projects)");
    }

    private static void copyIfPresent(Path src, Path dst) throws IOException {
        if (Files.isRegularFile(src)) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
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
