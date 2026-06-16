package com.flowmap.nexcore.openapi;

import com.flowmap.nexcore.nexcore.NexcoreModel;
import com.flowmap.nexcore.nexcore.SourceScanner;
import com.flowmap.nexcore.nexcore.UnitClass;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates an OpenAPI 3.1 document from NEXCORE ProcessUnit transactions. Each PU
 * entry becomes {@code POST /<Tid>.jmd} whose {@code operationId} equals the call-graph
 * node id ({@code <fqcn>#<pmethod>}) so the docs and topology cross-link. Request/response
 * schemas come from the matching {@code uio/<Unit>.uio} spec when present, else a generic
 * {@code common_header}+{@code data} envelope.
 */
public final class OpenApi {

    private OpenApi() {
    }

    public static LinkedHashMap<String, Object> generate(SourceScanner.Scan scan, Path repoRoot,
                                                         String title, String version) {
        Map<String, UioParser.Io> uio = indexUio(repoRoot);

        LinkedHashMap<String, Object> paths = new LinkedHashMap<>();
        Set<String> tags = new LinkedHashSet<>();

        for (UnitClass u : scan.units) {
            if (u.type != NexcoreModel.NodeType.PM) continue;
            for (MethodDeclaration em : u.entryMethods()) {
                String tid = "T" + u.simpleName.substring(1);
                String path = "/" + tid + ".jmd";
                String opId = u.fqcn + "#" + em.getNameAsString();
                String summary = annotationOr(u, em);
                tags.add(u.project);

                LinkedHashMap<String, Object> op = new LinkedHashMap<>();
                op.put("operationId", opId);
                op.put("tags", List.of(u.project));
                if (summary != null) op.put("summary", summary);
                op.put("requestBody", body(schemaFromIo(uio.get(u.simpleName), true)));
                op.put("responses", responses(schemaFromIo(uio.get(u.simpleName), false)));

                LinkedHashMap<String, Object> verb = new LinkedHashMap<>();
                verb.put("post", op);
                paths.put(path, verb);
            }
        }

        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", version);

        List<Object> tagList = new ArrayList<>();
        for (String t : tags) {
            LinkedHashMap<String, Object> tg = new LinkedHashMap<>();
            tg.put("name", t);
            tagList.add(tg);
        }

        LinkedHashMap<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.1.0");
        doc.put("info", info);
        doc.put("tags", tagList);
        doc.put("paths", paths);
        LinkedHashMap<String, Object> components = new LinkedHashMap<>();
        components.put("schemas", new LinkedHashMap<>());
        doc.put("components", components);
        return doc;
    }

    private static Map<String, UioParser.Io> indexUio(Path repoRoot) {
        Map<String, UioParser.Io> map = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".uio"))
                    .forEach(p -> {
                        String f = p.getFileName().toString();
                        String base = f.substring(0, f.length() - ".uio".length());
                        UioParser.Io io = UioParser.parse(p);
                        if (io != null) map.put(base, io);
                    });
        } catch (IOException ignore) {
        }
        return map;
    }

    private static LinkedHashMap<String, Object> body(Object schema) {
        LinkedHashMap<String, Object> json = new LinkedHashMap<>();
        json.put("schema", schema);
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("application/json", json);
        LinkedHashMap<String, Object> rb = new LinkedHashMap<>();
        rb.put("required", true);
        rb.put("content", content);
        return rb;
    }

    private static LinkedHashMap<String, Object> responses(Object schema) {
        LinkedHashMap<String, Object> json = new LinkedHashMap<>();
        json.put("schema", schema);
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("application/json", json);
        LinkedHashMap<String, Object> ok = new LinkedHashMap<>();
        ok.put("description", "OK");
        ok.put("content", content);
        LinkedHashMap<String, Object> resps = new LinkedHashMap<>();
        resps.put("200", ok);
        return resps;
    }

    /** Build {common_header, data} envelope; {@code data} carries uio fields when known. */
    private static LinkedHashMap<String, Object> schemaFromIo(UioParser.Io io, boolean request) {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("common_header", objectSchema(null, null));

        List<UioParser.Field> fields = io == null ? null : (request ? io.input : io.output);
        LinkedHashMap<String, Object> dataProps = new LinkedHashMap<>();
        List<String> req = new ArrayList<>();
        if (fields != null) {
            for (UioParser.Field f : fields) {
                LinkedHashMap<String, Object> fs = new LinkedHashMap<>();
                fs.put("type", openapiType(f.type));
                if (f.name != null && !f.name.isEmpty()) fs.put("description", f.name);
                dataProps.put(f.id, fs);
                if (f.required) req.add(f.id);
            }
        }
        props.put("data", objectSchema(dataProps, req.isEmpty() ? null : req));
        return objectSchema(props, null);
    }

    private static LinkedHashMap<String, Object> objectSchema(Map<String, Object> props, List<String> required) {
        LinkedHashMap<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        if (props != null) s.put("properties", props);
        else s.put("additionalProperties", true);
        if (required != null) s.put("required", required);
        return s;
    }

    private static String openapiType(String uioType) {
        switch (uioType == null ? "" : uioType.toLowerCase()) {
            case "int":
            case "integer":
            case "long":
                return "integer";
            case "number":
            case "decimal":
                return "number";
            case "boolean":
                return "boolean";
            default:
                return "string";
        }
    }

    private static String annotationOr(UnitClass u, MethodDeclaration em) {
        String v = com.flowmap.nexcore.nexcore.Ast.annotationValue(em, NexcoreModel.BIZ_METHOD_SIMPLE);
        if (v != null) return v;
        return com.flowmap.nexcore.nexcore.Ast.annotationValue(u.decl, NexcoreModel.BIZ_UNIT_SIMPLE);
    }
}
