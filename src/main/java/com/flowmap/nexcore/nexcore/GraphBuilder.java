package com.flowmap.nexcore.nexcore;

import com.flowmap.nexcore.model.CallEdge;
import com.flowmap.nexcore.model.CallGraph;
import com.flowmap.nexcore.model.CallMode;
import com.flowmap.nexcore.model.EdgeKind;
import com.flowmap.nexcore.model.EntryPointKind;
import com.flowmap.nexcore.model.Layer;
import com.flowmap.nexcore.model.MethodNode;
import com.flowmap.nexcore.nexcore.NexcoreModel.CallKind;
import com.github.javaparser.Position;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a flowmap-style call graph from scanned NEXCORE sources. Reproduces the
 * reference plugin's logic: {@code @BizMethod}/batch {@code execute()} entries, private
 * helper inlining ({@code _A0000}/{@code _A1000}/...), the 16 {@code call*}/{@code
 * sendOutbound*} kinds, {@code @BizUnitBind} direct calls, and {@code dbXxx → xsql →
 * db:table} resource edges.
 */
public final class GraphBuilder {

    private final UnitIndex index;
    private final CallGraph graph = new CallGraph();

    private GraphBuilder(UnitIndex index) {
        this.index = index;
    }

    /** Single-scan build (analyze): index and source units both come from {@code scan}. */
    public static CallGraph build(SourceScanner.Scan scan) {
        UnitIndex idx = new UnitIndex(scan);
        GraphBuilder gb = new GraphBuilder(idx);
        for (UnitClass u : scan.units) gb.analyzeUnit(u);
        return gb.graph;
    }

    /**
     * Project build using a repo-wide {@code globalIndex}: only {@code project}'s units are
     * iterated as sources, but call targets resolve across all projects so cross-project shared
     * calls become real {@code s2s} edges to the provider node (web-renderable without combine).
     */
    public static CallGraph buildProject(UnitIndex globalIndex, String project) {
        GraphBuilder gb = new GraphBuilder(globalIndex);
        for (UnitClass u : globalIndex.allUnits()) {
            if (project.equals(u.project)) gb.analyzeUnit(u);
        }
        return gb.graph;
    }

    private void analyzeUnit(UnitClass u) {
        Map<String, String> binds = Ast.bindFields(u.decl);
        for (MethodDeclaration em : u.entryMethods()) {
            MethodNode src = makeUnitNode(u, em);
            graph.addNode(src);
            Set<MethodDeclaration> visited = new HashSet<>();
            visited.add(em);
            em.getBody().ifPresent(b -> processCalls(b, u, src.id, binds, visited));
        }
    }

    // ---------- traversal ----------

