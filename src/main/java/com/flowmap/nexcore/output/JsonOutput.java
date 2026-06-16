package com.flowmap.nexcore.output;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stable, diff-friendly JSON output. Matches flowmap-spring's writer:
 * 2-space indentation, spaces around the object {@code :} separator, and the
 * insertion order of {@link java.util.LinkedHashMap} preserved (no key sorting).
 */
public final class JsonOutput {

    private static final ObjectWriter WRITER = buildWriter();

    private JsonOutput() {
    }

    private static ObjectWriter buildWriter() {
        // Match flowmap-spring exactly: 2-space newline-indented object fields,
        // default FixedSpace array indenter ("[ {" inline, "}, {" boundaries),
        // and "key" : value spacing (space before and after the colon).
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        pp = (DefaultPrettyPrinter) pp.withSeparators(
                com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(com.fasterxml.jackson.core.util.Separators.Spacing.BOTH));
        return new ObjectMapper().writer(pp);
    }

    public static String toString(Object value) {
        try {
            return WRITER.writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /** Write to {@code out} (created/overwritten), or to stdout when {@code out} is null. */
    public static void write(Object value, Path out) {
        String json = toString(value);
        if (out == null) {
            PrintStream ps = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            ps.println(json);
            return;
        }
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, json + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + out, e);
        }
    }
}
