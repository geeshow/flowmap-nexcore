package com.flowmap.nexcore;

import com.flowmap.nexcore.nexcore.NexcoreModel;
import com.flowmap.nexcore.nexcore.NexcoreModel.CallKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexcoreModelTest {

    @Test
    void mapsLocalAndSharedAndLinked() {
        assertEquals(CallKind.LOCAL_NEW_TX, NexcoreModel.frameworkCallKind("callMethodByRequiresNew", null));
        assertEquals(CallKind.SHARED_CALL, NexcoreModel.frameworkCallKind("callSharedMethodByDirect", null));
        assertEquals(CallKind.SHARED_CALL, NexcoreModel.frameworkCallKind("callSharedMethod", null)); // batch→online
        assertEquals(CallKind.SHARED_NEW_TX, NexcoreModel.frameworkCallKind("callSharedMethodByRequiresNew", null));
        assertEquals(CallKind.LINKED_SYNC, NexcoreModel.frameworkCallKind("callService", null));
        assertEquals(CallKind.LINKED_ASYNC_AC, NexcoreModel.frameworkCallKind("callAsyncServiceAfterCommit", null));
        assertEquals(CallKind.BATCH_NOW, NexcoreModel.frameworkCallKind("callBatchJobNow", null));
    }

    @Test
    void classifiesOutboundByKindConstant() {
        assertEquals(CallKind.FEP_SYNC, NexcoreModel.frameworkCallKind("callOutbound", "KIND_FEP"));
        assertEquals(CallKind.EDW_SYNC, NexcoreModel.frameworkCallKind("callOutbound", "KIND_EDW_FA"));
        assertEquals(CallKind.FEP_SYNC, NexcoreModel.frameworkCallKind("callOutbound", null)); // default
        assertEquals(CallKind.KAFKA_PUBLISH, NexcoreModel.frameworkCallKind("sendOutboundNow", "KIND_KAFKA"));
        assertEquals(CallKind.FEP_ASYNC_AC, NexcoreModel.frameworkCallKind("sendOutboundAfterCommit", "KIND_FEP"));
        assertEquals(CallKind.KAFKA_PUBLISH, NexcoreModel.frameworkCallKind("sendOutboundAfterCommit", "KIND_KAFKA"));
    }

    @Test
    void nonFrameworkReturnsNull() {
        assertNull(NexcoreModel.frameworkCallKind("getLog", null));
        assertFalse(NexcoreModel.isFrameworkCallName("dbSelect"));
        assertTrue(NexcoreModel.isDbCallName("dbSelectSingle"));
        assertTrue(NexcoreModel.isFrameworkCallName("callService"));
    }

    @Test
    void splitsUnitMethod() {
        assertArrayEquals(new String[]{"FACU6017", "fACU6017"}, NexcoreModel.splitUnitMethod("FACU6017.fACU6017"));
        assertNull(NexcoreModel.splitUnitMethod("noDot"));
    }

    @Test
    void coversAll16CallKinds() {
        assertEquals(16, CallKind.values().length);
    }
}
