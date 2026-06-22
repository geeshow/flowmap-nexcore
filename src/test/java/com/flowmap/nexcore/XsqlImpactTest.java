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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-level xsql change impact: a commit touching only {@code <X>.xsql} must seed every
 * method node of its owning DataUnit ({@code <X>} class) and — via reverse-BFS over callers —
 * surface the FunctionUnit and ProcessUnit endpoint above it. Builds a throwaway git repo so
 * the test is self-contained (no dependency on the ../nexcore sample).
 */
class XsqlImpactTest {

    @Test
    void xsqlChangeReachesOwningDataUnitAndEndpoint(@TempDir Path repo) throws Exception {
        // graph: PU#pAC0001 (HTTP entry) → FU#fAC0001 → DU(DAC0002)#s001 → db:table:tb_acc
        String pu = "com.kakaopay.ac.acgo0001.biz.PAC0001#pAC0001";
        String fu = "com.kakaopay.ac.acgo0001.biz.FAC0001#fAC0001";
        String du = "com.kakaopay.ac.acgo0001.biz.DAC0002#s001";
        String graph = "{\"directed\":true,\"multigraph\":true,\"meta\":{},\"nodes\":["
                + node(pu, "com.kakaopay.ac.acgo0001.biz.PAC0001", "pAC0001",
                       "\"entryPoint\":\"HTTP\",\"httpMethod\":\"POST\",\"endpoint\":\"/TAC0001.jmd\",\"project\":\"acc-app-ac\"")
                + "," + node(fu, "com.kakaopay.ac.acgo0001.biz.FAC0001", "fAC0001", "\"entryPoint\":null")
                + "," + node(du, "com.kakaopay.ac.acgo0001.biz.DAC0002", "s001", "\"entryPoint\":null")
                + ",{\"id\":\"db:table:tb_acc\",\"fqcn\":\"db:table:tb_acc\",\"method\":\"tb_acc\"}"
                + "],\"edges\":["
                + edge(pu, fu) + "," + edge(fu, du) + "," + edge(du, "db:table:tb_acc")
                + "]}";
        Path graphFile = repo.resolve("graph.json");
        Files.writeString(graphFile, graph);

        // a git repo whose only commit changes the DataUnit's xsql map
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        Path xsql = repo.resolve("acc-app-ac/src/main/java/com/kakaopay/ac/acgo0001/xsql/DAC0002.xsql");
        Files.createDirectories(xsql.getParent());
        Files.writeString(xsql, "<sqlMap><select id=\"s001\">SELECT * FROM tb_acc</select></sqlMap>\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "tune DAC0002 query");

        LinkedHashMap<String, Object> out =
                Impact.run(new GitLog(repo), graphFile, "main", 10, 5, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commits = (List<Map<String, Object>>) out.get("commits");
        assertTrue(commits.size() >= 1, "one commit analyzed");
        Map<String, Object> c0 = commits.get(0);

        // the DataUnit method node is seeded from the xsql filename
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changedNodes = (List<Map<String, Object>>) c0.get("changedNodes");
        assertTrue(changedNodes.stream().anyMatch(n -> du.equals(n.get("id"))),
                "DU node seeded from DAC0002.xsql");

        // reverse-BFS reached the ProcessUnit endpoint above the DU
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) c0.get("impactedEndpoints");
        assertTrue(endpoints.stream().anyMatch(e -> pu.equals(e.get("id"))),
                "PU endpoint impacted via FU→DU chain");

        // aggregate endpointImpact also lists it
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agg = (List<Map<String, Object>>) out.get("endpointImpact");
        assertNotNull(agg);
        assertTrue(agg.stream().anyMatch(e -> pu.equals(e.get("id"))), "endpointImpact lists PU");
    }

    private static String node(String id, String fqcn, String method, String extra) {
        return "{\"id\":\"" + id + "\",\"fqcn\":\"" + fqcn + "\",\"method\":\"" + method + "\"," + extra + "}";
    }

    private static String edge(String src, String dst) {
        return "{\"source\":\"" + src + "\",\"target\":\"" + dst + "\"}";
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
