package com.flowmap.nexcore;

import com.flowmap.nexcore.impact.GitLog;
import com.flowmap.nexcore.impact.Impact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statement-level (option B) xsql impact: one xsql file owns two statements (S001, S002),
 * each reached by a different ProcessUnit. Editing only S001's SQL must impact only the
 * S001 ProcessUnit — the S002 path stays untouched. Relies on the {@code sqlId} recorded on
 * {@code db:io} edges plus {@link com.flowmap.nexcore.impact.XsqlDiff} hunk→statement mapping.
 */
class XsqlSqlIdImpactTest {

    @Test
    void editingOneStatementImpactsOnlyThatProcessUnit(@TempDir Path repo) throws Exception {
        // two independent chains through the same DataUnit class DAC0002, distinguished by sqlId
        String puA = "com.kakaopay.ac.a.PA#pA", fuA = "com.kakaopay.ac.a.FA#fA";
        String puB = "com.kakaopay.ac.b.PB#pB", fuB = "com.kakaopay.ac.b.FB#fB";
        String duA = "com.kakaopay.ac.acgo0001.biz.DAC0002#m1"; // calls dbXxx("S001")
        String duB = "com.kakaopay.ac.acgo0001.biz.DAC0002#m2"; // calls dbXxx("S002")
        String graph = "{\"directed\":true,\"multigraph\":true,\"meta\":{},\"nodes\":["
                + node(puA, "com.kakaopay.ac.a.PA", "pA",
                       "\"entryPoint\":\"HTTP\",\"httpMethod\":\"POST\",\"endpoint\":\"/TA.jmd\"")
                + "," + node(fuA, "com.kakaopay.ac.a.FA", "fA", "\"entryPoint\":null")
                + "," + node(duA, "com.kakaopay.ac.acgo0001.biz.DAC0002", "m1", "\"entryPoint\":null")
                + "," + node(puB, "com.kakaopay.ac.b.PB", "pB",
                       "\"entryPoint\":\"HTTP\",\"httpMethod\":\"POST\",\"endpoint\":\"/TB.jmd\"")
                + "," + node(fuB, "com.kakaopay.ac.b.FB", "fB", "\"entryPoint\":null")
                + "," + node(duB, "com.kakaopay.ac.acgo0001.biz.DAC0002", "m2", "\"entryPoint\":null")
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

        // change ONLY the S001 statement body (line 3)
        Files.writeString(xsql, v1.replace("SELECT col_a FROM tb_acc WHERE 1=1",
                "SELECT col_a, col_c FROM tb_acc WHERE 1=1"));
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "tune S001 only");

        // max=1 → analyze just the latest (S001-only) commit
        LinkedHashMap<String, Object> out =
                Impact.run(new GitLog(repo), graphFile, "main", 1, 5, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commits = (List<Map<String, Object>>) out.get("commits");
        assertEquals(1, commits.size());
        Map<String, Object> c0 = commits.get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changedNodes = (List<Map<String, Object>>) c0.get("changedNodes");
        Set<String> seededIds = changedNodes.stream()
                .map(n -> (String) n.get("id")).collect(Collectors.toSet());
        assertTrue(seededIds.contains(duA), "S001 call site (m1) seeded");
        assertFalse(seededIds.contains(duB), "S002 call site (m2) NOT seeded");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) c0.get("impactedEndpoints");
        Set<String> epIds = endpoints.stream().map(e -> (String) e.get("id")).collect(Collectors.toSet());
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
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }
}
