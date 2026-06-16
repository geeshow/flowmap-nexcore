package com.flowmap.nexcore.nexcore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NEXCORE BizUnit call-structure domain model — a faithful port of
 * {@code nexcore-hierarchy}'s {@code NexcoreModel.kt}.
 *
 * <p>Two call mechanisms exist:
 * (1) {@code @BizUnitBind} field direct calls ({@code local-call}), and
 * (2) {@code AbstractBizUnit} standard {@code call*}/{@code sendOutbound*} methods
 * whose <b>method name determines the {@link CallKind}</b>. The 16 CallKinds and their
 * mapping match the reference implementation exactly.</p>
 */
public final class NexcoreModel {

    private NexcoreModel() {
    }

    // ---- BizUnit base class FQNs (online) ----
    public static final String PROCESS_UNIT_FQN = "com.kakaopay.moneyball.base.ProcessUnit";
    public static final String FUNCTION_UNIT_FQN = "com.kakaopay.moneyball.base.FunctionUnit";
    public static final String DATA_UNIT_FQN = "com.kakaopay.moneyball.base.DataUnit";
    public static final List<String> BIZ_UNIT_BASE_SIMPLE =
            Arrays.asList("ProcessUnit", "FunctionUnit", "DataUnit");

    // ---- batch base / annotation ----
    public static final String MB_BATCH_FQN = "com.kakaopay.moneyball.bat.base.MBBatchComponent";
    public static final String MB_BATCH_SIMPLE = "MBBatchComponent";
    public static final String BIZ_BATCH_SIMPLE = "BizBatch";

    // ---- entry annotation ----
    public static final String BIZ_METHOD_FQN = "nexcore.framework.core.component.streotype.BizMethod";
    public static final String BIZ_METHOD_SIMPLE = "BizMethod";
    public static final String BIZ_UNIT_BIND_SIMPLE = "BizUnitBind";
    public static final String BIZ_UNIT_SIMPLE = "BizUnit";

    /** Node/unit type. */
    public enum NodeType {
        PM, FM, DM, BATCH_JOB, SVC, JOB, OUT
    }

    /** Call-kind category — colour bucket and resolution strategy. */
    public enum Category {
        LOCAL, CROSS, LINKED, BATCH, OUT
    }

    /**
     * Call-edge kind. The 16 entries of the NEXCORE call coverage matrix.
     * {@code wire} is the JSON identifier stored in {@code edge.relation}.
     */
    public enum CallKind {
        LOCAL_CALL("local-call", Category.LOCAL, false, false, false, "직접호출"),
        LOCAL_NEW_TX("local-new-tx", Category.LOCAL, false, true, false, "직접·새TX"),
        SHARED_CALL("shared-call", Category.CROSS, false, false, false, "공유호출"),
        SHARED_NEW_TX("shared-new-tx", Category.CROSS, false, true, false, "공유·새TX"),
        LINKED_SYNC("linked-tx-sync", Category.LINKED, false, false, false, "연동동기"),
        LINKED_SYNC_NEW("linked-tx-sync-new", Category.LINKED, false, true, false, "연동동기·새TX"),
        LINKED_ASYNC_NOW("linked-tx-async-now", Category.LINKED, true, false, false, "연동비동기"),
        LINKED_ASYNC_AC("linked-tx-async-after-commit", Category.LINKED, true, false, true, "연동비동기·커밋후"),
        LINKED_DELAY("linked-tx-delay-async", Category.LINKED, true, false, false, "연동지연비동기"),
        BATCH_NOW("batch-now", Category.BATCH, false, false, false, "배치즉시"),
        BATCH_AC("batch-after-commit", Category.BATCH, false, false, true, "배치커밋후"),
        FEP_SYNC("fep-sync", Category.OUT, false, false, false, "FEP동기"),
        EDW_SYNC("edw-sync", Category.OUT, false, false, false, "EDW동기"),
        FEP_ASYNC_NOW("fep-async-now", Category.OUT, true, false, false, "FEP비동기"),
        FEP_ASYNC_AC("fep-async-after-commit", Category.OUT, true, false, true, "FEP비동기·커밋후"),
        KAFKA_PUBLISH("kafka-publish", Category.OUT, true, false, false, "Kafka발행");

        public final String wire;
        public final Category category;
        public final boolean async;
        public final boolean newTx;
        public final boolean afterCommit;
        public final String label;

        CallKind(String wire, Category category, boolean async, boolean newTx,
                 boolean afterCommit, String label) {
            this.wire = wire;
            this.category = category;
            this.async = async;
            this.newTx = newTx;
            this.afterCommit = afterCommit;
            this.label = label;
        }
    }

