package com.flowmap.nexcore.nexcore;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed NEXCORE unit (PU/FU/DU) or batch job class, with the metadata the
 * graph builder/resolver needs: identity, repo location, and its AST node.
 */
public final class UnitClass {
    public final String simpleName;
    public final String fqcn;
    public final String pkg;
    public final String relFile;
    public final String project;
    public final String module;
    public final NexcoreModel.NodeType type;
    public final boolean batch;
    public final ClassOrInterfaceDeclaration decl;

    public UnitClass(String simpleName, String fqcn, String pkg, String relFile,
                     String project, String module, NexcoreModel.NodeType type,
                     boolean batch, ClassOrInterfaceDeclaration decl) {
        this.simpleName = simpleName;
        this.fqcn = fqcn;
        this.pkg = pkg;
        this.relFile = relFile;
        this.project = project;
        this.module = module;
        this.type = type;
        this.batch = batch;
        this.decl = decl;
    }

    /**
     * Entry methods. Batch: {@code execute()}. Units: {@code @BizMethod}-annotated methods;
     * if a unit declares none (some NEXCORE FunctionUnits omit the annotation), fall back to
     * its public, non-constructor methods (the conventional single {@code f<Unit>} entry).
     */
    public List<MethodDeclaration> entryMethods() {
        List<MethodDeclaration> out = new ArrayList<>();
        if (batch) {
            for (MethodDeclaration m : decl.getMethods()) {
                if (m.getNameAsString().equals("execute") && m.getParameters().isEmpty()) out.add(m);
            }
            return out;
        }
        for (MethodDeclaration m : decl.getMethods()) {
            if (hasBizMethod(m)) out.add(m);
        }
        if (out.isEmpty()) {
            for (MethodDeclaration m : decl.getMethods()) {
                if (m.isPublic() && !m.isStatic()) out.add(m);
            }
        }
        return out;
    }

    public static boolean hasBizMethod(MethodDeclaration m) {
        return m.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals(NexcoreModel.BIZ_METHOD_SIMPLE)
                    || n.equals(NexcoreModel.BIZ_METHOD_FQN)
                    || n.endsWith(".BizMethod");
        });
    }

    /** node id for a method of this unit: {@code <fqcn>#<method>}. */
    public String nodeId(String methodName) {
        return fqcn + "#" + methodName;
    }
}
