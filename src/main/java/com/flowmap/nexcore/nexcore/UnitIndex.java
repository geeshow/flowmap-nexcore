package com.flowmap.nexcore.nexcore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves call targets to {@link UnitClass}es. Mirrors the reference plugin's
 * strategy: try an exact-package match (the {@code findClass} path), else fall back
 * to a simple-name lookup (the {@code FilenameIndex} path) since NEXCORE keeps
 * one unit = one file = one class with a unique class name.
 */
public final class UnitIndex {

    private final Map<String, List<UnitClass>> bySimple = new LinkedHashMap<>();
    private final Map<String, UnitClass> byFqcn = new LinkedHashMap<>();
    /** xsql base name → (sqlId → Stmt). */
    private final Map<String, Map<String, XsqlParser.Stmt>> xsql;

    public UnitIndex(SourceScanner.Scan scan) {
        this.xsql = scan.xsql;
        for (UnitClass u : scan.units) {
            bySimple.computeIfAbsent(u.simpleName, k -> new ArrayList<>()).add(u);
            byFqcn.putIfAbsent(u.fqcn, u);
        }
    }

    public UnitClass byFqcn(String fqcn) {
        return byFqcn.get(fqcn);
    }

    /**
     * Find a unit by simple name, preferring one whose package equals {@code preferredPkg}.
     * Returns null if no class with that simple name is indexed (→ caller emits an external
     * placeholder to be reconnected in combine).
     */
    public UnitClass resolve(String simpleName, String preferredPkg) {
        List<UnitClass> list = bySimple.get(simpleName);
        if (list == null || list.isEmpty()) return null;
        if (preferredPkg != null) {
            for (UnitClass u : list) {
                if (preferredPkg.equals(u.pkg)) return u;
            }
        }
        return list.get(0);
    }

    /** xsql statement for a DataUnit/batch class + sqlId, or null. */
    public XsqlParser.Stmt sql(String classSimpleName, String sqlId) {
        Map<String, XsqlParser.Stmt> m = xsql.get(classSimpleName);
        return m == null ? null : m.get(sqlId);
    }

    public List<UnitClass> allUnits() {
        List<UnitClass> out = new ArrayList<>();
        for (List<UnitClass> l : bySimple.values()) out.addAll(l);
        return out;
    }
}
