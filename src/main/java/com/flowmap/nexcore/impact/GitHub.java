package com.flowmap.nexcore.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merged pull-request source for {@link PrImpact}, which analyzes change impact per PR
 * (not per raw commit). Each PR is reduced to its merge/squash commit, whose first-parent
 * diff is the PR's net change set. A faithful Java port of flowmap-spring's {@code GitHub}
 * so nexcore emits the identical {@code <svc>.pulls.json}/{@code <svc>.impact.json}
 * artifacts (file paths and schema).
 *
 * <p>GIT-FIRST, gh-fallback: PRs and their file diffs come from local git
 * ({@code git log --first-parent} + {@code git show}) so analysis works with no {@code gh}
 * and on GitHub Enterprise. {@code gh} is used only when git yields no PR markers.</p>
 */
public final class GitHub {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GitHub() {
    }

    public static final class Pr {
        public final int number;
        public final String title;
        public final String author;
        public final String mergedAt;
        public final String mergeCommit;   // merge/squash commit oid; null if unavailable

        public Pr(int number, String title, String author, String mergedAt, String mergeCommit) {
            this.number = number;
            this.title = title;
            this.author = author;
            this.mergedAt = mergedAt;
            this.mergeCommit = mergeCommit;
        }
    }

    public static final class PrFile {
        public final String path;
        public final String status;
        public final int additions;
        public final int deletions;
        public final int changes;
        public final String previousPath;
        public final String patch;

        public PrFile(String path, String status, int additions, int deletions, int changes,
                      String previousPath, String patch) {
            this.path = path;
            this.status = status;
            this.additions = additions;
            this.deletions = deletions;
            this.changes = changes;
            this.previousPath = previousPath;
            this.patch = patch;
        }
    }

    /**
     * Newest-first merged PRs targeting {@code base} (all bases if null), capped at {@code limit}.
     * GIT-FIRST (merge + squash markers in {@code git log --first-parent}); falls back to
     * {@code gh pr list} only when git yields nothing. Returns null only when BOTH sources are
     * unavailable (distinct from an empty list = a source ran but the base has no merged PRs).
     */
    public static List<Pr> mergedPulls(File repo, String base, int limit) {
        List<Pr> fromGit = gitMergedPulls(repo, base == null ? "HEAD" : base, limit);
        if (!fromGit.isEmpty()) return fromGit;
        return ghMergedPulls(repo, base, limit);
    }

    /** PR set parsed from {@code git log --first-parent} (merge + squash markers). Empty if none. */
    public static List<Pr> gitMergedPulls(File repo, String base, int limit) {
        Exec r = git(repo, List.of(
                "log", "--first-parent", base, "-n", "5000", "--no-color",
                "--pretty=format:%H%x1f%cI%x1f%an%x1f%s%x1f%b%x1e"));
        if (r.code != 0) return new ArrayList<>();
        return parseGitLog(r.out, limit);
    }

    private static final Pattern MERGE_PR = Pattern.compile("^Merge pull request #(\\d+) ");
    private static final Pattern SQUASH_PR = Pattern.compile("\\(#(\\d+)\\)\\s*$");

    /** Parse the {@code %H\x1f%cI\x1f%an\x1f%s\x1f%b\x1e}-formatted log into newest-first PRs. Pure. */
    public static List<Pr> parseGitLog(String out, int limit) {
        List<Pr> prs = new ArrayList<>();
        for (String rec : out.split("")) {
            String r = stripNlCr(rec);
            if (r.isBlank()) continue;
            String[] f = r.split("", -1);
            if (f.length < 4) continue;
            String sha = f[0].isBlank() ? null : f[0];
            if (sha == null) continue;
            String date = f[1].isBlank() ? null : f[1];
            String author = f[2].isBlank() ? null : f[2];
            String subject = f[3];
            String body = f.length > 4 ? f[4] : "";
            Matcher merge = MERGE_PR.matcher(subject);
            int number;
            String title;
            if (merge.find()) {
                number = Integer.parseInt(merge.group(1));
                String firstLine = firstNonBlankLine(body);
                title = firstLine != null ? firstLine : subject;
            } else {
                Matcher squash = SQUASH_PR.matcher(subject);
                if (!squash.find()) continue;   // non-PR commit
                number = Integer.parseInt(squash.group(1));
                title = SQUASH_PR.matcher(subject).replaceAll("").trim();
            }
            prs.add(new Pr(number, title, author, date, sha));
            if (prs.size() >= limit) break;
        }
        return prs;
    }

