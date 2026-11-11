package io.github.chenxiaoming233.hyperlink.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.RuleLifecycleController;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.NotificationAccessSettingsIntentSpec;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import io.github.chenxiaoming233.hyperlink.fake.FakeTargetPermissionGateway;
import java.util.Collections;
import java.util.List;

public final class SettingsActivity extends Activity {
    private static final String[] SCENARIOS = {
        "正常演示数据",
        "过期缓存",
        "空数据",
        "读取失败",
        "系统通知使用权缺失",
        "系统通知使用权已撤销"
    };

    private TextView stateTitle;
    private TextView stateMessage;
    private TextView compatibilityBadge;
    private TextView staleCacheBadge;
    private TextView notificationAccess;
    private Button notificationAccessSettingsButton;
    private TextView stateVersion;
    private Button retryButton;
    private Spinner stateSelector;
    private Spinner scenarioSelector;
    private LinearLayout targetContainer;
    private LinearLayout ruleContainer;
    private FakeRuleStoreGateway sharedGateway;
    private SafeNotificationRuleWriter sharedWriter;
    private RuleLifecycleController lifecycleController;
    private RuleEditorPanel editorPanel;
    private NotificationFlowSnapshot baseSnapshot;
    private int appliedScenario = -1;
    private ConnectionState appliedConnectionState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeSharedStore();
        setContentView(createContent());
    }

    private void initializeSharedStore() {
        baseSnapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        SourceDevice source =
                new SourceDevice("demo-source-phone", "Demo-Phone");
        sharedGateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                baseSnapshot,
                                source,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        sharedWriter =
                new SafeNotificationRuleWriter(
                        sharedGateway, new FakeLastKnownGoodStore());
        lifecycleController =
                new RuleLifecycleController(
                        sharedGateway,
                        sharedWriter,
                        new FakeTargetPermissionGateway(sharedGateway),
                        SystemClock::elapsedRealtime,
                        10_000L);
    }

    private View createContent() {
        int pagePadding = dp(24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(248, 246, 240));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pagePadding, pagePadding, pagePadding, pagePadding);
        page.setBackgroundColor(Color.rgb(248, 246, 240));
        scroll.addView(
                page,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("HYPERLINK / FAKE DEMO + EDITOR");
        eyebrow.setTextColor(Color.rgb(165, 72, 32));
        eyebrow.setTextSize(12);
        eyebrow.setGravity(Gravity.START);
        page.addView(eyebrow);

        TextView heading = new TextView(this);
        heading.setText("通知流转设置");
        heading.setTextColor(Color.rgb(30, 38, 36));
        heading.setTextSize(30);
        heading.setPadding(0, dp(6), 0, dp(8));
        page.addView(heading);

        TextView demoNotice = new TextView(this);
        demoNotice.setText(
                "演示模式：场景快照只读取内存 fake gateway。未连接 VoiceAssist，"
                        + "不会接触真实规则、权限、Provider 或通知订阅文件。"
                        + "下方结构化编辑器可显式写入独立内存 fake store，绝不降级到真实路径。");
        demoNotice.setTextColor(Color.rgb(75, 82, 78));
        demoNotice.setTextSize(15);
        demoNotice.setPadding(0, 0, 0, dp(20));
        page.addView(demoNotice);

        TextView scenarioLabel = label("演示场景");
        page.addView(scenarioLabel);
        scenarioSelector = new Spinner(this);
        scenarioSelector.setAdapter(
                new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_dropdown_item, SCENARIOS));
        scenarioSelector.setSelection(0);
        page.addView(scenarioSelector, matchWrap());

        TextView stateLabel = label("fake 活体状态");
        stateLabel.setPadding(0, dp(12), 0, 0);
        page.addView(stateLabel);
        stateSelector = new Spinner(this);
        ArrayAdapter<ConnectionState> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        ConnectionState.values());
        stateSelector.setAdapter(adapter);
        stateSelector.setSelection(ConnectionState.CONNECTED.ordinal());
        page.addView(stateSelector, matchWrap());

        compatibilityBadge = new TextView(this);
        compatibilityBadge.setText("兼容性未经完整验收");
        compatibilityBadge.setTextColor(Color.rgb(132, 54, 24));
        compatibilityBadge.setTextSize(13);
        compatibilityBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        compatibilityBadge.setBackgroundColor(Color.rgb(250, 218, 190));
        LinearLayout.LayoutParams badgeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.topMargin = dp(18);
        badgeParams.rightMargin = dp(8);
        page.addView(compatibilityBadge, badgeParams);

        staleCacheBadge = new TextView(this);
        staleCacheBadge.setText("缓存快照已过期");
        staleCacheBadge.setTextColor(Color.rgb(126, 48, 30));
        staleCacheBadge.setTextSize(13);
        staleCacheBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        staleCacheBadge.setBackgroundColor(Color.rgb(255, 220, 204));
        LinearLayout.LayoutParams staleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        staleParams.topMargin = dp(18);
        page.addView(staleCacheBadge, staleParams);

        stateTitle = new TextView(this);
        stateTitle.setTextColor(Color.rgb(30, 38, 36));
        stateTitle.setTextSize(24);
        stateTitle.setPadding(0, dp(16), 0, dp(8));
        page.addView(stateTitle);

        stateMessage = new TextView(this);
        stateMessage.setTextColor(Color.rgb(75, 82, 78));
        stateMessage.setTextSize(16);
        stateMessage.setLineSpacing(0f, 1.2f);
        page.addView(stateMessage);

        stateVersion = new TextView(this);
        stateVersion.setTextColor(Color.rgb(111, 119, 114));
        stateVersion.setTextSize(13);
        stateVersion.setPadding(0, dp(14), 0, dp(20));
        page.addView(stateVersion);

        retryButton = new Button(this);
        retryButton.setText("重新读取 fake 快照");
        page.addView(retryButton);

        TextView accessHeading = sectionHeading("系统通知使用权");
        accessHeading.setPadding(0, dp(28), 0, dp(6));
        page.addView(accessHeading);
        notificationAccess = new TextView(this);
        notificationAccess.setTextSize(15);
        notificationAccess.setLineSpacing(0f, 1.2f);
        notificationAccess.setPadding(0, 0, 0, dp(8));
        page.addView(notificationAccess);
        notificationAccessSettingsButton = new Button(this);
        notificationAccessSettingsButton.setText("打开系统通知使用权设置");
        notificationAccessSettingsButton.setOnClickListener(
                view -> openNotificationAccessSettings());
        page.addView(notificationAccessSettingsButton);

        editorPanel =
                new RuleEditorPanel(
                        this,
                        sharedGateway,
                        sharedWriter,
                        lifecycleController,
                        this::refreshPageFromSharedStore);
        LinearLayout.LayoutParams editorParams = matchWrap();
        editorParams.topMargin = dp(28);
        page.addView(editorPanel, editorParams);

        TextView targetHeading = sectionHeading("目标设备");
        targetHeading.setPadding(0, dp(28), 0, dp(4));
        page.addView(targetHeading);
        TextView targetCaption = caption("仅显示具备接收能力的 PC、PAD 与 MAC；手机等其他类型已过滤。");
        page.addView(targetCaption);
        targetContainer = new LinearLayout(this);
        targetContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(targetContainer, matchWrap());

        TextView ruleHeading = sectionHeading("场景快照规则（保持上行顺序）");
        ruleHeading.setPadding(0, dp(28), 0, dp(4));
        page.addView(ruleHeading);
        TextView ruleCaption =
                caption("完整展示 ruleId、来源、目标、状态、规则摘要和 DSL；不做字段裁剪或规则重排。");
        page.addView(ruleCaption);
        ruleContainer = new LinearLayout(this);
        ruleContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(ruleContainer, matchWrap());

        TextView footer = caption(
                "边界：场景展示只读；结构化编辑器只显式写入独立 fake store，"
                        + "不存在真实路径降级、认证绕过或能力探测绕过。"
                        + "“已启用”不代表通知已经送达。");
        footer.setPadding(0, dp(28), 0, dp(8));
        page.addView(footer);

        AdapterView.OnItemSelectedListener listener =
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        render();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                };
        scenarioSelector.setOnItemSelectedListener(listener);
        stateSelector.setOnItemSelectedListener(listener);
        retryButton.setOnClickListener(view -> render());
        render();
        return scroll;
    }

    private void render() {
        if (scenarioSelector.getSelectedItemPosition() == 3) {
            PanelDisplayModel failure =
                    PanelStateMapper.mapFailure(
                            "演示读取失败，未接触真实 VoiceAssist 数据，请稍后重试。");
            renderModel(failure, null);
            return;
        }
        applyScenarioToSharedStore();
        refreshPageFromSharedStore();
        if (editorPanel != null) {
            editorPanel.refreshFromStore();
        }
    }

    private void refreshPageFromSharedStore() {
        ServiceResult<RuleStoreSnapshot> result = sharedGateway.read();
        if (!result.isSuccess()) {
            renderModel(
                    PanelStateMapper.mapFailure("无法读取共享 fake store。"), null);
            return;
        }
        NotificationFlowSnapshot snapshot = result.value().orElseThrow().flowSnapshot();
        renderModel(PanelStateMapper.map(snapshot), snapshot);
    }

    private void applyScenarioToSharedStore() {
        int scenario = scenarioSelector.getSelectedItemPosition();
        ConnectionState state = selectedState();
        if (scenario == appliedScenario && state == appliedConnectionState) {
            return;
        }
        RuleStoreSnapshot current = sharedGateway.snapshot();
        NotificationFlowSnapshot flow = current.flowSnapshot();
        boolean stale = scenario == 1;
        NotificationAccessState access = NotificationAccessState.GRANTED;
        if (scenario == 4) {
            access = NotificationAccessState.MISSING;
        } else if (scenario == 5) {
            access = NotificationAccessState.REVOKED;
        }
        List<NotificationRule> rules = flow.rules();
        if (scenario == 2 && sharedGateway.writeCount() == 0) {
            rules = Collections.emptyList();
        } else if (scenario != 2
                && rules.isEmpty()
                && sharedGateway.writeCount() == 0) {
            rules = baseSnapshot.rules();
        }
        NotificationFlowSnapshot nextFlow =
                new NotificationFlowSnapshot(
                        state,
                        flow.stateVersion() + 1L,
                        state == ConnectionState.COMPATIBILITY_UNVERIFIED,
                        flow.targets(),
                        rules,
                        access,
                        stale);
        sharedGateway.replaceSnapshot(
                new RuleStoreSnapshot(
                        nextFlow,
                        current.sourceDevice(),
                        current.capabilities(),
                        current.unknownFieldsPresent(),
                        current.unexpressibleDslPresent()));
        appliedScenario = scenario;
        appliedConnectionState = state;
    }

    private void renderModel(PanelDisplayModel model, NotificationFlowSnapshot snapshot) {
        stateTitle.setText(model.title());
        stateMessage.setText(model.message());
        compatibilityBadge.setVisibility(
                model.compatibilityBadgeVisible() ? View.VISIBLE : View.GONE);
        staleCacheBadge.setVisibility(model.staleCacheVisible() ? View.VISIBLE : View.GONE);
        notificationAccess.setText(model.notificationAccessText());
        notificationAccess.setTextColor(
                model.notificationAccessBlocked()
                        ? Color.rgb(156, 50, 35)
                        : Color.rgb(45, 91, 62));
        notificationAccessSettingsButton.setVisibility(
                snapshot != null
                                && snapshot.notificationAccess()
                                        != NotificationAccessState.GRANTED
                        ? View.VISIBLE
                        : View.GONE);
        stateVersion.setText(
                snapshot == null
                        ? "状态版本：未知 · 只读"
                        : "状态版本：" + snapshot.stateVersion() + " · 只读");
        retryButton.setEnabled(model.retryEnabled());

        targetContainer.removeAllViews();
        for (PanelDisplayModel.TargetItem target : model.targets()) {
            targetContainer.addView(createTargetCard(target));
        }
        if (model.targets().isEmpty()) {
            targetContainer.addView(emptySectionText(model.targetSectionPlaceholder()));
        }

        ruleContainer.removeAllViews();
        for (PanelDisplayModel.RuleItem rule : model.rules()) {
            ruleContainer.addView(createRuleCard(rule));
        }
        if (model.rules().isEmpty()) {
            ruleContainer.addView(emptySectionText(model.ruleSectionPlaceholder()));
        }
    }

    private void openNotificationAccessSettings() {
        NotificationAccessSettingsIntentSpec spec =
                NotificationAccessSettingsIntentSpec.systemSettings();
        try {
            startActivity(new Intent(spec.action()));
        } catch (ActivityNotFoundException exception) {
            stateMessage.setText(
                    "当前系统没有可处理通知使用权设置页的 Activity；"
                            + "未调用任何授权写入 API。");
        }
    }

    private View createTargetCard(PanelDisplayModel.TargetItem target) {
        LinearLayout card = card();
        TextView heading = new TextView(this);
        heading.setText(target.displayName() + "  /  " + target.typeLabel());
        heading.setTextColor(Color.rgb(30, 38, 36));
        heading.setTextSize(19);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);

        TextView states = bodyText(
                "在线：" + target.onlineLabel()
                        + "    可达：" + target.reachableLabel()
                        + "\n" + target.authorizationLabel()
                        + "    " + target.receiveCapabilityLabel());
        states.setPadding(0, dp(8), 0, 0);
        card.addView(states);

        TextView id = caption(target.deviceId());
        id.setPadding(0, dp(6), 0, 0);
        card.addView(id);
        if (!target.notice().isEmpty()) {
            card.addView(noticeText(target.notice()));
        }
        return card;
    }

    private View createRuleCard(PanelDisplayModel.RuleItem rule) {
        LinearLayout card = card();
        TextView heading = new TextView(this);
        heading.setText("#" + rule.position() + "  " + rule.ruleId());
        heading.setTextColor(Color.rgb(30, 38, 36));
        heading.setTextSize(19);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);

        TextView status = bodyText("状态：" + rule.statusLabel() + "（原始值：" + rule.storedStatus() + "）");
        status.setPadding(0, dp(8), 0, 0);
        card.addView(status);

        TextView endpoints = bodyText(
                "来源：" + rule.sourceDeviceName() + "\n" + rule.sourceDeviceId()
                        + "\n目标：" + rule.targetDeviceName() + "\n" + rule.targetDeviceId());
        endpoints.setPadding(0, dp(8), 0, 0);
        card.addView(endpoints);

        TextView summary = bodyText("规则摘要：" + rule.summary());
        summary.setPadding(0, dp(10), 0, 0);
        card.addView(summary);

        TextView dslLabel = caption("完整 DSL");
        dslLabel.setPadding(0, dp(14), 0, 0);
        card.addView(dslLabel);

        TextView dsl = bodyText(rule.dslJson());
        dsl.setTypeface(Typeface.MONOSPACE);
        dsl.setTextIsSelectable(true);
        dsl.setBackgroundColor(Color.rgb(245, 242, 234));
        dsl.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams dslParams = matchWrap();
        dslParams.topMargin = dp(6);
        card.addView(dsl, dslParams);

        if (!rule.notice().isEmpty()) {
            card.addView(noticeText(rule.notice()));
        }
        return card;
    }

    private ConnectionState selectedState() {
        return ConnectionState.values()[stateSelector.getSelectedItemPosition()];
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(255, 253, 247));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.rgb(224, 218, 204));
        card.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(111, 119, 114));
        label.setTextSize(12);
        return label;
    }

    private TextView sectionHeading(String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(Color.rgb(30, 38, 36));
        heading.setTextSize(21);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return heading;
    }

    private TextView caption(String text) {
        TextView caption = new TextView(this);
        caption.setText(text);
        caption.setTextColor(Color.rgb(111, 119, 114));
        caption.setTextSize(13);
        caption.setLineSpacing(0f, 1.15f);
        return caption;
    }

    private TextView bodyText(String text) {
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(Color.rgb(50, 58, 54));
        body.setTextSize(15);
        body.setLineSpacing(0f, 1.2f);
        return body;
    }

    private TextView noticeText(String text) {
        TextView notice = bodyText(text);
        notice.setTextColor(Color.rgb(156, 50, 35));
        notice.setPadding(0, dp(10), 0, 0);
        return notice;
    }

    private TextView emptySectionText(String text) {
        TextView empty = bodyText(text);
        empty.setTextColor(Color.rgb(111, 119, 114));
        empty.setPadding(0, dp(10), 0, dp(4));
        return empty;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
