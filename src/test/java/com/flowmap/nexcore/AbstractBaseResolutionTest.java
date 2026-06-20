package com.flowmap.nexcore;

import com.flowmap.nexcore.model.CallGraph;
import com.flowmap.nexcore.nexcore.GraphBuilder;
import com.flowmap.nexcore.nexcore.SourceScanner;
import com.flowmap.nexcore.nexcore.UnitClass;
import com.flowmap.nexcore.nexcore.UnitIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Units that reach their {@code ProcessUnit}/{@code FunctionUnit}/{@code DataUnit} base through an
 * abstract framework layer ({@code FCA0049 extends AbstractFunctionUnit extends FunctionUnit}) must
 * still be indexed — otherwise a cross-project {@code callSharedMethod} target to them is emitted as
 * {@code ext:SHARED#} (external) instead of resolving to a real {@code s2s} edge. This was the root
 * cause of endpoints showing up as external on the operational repo.
 */
class AbstractBaseResolutionTest {

    /** Write a .java file under repo/<project>/.../biz/. */
    private static void java(Path repo, String project, String comp, String cls, String body) throws IOException {
        Path dir = repo.resolve(project).resolve("src/main/java/com/k/" + comp + "/biz");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(cls + ".java"),
                "package com.k." + comp + ".biz;\n" + body);
    }

    /** Framework base module: ProcessUnit/FunctionUnit + an abstract intermediate. */
    private static void fwk(Path repo) throws IOException {
        Path dir = repo.resolve("fwk/src/main/java/com/k/base");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("FunctionUnit.java"),
                "package com.k.base;\npublic abstract class FunctionUnit {}\n");
        Files.writeString(dir.resolve("AbstractFunctionUnit.java"),
                "package com.k.base;\npublic abstract class AbstractFunctionUnit extends FunctionUnit {}\n");
    }

    @Test
    void indexesUnitBehindAbstractBaseAndResolvesSharedCallAsS2s(@TempDir Path repo) throws IOException {
        fwk(repo);
        // provider in project "ca": FCA0049 sits behind an abstract layer.
        java(repo, "ca", "ca0049",
                "FCA0049",
                "import com.k.base.AbstractFunctionUnit;\n"
                        + "public class FCA0049 extends AbstractFunctionUnit {\n"
                        + "  public Object fCA0049(Object in) { return in; }\n}\n");
        // caller in project "ac": shared-call into ca's FCA0049.
        java(repo, "ac", "ac0001",
                "FAC0001",
                "import com.k.base.FunctionUnit;\n"
                        + "public class FAC0001 extends FunctionUnit {\n"
                        + "  public Object fAC0001(Object in) {\n"
                        + "    return callSharedMethodByDirect(\"ca.ca0049\", \"FCA0049.fCA0049\", in);\n"
                        + "  }\n}\n");

        // 1) the unit behind the abstract base is indexed
        SourceScanner.Scan all = SourceScanner.scan(repo, null);
        Set<String> names = all.units.stream().map(u -> u.simpleName).collect(Collectors.toSet());
        assertTrue(names.contains("FCA0049"), "FCA0049 (behind AbstractFunctionUnit) must be indexed");
        assertTrue(names.contains("FAC0001"));
        assertFalse(names.contains("AbstractFunctionUnit"), "abstract base must not be a unit node");

        // 2) buildProject resolves the shared call to a real cross-project s2s edge (no ext:SHARED#)
        UnitIndex global = new UnitIndex(all);
        CallGraph g = GraphBuilder.buildProject(global, "ac");
        boolean anyExtShared = g.edges().stream().anyMatch(e -> e.target.startsWith("ext:SHARED#"));
        assertFalse(anyExtShared, "shared call must resolve, not stay ext:SHARED#");
        long s2s = g.edges().stream().filter(e -> "S2S".equals(e.kind.name())).count();
        assertEquals(1, s2s, "the cross-project shared call should be one s2s edge");
    }

    @Test
    void perProjectScanStillIndexesUnitWhenBaseIsOutOfScope(@TempDir Path repo) throws IOException {
        fwk(repo);
        java(repo, "ca", "ca0049",
                "FCA0049",
                "import com.k.base.AbstractFunctionUnit;\n"
                        + "public class FCA0049 extends AbstractFunctionUnit {\n"
                        + "  public Object fCA0049(Object in) { return in; }\n}\n");
        // scope to project "ca" only — the abstract base lives in fwk/, out of scope. The name-suffix
        // heuristic (AbstractFunctionUnit → FunctionUnit) must still classify it.
        SourceScanner.Scan ca = SourceScanner.scan(repo, "ca");
        assertEquals(1, ca.units.size());
        UnitClass u = ca.units.get(0);
        assertEquals("FCA0049", u.simpleName);
        assertEquals("FM", u.type.name());
    }
}
