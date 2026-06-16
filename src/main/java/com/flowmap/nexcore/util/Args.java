package com.flowmap.nexcore.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal {@code --key value} / {@code --flag} parser. First token is the command. */
public final class Args {
    public final String command;
    private final Map<String, String> opts = new LinkedHashMap<>();
    private final List<String> flags = new ArrayList<>();

    private Args(String command) {
        this.command = command;
    }

    public static Args parse(String[] argv) {
        String cmd = argv.length > 0 ? argv[0] : "refresh";
        Args a = new Args(cmd);
        for (int i = 1; i < argv.length; i++) {
            String t = argv[i];
            if (t.startsWith("--")) {
                String key = t.substring(2);
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    a.opts.put(key, argv[++i]);
                } else {
                    a.flags.add(key);
                }
            }
        }
        return a;
    }

    public String get(String key, String def) {
        return opts.getOrDefault(key, def);
    }

    public String get(String key) {
        return opts.get(key);
    }

    public boolean has(String flag) {
        return flags.contains(flag);
    }

    public int getInt(String key, int def) {
        String v = opts.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
