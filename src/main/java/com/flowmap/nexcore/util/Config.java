package com.flowmap.nexcore.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code flowmap.config} (or {@code $FLOWMAP_CONFIG}) when the analyzer is run
 * with no CLI args — mirrors flowmap-spring. {@code KEY=VALUE}, {@code #} comments,
 * {@code ${VAR}}/{@code $VAR} substitution (prior keys, then environment).
 */
public final class Config {

    private Config() {
    }

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}|\\$([A-Za-z0-9_]+)");

    /** Build an argv from the config file, or null if no config present. */
    public static String[] toArgs(Path repoRoot) {
        String env = System.getenv("FLOWMAP_CONFIG");
        Path cfg = env != null ? Path.of(env) : repoRoot.resolve("flowmap.config");
        if (!Files.isRegularFile(cfg)) return null;

        Map<String, String> kv = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = subst(line.substring(eq + 1).trim(), kv);
                kv.put(key, val);
            }
        } catch (IOException e) {
            return null;
        }

        String command = kv.getOrDefault("COMMAND", "refresh");
        List<String> argv = new ArrayList<>();
        argv.add(command);
        if (kv.containsKey("REPO")) {
            argv.add("--repo");
            argv.add(kv.get("REPO"));
        }
        if (kv.containsKey("OUT_DIR")) {
            argv.add("--out-dir");
            argv.add(kv.get("OUT_DIR"));
        }
        if (kv.containsKey("EXTRA_ARGS") && !kv.get("EXTRA_ARGS").isEmpty()) {
            for (String tok : kv.get("EXTRA_ARGS").split("\\s+")) argv.add(tok);
        }
        return argv.toArray(new String[0]);
    }

    private static String subst(String s, Map<String, String> kv) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            String rep = kv.containsKey(name) ? kv.get(name)
                    : System.getenv(name) != null ? System.getenv(name) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
