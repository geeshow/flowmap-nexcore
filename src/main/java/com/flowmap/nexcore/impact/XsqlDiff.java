package com.flowmap.nexcore.impact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps changed line ranges in an iBATIS {@code *.xsql} SQL map to the statement ids
 * (sqlIds) they touch. Each {@code <select|insert|update|delete|procedure id="...">}
 * element occupies a line range; a git hunk overlapping that range means that sqlId's
 * SQL changed, so only the DataUnit methods calling {@code dbXxx("<id>")} need re-impacting.
 *
 * <p>Conservative by design: if a changed hunk is not fully contained inside a single
 * statement (e.g. it edits the {@code <sqlMap>} header, a shared {@code <sql>} fragment,
 * a {@code resultMap}, or straddles two statements) the result is marked {@link #coarse},
 * signalling the caller to fall back to file-level (whole-DataUnit) seeding rather than
 * risk a false negative.
 */
public final class XsqlDiff {

    private XsqlDiff() {
    }

    /** Statement tags whose {@code id} a {@code dbXxx(sqlId)} call can select. */
    private static final Pattern STMT = Pattern.compile(
            "<(select|insert|update|delete|procedure)\\b[^>]*?\\bid\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static final class Result {
        /** sqlIds whose statement body overlaps a changed hunk. */
        public final Set<String> ids;
        /** true → a change fell outside/across statements; caller should seed the whole DataUnit. */
        public final boolean coarse;

        Result(Set<String> ids, boolean coarse) {
            this.ids = ids;
            this.coarse = coarse;
        }
    }

    private static final class Stmt {
        final String id;
        final int start;
        final int end;

        Stmt(String id, int start, int end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * @param text  xsql file content at the changed revision (new side, so line numbers
     *              align with the new-side hunk ranges)
     * @param hunks new-side changed line ranges ({@code [start, end]}, 1-based, inclusive)
     */
    public static Result of(String text, List<int[]> hunks) {
        Set<String> ids = new LinkedHashSet<>();
        if (text == null || text.isBlank() || hunks == null || hunks.isEmpty()) {
            return new Result(ids, true); // can't reason precisely → fall back to whole DataUnit
        }
        List<Stmt> stmts = new ArrayList<>();
        Matcher m = STMT.matcher(text);
        while (m.find()) {
            stmts.add(new Stmt(m.group(2), lineAt(text, m.start()), lineAt(text, m.end() - 1)));
        }
        boolean coarse = false;
        for (int[] h : hunks) {
            int hs = h[0], he = h[1];
            Stmt container = null;
            for (Stmt st : stmts) {
                if (st.start <= he && hs <= st.end) {       // overlaps this statement
                    ids.add(st.id);
                    if (st.start <= hs && he <= st.end) container = st; // fully inside one
                }
            }
            if (container == null) coarse = true;           // outside / straddling → be safe
        }
        return new Result(ids, coarse);
    }

    /** 1-based line number of character offset {@code off} in {@code text}. */
    private static int lineAt(String text, int off) {
        int line = 1;
        int limit = Math.min(off, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