    private void processCalls(BlockStmt body, UnitClass u, String srcId,
                              Map<String, String> binds, Set<MethodDeclaration> visited) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            handleCall(call, u, srcId, binds, visited);
        }
    }

    private void handleCall(MethodCallExpr call, UnitClass u, String srcId,
                            Map<String, String> binds, Set<MethodDeclaration> visited) {
        String name = call.getNameAsString();
        boolean unqualified = isUnqualified(call);

        if (unqualified) {
            if (NexcoreModel.isFrameworkCallName(name)) {
                handleFramework(call, name, u, srcId);
                return;
            }
            if (NexcoreModel.isDbCallName(name)) {
                handleDb(call, u, srcId);
                return;
            }
            // same-class private helper → inline its body into this entry node
            MethodDeclaration helper = Ast.findOwnMethod(u.decl, name);
            if (helper != null && !UnitClass.hasBizMethod(helper) && visited.add(helper)) {
                helper.getBody().ifPresent(b -> processCalls(b, u, srcId, binds, visited));
            }
            return;
        }

        // qualified call — only @BizUnitBind field.method() matters (local-call)
        String scope = Ast.scopeName(call);
        if (scope != null && binds.containsKey(scope)) {
            handleBind(call, name, binds.get(scope), u, srcId);
        }
    }

    private static boolean isUnqualified(MethodCallExpr call) {
        Optional<Expression> sc = call.getScope();
        return sc.isEmpty() || sc.get().isThisExpr();
    }

    // ---------- call kinds ----------

    private void handleFramework(MethodCallExpr call, String name, UnitClass u, String srcId) {
        boolean outbound = name.equals("callOutbound") || name.equals("sendOutboundNow")
                || name.equals("sendOutboundAfterCommit");
        String outboundConst = outbound ? Ast.refNameArg(call, 0) : null;
        CallKind kind = NexcoreModel.frameworkCallKind(name, outboundConst);
        if (kind == null) return;

        switch (kind.category) {
            case LOCAL: {
                String[] um = NexcoreModel.splitUnitMethod(Ast.stringArg(call, 0));
                if (um == null) return;
                unitEdge(u, srcId, um[0], um[1], u.pkg, kind, EdgeKind.INTERNAL, call);
                break;
            }
            case CROSS: {
                String comp = Ast.stringArg(call, 0);
                String[] um = NexcoreModel.splitUnitMethod(Ast.stringArg(call, 1));
                if (um == null) return;
                String preferredPkg = (comp != null && comp.contains(".")) ? comp + ".biz" : null;
                UnitClass target = index.resolve(um[0], preferredPkg);
                if (target != null) {
                    addResolvedUnitEdge(target, um[1], srcId, kind, EdgeKind.INTERNAL, call, u);
                } else {
                    String compShort = comp == null ? "?" : comp.substring(comp.lastIndexOf('.') + 1);
                    String id = "ext:SHARED#" + (comp == null ? "?" : comp) + "." + um[0] + "." + um[1];
                    MethodNode n = MethodNode.builder(id).fqcn(id).method(um[1])
                            .layer(Layer.EXTERNAL).externalService(compShort).endpoint(um[0] + "." + um[1])
                            .build();
                    graph.addNode(n);
                    addEdge(srcId, id, kind.wire, EdgeKind.EXTERNAL, mode(kind), call, u);
                }
                break;
            }
            case LINKED: {
                String tx = orQ(Ast.stringArg(call, 0));
                String id = "ext:LINKED#" + tx;
                MethodNode n = MethodNode.builder(id).fqcn(id).method(tx)
                        .layer(Layer.EXTERNAL).externalService("LINKED").endpoint(tx).build();
                graph.addNode(n);
                addEdge(srcId, id, kind.wire, EdgeKind.EXTERNAL, mode(kind), call, u);
                break;
            }
            case BATCH: {
                String job = orQ(Ast.stringArg(call, 0));
                String id = "ext:JOB#" + job;
                MethodNode n = MethodNode.builder(id).fqcn(id).method(job)
                        .layer(Layer.BATCH).externalService("batch").endpoint(job).build();
                graph.addNode(n);
                addEdge(srcId, id, kind.wire, EdgeKind.BATCH, mode(kind), call, u);
                break;
            }
            case OUT: {
                String tag = NexcoreModel.outboundTag(kind);
                String label = outboundLabel(call, tag);
                if ("KAFKA".equals(tag)) {
                    String topic = label.isEmpty() ? "?" : label;
                    String id = "kafka:" + topic;
                    MethodNode n = MethodNode.builder(id).fqcn(id).method(topic)
                            .layer(Layer.RESOURCE).resourceType("kafka-topic").build();
                    graph.addNode(n);
                    addEdge(srcId, id, kind.wire, EdgeKind.RESOURCE, mode(kind), call, u);
                } else {
                    String id = label.isEmpty() ? "ext:" + tag : "ext:" + tag + "#" + label;
                    MethodNode n = MethodNode.builder(id).fqcn(id).method(label.isEmpty() ? tag : label)
                            .layer(Layer.EXTERNAL).externalService(tag)
                            .endpoint(label.isEmpty() ? null : label).build();
                    graph.addNode(n);
                    addEdge(srcId, id, kind.wire, EdgeKind.EXTERNAL, mode(kind), call, u);
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleBind(MethodCallExpr call, String method, String targetSimple,
                            UnitClass u, String srcId) {
        unitEdge(u, srcId, targetSimple, method, u.pkg, CallKind.LOCAL_CALL, EdgeKind.INTERNAL, call);
    }

    /** Resolve a unit by simple name (preferring {@code preferredPkg}) and add the edge. */
    private void unitEdge(UnitClass from, String srcId, String unit, String method,
                          String preferredPkg, CallKind kind, EdgeKind edgeKind, MethodCallExpr call) {
        UnitClass target = index.resolve(unit, preferredPkg);
        if (target != null) {
            addResolvedUnitEdge(target, method, srcId, kind, edgeKind, call, from);
        } else {
            String id = unit + "#" + method;
            MethodNode n = MethodNode.builder(id).fqcn(unit).method(method)
                    .layer(layerOfType(NexcoreModel.typeByUnitPrefix(unit))).build();
            graph.addNode(n);
            addEdge(srcId, id, kind.wire, edgeKind, mode(kind), call, from);
        }
    }

    private void addResolvedUnitEdge(UnitClass target, String method, String srcId,
                                     CallKind kind, EdgeKind edgeKind, MethodCallExpr call, UnitClass from) {
        MethodDeclaration tm = Ast.findOwnMethod(target.decl, method);
        MethodNode n = tm != null ? makeUnitNode(target, tm) : makeUnitNodeLite(target, method);
        graph.addNode(n);
        // cross-project resolved call → s2s (provider node lives in another project's graph)
        EdgeKind k = (target.project != null && !target.project.equals(from.project))
                ? EdgeKind.S2S : edgeKind;
        addEdge(srcId, n.id, kind.wire, k, mode(kind), call, from);
    }

    private void handleDb(MethodCallExpr call, UnitClass u, String srcId) {
        String sqlId = Ast.stringArg(call, 0);
        if (sqlId == null) return; // e.g. dbExecuteBatch() with no statement
        XsqlParser.Stmt stmt = index.sql(u.simpleName, sqlId);
        String table = stmt != null ? stmt.table : null;
        String id;
        MethodNode n;
        if (table != null) {
            id = "db:table:" + table;
            n = MethodNode.builder(id).fqcn(id).method(table)
                    .layer(Layer.RESOURCE).resourceType("db-table").build();
        } else {
            // no parseable table (e.g. stored procedure): keep a distinct id but use the
            // db-table resourceType so the web colours/groups it as DB (it switches on
            // resourceType only — an unknown type would render as the Redis colour).
            id = "db:sql:" + u.simpleName + "." + sqlId;
            n = MethodNode.builder(id).fqcn(id).method(sqlId)
                    .layer(Layer.RESOURCE).resourceType("db-table").build();
        }
        graph.addNode(n);
        // record the sqlId on the edge so xsql change-impact can map a changed statement
        // back to exactly this DataUnit method (the dbXxx call site), not the whole class.
        CallEdge e = new CallEdge(srcId, id, CallMode.SYNC, EdgeKind.RESOURCE, "db:io",
                u.relFile, Ast.beginLine(call));
        e.sqlId = sqlId;
        if (!srcId.equals(id)) graph.addEdge(e);
    }

    // ---------- outbound label back-scan ----------

    private String outboundLabel(MethodCallExpr call, String tag) {
        String headerVar = Ast.refNameArg(call, 1);
        if (headerVar == null) return "";
        String wantSetter = NexcoreModel.outboundSetter(tag);
        Optional<MethodDeclaration> enc = call.findAncestor(MethodDeclaration.class);
        if (enc.isEmpty() || call.getRange().isEmpty()) return "";
        Position callBegin = call.getRange().get().begin;
        String best = "";
        for (MethodCallExpr c : enc.get().findAll(MethodCallExpr.class)) {
            if (c.getRange().isEmpty() || !c.getRange().get().begin.isBefore(callBegin)) continue;
            if (!headerVar.equals(Ast.scopeName(c))) continue;
            if (!c.getNameAsString().equals(wantSetter)) continue;
            String v = Ast.stringArg(c, 0);
            if (v != null) best = "EDW".equals(tag) ? v.substring(v.lastIndexOf('/') + 1) : v;
        }
        return best;
    }

    // ---------- node construction ----------

    private MethodNode makeUnitNode(UnitClass u, MethodDeclaration m) {
        MethodNode.Builder b = MethodNode.builder(u.nodeId(m.getNameAsString()))
                .fqcn(u.fqcn).method(m.getNameAsString())
                .layer(layerOfType(u.type))
                .visibility(m.isPublic() ? "public" : m.isPrivate() ? "private" : "package")
                .returnType(m.getType().asString())
                .file(u.relFile).line(Ast.beginLine(m))
                .project(u.project).module(u.module)
                .description(descriptionOf(u, m));
        applyEntry(b, u);
        return b.build();
    }

    private MethodNode makeUnitNodeLite(UnitClass u, String method) {
        MethodNode.Builder b = MethodNode.builder(u.nodeId(method))
                .fqcn(u.fqcn).method(method)
                .layer(layerOfType(u.type))
                .file(u.relFile).project(u.project).module(u.module)
                .description(descriptionOf(u, null));
        applyEntry(b, u);
        return b.build();
    }

    private void applyEntry(MethodNode.Builder b, UnitClass u) {
        if (u.type == NexcoreModel.NodeType.PM) {
            String tid = "T" + u.simpleName.substring(1);
            b.entryPoint(EntryPointKind.HTTP).httpMethod("POST").endpoint("/" + tid + ".jmd")
                    .aliases(java.util.List.of(tid));
        } else if (u.batch) {
            b.entryPoint(EntryPointKind.BATCH);
        }
    }

    private String descriptionOf(UnitClass u, MethodDeclaration m) {
        if (u.batch) {
            String v = Ast.annotationValue(u.decl, NexcoreModel.BIZ_BATCH_SIMPLE);
            return v;
        }
        if (m != null) {
            String v = Ast.annotationValue(m, NexcoreModel.BIZ_METHOD_SIMPLE);
            if (v != null) return v;
        }
        return Ast.annotationValue(u.decl, NexcoreModel.BIZ_UNIT_SIMPLE);
    }

    private static Layer layerOfType(NexcoreModel.NodeType t) {
        switch (t) {
            case PM: return Layer.CONTROLLER;
            case FM: return Layer.SERVICE;
            case DM: return Layer.REPOSITORY;
            case BATCH_JOB: return Layer.BATCH;
            default: return Layer.OTHER;
        }
    }

    // ---------- edges ----------

    private void addEdge(String src, String dst, String relation, EdgeKind kind,
                         CallMode mode, MethodCallExpr call, UnitClass from) {
        if (src.equals(dst)) return;
        graph.addEdge(new CallEdge(src, dst, mode, kind, relation, from.relFile, Ast.beginLine(call)));
    }

    private static CallMode mode(CallKind kind) {
        return kind.async ? CallMode.ASYNC : CallMode.SYNC;
    }

    private static String orQ(String s) {
        return s == null ? "?" : s;
    }
}
