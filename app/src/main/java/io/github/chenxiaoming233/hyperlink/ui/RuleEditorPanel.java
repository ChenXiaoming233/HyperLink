package io.github.chenxiaoming233.hyperlink.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppCatalogEntry;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppConditionEditorModel;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppSelectorModel;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleAndGroup;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleBranch;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleCondition;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.DeleteConfirmation;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.RuleLifecycleController;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.RuleLifecycleModel;
import io.github.chenxiaoming233.hyperlink.domain.lifecycle.RuleUndoToken;
import io.github.chenxiaoming233.hyperlink.domain.write.ConfirmedTargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityProbe;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityReport;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleCreationResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleModelReadiness;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleRecoveryModel;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.StructuredRuleDsl;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelectionFlow;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleWriteRecoveryController;
import io.github.chenxiaoming233.hyperlink.fake.FakeAppCatalog;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class RuleEditorPanel extends LinearLayout {
    private final Activity activity;
    private SourceDevice source;
    private List<TargetDevice> targets;
    private final FakeAppCatalog appCatalog;
    private final List<AppCatalogEntry> catalogApps;
    private final FakeRuleStoreGateway gateway;
    private final SafeNotificationRuleWriter writer;
    private final RuleWriteRecoveryController recoveryController;
    private final RuleLifecycleController lifecycleController;
    private final Runnable storeChangedListener;
    private TargetSelectionFlow targetSelectionFlow;
    private final Set<RulePreset> selectedPresets = new LinkedHashSet<>();
    private final List<CheckBox> presetChecks = new ArrayList<>();
    private final EditableViewTracker<View> persistentEditableViews =
            new EditableViewTracker<>();
    private final RuleEditorRefreshCoordinator<View> refreshCoordinator =
            new RuleEditorRefreshCoordinator<>();
    private final RuleEditorInteractionPolicy interactionPolicy;

    private StructuredRuleDraft draft;
    private AppConditionEditorModel appEditor;
    private RuleModelReadiness modelReadiness;
    private String selectedTargetId;
    private String appQuery = "";
    private boolean showAllApps;
    private boolean updatingApps;
    private Spinner targetSelector;
    private EditText appSearch;
    private CheckBox showAllAppsCheck;
    private LinearLayout appItemsContainer;
    private TextView appScopeView;
    private LinearLayout structureContainer;
    private LinearLayout existingRulesContainer;
    private TextView summaryView;
    private TextView modelReadinessView;
    private TextView writeCompatibilityWarningView;
    private TextView statusView;
    private TextView recoveryMessageView;
    private LinearLayout recoveryActionsContainer;
    private CheckBox debugModeCheck;
    private TextView debugWarningView;
    private boolean syncingDebugCheck;
    private RuleUndoToken undoToken;
    private final RuleEditorWriteGate writeGate = new RuleEditorWriteGate();

    RuleEditorPanel(
            Activity activity,
            FakeRuleStoreGateway gateway,
            SafeNotificationRuleWriter writer,
            RuleLifecycleController lifecycleController,
            RuleWriteRecoveryController recoveryController,
            Runnable storeChangedListener) {
        super(activity);
        this.activity = activity;
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.recoveryController =
                Objects.requireNonNull(recoveryController, "recoveryController");
        this.lifecycleController =
                Objects.requireNonNull(lifecycleController, "lifecycleController");
        this.storeChangedListener =
                Objects.requireNonNull(storeChangedListener, "storeChangedListener");
        NotificationFlowSnapshot snapshot = gateway.snapshot().flowSnapshot();
        this.source = sourceFrom(snapshot);
        this.targets = receivableTargets(snapshot);
        this.appCatalog = FakeAppCatalog.demo();
        this.catalogApps = readCatalog(appCatalog);
        this.targetSelectionFlow = new TargetSelectionFlow(snapshot);
        this.modelReadiness = RuleModelReadiness.inspect(gateway);
        this.interactionPolicy = new RuleEditorInteractionPolicy(modelReadiness);
        this.draft = StructuredRuleDraft.empty(System.currentTimeMillis());
        this.selectedTargetId = targets.isEmpty() ? "" : targets.get(0).deviceId();
        setOrientation(VERTICAL);
        build();
    }

    private String fakeBoundaryNotice() {
        return "仅写入独立内存 fake store；不连接真实 VoiceAssist。";
    }

    private void build() {
        TextView eyebrow = text("HYPERLINK / FAKE STRUCTURED EDITOR", 12, Color.rgb(165, 72, 32));
        addView(eyebrow);

        TextView heading = text("结构化规则编辑器", 24, Color.rgb(30, 38, 36));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(6), 0, dp(8));
        addView(heading);

        TextView notice =
                text(
                        "演示模式：预设、应用目录、目标与规则均来自 Demo/demo 合成数据。"
                                + "这里只显式写入独立的内存 fake store，"
                                + "不会读取或写入真实应用列表、VoiceAssist 规则、权限、Provider 或设备。",
                        14,
                        Color.rgb(75, 82, 78));
        notice.setLineSpacing(0f, 1.2f);
        notice.setPadding(0, 0, 0, dp(16));
        addView(notice);

        modelReadinessView = text("", 14, Color.rgb(45, 91, 62));
        modelReadinessView.setPadding(dp(10), dp(8), dp(10), dp(8));
        addView(modelReadinessView, matchWrap());
        Button redetectModel = rawActionButton("重新检测规则模型");
        redetectModel.setOnClickListener(view -> redetectModel());
        addView(redetectModel);

        writeCompatibilityWarningView = text("", 13, Color.rgb(132, 54, 24));
        writeCompatibilityWarningView.setPadding(dp(10), dp(6), dp(10), dp(6));
        writeCompatibilityWarningView.setBackgroundColor(Color.rgb(250, 218, 190));
        addView(writeCompatibilityWarningView, matchWrap());

        addView(label("规则目标（fake）"));
        targetSelector = new Spinner(activity);
        List<String> targetLabels = new ArrayList<>();
        for (TargetDevice target : targets) {
            targetLabels.add(target.displayName() + " · " + target.deviceId());
        }
        targetSelector.setAdapter(
                new ArrayAdapter<>(
                        activity,
                        android.R.layout.simple_spinner_dropdown_item,
                        targetLabels));
        targetSelector.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (position >= 0 && position < targets.size()) {
                            selectedTargetId = targets.get(position).deviceId();
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
        trackEditable(targetSelector);
        addView(targetSelector, matchWrap());

        TextView presetLabel = label("可编辑预设模板");
        presetLabel.setPadding(0, dp(16), 0, dp(4));
        addView(presetLabel);
        for (RulePreset preset : RulePreset.values()) {
            CheckBox check = new CheckBox(activity);
            check.setText(preset.label());
            check.setOnCheckedChangeListener(
                    (button, checked) -> {
                        if (checked) {
                            selectedPresets.add(preset);
                        } else {
                            selectedPresets.remove(preset);
                        }
                    });
            presetChecks.add(check);
            trackEditable(check);
            addView(check);
        }
        Button expandPresets = actionButton("展开所选预设为 OR 分支");
        expandPresets.setOnClickListener(view -> expandPresets());
        addView(expandPresets);

        TextView appLabel = label("应用选择器（fake AppCatalog）");
        appLabel.setPadding(0, dp(18), 0, dp(4));
        addView(appLabel);
        appSearch = new EditText(activity);
        appSearch.setSingleLine(true);
        appSearch.setHint("搜索名称或包名");
        appSearch.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(
                            CharSequence text, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable editable) {
                        appQuery = editable.toString();
                        refreshApps();
                    }
                });
        trackEditable(appSearch);
        addView(appSearch, matchWrap());
        showAllAppsCheck = new CheckBox(activity);
        showAllAppsCheck.setText("显示全部已安装应用（默认仅可启动与规则已引用应用）");
        showAllAppsCheck.setOnCheckedChangeListener(
                (button, checked) -> {
                    showAllApps = checked;
                    refreshApps();
                });
        trackEditable(showAllAppsCheck);
        addView(showAllAppsCheck);
        appScopeView = text("", 13, Color.rgb(111, 119, 114));
        appScopeView.setPadding(0, dp(8), 0, dp(4));
        addView(appScopeView);
        appItemsContainer = new LinearLayout(activity);
        appItemsContainer.setOrientation(VERTICAL);
        addView(appItemsContainer, matchWrap());
        Button applyAppSelection = actionButton("将选择结果写入当前 app 条件");
        applyAppSelection.setOnClickListener(view -> applyScopedApps());
        addView(applyAppSelection);

        summaryView = text("", 15, Color.rgb(45, 91, 62));
        summaryView.setPadding(0, dp(16), 0, dp(6));
        addView(summaryView);

        TextView advancedLabel =
                label("高级区域：sender / text 的 regex / not_regex 条件");
        advancedLabel.setPadding(0, dp(8), 0, dp(2));
        addView(advancedLabel);

        debugModeCheck = new CheckBox(activity);
        debugModeCheck.setText("启用高风险调试重试（默认关闭）");
        debugModeCheck.setChecked(false);
        debugModeCheck.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (syncingDebugCheck) {
                        return;
                    }
                    recoveryController.setDebugMode(checked);
                    renderRecoveryModel(recoveryController.model());
                });
        trackEditable(debugModeCheck);
        addView(debugModeCheck);
        debugWarningView = text(recoveryController.model().debugWarning(), 12, Color.rgb(142, 42, 28));
        debugWarningView.setPadding(dp(8), dp(4), dp(8), dp(6));
        debugWarningView.setBackgroundColor(Color.rgb(251, 226, 216));
        addView(debugWarningView, matchWrap());

        TextView structureLabel = label("andGroups → 规则分支 → 条件");
        structureLabel.setPadding(0, dp(12), 0, dp(4));
        addView(structureLabel);
        structureContainer = new LinearLayout(activity);
        structureContainer.setOrientation(VERTICAL);
        addView(structureContainer, matchWrap());
        Button addGroup = actionButton("增加 AND 组");
        addGroup.setOnClickListener(
                view ->
                        updateDraft(
                                () ->
                                        draft.addGroup(
                                                RuleAndGroup.of(
                                                        RuleBranch.of(
                                                                RuleCondition.isIsland(true)))),
                                "已增加 AND 组"));
        addView(addGroup);

        LinearLayout editorActions = new LinearLayout(activity);
        editorActions.setOrientation(HORIZONTAL);
        Button save = actionButton("保存到 fake store");
        Button createNew = actionButton("新建结构化规则");
        editorActions.addView(save);
        editorActions.addView(createNew);
        addView(editorActions);
        save.setOnClickListener(view -> save());
        createNew.setOnClickListener(view -> newRule());

        statusView = text("", 14, Color.rgb(111, 119, 114));
        statusView.setPadding(0, dp(10), 0, dp(10));
        addView(statusView);

        recoveryMessageView = text("", 14, Color.rgb(142, 42, 28));
        recoveryMessageView.setPadding(dp(10), dp(8), dp(10), dp(8));
        addView(recoveryMessageView, matchWrap());
        recoveryActionsContainer = new LinearLayout(activity);
        recoveryActionsContainer.setOrientation(VERTICAL);
        addView(recoveryActionsContainer, matchWrap());

        TextView existingLabel = label("fake store 中的现有规则（可载入编辑）");
        existingLabel.setPadding(0, dp(18), 0, dp(4));
        addView(existingLabel);
        existingRulesContainer = new LinearLayout(activity);
        existingRulesContainer.setOrientation(VERTICAL);
        addView(existingRulesContainer, matchWrap());

        refreshApps();
        refreshStructure();
        refreshExistingRules();
        applyModelReadiness();
        refreshWriteCompatibilityWarning();
        renderRecoveryModel(recoveryController.model());
    }

    private void expandPresets() {
        if (rejectWhenReadOnly()) {
            return;
        }
        if (selectedPresets.isEmpty()) {
            setStatus("请先选择至少一个预设。");
            return;
        }
        List<RulePreset> orderedPresets = new ArrayList<>();
        for (RulePreset preset : RulePreset.values()) {
            if (selectedPresets.contains(preset)) {
                orderedPresets.add(preset);
            }
        }
        try {
            StructuredRuleDraft expanded =
                    StructuredRuleDraft.fromPresets(
                            orderedPresets,
                            appEditor == null
                                    ? new ArrayList<>()
                                    : new ArrayList<>(appEditor.selectedPackages()),
                            draft.createdAt());
            if (draft.hasRuleId()) {
                expanded = expanded.withRuleId(draft.ruleId());
            }
            expanded = expanded.withStatus(draft.status());
            draft = expanded;
            appEditor = AppConditionEditorModel.firstEditable(draft);
            refreshStructure();
            refreshApps();
            setStatus("预设已展开为可编辑的 OR 规则分支；预设类型不会写入规则。");
        } catch (IllegalArgumentException exception) {
            setStatus("无法展开预设：IM 预设至少需要在应用选择器中选择一个应用。");
        }
    }

    private void refreshApps() {
        if (appItemsContainer == null) {
            return;
        }
        AppSelectorModel model =
                AppSelectorModel.fromCatalog(
                                catalogApps,
                                new ArrayList<>(draft.referencedPackages()),
                                appEditor == null
                                        ? new ArrayList<>()
                                        : new ArrayList<>(appEditor.selectedPackages()))
                        .withShowAllInstalled(showAllApps)
                        .withSearchQuery(appQuery);
        appScopeView.setText(appScopeText());
        refreshCoordinator.beginRebuild(RuleEditorRefreshCoordinator.Area.APP);
        appItemsContainer.removeAllViews();
        updatingApps = true;
        try {
            for (AppSelectorModel.AppItem item : model.items()) {
                appItemsContainer.addView(createAppRow(item));
            }
            if (model.items().isEmpty()) {
                appItemsContainer.addView(
                        text("没有匹配的 fake 应用。", 13, Color.rgb(111, 119, 114)));
            }
        } finally {
            updatingApps = false;
        }
    }

    private View createAppRow(AppSelectorModel.AppItem item) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView icon = text(item.iconPlaceholder(), 14, Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setColor(Color.rgb(165, 72, 32));
        iconBackground.setCornerRadius(dp(6));
        icon.setBackground(iconBackground);
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        CheckBox check = new CheckBox(activity);
        check.setText(
                item.displayName()
                        + "\n"
                        + item.packageName()
                        + " · "
                        + item.statusLabel());
        check.setTextSize(14);
        check.setChecked(item.selected());
        check.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (updatingApps) {
                        return;
                    }
                    RuleEditorInteractionPolicy.AppSelection selection =
                            interactionPolicy.selectApp(appEditor, item.packageName());
                    if (!selection.accepted()) {
                        updatingApps = true;
                        try {
                            button.setChecked(item.selected());
                        } finally {
                            updatingApps = false;
                        }
                        setStatus(selection.message());
                        return;
                    }
                    appEditor = selection.editor();
                    refreshApps();
                });
        trackAppEditable(check);
        LinearLayout.LayoutParams checkParams =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        checkParams.leftMargin = dp(8);
        row.addView(check, checkParams);
        return row;
    }

    private void refreshStructure() {
        if (structureContainer == null) {
            return;
        }
        summaryView.setText("自动摘要：" + draft.summary());
        refreshCoordinator.beginRebuild(RuleEditorRefreshCoordinator.Area.STRUCTURE);
        structureContainer.removeAllViews();
        if (draft.groups().isEmpty()) {
            structureContainer.addView(
                    text(
                            "尚无筛选条件。可选择预设展开，或通过“增加 AND 组”开始编辑。",
                            14,
                            Color.rgb(111, 119, 114)));
            return;
        }
        for (int groupIndex = 0; groupIndex < draft.groups().size(); groupIndex++) {
            structureContainer.addView(createGroupCard(groupIndex));
        }
    }

    private View createGroupCard(int groupIndex) {
        RuleAndGroup group = draft.groups().get(groupIndex);
        LinearLayout card = card();
        TextView heading = text("AND 组 " + (groupIndex + 1), 17, Color.rgb(30, 38, 36));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);
        LinearLayout groupActions = actionRow();
        groupActions.addView(
                smallButton(
                        "组上移",
                        view ->
                                updateDraft(
                                        () -> draft.moveGroup(groupIndex, groupIndex - 1),
                                        "AND 组已上移")));
        groupActions.addView(
                smallButton(
                        "组下移",
                        view ->
                                updateDraft(
                                        () -> draft.moveGroup(groupIndex, groupIndex + 1),
                                        "AND 组已下移")));
        groupActions.addView(
                smallButton(
                        "删除组",
                        view ->
                                updateDraft(
                                        () -> draft.removeGroup(groupIndex),
                                        "AND 组已删除")));
        groupActions.addView(
                smallButton(
                        "增加 OR 分支",
                        view ->
                                updateDraft(
                                        () ->
                                                draft.replaceGroup(
                                                        groupIndex,
                                                        group.addBranch(
                                                                RuleBranch.of(
                                                                        RuleCondition.isIsland(
                                                                                true)))),
                                        "已增加 OR 分支")));
        card.addView(groupActions);

        for (int branchIndex = 0; branchIndex < group.branches().size(); branchIndex++) {
            card.addView(createBranchCard(groupIndex, branchIndex));
        }
        return card;
    }

    private View createBranchCard(int groupIndex, int branchIndex) {
        RuleAndGroup group = draft.groups().get(groupIndex);
        RuleBranch branch = group.branches().get(branchIndex);
        LinearLayout branchCard = new LinearLayout(activity);
        branchCard.setOrientation(VERTICAL);
        branchCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(248, 246, 240));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.rgb(224, 218, 204));
        branchCard.setBackground(background);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(8);
        branchCard.setLayoutParams(cardParams);

        TextView title =
                text("OR 分支 " + (branchIndex + 1), 15, Color.rgb(50, 58, 54));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        branchCard.addView(title);
        for (int conditionIndex = 0;
                conditionIndex < branch.conditions().size();
                conditionIndex++) {
            branchCard.addView(
                    createConditionRow(
                            groupIndex, branchIndex, conditionIndex));
        }
        LinearLayout branchActions = actionRow();
        branchActions.addView(
                smallButton(
                        "分支上移",
                        view ->
                                updateDraft(
                                        () ->
                                                draft.replaceGroup(
                                                        groupIndex,
                                                        group.moveBranch(
                                                                branchIndex,
                                                                branchIndex - 1)),
                                        "OR 分支已上移")));
        branchActions.addView(
                smallButton(
                        "分支下移",
                        view ->
                                updateDraft(
                                        () ->
                                                draft.replaceGroup(
                                                        groupIndex,
                                                        group.moveBranch(
                                                                branchIndex,
                                                                branchIndex + 1)),
                                        "OR 分支已下移")));
        branchActions.addView(
                smallButton(
                        "删除分支",
                        view ->
                                updateDraft(
                                        () ->
                                                draft.replaceGroup(
                                                        groupIndex,
                                                        group.removeBranch(branchIndex)),
                                        "OR 分支已删除")));
        branchCard.addView(branchActions);

        LinearLayout addConditions = actionRow();
        addConditions.addView(
                smallButton(
                        "加焦点条件",
                        view ->
                                replaceBranch(
                                        groupIndex,
                                        branchIndex,
                                        branch.addCondition(RuleCondition.isIsland(true)),
                                        "已增加焦点通知条件")));
        addConditions.addView(
                smallButton(
                        "加验证码条件",
                        view ->
                                replaceBranch(
                                        groupIndex,
                                        branchIndex,
                                        branch.addCondition(RuleCondition.isVerifyCode(true)),
                                        "已增加验证码条件")));
        addConditions.addView(
                smallButton(
                        "选择本分支 app 集合",
                        view -> selectAppScopeForBranch(groupIndex, branchIndex)));
        addConditions.addView(
                smallButton(
                        "加 sender 正则",
                        view ->
                                showRegexConditionDialog(
                                        RuleCondition.Field.SENDER,
                                        RuleCondition.Operator.REGEX,
                                        "",
                                        condition ->
                                                replaceBranch(
                                                        groupIndex,
                                                        branchIndex,
                                                        branch.addCondition(condition),
                                                        "已增加 sender 正则条件"))));
        addConditions.addView(
                smallButton(
                        "加 text 正则",
                        view ->
                                showRegexConditionDialog(
                                        RuleCondition.Field.TEXT,
                                        RuleCondition.Operator.REGEX,
                                        "",
                                        condition ->
                                                replaceBranch(
                                                        groupIndex,
                                                        branchIndex,
                                                        branch.addCondition(condition),
                                                        "已增加 text 正则条件"))));
        branchCard.addView(addConditions);
        return branchCard;
    }

    private View createConditionRow(
            int groupIndex, int branchIndex, int conditionIndex) {
        RuleBranch branch =
                draft.groups().get(groupIndex).branches().get(branchIndex);
        RuleCondition condition = branch.conditions().get(conditionIndex);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(VERTICAL);
        row.setPadding(0, dp(7), 0, dp(4));

        row.addView(text(conditionText(condition), 14, Color.rgb(50, 58, 54)));
        LinearLayout actions = actionRow();
        actions.addView(
                smallButton(
                        "条件上移",
                        view ->
                                replaceBranch(
                                        groupIndex,
                                        branchIndex,
                                        branch.moveCondition(
                                                conditionIndex,
                                                conditionIndex - 1),
                                        "条件已上移")));
        actions.addView(
                smallButton(
                        "条件下移",
                        view ->
                                replaceBranch(
                                        groupIndex,
                                        branchIndex,
                                        branch.moveCondition(
                                                conditionIndex,
                                                conditionIndex + 1),
                                        "条件已下移")));
        if (condition.field() == RuleCondition.Field.APP) {
            actions.addView(
                    smallButton(
                            "选择此 app 条件",
                            view -> selectAppScopeForCondition(
                                    groupIndex, branchIndex, conditionIndex)));
            actions.addView(
                    smallButton(
                            condition.operator() == RuleCondition.Operator.IN
                                    ? "改为 not_in"
                                    : "改为 in",
                            view ->
                                    replaceCondition(
                                            groupIndex,
                                            branchIndex,
                                            conditionIndex,
                                            condition.withOperator(
                                                    condition.operator()
                                                                    == RuleCondition.Operator.IN
                                                            ? RuleCondition.Operator.NOT_IN
                                                            : RuleCondition.Operator.IN),
                                            "应用集合操作符已切换")));
        } else if (condition.field() == RuleCondition.Field.SENDER
                || condition.field() == RuleCondition.Field.TEXT) {
            actions.addView(
                    smallButton(
                            "编辑正则",
                            view ->
                                    showRegexConditionDialog(
                                            condition.field(),
                                            condition.operator(),
                                            condition.pattern(),
                                            nextCondition ->
                                                    replaceCondition(
                                                            groupIndex,
                                                            branchIndex,
                                                            conditionIndex,
                                                            nextCondition,
                                                            "正则条件已更新"))));
            actions.addView(
                    smallButton(
                            condition.operator() == RuleCondition.Operator.REGEX
                                    ? "改为 not_regex"
                                    : "改为 regex",
                            view ->
                                    replaceCondition(
                                            groupIndex,
                                            branchIndex,
                                            conditionIndex,
                                            condition.withOperator(
                                                    condition.operator()
                                                                    == RuleCondition.Operator.REGEX
                                                            ? RuleCondition.Operator.NOT_REGEX
                                                            : RuleCondition.Operator.REGEX),
                                            "正则操作符已切换")));
        } else {
            actions.addView(
                    smallButton(
                            "切换布尔值",
                            view ->
                                    replaceCondition(
                                            groupIndex,
                                            branchIndex,
                                            conditionIndex,
                                            condition.withBooleanValue(
                                                    !condition.booleanValue()),
                                            "布尔条件已切换")));
        }
        actions.addView(
                smallButton(
                        "删除条件",
                        view ->
                                replaceBranch(
                                        groupIndex,
                                        branchIndex,
                                        branch.removeCondition(conditionIndex),
                                        "条件已删除")));
        row.addView(actions);
        return row;
    }

    private void selectAppScopeForBranch(int groupIndex, int branchIndex) {
        if (rejectWhenReadOnly()) {
            return;
        }
        try {
            appEditor = AppConditionEditorModel.atBranch(draft, groupIndex, branchIndex);
            refreshApps();
            setStatus("应用选择器已切换到当前分支的 app 条件作用域。");
        } catch (IllegalArgumentException exception) {
            setStatus("无法选择该分支的 app 条件作用域。");
        }
    }

    private void selectAppScopeForCondition(
            int groupIndex, int branchIndex, int conditionIndex) {
        if (rejectWhenReadOnly()) {
            return;
        }
        try {
            appEditor =
                    AppConditionEditorModel.atCondition(
                            draft, groupIndex, branchIndex, conditionIndex);
            refreshApps();
            setStatus("应用选择器已载入当前 app 条件的包集合。");
        } catch (IllegalArgumentException exception) {
            setStatus("无法载入该 app 条件的包集合。");
        }
    }

    private void applyScopedApps() {
        if (rejectWhenReadOnly()) {
            return;
        }
        if (appEditor == null) {
            setStatus("请先在规则分支中选定一个 app 条件作用域。");
            return;
        }
        try {
            appEditor = appEditor.apply(appEditor.operator());
            draft = appEditor.draft();
            refreshStructure();
            refreshApps();
            setStatus("选中应用仅写入当前 app 条件；其他分支保持不变。");
        } catch (RuntimeException exception) {
            setStatus("请至少选择一个 fake 应用后再写入当前 app 条件。");
        }
    }

    private void replaceCondition(
            int groupIndex,
            int branchIndex,
            int conditionIndex,
            RuleCondition condition,
            String successMessage) {
        RuleAndGroup group = draft.groups().get(groupIndex);
        RuleBranch branch = group.branches().get(branchIndex);
        replaceBranch(
                groupIndex,
                branchIndex,
                branch.replaceCondition(conditionIndex, condition),
                successMessage);
    }

    private void showRegexConditionDialog(
            RuleCondition.Field field,
            RuleCondition.Operator operator,
            String initialPattern,
            Consumer<RuleCondition> onValidCondition) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), 0);

        TextView help =
                text(
                        "使用 Java Pattern 语法。非法正则会即时提示，且“应用”按钮不可用。",
                        13,
                        Color.rgb(75, 82, 78));
        help.setPadding(0, 0, 0, dp(8));
        content.addView(help, matchWrap());

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(initialPattern);
        input.setHint(field == RuleCondition.Field.SENDER ? "sender 正则" : "text 正则");
        content.addView(input, matchWrap());

        TextView error = text("", 13, Color.rgb(165, 48, 32));
        error.setPadding(0, dp(6), 0, 0);
        content.addView(error, matchWrap());

        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(
                                (field == RuleCondition.Field.SENDER ? "sender" : "text")
                                        + " / "
                                        + (operator == RuleCondition.Operator.REGEX
                                                ? "regex"
                                                : "not_regex"))
                        .setView(content)
                        .setPositiveButton("应用", null)
                        .setNegativeButton("取消", null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    Button applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    Runnable validate =
                            () -> {
                                String issue =
                                        RuleCondition.regexIssue(
                                                input.getText().toString());
                                error.setText(issue);
                                applyButton.setEnabled(
                                        issue.isEmpty() && modelReadiness.writable());
                            };
                    input.addTextChangedListener(
                            new TextWatcher() {
                                @Override
                                public void beforeTextChanged(
                                        CharSequence text,
                                        int start,
                                        int count,
                                        int after) {}

                                @Override
                                public void onTextChanged(
                                        CharSequence text,
                                        int start,
                                        int before,
                                        int count) {}

                                @Override
                                public void afterTextChanged(Editable editable) {
                                    validate.run();
                                }
                            });
                    applyButton.setOnClickListener(
                            view -> {
                                String pattern = input.getText().toString();
                                String issue = RuleCondition.regexIssue(pattern);
                                if (!issue.isEmpty()) {
                                    error.setText(issue);
                                    applyButton.setEnabled(false);
                                    return;
                                }
                                if (rejectWhenReadOnly()) {
                                    dialog.dismiss();
                                    return;
                                }
                                onValidCondition.accept(
                                        regexCondition(field, operator, pattern));
                                dialog.dismiss();
                            });
                    validate.run();
                });
        dialog.show();
    }

    private void replaceBranch(
            int groupIndex,
            int branchIndex,
            RuleBranch branch,
            String successMessage) {
        RuleAndGroup group = draft.groups().get(groupIndex);
        updateDraft(
                () -> draft.replaceGroup(groupIndex, group.replaceBranch(branchIndex, branch)),
                successMessage);
    }

    private void updateDraft(DraftChange change, String successMessage) {
        if (rejectWhenReadOnly()) {
            return;
        }
        try {
            draft = change.apply();
            appEditor = AppConditionEditorModel.firstEditable(draft);
            refreshStructure();
            refreshApps();
            setStatus(successMessage + "。自动摘要已按筛选结构更新。");
        } catch (RuntimeException exception) {
            setStatus("未执行操作：至少需要保留一个规则分支和一个条件。");
        }
    }

    private void save() {
        if (rejectWhenReadOnly() || !writesAvailable()) {
            return;
        }
        if (!draft.canSave()) {
            setStatus("当前没有可保存的筛选条件；未调用写入。");
            return;
        }
        ServiceResult<TargetSelection> selected =
                targetSelectionFlow.select(source, selectedTargetId);
        if (!selected.isSuccess()) {
            setStatus(selected.userMessage());
            return;
        }
        RuleSaveGate gate = RuleSaveGate.from(selected.value().orElseThrow());
        if (!gate.confirmationRequired()) {
            completeSave(gate.decide(true));
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(gate.promptTitle())
                .setMessage(gate.promptMessage())
                .setPositiveButton(
                        "确认并保存",
                        (dialog, which) -> completeSave(gate.decide(true)))
                .setNegativeButton(
                        "取消",
                        (dialog, which) -> completeSave(gate.decide(false)))
                .show();
    }

    private void completeSave(RuleSaveGate.SaveDecision decision) {
        if (rejectWhenReadOnly() || !writesAvailable()) {
            return;
        }
        if (!decision.shouldSave()) {
            setStatus(decision.message());
            return;
        }
        ConfirmedTargetSelection confirmed =
                decision.confirmedSelection().orElseThrow();
        int writesBefore = gateway.writeCount();
        ServiceResult<RuleCreationResult> result =
                recoveryController.saveStructuredRule(confirmed, draft);
        modelReadiness = RuleModelReadiness.inspect(gateway);
        applyModelReadiness();
        if (!result.isSuccess()) {
            boolean writeAttempted = gateway.writeCount() > writesBefore;
            RuleEditorLoadState loadState =
                    writeGate.refreshAfterWriteFailure(gateway, writeAttempted);
            RuleRecoveryModel recoveryModel = recoveryController.model();
            if (recoveryModel.state() == RuleRecoveryModel.State.AWAITING_DECISION
                    || recoveryModel.state()
                            == RuleRecoveryModel.State.PERSISTENT_READ_ONLY_FAULT) {
                refreshExistingRules(RuleEditorLoadState.from(gateway));
                notifyStoreChanged();
                renderRecoveryModel(recoveryModel);
                setStatus(result.userMessage());
                return;
            }
            applyRefreshState(loadState);
            refreshExistingRules(loadState);
            notifyStoreChanged();
            setStatus(result.userMessage());
            return;
        }
        RuleCreationResult creationResult = result.value().orElseThrow();
        completeSuccessfulSave(creationResult);
    }

    private void completeSuccessfulSave(RuleCreationResult creationResult) {
        NotificationRule saved = creationResult.rule();
        draft = StructuredRuleDsl.parse(saved);
        appEditor = AppConditionEditorModel.firstEditable(draft);
        refreshStructure();
        refreshApps();
        refreshExistingRules();
        notifyStoreChanged();
        setStatus(RuleSaveFeedback.from(saved, creationResult.notes()).displayText());
        renderRecoveryModel(recoveryController.model());
    }

    private void newRule() {
        if (rejectWhenReadOnly()) {
            return;
        }
        draft = StructuredRuleDraft.empty(System.currentTimeMillis());
        appEditor = null;
        selectedPresets.clear();
        for (CheckBox check : presetChecks) {
            check.setChecked(false);
        }
        refreshStructure();
        refreshApps();
        setStatus("已新建空白结构化草稿；不会自动保存。");
    }

    private void loadRule(NotificationRule rule) {
        if (rejectWhenReadOnly()) {
            return;
        }
        try {
            draft = StructuredRuleDsl.parse(rule);
        } catch (IllegalArgumentException exception) {
            setStatus("该规则 DSL 无法表示为当前基础编辑器，不会改写它。");
            return;
        }
        selectTarget(rule.targetDeviceId());
        appEditor = AppConditionEditorModel.firstEditable(draft);
        selectedPresets.clear();
        for (CheckBox check : presetChecks) {
            check.setChecked(false);
        }
        refreshStructure();
        refreshApps();
        if (!draft.canSave()) {
            setStatus(
                    "已载入“所有符合条件的通知”规则；保留 ruleId、状态和创建时间，"
                            + "添加筛选条件后可再次保存。");
        } else {
            setStatus("已载入现有规则；保存时会复用 SafeNotificationRuleWriter 并保留元数据。");
        }
    }

    private void selectTarget(String targetDeviceId) {
        for (int index = 0; index < targets.size(); index++) {
            if (targets.get(index).deviceId().equals(targetDeviceId)) {
                selectedTargetId = targetDeviceId;
                targetSelector.setSelection(index);
                return;
            }
        }
    }

    void refreshFromStore() {
        RuleEditorLoadState loadState = refreshLoadState();
        if (!loadState.writable()) {
            return;
        }
        NotificationFlowSnapshot snapshot = gateway.snapshot().flowSnapshot();
        source = sourceFrom(snapshot);
        targets = receivableTargets(snapshot);
        targetSelectionFlow = new TargetSelectionFlow(snapshot);
        refreshTargetSelector();
        refreshExistingRules();
        refreshWriteCompatibilityWarning();
    }

    private void refreshTargetSelector() {
        if (targetSelector == null) {
            return;
        }
        List<String> targetLabels = new ArrayList<>();
        int selectedIndex = -1;
        for (int index = 0; index < targets.size(); index++) {
            TargetDevice target = targets.get(index);
            targetLabels.add(target.displayName() + " · " + target.deviceId());
            if (target.deviceId().equals(selectedTargetId)) {
                selectedIndex = index;
            }
        }
        targetSelector.setAdapter(
                new ArrayAdapter<>(
                        activity,
                        android.R.layout.simple_spinner_dropdown_item,
                        targetLabels));
        if (selectedIndex < 0 && !targets.isEmpty()) {
            selectedIndex = 0;
            selectedTargetId = targets.get(0).deviceId();
        }
        if (selectedIndex >= 0) {
            targetSelector.setSelection(selectedIndex);
        }
    }

    private void refreshExistingRules() {
        refreshExistingRules(refreshLoadState());
    }

    private void refreshExistingRules(RuleEditorLoadState loadState) {
        if (!loadState.writable()) {
            if (existingRulesContainer != null) {
                existingRulesContainer.removeAllViews();
                existingRulesContainer.addView(
                        text(loadState.message(), 14, Color.rgb(156, 50, 35)));
            }
            return;
        }
        if (existingRulesContainer == null) {
            return;
        }
        refreshCoordinator.beginRebuild(
                RuleEditorRefreshCoordinator.Area.EXISTING_RULES);
        existingRulesContainer.removeAllViews();
        ServiceResult<RuleLifecycleModel> refreshed = lifecycleController.refresh();
        if (!refreshed.isSuccess()) {
            existingRulesContainer.addView(
                    text(refreshed.userMessage(), 14, Color.rgb(156, 50, 35)));
            return;
        }
        RuleLifecycleModel model = refreshed.value().orElseThrow();
        undoToken = model.canUndo() ? model.undoToken().orElse(null) : null;
        if (!model.blockedError().isEmpty()) {
            existingRulesContainer.addView(
                    text(model.blockedError(), 14, Color.rgb(156, 50, 35)));
        }
        if (undoToken != null) {
            Button undo = actionButton("撤销最近的暂停/恢复");
            undo.setOnClickListener(view -> undoLifecycleChange(undoToken));
            existingRulesContainer.addView(undo);
        }
        for (RuleLifecycleModel.RuleItem item : model.rules()) {
            NotificationRule stored = findStoredRule(item.ruleId());
            if (stored != null) {
                existingRulesContainer.addView(createExistingRuleCard(item, stored));
            }
        }
    }

    private View createExistingRuleCard(
            RuleLifecycleModel.RuleItem item, NotificationRule rule) {
        LinearLayout card = card();
        TextView heading = text(rule.ruleId(), 15, Color.rgb(30, 38, 36));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);
        card.addView(
                text(
                        "状态："
                                + item.displayStatus()
                                + "（原始值："
                                + rule.status()
                                + "） · 创建时间："
                                + rule.createdAt(),
                        13,
                        Color.rgb(111, 119, 114)));
        card.addView(
                text(
                        "目标："
                                + item.targetDeviceName()
                                + " · 目标授权："
                                + (item.targetAuthorized() ? "已授权" : "未授权"),
                        13,
                        Color.rgb(111, 119, 114)));
        card.addView(text(rule.summary(), 14, Color.rgb(50, 58, 54)));
        if (!item.blockedMessage().isEmpty()) {
            card.addView(
                    text(item.blockedMessage(), 13, Color.rgb(156, 50, 35)));
        }
        LinearLayout actions = actionRow();
        if (item.canPause()) {
            actions.addView(
                    smallButton(
                            "暂停规则",
                            view -> pauseRule(item.ruleId())));
        }
        if (item.canResume()) {
            actions.addView(
                    smallButton(
                            "恢复规则",
                            view -> resumeRule(item.ruleId())));
        }
        if (item.canDelete()) {
            actions.addView(
                    smallButton(
                            "删除规则",
                            view -> requestDeleteRule(item.ruleId())));
        }
        Button edit = rawActionButton("载入并编辑");
        trackExistingRuleEditable(edit);
        edit.setOnClickListener(view -> loadRule(rule));
        actions.addView(edit);
        card.addView(actions);
        return card;
    }

    private void pauseRule(String ruleId) {
        if (!writesAvailable()) {
            return;
        }
        completeLifecycleChange(
                lifecycleController.pause(ruleId),
                "规则已暂停并持久化为 paused；可在 10 秒内撤销。");
    }

    private void resumeRule(String ruleId) {
        if (!writesAvailable()) {
            return;
        }
        completeLifecycleChange(
                lifecycleController.resume(ruleId),
                "规则已恢复为 active，并复用原 ruleId；可在 10 秒内撤销。");
    }

    private void undoLifecycleChange(RuleUndoToken token) {
        if (!writesAvailable()) {
            return;
        }
        completeLifecycleChange(
                lifecycleController.undo(token),
                "已撤销最近一次暂停/恢复操作。");
    }

    private void completeLifecycleChange(
            ServiceResult<RuleLifecycleModel> result, String successMessage) {
        refreshExistingRules();
        if (!result.isSuccess()) {
            notifyStoreChanged();
            setStatus(result.userMessage());
            return;
        }
        RuleLifecycleModel model = result.value().orElseThrow();
        undoToken = model.canUndo() ? model.undoToken().orElse(null) : null;
        refreshExistingRules();
        notifyStoreChanged();
        setStatus(successMessage);
    }

    private void requestDeleteRule(String ruleId) {
        if (!writesAvailable()) {
            return;
        }
        ServiceResult<DeleteConfirmation> requested =
                lifecycleController.requestDelete(ruleId);
        if (!requested.isSuccess()) {
            setStatus(requested.userMessage());
            return;
        }
        DeleteConfirmation confirmation = requested.value().orElseThrow();
        new AlertDialog.Builder(activity)
                .setTitle("确认删除规则并撤销目标授权")
                .setMessage(confirmation.prompt())
                .setPositiveButton(
                        "确认删除并撤销授权",
                        (dialog, which) -> completeDelete(confirmation, true))
                .setNegativeButton(
                        "取消",
                        (dialog, which) -> completeDelete(confirmation, false))
                .show();
    }

    private void completeDelete(DeleteConfirmation confirmation, boolean confirmed) {
        if (!writesAvailable()) {
            return;
        }
        ServiceResult<RuleLifecycleModel> result =
                lifecycleController.confirmDelete(confirmation, confirmed);
        refreshExistingRules();
        if (confirmed) {
            notifyStoreChanged();
        }
        if (!result.isSuccess()) {
            setStatus(result.userMessage());
        } else if (!confirmed) {
            setStatus("已取消删除；未写入规则，也未撤销目标授权。");
        } else {
            setStatus(
                    "规则已删除，并已通过 fake 权限 gateway 撤销目标授权；"
                            + "未连接真实系统授权 API。");
        }
    }

    private void notifyStoreChanged() {
        storeChangedListener.run();
    }

    private RuleEditorLoadState refreshLoadState() {
        RuleEditorLoadState loadState = writeGate.refresh(gateway);
        applyRefreshState(loadState);
        return loadState;
    }

    private void applyRefreshState(RuleEditorLoadState loadState) {
        setVisibility(loadState.writable() ? VISIBLE : GONE);
    }

    private boolean writesAvailable() {
        if (!writeGate.writesAvailable()) {
            setStatus("共享 fake store 当前不可读或处于只读故障态，未执行写入。");
        }
        return writeGate.writesAvailable();
    }

    private NotificationRule findStoredRule(String ruleId) {
        for (NotificationRule rule : gateway.currentRules()) {
            if (rule.ruleId().equals(ruleId)) {
                return rule;
            }
        }
        return null;
    }

    private String conditionText(RuleCondition condition) {
        switch (condition.field()) {
            case APP:
                return "应用 "
                        + (condition.operator() == RuleCondition.Operator.IN
                                ? "in "
                                : "not_in ")
                        + String.join(", ", condition.appPackages());
            case ISLAND:
                return "isIsland = " + condition.booleanValue();
            case VERIFY_CODE:
                return "isVerifyCode = " + condition.booleanValue();
            case SENDER:
                return "sender "
                        + (condition.operator() == RuleCondition.Operator.REGEX
                                ? "regex /"
                                : "not_regex /")
                        + condition.pattern()
                        + "/";
            case TEXT:
                return "text "
                        + (condition.operator() == RuleCondition.Operator.REGEX
                                ? "regex /"
                                : "not_regex /")
                        + condition.pattern()
                        + "/";
            default:
                return condition.field().name();
        }
    }

    private String appScopeText() {
        if (appEditor == null) {
            return "当前未选择 app 条件作用域；请先在规则分支中点击“选择本分支 app 集合”。";
        }
        String scope =
                "当前作用域：AND 组 "
                        + (appEditor.groupIndex() + 1)
                        + " / OR 分支 "
                        + (appEditor.branchIndex() + 1);
        if (appEditor.appConditionIndex() < 0) {
            scope += " / 新建 app 条件";
        } else {
            scope += " / app 条件 " + (appEditor.appConditionIndex() + 1);
        }
        return scope
                + "；已载入 "
                + String.join(", ", appEditor.selectedPackages());
    }

    private void renderRecoveryModel(RuleRecoveryModel model) {
        if (recoveryMessageView == null
                || recoveryActionsContainer == null
                || debugModeCheck == null) {
            return;
        }
        refreshWriteCompatibilityWarning();
        syncingDebugCheck = true;
        try {
            debugModeCheck.setChecked(model.debugModeEnabled());
        } finally {
            syncingDebugCheck = false;
        }
        debugModeCheck.setEnabled(
                interactionPolicy.canEdit()
                        || model.state() == RuleRecoveryModel.State.AWAITING_DECISION);
        debugWarningView.setText(model.debugWarning());

        boolean visible = model.state() != RuleRecoveryModel.State.IDLE;
        recoveryMessageView.setVisibility(visible ? VISIBLE : GONE);
        recoveryActionsContainer.setVisibility(visible ? VISIBLE : GONE);
        recoveryMessageView.setText(model.message());
        recoveryActionsContainer.removeAllViews();
        if (!visible) {
            return;
        }
        if (model.canRollback()) {
            recoveryActionsContainer.addView(
                    recoveryButton("立即回滚（推荐）", view -> rollbackRecovery()));
        }
        if (model.canRetry()) {
            recoveryActionsContainer.addView(
                    recoveryButton(
                            model.debugModeEnabled()
                                    ? "调试模式继续发起原写入"
                                    : "受控重试一次",
                            view -> retryRecovery()));
        }
        if (model.canRedetect()) {
            recoveryActionsContainer.addView(
                    recoveryButton("重新检测", view -> redetectRecovery()));
        }
        if (model.canRetryRollback()) {
            recoveryActionsContainer.addView(
                    recoveryButton("再次尝试回滚", view -> retryRollbackRecovery()));
        }
    }

    private void refreshWriteCompatibilityWarning() {
        if (writeCompatibilityWarningView == null) {
            return;
        }
        RuleStoreSnapshot snapshot = gateway.snapshot();
        CapabilityReport capability = CapabilityProbe.evaluate(snapshot.capabilities());
        boolean unverified =
                capability.compatibilityUnverified()
                        || snapshot.flowSnapshot().compatibilityUnverified();
        writeCompatibilityWarningView.setVisibility(unverified ? VISIBLE : GONE);
        writeCompatibilityWarningView.setText(
                unverified
                        ? "兼容性未经完整验收：运行时能力探测已通过，"
                                + "但当前 VoiceAssist 版本未完成完整验收。"
                        : "");
    }

    private Button recoveryButton(String label, OnClickListener listener) {
        Button button = rawActionButton(label);
        button.setTextSize(13);
        button.setOnClickListener(listener);
        return button;
    }

    private void retryRecovery() {
        ServiceResult<RuleCreationResult> result = recoveryController.retry();
        modelReadiness = RuleModelReadiness.inspect(gateway);
        applyModelReadiness();
        writeGate.refresh(gateway);
        if (result.isSuccess()) {
            completeSuccessfulSave(result.value().orElseThrow());
            return;
        }
        refreshExistingRules(RuleEditorLoadState.from(gateway));
        notifyStoreChanged();
        renderRecoveryModel(recoveryController.model());
        setStatus(result.userMessage());
    }

    private void rollbackRecovery() {
        completeRecoveryRollback(recoveryController.rollback());
    }

    private void retryRollbackRecovery() {
        completeRecoveryRollback(recoveryController.retryRollback());
    }

    private void completeRecoveryRollback(ServiceResult<RuleRecoveryModel> result) {
        writeGate.refresh(gateway);
        modelReadiness = RuleModelReadiness.inspect(gateway);
        applyModelReadiness();
        refreshExistingRules(RuleEditorLoadState.from(gateway));
        notifyStoreChanged();
        RuleRecoveryModel model = recoveryController.model();
        renderRecoveryModel(model);
        if (result.isSuccess()) {
            setStatus(model.message());
        } else {
            setStatus(result.userMessage());
        }
    }

    private void redetectRecovery() {
        ServiceResult<RuleRecoveryModel> result = recoveryController.redetect();
        writeGate.refresh(gateway);
        modelReadiness = RuleModelReadiness.inspect(gateway);
        applyModelReadiness();
        refreshExistingRules(RuleEditorLoadState.from(gateway));
        renderRecoveryModel(result.value().orElseGet(recoveryController::model));
        setStatus(result.isSuccess() ? result.value().orElseThrow().message() : result.userMessage());
    }

    private void setStatus(String message) {
        statusView.setText(message + "\n" + fakeBoundaryNotice());
    }

    private void redetectModel() {
        modelReadiness = RuleModelReadiness.inspect(gateway);
        applyModelReadiness();
        refreshApps();
        refreshStructure();
        refreshExistingRules();
        if (modelReadiness.writable()) {
            setStatus("重新检测通过：整份 fake 规则模型可编辑。");
        } else {
            setStatus(
                    "重新检测仍为只读："
                            + modelReadiness.reason()
                            + " 请先修复 fake 快照，再次点击“重新检测规则模型”。");
        }
    }

    private void applyModelReadiness() {
        if (modelReadiness == null) {
            return;
        }
        interactionPolicy.update(modelReadiness);
        if (modelReadinessView != null) {
            if (modelReadiness.writable()) {
                modelReadinessView.setText(
                        "规则模型检查：通过。只能编辑已表达的合法 DSL；保存仍经过安全写入链路。");
                modelReadinessView.setTextColor(Color.rgb(45, 91, 62));
                modelReadinessView.setBackgroundColor(Color.rgb(226, 240, 226));
            } else {
                modelReadinessView.setText(
                        "整份规则模型只读："
                                + modelReadiness.reason()
                                + "\n编辑与保存已禁用；修复 fake 快照后使用“重新检测规则模型”。");
                modelReadinessView.setTextColor(Color.rgb(142, 42, 28));
                modelReadinessView.setBackgroundColor(Color.rgb(251, 226, 216));
            }
        }
        boolean writable = modelReadiness.writable();
        persistentEditableViews.forEach(view -> view.setEnabled(writable));
        refreshCoordinator.forEach(
                RuleEditorRefreshCoordinator.Area.APP,
                view ->
                        view.setEnabled(
                                interactionPolicy.appCheckboxEnabled(
                                        appEditor != null)));
        refreshCoordinator.forEach(
                RuleEditorRefreshCoordinator.Area.STRUCTURE,
                view -> view.setEnabled(interactionPolicy.canEdit()));
        refreshCoordinator.forEach(
                RuleEditorRefreshCoordinator.Area.EXISTING_RULES,
                view -> view.setEnabled(interactionPolicy.canEdit()));
    }

    private boolean rejectWhenReadOnly() {
        if (interactionPolicy.canEdit()) {
            return false;
        }
        setStatus(
                "整份规则模型为只读："
                        + interactionPolicy.reason()
                        + " 未执行编辑或保存；请修复 fake 快照后重新检测。");
        return true;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(4), 0, 0);
        return row;
    }

    private Button actionButton(String label) {
        Button button = rawActionButton(label);
        trackEditable(button);
        return button;
    }

    private Button rawActionButton(String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private Button smallButton(String label, OnClickListener listener) {
        Button button = rawActionButton(label);
        trackStructureEditable(button);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(listener);
        return button;
    }

    private <T extends View> T trackEditable(T view) {
        persistentEditableViews.track(view);
        view.setEnabled(modelReadiness != null && modelReadiness.writable());
        return view;
    }

    private <T extends View> T trackAppEditable(T view) {
        refreshCoordinator.track(RuleEditorRefreshCoordinator.Area.APP, view);
        view.setEnabled(interactionPolicy.appCheckboxEnabled(appEditor != null));
        return view;
    }

    private <T extends View> T trackStructureEditable(T view) {
        refreshCoordinator.track(RuleEditorRefreshCoordinator.Area.STRUCTURE, view);
        view.setEnabled(interactionPolicy.canEdit());
        return view;
    }

    private <T extends View> T trackExistingRuleEditable(T view) {
        refreshCoordinator.track(
                RuleEditorRefreshCoordinator.Area.EXISTING_RULES, view);
        view.setEnabled(interactionPolicy.canEdit());
        return view;
    }

    private static RuleCondition regexCondition(
            RuleCondition.Field field,
            RuleCondition.Operator operator,
            String pattern) {
        if (field == RuleCondition.Field.SENDER) {
            return operator == RuleCondition.Operator.REGEX
                    ? RuleCondition.senderRegex(pattern)
                    : RuleCondition.senderNotRegex(pattern);
        }
        if (field == RuleCondition.Field.TEXT) {
            return operator == RuleCondition.Operator.REGEX
                    ? RuleCondition.textRegex(pattern)
                    : RuleCondition.textNotRegex(pattern);
        }
        throw new IllegalArgumentException("Only sender and text conditions use regex");
    }

    private TextView label(String value) {
        return text(value, 12, Color.rgb(111, 119, 114));
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(255, 253, 247));
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), Color.rgb(224, 218, 204));
        card.setBackground(background);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static SourceDevice sourceFrom(NotificationFlowSnapshot snapshot) {
        if (!snapshot.rules().isEmpty()) {
            NotificationRule first = snapshot.rules().get(0);
            return new SourceDevice(
                    first.sourceDeviceId(), first.sourceDeviceName());
        }
        return new SourceDevice("demo-source-phone", "Demo-Phone");
    }

    private static List<TargetDevice> receivableTargets(NotificationFlowSnapshot snapshot) {
        List<TargetDevice> result = new ArrayList<>();
        for (TargetDevice target : snapshot.targets()) {
            if (target.supportsNotificationReceive()) {
                result.add(target);
            }
        }
        return result;
    }

    private static List<AppCatalogEntry> readCatalog(FakeAppCatalog appCatalog) {
        ServiceResult<List<AppCatalogEntry>> result = appCatalog.readApps();
        if (!result.isSuccess()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(result.value().orElseThrow());
    }

    @FunctionalInterface
    private interface DraftChange {
        StructuredRuleDraft apply();
    }
}
