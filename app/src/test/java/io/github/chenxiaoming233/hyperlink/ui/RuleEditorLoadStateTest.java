package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import org.junit.Test;

public final class RuleEditorLoadStateTest {
    @Test
    public void sharedStoreReadFailureHidesEditorWritesUntilReadsRecover() {
        FakeRuleStoreGateway gateway = gateway();
        assertTrue(RuleEditorLoadState.from(gateway).writable());

        gateway.failAllReads();
        assertFalse(RuleEditorLoadState.from(gateway).writable());

        gateway.clearReadFailures();
        assertTrue(RuleEditorLoadState.from(gateway).writable());
    }

    @Test
    public void persistentFaultKeepsSharedEditorReadOnly() {
        FakeRuleStoreGateway gateway = gateway();
        gateway.enterReadOnlyFault("演示持久只读故障。");

        RuleEditorLoadState state = RuleEditorLoadState.from(gateway);

        assertFalse(state.writable());
        assertTrue(state.message().contains("只读故障"));
    }

    @Test
    public void sharedMemoryReadOnlyGateAlsoKeepsEditorReadOnly() {
        FakeRuleStoreGateway gateway = gateway();
        gateway.store().enterMemoryReadOnlyFault("演示共享内存只读闸门。");

        RuleEditorLoadState state = RuleEditorLoadState.from(gateway);

        assertFalse(state.writable());
        assertTrue(state.message().contains("共享内存只读闸门"));
    }

    private static FakeRuleStoreGateway gateway() {
        NotificationFlowSnapshot flow =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        return new FakeRuleStoreGateway(
                new RuleStoreSnapshot(
                        flow,
                        new SourceDevice("demo-source-phone", "Demo-Phone"),
                        CapabilityEvidence.demoComplete(),
                        false,
                        false));
    }
}
