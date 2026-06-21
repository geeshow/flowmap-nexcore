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

    /**
     * Pick the branch to mine, matching flowmap-spring: explicit override → the currently
     * checked-out branch (the one {@code refresh} pulled) → (only if HEAD is detached) the
     * repo's default via {@code origin/HEAD} → main/master/develop.
     *
     * <p>The checked-out branch wins so impact mines the branch actually selected — not
     * {@code origin/HEAD}, which on a freshly cloned/added history points at the remote's
     * default and would miss local PR commits.</p>
     */
    public String resolveBranch(String requested) {
        if (requested != null) return requested;
        String cur = currentBranch();
        if (!cur.isEmpty() && !cur.equals("HEAD")) return cur;
        // Detached HEAD: fall back to the repo's default branch.
        try {
            String def = run("symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD").trim();
            if (def.startsWith("origin/")) def = def.substring("origin/".length());
            if (!def.isEmpty() && verifyRef(def)) return def;
        } catch (Exception ignore) {
        }
        for (String cand : new String[]{"main", "master", "develop"}) if (verifyRef(cand)) return cand;
        return "HEAD";
    }

    /** Currently checked-out branch (the "selected" branch), or "HEAD" if detached. */
    private String currentBranch() {
        try {
            return run("rev-parse", "--abbrev-ref", "HEAD").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean verifyRef(String ref) {
        try {
            return !run("rev-parse", "--verify", "--quiet", ref).trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
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

    /** Content of {@code path} at {@code sha}, or null if absent/empty. (Alias of {@link #blob} matching spring.) */
    public String fileAt(String sha, String path) {
        String s = blob(sha, path);
        return s == null || s.isEmpty() ? null : s;
    }

    /** First parent of {@code sha} (the base side of a PR merge), or null if absent/root. */
    public String firstParent(String sha) {
        try {
            String p = run("rev-parse", "--verify", "--quiet", sha + "^1").trim();
            return p.isEmpty() ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code origin} remote URL, or null if there is no {@code origin} remote. */
    public String remoteUrl() {
        try {
            String u = run("remote", "get-url", "origin").trim();
            return u.isEmpty() ? null : u;
        } catch (Exception e) {
            return null;
        }
    }

    /** Web base URL for this repo's {@code origin} (e.g. {@code https://github.com/owner/repo}), or null. */
    public String webBaseUrl() {
        return toWebBase(remoteUrl());
    }

    /**
     * Git namespace (owner) of this repo's {@code origin} remote — the second-to-last path
     * segment of the normalized web base — or null when there is no usable remote. Used as the
     * top level of the output layout {@code projects/<namespace>/<repo>/<perRoot>/}.
     */
    public String namespace() {
        String web = toWebBase(remoteUrl());
        if (web == null) return null;
        String[] segs = web.replaceFirst("^https://", "").split("/");
        return segs.length >= 3 ? segs[segs.length - 2] : null;   // [host, owner..., repo]
    }

    /**
     * Repo name from this repo's {@code origin} remote (last path segment of the normalized web
     * base), or the work-tree basename when there is no usable remote. Used as the {@code repo}
     * slot of the output layout {@code projects/<namespace>/<repo>/<perRoot>/}.
     */
    public String repoSlug() {
        String web = toWebBase(remoteUrl());
        if (web != null) {
            String[] segs = web.replaceFirst("^https://", "").split("/");
            if (segs.length >= 1) {
                String last = segs[segs.length - 1];
                if (last != null && !last.isBlank()) return last;
            }
        }
        return repoName();
    }

    /**
     * Normalize a git remote URL to its https web base (no trailing {@code .git}),
     * handling scp-style ({@code git@host:owner/repo.git}), {@code ssh://}, and
     * {@code http(s)://} forms and stripping embedded credentials. Pure (no git call).
     * Mirrors flowmap-spring's {@code GitLog.toWebBase}.
     */
    public static String toWebBase(String remote) {
        if (remote == null) return null;
        String u = remote.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("git@")) u = "https://" + u.substring("git@".length()).replaceFirst(":", "/");
        else if (u.startsWith("ssh://")) u = "https://" + u.substring("ssh://".length());
        else if (u.startsWith("http://")) u = "https://" + u.substring("http://".length());
        else if (!u.startsWith("https://")) return null;
        u = u.replaceFirst("^https://[^/@]+@", "https://");   // strip user[:token]@ credentials
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith(".git")) u = u.substring(0, u.length() - ".git".length());
        return u.isEmpty() ? null : u;
    }

    /** A changed file with the NEW-side line ranges touched (empty for pure deletions). */
    public static final class FileChange {
        public final String path;
        public final String oldPath;     // set only for renames
        public final String changeType;  // ADD / DELETE / RENAME / MODIFY
        public final List<int[]> newRanges = new ArrayList<>();

        public FileChange(String path, String oldPath, String changeType) {
            this.path = path;
            this.oldPath = oldPath;
            this.changeType = changeType;
        }
    }

    /** Per-file new-side changed line ranges for {@code sha} vs its first parent (rename-aware). */
    public List<FileChange> changesIn(String sha) {
        String diff;
        try {
            diff = run("show", sha, "--first-parent", "-U0", "-M", "--no-color", "--format=");
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return parseDiff(diff);
    }

    private static List<FileChange> parseDiff(String text) {
        List<FileChange> out = new ArrayList<>();
        // Flush the accumulated file block at each "diff --git" boundary and at end.
        String aPath = null, bPath = null, ctype = "MODIFY";
        List<int[]> cur = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            if (raw.startsWith("diff --git ")) {
                flush(out, aPath, bPath, ctype, cur);
                aPath = null; bPath = null; ctype = "MODIFY"; cur = new ArrayList<>();
            } else if (raw.startsWith("rename from ")) {
                aPath = raw.substring("rename from ".length()); ctype = "RENAME";
            } else if (raw.startsWith("rename to ")) {
                bPath = raw.substring("rename to ".length());
            } else if (raw.startsWith("new file")) {
                ctype = "ADD";
            } else if (raw.startsWith("deleted file")) {
                ctype = "DELETE";
            } else if (raw.startsWith("--- a/")) {
                aPath = raw.substring("--- a/".length());
            } else if (raw.startsWith("+++ b/")) {
                bPath = raw.substring("+++ b/".length());
            } else if (raw.startsWith("@@")) {
                int[] r = parseNewRange(raw);
                if (r != null) cur.add(r);
            }
        }
        flush(out, aPath, bPath, ctype, cur);
        return out;
    }

    private static void flush(List<FileChange> out, String aPath, String bPath, String ctype, List<int[]> ranges) {
        String path = bPath != null ? bPath : aPath;
        if (path == null || path.isEmpty()) return;
        FileChange fc = new FileChange(path, (aPath != null && !aPath.equals(path)) ? aPath : null, ctype);
        fc.newRanges.addAll(ranges);
        out.add(fc);
    }

    /** "@@ -a,b +c,d @@" → new-side {@code [c, c+d-1]} (count 0 → [c,c]). */
    private static int[] parseNewRange(String line) {
        int plus = line.indexOf('+');
        if (plus < 0) return null;
        int sp = line.indexOf(' ', plus);
        if (sp < 0) return null;
        String[] parts = line.substring(plus + 1, sp).split(",");
        try {
            int start = Integer.parseInt(parts[0]);
            int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            if (count == 0) return new int[]{start, start};
            return new int[]{start, start + count - 1};
        } catch (NumberFormatException e) {
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
