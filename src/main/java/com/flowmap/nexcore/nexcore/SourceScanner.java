package com.flowmap.nexcore.nexcore;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Walks a NEXCORE source tree, parses {@code *.java} with JavaParser and indexes
 * {@code *.xsql} SQL maps. Recognises units by base class (ProcessUnit/FunctionUnit/
 * DataUnit) and batch jobs by {@code MBBatchComponent}/{@code @BizBatch}.
 *
 * <p>A unit's base is matched even when it sits behind an abstract framework layer
 * ({@code FCA0049 extends AbstractFunctionUnit extends FunctionUnit}): the direct
 * super is matched by name suffix ({@code *FunctionUnit}) and, failing that, the
 * super chain is followed across the scan ({@code superIndex}). Missing this is the
 * root cause of shared-call targets staying {@code ext:SHARED#} (external) instead of
 * resolving to real {@code s2s} edges — the unit was never indexed.
 */
public final class SourceScanner {

    /** Result of scanning a repo (optionally filtered to one project/module dir). */
    public static final class Scan {
        public final Path repoRoot;
        public final List<UnitClass> units = new ArrayList<>();
        /** xsql base name (e.g. "DACU6017") → (sqlId → Stmt). */
        public final Map<String, Map<String, XsqlParser.Stmt>> xsql = new LinkedHashMap<>();
        /** repo-relative path → absolute path, for all parsed .java files. */
        public final Map<String, Path> javaFiles = new LinkedHashMap<>();

        Scan(Path repoRoot) {
            this.repoRoot = repoRoot;
        }
    }

    private SourceScanner() {
    }

    private static JavaParser newParser() {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setAttributeComments(false);
        return new JavaParser(cfg);
    }

    /**
     * @param repoRoot  repo root (e.g. .../nexcore)
     * @param project   optional single module dir under repoRoot (e.g. "acc-app-ac"); null = whole repo
     */
    public static Scan scan(Path repoRoot, String project) {
        Scan scan = new Scan(repoRoot);
        Path root = project == null ? repoRoot : repoRoot.resolve(project);
        if (!Files.isDirectory(root)) return scan;
        JavaParser parser = newParser();
        // Pass 1: parse every .java, collecting candidate classes and a repo-wide
        // simpleName → direct-super-simpleName index so the super chain can be followed.
        List<Candidate> candidates = new ArrayList<>();
        Map<String, String> superIndex = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (name.endsWith(".xsql")) {
                    String base = name.substring(0, name.length() - ".xsql".length());
                    scan.xsql.put(base, XsqlParser.parse(p));
                } else if (name.endsWith(".java")) {
                    parseJava(scan, repoRoot, p, parser, candidates, superIndex);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + root, e);
        }
        // Pass 2: classify by base, resolving abstract framework layers transitively.
        Map<String, Integer> skippedSupers = new TreeMap<>();
        for (Candidate c : candidates) {
            UnitClass uc = classify(c, superIndex, skippedSupers);
            if (uc != null) scan.units.add(uc);
        }
        if (!skippedSupers.isEmpty()) {
            // Diagnostics: supers that looked unit-like context but resolved to no base. A unit
            // base appearing here (high count) signals an unrecognised framework layer to add.
            String top = skippedSupers.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            System.err.println("[scan] " + (project == null ? "<repo>" : project)
                    + ": units=" + scan.units.size() + ", skipped supers=[" + top + "]");
        }
        return scan;
    }

    /** A parsed concrete class awaiting base classification in pass 2. */
    private static final class Candidate {
        final ClassOrInterfaceDeclaration cls;
        final String pkg;
        final String rel;
        final String superSimple;
        final boolean bizBatch;

        Candidate(ClassOrInterfaceDeclaration cls, String pkg, String rel,
                  String superSimple, boolean bizBatch) {
            this.cls = cls;
            this.pkg = pkg;
            this.rel = rel;
            this.superSimple = superSimple;
            this.bizBatch = bizBatch;
        }
    }

    private static void parseJava(Scan scan, Path repoRoot, Path file, JavaParser parser,
                                  List<Candidate> candidates, Map<String, String> superIndex) {
        String rel = rel(repoRoot, file);
        scan.javaFiles.put(rel, file);
        ParseResult<CompilationUnit> res;
        try {
            res = parser.parse(file);
        } catch (IOException e) {
            return;
        }
        if (!res.isSuccessful() || res.getResult().isEmpty()) return;
        CompilationUnit cu = res.getResult().get();
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) continue;
            String superSimple = directSuper(cls);
            // index EVERY class (incl. abstract framework bases) so the chain can be followed
            superIndex.putIfAbsent(cls.getNameAsString(), superSimple);
            if (cls.isAbstract()) continue; // abstract = framework base, never a concrete unit node
            boolean bizBatch = cls.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals(NexcoreModel.BIZ_BATCH_SIMPLE)
                            || a.getNameAsString().endsWith(".BizBatch"));
            candidates.add(new Candidate(cls, pkg, rel, superSimple, bizBatch));
        }
    }

    private static UnitClass classify(Candidate c, Map<String, String> superIndex,
                                      Map<String, Integer> skippedSupers) {
        ClassOrInterfaceDeclaration cls = c.cls;
        String simple = cls.getNameAsString();
        String fqcn = c.pkg.isEmpty() ? simple : c.pkg + "." + simple;
        String project = projectOf(c.rel);
        String module = moduleOf(c.pkg);

        String base = resolveBase(c.superSimple, superIndex);
        NexcoreModel.NodeType type = null;
        boolean batch = false;
        if ("ProcessUnit".equals(base)) {
            type = NexcoreModel.NodeType.PM;
        } else if ("FunctionUnit".equals(base)) {
            type = NexcoreModel.NodeType.FM;
        } else if ("DataUnit".equals(base)) {
            type = NexcoreModel.NodeType.DM;
        } else if (NexcoreModel.MB_BATCH_SIMPLE.equals(base) || c.bizBatch) {
            type = NexcoreModel.NodeType.BATCH_JOB;
            batch = true;
        }
        if (type == null) {
            if (c.superSimple != null) skippedSupers.merge(c.superSimple, 1, Integer::sum);
            return null; // not a unit/batch class
        }
        return new UnitClass(simple, fqcn, c.pkg, c.rel, project, module, type, batch, cls);
    }

    /**
     * Resolve a class's unit base ({@code ProcessUnit}/{@code FunctionUnit}/{@code DataUnit}/
     * {@code MBBatchComponent}) from its direct super, transparently crossing abstract framework
     * layers. At each hop the name is matched by suffix ({@code AbstractFunctionUnit} →
     * {@code FunctionUnit}) — which works even when the intermediate is out of scan scope — and,
     * failing that, the super chain is followed via {@code superIndex}. Null = not a unit.
     */
    private static String resolveBase(String superSimple, Map<String, String> superIndex) {
        String cur = superSimple;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            String m = matchBaseName(cur);
            if (m != null) return m;
            cur = superIndex.get(cur);
        }
        return null;
    }

    /** Map a super simple-name to a known unit base by exact match or name suffix, else null. */
    private static String matchBaseName(String name) {
        if (name == null) return null;
        if (name.equals("ProcessUnit") || name.endsWith("ProcessUnit")) return "ProcessUnit";
        if (name.equals("FunctionUnit") || name.endsWith("FunctionUnit")) return "FunctionUnit";
        if (name.equals("DataUnit") || name.endsWith("DataUnit")) return "DataUnit";
        if (name.equals(NexcoreModel.MB_BATCH_SIMPLE) || name.endsWith("BatchComponent")) {
            return NexcoreModel.MB_BATCH_SIMPLE;
        }
        return null;
    }

    private static String directSuper(ClassOrInterfaceDeclaration cls) {
        for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
            return simpleOf(ext.getNameWithScope());
        }
        return null;
    }

    // ---------- helpers ----------

    static String rel(Path repoRoot, Path file) {
        return repoRoot.relativize(file).toString().replace('\\', '/');
    }

    static String projectOf(String rel) {
        int slash = rel.indexOf('/');
        return slash > 0 ? rel.substring(0, slash) : rel;
    }

    /** "...ac.acgo0016.biz" → "acgo0016"; "...ac.batch" → "batch". */
    static String moduleOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String[] seg = pkg.split("\\.");
        for (int i = 1; i < seg.length; i++) {
            if (seg[i].equals("biz")) return seg[i - 1];
        }
        return seg[seg.length - 1];
    }

    private static String simpleOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        String s = dot >= 0 ? name.substring(dot + 1) : name;
        int lt = s.indexOf('<');
        return lt >= 0 ? s.substring(0, lt) : s;
    }
}
