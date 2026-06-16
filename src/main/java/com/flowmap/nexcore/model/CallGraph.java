package com.flowmap.nexcore.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node-link call graph — serializes to the exact structure flowmap-spring
 * emits: {@code { directed, multigraph, meta, nodes[], edges[] }}.
 *
 * <p>Nodes are deduped by id (first-seen wins, with a merge that fills nulls).
 * Edges are deduped by {@link CallEdge#key()}.</p>
 */
public final class CallGraph {
    private final LinkedHashMap<String, MethodNode> nodes = new LinkedHashMap<>();
    private final List<CallEdge> edges = new ArrayList<>();
    private final Set<List<Object>> edgeKeys = new LinkedHashSet<>();

    public MethodNode addNode(MethodNode node) {
        MethodNode existing = nodes.get(node.id);
        if (existing == null) {
            nodes.put(node.id, node);
            return node;
        }
        mergeFillNulls(existing, node);
        return existing;
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public MethodNode node(String id) {
        return nodes.get(id);
    }

    public void addEdge(CallEdge edge) {
        if (edgeKeys.add(edge.key())) {
            edges.add(edge);
        }
    }

    public Collection<MethodNode> nodes() {
        return nodes.values();
    }

    public List<CallEdge> edges() {
        return edges;
    }

    /** Fill null fields of {@code into} from {@code from} (combine merges partial nodes). */
    private static void mergeFillNulls(MethodNode into, MethodNode from) {
        if (into.fqcn == null) into.fqcn = from.fqcn;
        if (into.method == null) into.method = from.method;
        if (into.layer == Layer.OTHER && from.layer != Layer.OTHER) into.layer = from.layer;
        if (into.returnType == null) into.returnType = from.returnType;
        if (into.httpMethod == null) into.httpMethod = from.httpMethod;
        if (into.endpoint == null) into.endpoint = from.endpoint;
        if (into.externalService == null) into.externalService = from.externalService;
        if (into.externalUrl == null) into.externalUrl = from.externalUrl;
        if (into.resourceType == null) into.resourceType = from.resourceType;
        if (into.description == null) into.description = from.description;
        if (into.entryPoint == null) into.entryPoint = from.entryPoint;
        if (into.file == null) into.file = from.file;
        if (into.line == null) into.line = from.line;
        if (into.project == null) into.project = from.project;
        if (into.module == null) into.module = from.module;
    }

    public LinkedHashMap<String, Object> toNodeLink(Map<String, Object> meta) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("directed", true);
        root.put("multigraph", true);
        root.put("meta", meta);
        List<Object> nodeList = new ArrayList<>();
        for (MethodNode n : nodes.values()) nodeList.add(n.toJson());
        root.put("nodes", nodeList);
        List<Object> edgeList = new ArrayList<>();
        for (CallEdge e : edges) edgeList.add(e.toJson());
        root.put("edges", edgeList);
        return root;
    }
}
