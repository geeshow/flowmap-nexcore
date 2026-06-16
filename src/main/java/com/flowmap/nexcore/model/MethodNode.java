package com.flowmap.nexcore.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * A graph node. Field set and JSON key order are identical to
 * flowmap-spring's {@code MethodNode.toJson()} so the same web tooling
 * and {@code _combined}/{@code _manifest} consumers work unchanged.
 *
 * <p>Built via {@link #builder(String)}. {@code id} is the join key
 * ({@code <fqcn>#<method>} for units, {@code ext:*}/{@code db:*}/{@code kafka:*}
 * for externals/resources).</p>
 */
public final class MethodNode {
    public String id;
    public String fqcn;
    public String method;
    public Layer layer = Layer.OTHER;
    public String visibility = "public";
    public boolean async = false;
    public String returnType;
    public String httpMethod;
    public String endpoint;
    /**
     * Alternate match keys for this endpoint, beyond its {@link #endpoint} path.
     * For a NEXCORE {@code .jmd} entry the transaction id (e.g. {@code TACU0001})
     * goes here, so a frontend call that addresses the transaction by a context-
     * prefixed path ({@code /std/TACU0001}, {@code /lng/TACU0001}, {@code /TACU0001},
     * with or without {@code .jmd}) can join on the bare token. Omitted from JSON
     * when empty. Read by the react join's segment-probe tier.
     */
    public List<String> aliases;
    public String externalService;
    public String externalUrl;
    public String resourceType;
    public String description;
    public EntryPointKind entryPoint;
    public String urlPlaceholder;
    public String clientPackage;
    public String file;
    public Integer line;
    public String project;
    public String module;

    private MethodNode(String id) {
        this.id = id;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public LinkedHashMap<String, Object> toJson() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("fqcn", fqcn);
        m.put("method", method);
        m.put("layer", layer.name());
        m.put("visibility", visibility);
        m.put("async", async);
        m.put("returnType", returnType);
        m.put("httpMethod", httpMethod);
        m.put("endpoint", endpoint);
        if (aliases != null && !aliases.isEmpty()) m.put("aliases", aliases);
        m.put("externalService", externalService);
        m.put("externalUrl", externalUrl);
        m.put("resourceType", resourceType);
        m.put("description", description);
        m.put("entryPoint", entryPoint == null ? null : entryPoint.name());
        m.put("urlPlaceholder", urlPlaceholder);
        m.put("clientPackage", clientPackage);
        m.put("file", file);
        m.put("line", line);
        m.put("project", project);
        m.put("module", module);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MethodNode && ((MethodNode) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /** Fluent builder. */
    public static final class Builder {
        private final MethodNode n;

        private Builder(String id) {
            n = new MethodNode(id);
        }

        public Builder fqcn(String v) { n.fqcn = v; return this; }
        public Builder method(String v) { n.method = v; return this; }
        public Builder layer(Layer v) { n.layer = v; return this; }
        public Builder visibility(String v) { n.visibility = v; return this; }
        public Builder async(boolean v) { n.async = v; return this; }
        public Builder returnType(String v) { n.returnType = v; return this; }
        public Builder httpMethod(String v) { n.httpMethod = v; return this; }
        public Builder endpoint(String v) { n.endpoint = v; return this; }
        public Builder aliases(List<String> v) { n.aliases = v; return this; }
        public Builder externalService(String v) { n.externalService = v; return this; }
        public Builder externalUrl(String v) { n.externalUrl = v; return this; }
        public Builder resourceType(String v) { n.resourceType = v; return this; }
        public Builder description(String v) { n.description = v; return this; }
        public Builder entryPoint(EntryPointKind v) { n.entryPoint = v; return this; }
        public Builder file(String v) { n.file = v; return this; }
        public Builder line(Integer v) { n.line = v; return this; }
        public Builder project(String v) { n.project = v; return this; }
        public Builder module(String v) { n.module = v; return this; }

        public MethodNode build() {
            return n;
        }
    }
}
