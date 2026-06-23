package com.flowmap.nexcore;

import com.flowmap.nexcore.impact.GitHub;
import com.flowmap.nexcore.impact.GitLog;
import com.flowmap.nexcore.impact.PrImpact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statement-level xsql impact through the PR-based path ({@link PrImpact}) — the one that
 * actually produces the {@code <svc>.impact/<number>.json} shards the web reads. A merged PR
 * that edits only statement S001 must record only the S001 DataUnit method in the shard's
 * {@code changedNodes} and only the S001 ProcessUnit in the index row's {@code impactedEndpoints}.
 */
class XsqlPrImpactTest {

    @Test
    void mergedPrEditingOneStatementImpactsOnlyThatProcessUnit(@TempDir Path repo) throws Exception {
        String puA = "com.kakaopay.ac.a.PA#pA", fuA = "com.kakaopay.ac.a.FA#fA";
        String puB = "com.kakaopay.ac.b.PB#pB", fuB = "com.kakaopay.ac.b.FB#fB";
        String duA = "com.kakaopay.ac.acgo0001.biz.DAC0002#m1"; // dbXxx("S001")
        String duB = "com.kakaopay.ac.acgo0001.biz.DAC0002#m2"; // dbXxx("S002")
        String graph = "{\"directed\":true,\"multigraph\":true,\"meta\":{},\"nodes\":["
                + node(puA, "com.kakaopay.ac.a.PA", "pA",
                       "\"entryPoint\":\"HTTP\",\"httpMethod\":\"POST\",\"endpoint\":\"/TA.jmd\"")
                + "," + node(fuA, "com.kakaopay.ac.a.FA", "fA", "\"visibility\":\"public\"")
                + "," + node(duA, "com.kakaopay.ac.acgo0001.biz.DAC0002", "m1", "\"visibility\":\"public\"")
                + "," + node(puB, "com.kakaopay.ac.b.PB", "pB",
                       "\"entryPoint\":\"HTTP\",\"httpMethod\":\"POST\",\"endpoint\":\"/TB.jmd\"")
                + "," + node(fuB, "com.kakaopay.ac.b.FB", "fB", "\"visibility\":\"public\"")
                + "," + node(duB, "com.kakaopay.ac.acgo0001.biz.DAC0002", "m2", "\"visibility\":\"public\"")
                + ",{\"id\":\"db:table:tb_acc\",\"fqcn\":\"db:table:tb_acc\",\"method\":\"tb_acc\"}"
                + "],\"edges\":["
                + edge(puA, fuA, null) + "," + edge(fuA, duA, null) + "," + edge(duA, "db:table:tb_acc", "S001")
                + "," + edge(puB, fuB, null) + "," + edge(fuB, duB, null) + "," + edge(duB, "db:table:tb_acc", "S002")
                + "]}";
        Path graphFile = repo.resolve("graph.json");
        Files.writeString(graphFile, graph);

        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        Path xsql = repo.resolve("acc-app-ac/src/main/java/com/kakaopay/ac/acgo0001/xsql/DAC0002.xsql");
        Files.createDirectories(xsql.getParent());
        String v1 = "<sqlMap namespace=\"DAC0002\">\n"
                + "  <select id=\"S001\">\n"
                + "    SELECT col_a FROM tb_acc WHERE 1=1\n"
                + "  </select>\n"
                + "  <select id=\"S002\">\n"
                + "    SELECT col_b FROM tb_acc WHERE 1=1\n"
                + "  </select>\n"
                + "</sqlMap>\n";
        Files.writeString(xsql, v1);
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "init xsql");

        // the "PR" commit: change ONLY S001's SQL (line 3)
        Files.writeString(xsql, v1.replace("SELECT col_a FROM tb_acc WHERE 1=1",
                "SELECT col_a, col_c FROM tb_acc WHERE 1=1"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "PR: tune S001 only");
        String prSha = gitOut(repo, "rev-parse", "HEAD").trim();

        GitHub.Pr pr = new GitHub.Pr(42, "tune S001", "alice", "2026-06-20T00:00:00Z", prSha);
        PrImpact.Result res = PrImpact.analyze(new GitLog(repo), "main", List.of(pr), graphFile);

        // shard: changedNodes records the S001 DataUnit method, not the S002 one
        Map<String, Object> shard = res.shards.get(42);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changedNodes = (List<Map<String, Object>>) shard.get("changedNodes");
        Set<String> seeded = changedNodes.stream().map(n -> (String) n.get("id")).collect(Collectors.toSet());
        assertTrue(seeded.contains(duA), "S001 DataUnit method (m1) in changedNodes");
        assertFalse(seeded.contains(duB), "S002 DataUnit method (m2) NOT in changedNodes");

        // index row: only the S001 ProcessUnit is impacted
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pulls = (List<Map<String, Object>>) res.index.get("pulls");
        Map<String, Object> row = pulls.stream()
                .filter(p -> Integer.valueOf(42).equals(p.get("number"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eps = (List<Map<String, Object>>) row.get("impactedEndpoints");
        Set<String> epIds = eps.stream().map(e -> (String) e.get("id")).collect(Collectors.toSet());
        assertTrue(epIds.contains(puA), "S001 ProcessUnit impacted");
        assertFalse(epIds.contains(puB), "S002 ProcessUnit NOT impacted");
    }

    private static String node(String id, String fqcn, String method, String extra) {
        return "{\"id\":\"" + id + "\",\"fqcn\":\"" + fqcn + "\",\"method\":\"" + method + "\"," + extra + "}";
    }

    private static String edge(String src, String dst, String sqlId) {
        String s = "{\"source\":\"" + src + "\",\"target\":\"" + dst + "\",\"relation\":\""
                + (sqlId == null ? "local-call" : "db:io") + "\"";
        if (sqlId != null) s += ",\"sqlId\":\"" + sqlId + "\"";
        return s + "}";
    }

    private static void git(Path repo, String... args) throws IOException, InterruptedException {
        gitOut(repo, args);
    }

    private static String gitOut(Path repo, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}
