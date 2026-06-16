package com.flowmap.nexcore.model;

/** Sync/async call mode — mirrors flowmap-spring's {@code CallMode}. */
public enum CallMode {
    SYNC("sync"), ASYNC("async");

    public final String json;

    CallMode(String json) {
        this.json = json;
    }
}
