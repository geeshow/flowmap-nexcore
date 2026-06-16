package com.flowmap.nexcore.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A graph edge. {@code relation} carries the NEXCORE {@code CallKind} wire string
 * (e.g. {@code shared-call}, {@code linked-tx-async-now}, {@code kafka-publish},
 * {@code db:io}) so the 16-kind richness is preserved while staying schema-compatible
 * with flowmap-spring's {@code CallEdge}.
 */
public final class CallEdge {
    public String source;
    public String target;
    public CallMode mode;
    public EdgeKind kind;
    public String relation;
    public String callSiteFile;
    public Integer callSiteLine;

    public CallEdge(String source, String target, CallMode mode, EdgeKind kind,
                    String relation, String callSiteFile, Integer callSiteLine) {
        this.source = source;
        this.target = target;
        this.mode = mode;
        this.kind = kind;
        this.relation = relation;
        this.callSiteFile = callSiteFile;
        this.callSiteLine = callSiteLine;
    }

    /** Dedup key — identical semantics to flowmap-spring's {@code CallEdge.key()}. */
    public List<Object> key() {
        return Arrays.asList(source, target, relation, callSiteLine);
    }

    public LinkedHashMap<String, Object> toJson() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("mode", mode.json);
        m.put("kind", kind.json);
        m.put("relation", relation);
        m.put("callSiteFile", callSiteFile);
        m.put("callSiteLine", callSiteLine);
        return m;
    }
}