    /** AbstractBizUnit standard call method names (name-only framework-call detection). */
    private static final Set<String> FRAMEWORK_CALL_NAMES = new HashSet<>(Arrays.asList(
            "callMethodByRequiresNew",
            "callSharedMethod", "callSharedMethodByDirect", "callSharedMethodByRequiresNew",
            "callService", "callServiceByRequiresNew",
            "callAsyncServiceAfterCommit", "callAsyncServiceNow", "callDelayAsyncService",
            "callBatchJobNow", "callBatchJobAfterCommit",
            "callOutbound", "sendOutboundNow", "sendOutboundAfterCommit"
    ));

    /** DataUnit/batch DB-access method names (first arg = SQL id → xsql element). */
    private static final Set<String> DB_CALL_NAMES = new HashSet<>(Arrays.asList(
            "dbSelect", "dbSelectSingle", "dbInsert", "dbInsertAndReturnPK",
            "dbUpdate", "dbDelete", "dbExecuteProcedure", "dbSelectToCSVFile"
    ));

    public static boolean isFrameworkCallName(String name) {
        return FRAMEWORK_CALL_NAMES.contains(name);
    }

    public static boolean isDbCallName(String name) {
        return DB_CALL_NAMES.contains(name);
    }

    /**
     * Framework call method name (+ outbound KIND constant) → CallKind, or null if not
     * a standard API.
     *
     * @param outboundKindConst first-arg KIND_* constant name for callOutbound/sendOutbound*, else null
     */
    public static CallKind frameworkCallKind(String methodName, String outboundKindConst) {
        switch (methodName) {
            case "callMethodByRequiresNew": return CallKind.LOCAL_NEW_TX;
            case "callSharedMethod": return CallKind.SHARED_CALL;            // batch → online shared
            case "callSharedMethodByDirect": return CallKind.SHARED_CALL;
            case "callSharedMethodByRequiresNew": return CallKind.SHARED_NEW_TX;
            case "callService": return CallKind.LINKED_SYNC;
            case "callServiceByRequiresNew": return CallKind.LINKED_SYNC_NEW;
            case "callAsyncServiceNow": return CallKind.LINKED_ASYNC_NOW;
            case "callAsyncServiceAfterCommit": return CallKind.LINKED_ASYNC_AC;
            case "callDelayAsyncService": return CallKind.LINKED_DELAY;
            case "callBatchJobNow": return CallKind.BATCH_NOW;
            case "callBatchJobAfterCommit": return CallKind.BATCH_AC;
            case "callOutbound":
                return outboundKind(outboundKindConst, CallKind.FEP_SYNC, CallKind.EDW_SYNC, CallKind.FEP_SYNC);
            case "sendOutboundNow":
                return outboundKind(outboundKindConst, CallKind.FEP_ASYNC_NOW, CallKind.FEP_ASYNC_NOW, CallKind.KAFKA_PUBLISH);
            case "sendOutboundAfterCommit":
                return outboundKind(outboundKindConst, CallKind.FEP_ASYNC_AC, CallKind.FEP_ASYNC_AC, CallKind.KAFKA_PUBLISH);
            default: return null;
        }
    }

    private static CallKind outboundKind(String constName, CallKind fep, CallKind edw, CallKind kafka) {
        if (constName == null) return fep;
        if (constName.startsWith("KIND_EDW")) return edw;
        if (constName.startsWith("KIND_KAFKA")) return kafka;
        return fep; // KIND_FEP (default)
    }

    /** Outbound system tag for a CallKind. */
    public static String outboundTag(CallKind kind) {
        if (kind == CallKind.EDW_SYNC) return "EDW";
        if (kind == CallKind.KAFKA_PUBLISH) return "KAFKA";
        return "FEP";
    }

    /** header setter whose argument is the outbound label, by tag. */
    public static String outboundSetter(String tag) {
        switch (tag) {
            case "EDW": return "setEDW_TR_URL";
            case "KAFKA": return "setEVENT_CODE";
            default: return "setFEP_IF_ID";
        }
    }

    /** "FAC0001.fAC0001" → ["FAC0001", "fAC0001"]; null if malformed. */
    public static String[] splitUnitMethod(String s) {
        if (s == null) return null;
        int dot = s.indexOf('.');
        if (dot <= 0 || dot >= s.length() - 1) return null;
        return new String[]{s.substring(0, dot), s.substring(dot + 1)};
    }

    /** Node type from a unit class simple name's first letter (P/F/D). */
    public static NodeType typeByUnitPrefix(String unit) {
        if (unit == null || unit.isEmpty()) return NodeType.FM;
        switch (unit.charAt(0)) {
            case 'P': return NodeType.PM;
            case 'F': return NodeType.FM;
            case 'D': return NodeType.DM;
            default: return NodeType.FM;
        }
    }
}
