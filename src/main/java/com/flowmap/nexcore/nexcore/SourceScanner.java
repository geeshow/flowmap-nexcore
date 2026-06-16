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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks a NEXCORE source tree, parses {@code *.java} with JavaParser and indexes
 * {@code *.xsql} SQL maps. Recognises units by base class (ProcessUnit/FunctionUnit/
 * DataUnit) and batch jobs by {@code MBBatchComponent}/{@code @BizBatch}.
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
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (name.endsWith(".xsql")) {
                    String base = name.substring(0, name.length() - ".xsql".length());
                    scan.xsql.put(base, XsqlParser.parse(p));
                } else if (name.endsWith(".java")) {
                    parseJava(scan, repoRoot, p, parser);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + root, e);
        }
        return scan;
    }

    private static void parseJava(Scan scan, Path repoRoot, Path file, JavaParser parser) {
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
            UnitClass uc = classify(cls, pkg, rel, repoRoot);
            if (uc != null) scan.units.add(uc);
        }
    }

    private static UnitClass classify(ClassOrInterfaceDeclaration cls, String pkg, String rel, Path repoRoot) {
        String simple = cls.getNameAsString();
        String fqcn = pkg.isEmpty() ? simple : pkg + "." + simple;
        String project = projectOf(rel);
        String module = moduleOf(pkg);

        String superSimple = null;
        for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
            superSimple = simpleOf(ext.getNameWithScope());
            break;
        }
        boolean bizBatch = cls.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(NexcoreModel.BIZ_BATCH_SIMPLE)
                        || a.getNameAsString().endsWith(".BizBatch"));

        NexcoreModel.NodeType type = null;
        boolean batch = false;
        if ("ProcessUnit".equals(superSimple)) {
            type = NexcoreModel.NodeType.PM;
        } else if ("FunctionUnit".equals(superSimple)) {
            type = NexcoreModel.NodeType.FM;
        } else if ("DataUnit".equals(superSimple)) {
            type = NexcoreModel.NodeType.DM;
        } else if (NexcoreModel.MB_BATCH_SIMPLE.equals(superSimple) || bizBatch) {
            type = NexcoreModel.NodeType.BATCH_JOB;
            batch = true;
        }
        if (type == null) return null; // not a unit/batch class
        return new UnitClass(simple, fqcn, pkg, rel, project, module, type, batch, cls);
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
