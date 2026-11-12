package io.github.chenxiaoming233.hyperlink.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.RuleLifecycleController;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.RuleLifecycleModel;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.ConfirmedTargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleCreationResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelectionFlow;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.fake.FakeTargetPermissionGateway;
import java.util.List;
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

    @Test
    public void lifecycleFailureThenReadFailureClearsCachedEditorWriteState() {
        FakeRuleStoreGateway gateway = gateway();
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        RuleLifecycleController controller =
                new RuleLifecycleController(
                        gateway,
                        writer,
                        new FakeTargetPermissionGateway(gateway),
                        () -> 1_000L,
                        30_000L);
        assertTrue(controller.refresh().isSuccess());
        RuleEditorWriteGate writeGate = new RuleEditorWriteGate();
        assertTrue(writeGate.writesAvailable());

        gateway.failAllReads();
        ServiceResult<RuleLifecycleModel> failure = controller.pause("demo-rule-1");
        assertFalse(failure.isSuccess());
        assertFalse(controller.refresh().isSuccess());
        assertFalse(writeGate.apply(RuleEditorLoadState.from(gateway)));
        assertFalse(writeGate.writesAvailable());
    }

    @Test
    public void structuredSaveReadFailureAfterWriteClosesGateAndRejectsNextSave() {
        StructuredSaveFixture fixture = structuredSaveFixture();
        fixture.gateway.failReadsAfterNextWrite(1);

        assertSaveFailureClosesGateAndRejectsNextSave(fixture);
    }

    @Test
    public void structuredSaveAllReadsFailureAfterWriteClosesGateAndRejectsNextSave() {
        StructuredSaveFixture fixture = structuredSaveFixture();
        fixture.gateway.failAllReadsAfterWrite();

        assertSaveFailureClosesGateAndRejectsNextSave(fixture);
    }

    @Test
    public void structuredSaveReadExceptionClosesGateWithoutThrowingOrLeakingDetails() {
        StructuredSaveFixture fixture = structuredSaveFixture();
        fixture.gateway.throwReadsAfterNextWrite(3);
        int writesBefore = fixture.gateway.writeCount();
        ServiceResult<RuleCreationResult> first;
        try {
            first = fixture.writer.saveStructuredRule(fixture.selected, fixture.draft);
        } catch (RuntimeException exception) {
            throw new AssertionError("保存失败路径不应抛出运行时异常。", exception);
        }
        assertFalse(first.isSuccess());
        assertTrue(fixture.gateway.writeCount() > writesBefore);

        RuleEditorLoadState loadState;
        try {
            loadState =
                    fixture.writeGate.refreshAfterWriteFailure(fixture.gateway, true);
        } catch (RuntimeException exception) {
            throw new AssertionError("写失败刷新不应让读取异常冒泡。", exception);
        }

        assertFalse(loadState.writable());
        assertFalse(fixture.writeGate.writesAvailable());
        assertTrue(loadState.message().contains("无法安全读取"));
        assertFalse(loadState.message().contains("演示规则读取异常"));
        ServiceResult<RuleCreationResult> second =
                fixture.writeGate.writesAvailable()
                        ? fixture.writer.saveStructuredRule(fixture.selected, fixture.draft)
                        : ServiceResult.failure("编辑器写门禁已关闭。");
        assertFalse(second.isSuccess());
    }

    @Test
    public void successfulStructuredSaveKeepsGateWritable() {
        StructuredSaveFixture fixture = structuredSaveFixture();

        ServiceResult<RuleCreationResult> result =
                fixture.writer.saveStructuredRule(fixture.selected, fixture.draft);

        assertTrue(result.isSuccess());
        assertTrue(fixture.writeGate.refresh(fixture.gateway).writable());
        assertTrue(fixture.writeGate.writesAvailable());
    }

    private static void assertSaveFailureClosesGateAndRejectsNextSave(
            StructuredSaveFixture fixture) {
        int writesBefore = fixture.gateway.writeCount();
        ServiceResult<RuleCreationResult> first =
                fixture.writer.saveStructuredRule(fixture.selected, fixture.draft);
        assertFalse(first.isSuccess());
        boolean writeAttempted = fixture.gateway.writeCount() > writesBefore;
        assertTrue(writeAttempted);
        assertFalse(
                fixture.writeGate
                        .refreshAfterWriteFailure(fixture.gateway, writeAttempted)
                        .writable());
        assertFalse(fixture.writeGate.writesAvailable());

        ServiceResult<RuleCreationResult> second =
                fixture.writeGate.writesAvailable()
                        ? fixture.writer.saveStructuredRule(fixture.selected, fixture.draft)
                        : ServiceResult.failure("编辑器写门禁已关闭。");
        assertFalse(second.isSuccess());
    }

    private static StructuredSaveFixture structuredSaveFixture() {
        FakeRuleStoreGateway gateway = gateway();
        SafeNotificationRuleWriter writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        NotificationFlowSnapshot flow = gateway.snapshot().flowSnapshot();
        ConfirmedTargetSelection selected =
                new TargetSelectionFlow(flow)
                        .select(gateway.snapshot().sourceDevice(), "demo-target-a")
                        .value()
                        .orElseThrow()
                        .confirmAuthorization();
        StructuredRuleDraft draft =
                StructuredRuleDraft.fromPresets(
                        List.of(RulePreset.FOCUSED_NOTIFICATION), List.of(), 100L);
        return new StructuredSaveFixture(
                gateway, writer, selected, draft, new RuleEditorWriteGate());
    }

    private static final class StructuredSaveFixture {
        private final FakeRuleStoreGateway gateway;
        private final SafeNotificationRuleWriter writer;
        private final ConfirmedTargetSelection selected;
        private final StructuredRuleDraft draft;
        private final RuleEditorWriteGate writeGate;

        private StructuredSaveFixture(
                FakeRuleStoreGateway gateway,
                SafeNotificationRuleWriter writer,
                ConfirmedTargetSelection selected,
                StructuredRuleDraft draft,
                RuleEditorWriteGate writeGate) {
            this.gateway = gateway;
            this.writer = writer;
            this.selected = selected;
            this.draft = draft;
            this.writeGate = writeGate;
        }
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
