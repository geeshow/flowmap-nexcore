package com.flowmap.nexcore.model;

/** Edge kind — mirrors flowmap-spring's {@code EdgeKind} json values. */
public enum EdgeKind {
    INTERNAL("internal"), EXTERNAL("external"), BATCH("batch"), S2S("s2s"),
    RESOURCE("resource"), GATEWAY("gateway");

    public final String json;

    EdgeKind(String json) {
        this.json = json;
    }
}
