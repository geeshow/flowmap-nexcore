package com.flowmap.nexcore.nexcore;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Small JavaParser helpers shared by the graph builder and resolver. */
public final class Ast {

    private Ast() {
    }

    /** String value of argument {@code i} if it is a string literal, else null. */
    public static String stringArg(MethodCallExpr call, int i) {
        if (call.getArguments().size() <= i) return null;
        Expression e = call.getArgument(i);
        if (e.isStringLiteralExpr()) return e.asStringLiteralExpr().asString();
        return null;
    }

    /** Identifier name of argument {@code i} when it is a field-access / name ref (e.g. {@code KIND_FEP}), else null. */
    public static String refNameArg(MethodCallExpr call, int i) {
        if (call.getArguments().size() <= i) return null;
        Expression e = call.getArgument(i);
        if (e.isFieldAccessExpr()) return e.asFieldAccessExpr().getNameAsString();
        if (e.isNameExpr()) return e.asNameExpr().getNameAsString();
        return null;
    }

    /** Scope (qualifier) simple name of a call, e.g. {@code header} in {@code header.setX()}, else null. */
    public static String scopeName(MethodCallExpr call) {
        Optional<Expression> sc = call.getScope();
        if (sc.isEmpty()) return null;
        Expression e = sc.get();
        if (e.isNameExpr()) return e.asNameExpr().getNameAsString();
        if (e.isFieldAccessExpr()) return e.asFieldAccessExpr().getNameAsString();
        return null;
    }

    public static Integer beginLine(com.github.javaparser.ast.Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(null);
    }

    /** @BizUnitBind field name → field type simple name. */
    public static Map<String, String> bindFields(ClassOrInterfaceDeclaration cls) {
        Map<String, String> map = new LinkedHashMap<>();
        for (FieldDeclaration f : cls.getFields()) {
            boolean bound = f.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals(NexcoreModel.BIZ_UNIT_BIND_SIMPLE)
                            || a.getNameAsString().endsWith(".BizUnitBind"));
            if (!bound) continue;
            String type = simpleType(f.getElementType().asString());
            for (VariableDeclarator v : f.getVariables()) {
                map.put(v.getNameAsString(), type);
            }
        }
        return map;
    }

    private static String simpleType(String t) {
        String s = t;
        int lt = s.indexOf('<');
        if (lt >= 0) s = s.substring(0, lt);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s.trim();
    }

    /** String value of annotation (by simple name) {@code value} member, else null. */
    public static String annotationValue(NodeWithAnnotations<?> node, String simpleName) {
        for (AnnotationExpr a : node.getAnnotations()) {
            String n = a.getNameAsString();
            if (!n.equals(simpleName) && !n.endsWith("." + simpleName)) continue;
            if (a.isSingleMemberAnnotationExpr()) {
                Expression v = a.asSingleMemberAnnotationExpr().getMemberValue();
                if (v.isStringLiteralExpr()) return v.asStringLiteralExpr().asString();
            } else if (a.isNormalAnnotationExpr()) {
                NormalAnnotationExpr na = a.asNormalAnnotationExpr();
                return na.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("value"))
                        .findFirst()
                        .map(p -> p.getValue().isStringLiteralExpr() ? p.getValue().asStringLiteralExpr().asString() : null)
                        .orElse(null);
            }
        }
        return null;
    }

    public static MethodDeclaration findOwnMethod(ClassOrInterfaceDeclaration cls, String name) {
        for (MethodDeclaration m : cls.getMethods()) {
            if (m.getNameAsString().equals(name)) return m;
        }
        return null;
    }
}
