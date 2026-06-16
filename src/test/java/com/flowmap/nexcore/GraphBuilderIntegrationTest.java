package com.flowmap.nexcore;

import com.flowmap.nexcore.model.CallEdge;
import com.flowmap.nexcore.model.CallGraph;
import com.flowmap.nexcore.model.MethodNode;
import com.flowmap.nexcore.nexcore.GraphBuilder;
import com.flowmap.nexcore.nexcore.SourceScanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end check against the sibling NEXCORE sample repo. Skipped when that repo
 * is not present (so CI without the fixture still passes).
 */
class GraphBuilderIntegrationTest {

    private static final Path REPO = Path.of("../nexcore");

    @Test
    void acgo0016CoversAllCallKindsAndHelperInlining() {
        assumeTrue(Files.isDirectory(REPO), "../nexcore sample not present");
        SourceScanner.Scan scan = SourceScanner.scan(REPO, "acc-app-ac");
        CallGraph g = GraphBuilder.build(scan);

        String pu = "com.kakaopay.moneyball.ac.acgo0016.biz.PACU6017#pACU6017";
        MethodNode puNode = g.node(pu);
        assertNotNull(puNode, "PACU6017 entry node");
        assertEquals("CONTROLLER", puNode.layer.name());
        assertEquals("/TACU6017.jmd", puNode.endpoint);
        assertEquals("POST", puNode.httpMethod);
        assertEquals("HTTP", puNode.entryPoint.name());

        // helper inlining: _A0000 → FACU6017 (local-call), _A1000 → FACU6018 / shared / kafka
        Set<String> relsFromPu = g.edges().stream()
                .filter(e -> e.source.equals(pu)).map(e -> e.relation).collect(Collectors.toSet());
        assertTrue(relsFromPu.contains("local-call"));
        assertTrue(relsFromPu.contains("local-new-tx"));
        assertTrue(relsFromPu.contains("shared-call"));
        assertTrue(relsFromPu.contains("shared-new-tx"));
        assertTrue(relsFromPu.contains("kafka-publish"));

        // FACU6017 carries the linked/batch/outbound coverage
        String fu = "com.kakaopay.moneyball.ac.acgo0016.biz.FACU6017#fACU6017";
        Set<String> relsFromFu = g.edges().stream()
                .filter(e -> e.source.equals(fu)).map(e -> e.relation).collect(Collectors.toSet());
        for (String rel : new String[]{"linked-tx-sync", "linked-tx-sync-new", "linked-tx-async-now",
                "linked-tx-async-after-commit", "linked-tx-delay-async", "batch-now", "batch-after-commit",
                "fep-sync", "edw-sync", "fep-async-now", "fep-async-after-commit", "local-call"}) {
            assertTrue(relsFromFu.contains(rel), "FACU6017 missing relation " + rel);
        }

        // DataUnit → db:table resource edge
        assertTrue(g.nodes().stream().anyMatch(n -> n.id.equals("db:table:tb_cust_base")));
    }

    @Test
    void batchSharedCallBecomesExternalPlaceholder() {
        assumeTrue(Files.isDirectory(REPO), "../nexcore sample not present");
        SourceScanner.Scan scan = SourceScanner.scan(REPO, "acc-bat-ac");
        CallGraph g = GraphBuilder.build(scan);

        String bat = "com.kakaopay.moneyball.ac.batch.BSAC0003#execute";
        assertNotNull(g.node(bat));
        boolean toOnline = g.edges().stream().anyMatch(e -> e.source.equals(bat)
                && e.target.equals("ext:SHARED#com.kakaopay.moneyball.ac.acgo0010.FAC5134.fAC5134")
                && e.relation.equals("shared-call"));
        assertTrue(toOnline, "batch → online shared placeholder");
    }
}
