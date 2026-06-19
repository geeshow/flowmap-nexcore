package com.flowmap.nexcore.impact;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the {@code git} CLI (no JGit dependency) — same approach as
 * flowmap-spring. Provides commit listing, per-commit changed files + new-side
 * hunk line ranges, and blob reads at a revision.
 */
public final class GitLog {

    public final Path repo;

    public GitLog(Path repo) {
        this.repo = repo;
    }

    public static final class Commit {
        public String sha;
        public String shortSha;
        public String author;
        public String date;
        public String subject;
        public final List<String> changedFiles = new ArrayList<>();
        /** file → list of new-side line ranges [start,end]. */
        public final Map<String, List<int[]>> hunks = new LinkedHashMap<>();
    }

    public boolean isGitRepo() {
        if (Files.isDirectory(repo.resolve(".git"))) return true;
        try {
            return run("rev-parse", "--is-inside-work-tree").trim().equals("true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Basename of the git work-tree root (e.g. {@code nexcore}) — used as the monorepo
     * identifier. Falls back to the repo directory name when git can't resolve it.
     */
    public String repoName() {
        try {
            String top = run("rev-parse", "--show-toplevel").trim();
            if (!top.isEmpty()) {
                Path name = Path.of(top).getFileName();
                if (name != null) return name.toString();
            }
        } catch (Exception ignore) {
        }
        Path name = repo.toAbsolutePath().normalize().getFileName();
        return name == null ? "repo" : name.toString();
    }

    public String resolveBranch(String requested) {
        if (requested != null) return requested;
        for (String cand : new String[]{"origin/HEAD", "main", "master", "develop", "HEAD"}) {
            try {
                String r = run("rev-parse", "--verify", "--quiet", cand).trim();
                if (!r.isEmpty()) return cand.equals("origin/HEAD") ? "origin/HEAD" : cand;
            } catch (Exception ignore) {
            }
        }
        return "HEAD";
    }

    public List<Commit> commits(String branch, int max, String range) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("log", "--no-merges",
                "--format=%H%h%an%aI%s"));
        if (range != null) {
            args.add(range);
        } else {
            args.add("-n");
            args.add(String.valueOf(max));
            args.add(branch);
        }
        String out = run(args.toArray(new String[0]));
        List<Commit> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.split("", -1);
            if (p.length < 5) continue;
            Commit c = new Commit();
            c.sha = p[0];
            c.shortSha = p[1];
            c.author = p[2];
            c.date = p[3];
            c.subject = p[4];
            list.add(c);
        }
        for (Commit c : list) fillDiff(c);
        return list;
    }

    private void fillDiff(Commit c) throws IOException, InterruptedException {
        String diff = run("show", "-U0", "-M", "--format=", c.sha);
        String currentFile = null;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (path.equals("/dev/null")) {
                    currentFile = null;
                } else {
                    currentFile = path.startsWith("b/") ? path.substring(2) : path;
                    c.changedFiles.add(currentFile);
                    c.hunks.computeIfAbsent(currentFile, k -> new ArrayList<>());
                }
            } else if (line.startsWith("@@") && currentFile != null) {
                int[] range = parseHunk(line);
                if (range != null) c.hunks.get(currentFile).add(range);
            }
        }
    }

    /** "@@ -a,b +c,d @@" → new-side [c, c+d-1]. */
    private static int[] parseHunk(String line) {
        int plus = line.indexOf('+');
        if (plus < 0) return null;
        int sp = line.indexOf(' ', plus);
        if (sp < 0) return null;
        String spec = line.substring(plus + 1, sp);
        String[] parts = spec.split(",");
        try {
            int start = Integer.parseInt(parts[0]);
            int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            if (count == 0) return new int[]{start, start};
            return new int[]{start, start + count - 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String blob(String sha, String path) {
        try {
            return run("show", sha + ":" + path);
        } catch (Exception e) {
            return null;
        }
    }

    private String run(String... gitArgs) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        for (String a : gitArgs) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        proc.waitFor();
        return sb.toString();
    }
}