    /** Merged PRs via {@code gh pr list} (server source / fallback). Null when {@code gh} cannot run. */
    public static List<Pr> ghMergedPulls(File repo, String base, int limit) {
        List<String> args = new ArrayList<>(List.of(
                "pr", "list", "--state", "merged", "--limit", String.valueOf(limit),
                "--json", "number,title,author,mergedAt,mergeCommit"));
        if (base != null) {
            args.add("--base");
            args.add(base);
        }
        Exec r = gh(repo, args);
        if (r.code != 0) return null;
        return parse(r.out);
    }

    /** Parse {@code gh pr list --json number,title,author,mergedAt,mergeCommit} output. Pure. */
    public static List<Pr> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<Pr> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        for (JsonNode n : root) {
            JsonNode num = n.path("number");
            if (!num.isNumber()) continue;
            out.add(new Pr(
                    num.asInt(),
                    n.path("title").asText(""),
                    blankToNull(n.path("author").path("login").asText(null)),
                    blankToNull(n.path("mergedAt").asText(null)),
                    blankToNull(n.path("mergeCommit").path("oid").asText(null))));
        }
        return out;
    }

    /**
     * File-level change set of a single PR (status + unified patch). GIT-FIRST from the merge
     * commit's first-parent diff; falls back to the REST API. Null only when neither source works.
     */
    public static List<PrFile> pullFiles(File repo, Pr pr) {
        if (pr.mergeCommit != null) {
            List<PrFile> fromGit = gitPullFiles(repo, pr.mergeCommit);
            if (fromGit != null) return fromGit;
        }
        return ghPullFiles(repo, pr.number);
    }

    /** Per-file status + patch from a merge/squash commit's first-parent diff. Null if git can't run. */
    public static List<PrFile> gitPullFiles(File repo, String sha) {
        Exec r = git(repo, List.of("show", "--first-parent", "-M", "--no-color", "--format=", sha));
        if (r.code != 0) return null;
        return parseShow(r.out);
    }

    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.*) b/(.*)$");

    /** Parse a {@code git show}/{@code git diff} unified diff into per-file {@link PrFile}s. Pure. */
    public static List<PrFile> parseShow(String diff) {
        List<PrFile> files = new ArrayList<>();
        FlushState st = new FlushState();
        st.status = "modified";
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                collect(files, st);
                st.reset();
                Matcher m = DIFF_GIT.matcher(line);
                if (m.find()) {
                    st.oldPath = m.group(1);
                    st.path = m.group(2);
                }
                continue;
            }
            if (st.inHunk) {
                st.patch.append(line).append('\n');
                char c = line.isEmpty() ? ' ' : line.charAt(0);
                if (c == '+') st.add++;
                else if (c == '-') st.del++;
                continue;
            }
            if (line.startsWith("new file")) st.status = "added";
            else if (line.startsWith("deleted file")) st.status = "removed";
            else if (line.startsWith("rename from ")) { st.oldPath = line.substring("rename from ".length()); st.status = "renamed"; }
            else if (line.startsWith("rename to ")) st.path = line.substring("rename to ".length());
            else if (line.startsWith("--- a/")) st.oldPath = line.substring("--- a/".length());
            else if (line.startsWith("+++ b/")) st.path = line.substring("+++ b/".length());
            else if (line.startsWith("@@")) { st.inHunk = true; st.patch.append(line).append('\n'); }
        }
        collect(files, st);
        List<PrFile> nonEmpty = new ArrayList<>();
        for (PrFile f : files) if (!f.path.isEmpty()) nonEmpty.add(f);
        return nonEmpty;
    }

    private static final class FlushState {
        String path, oldPath, status = "modified";
        int add, del;
        final StringBuilder patch = new StringBuilder();
        boolean inHunk;

        void reset() {
            path = null; oldPath = null; status = "modified"; add = 0; del = 0;
            patch.setLength(0); inHunk = false;
        }
    }

    private static void collect(List<PrFile> files, FlushState st) {
        if (st.path == null) return;
        String prev = ("renamed".equals(st.status) && st.oldPath != null && !st.oldPath.equals(st.path)) ? st.oldPath : null;
        String patch = st.patch.length() == 0 ? null : st.patch.toString();
        files.add(new PrFile(st.path, st.status, st.add, st.del, st.add + st.del, prev, patch));
    }

    /**
     * File-level change set via the REST API (fallback):
     * {@code gh api --paginate repos/{owner}/{repo}/pulls/{n}/files}. Null if {@code gh} can't run.
     */
    public static List<PrFile> ghPullFiles(File repo, int number) {
        Exec r = gh(repo, List.of("api", "--paginate", "repos/{owner}/{repo}/pulls/" + number + "/files"));
        if (r.code != 0) return null;
        return parseFiles(r.out);
    }

    /** Parse a REST {@code pulls/{n}/files} JSON array. Pure. */
    public static List<PrFile> parseFiles(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<PrFile> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        for (JsonNode n : root) {
            String p = blankToNull(n.path("filename").asText(null));
            if (p == null) continue;
            out.add(new PrFile(
                    p,
                    firstNonBlank(n.path("status").asText(null), "modified"),
                    n.path("additions").isNumber() ? n.path("additions").asInt() : 0,
                    n.path("deletions").isNumber() ? n.path("deletions").asInt() : 0,
                    n.path("changes").isNumber() ? n.path("changes").asInt() : 0,
                    blankToNull(n.path("previous_filename").asText(null)),
                    blankToNull(n.path("patch").asText(null))));
        }
        return out;
    }

    /** Full per-PR shard document — that PR's per-file status + unified patch (lazy-loaded). */
    public static Map<String, Object> buildShard(Pr pr, List<PrFile> files, String webBase) {
        int additions = 0, deletions = 0;
        List<Map<String, Object>> fileList = new ArrayList<>();
        for (PrFile f : files) {
            additions += f.additions;
            deletions += f.deletions;
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("path", f.path);
            fm.put("status", f.status);
            fm.put("additions", f.additions);
            fm.put("deletions", f.deletions);
            fm.put("changes", f.changes);
            fm.put("previousPath", f.previousPath);
            fm.put("patch", f.patch);
            fileList.add(fm);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", "pull-files");
        m.put("number", pr.number);
        m.put("title", pr.title);
        m.put("author", pr.author);
        m.put("mergedAt", pr.mergedAt);
        m.put("mergeCommit", pr.mergeCommit);
        m.put("url", webBase == null ? null : webBase + "/pull/" + pr.number);
        m.put("repoUrl", webBase);
        m.put("additions", additions);
        m.put("deletions", deletions);
        m.put("changedFiles", files.size());
        m.put("files", fileList);
        return m;
    }

    /** One light index entry derived from a shard doc — PR metadata + line stats + a {@code file} ref. */
    public static Map<String, Object> indexEntry(Map<String, Object> shard, String shardDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", shard.get("number"));
        m.put("title", shard.get("title"));
        m.put("author", shard.get("author"));
        m.put("mergedAt", shard.get("mergedAt"));
        m.put("url", shard.get("url"));
        m.put("additions", shard.get("additions"));
        m.put("deletions", shard.get("deletions"));
        m.put("changedFiles", shard.get("changedFiles"));
        m.put("file", shardDir + "/" + shard.get("number") + ".json");
        return m;
    }

    /** The {@code <project>.pulls.json} index doc wrapping the per-PR entries (newest-first). */
    public static Map<String, Object> pullIndexDoc(String base, String webBase, String shardDir,
                                                   List<Map<String, Object>> entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", "pull-files-index");
        m.put("base", base);
        m.put("repoUrl", webBase);
        m.put("dir", shardDir);
        m.put("pullCount", entries.size());
        m.put("pulls", entries);
        return m;
    }

    /** Read an existing shard json back into a map (to reuse an already-collected PR). Null if unreadable. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readShard(File file) {
        try {
            return MAPPER.readValue(file, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- helpers ----------

    private static String stripNlCr(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '\n' || s.charAt(a) == '\r')) a++;
        while (b > a && (s.charAt(b - 1) == '\n' || s.charAt(b - 1) == '\r')) b--;
        return s.substring(a, b);
    }

    private static String firstNonBlankLine(String body) {
        if (body == null) return null;
        for (String line : body.split("\n", -1)) {
            if (!line.isBlank()) return line.trim();
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static final class Exec {
        final String out;
        final int code;

        Exec(String out, int code) {
            this.out = out;
            this.code = code;
        }
    }

    private static Exec git(File repo, List<String> args) {
        return exec(repo, "git", args);
    }

    private static Exec gh(File repo, List<String> args) {
        return exec(repo, "gh", args);
    }

    /** Run {@code command} in {@code repo}; returns (stdout, exitCode), or ("", -1) if it can't launch. */
    private static Exec exec(File repo, String command, List<String> args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).directory(repo).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.getErrorStream().readAllBytes();
            int code = p.waitFor();
            return new Exec(out, code);
        } catch (Exception e) {
            return new Exec("", -1);   // command not installed / not executable
        }
    }
}
