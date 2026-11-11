package io.github.chenxiaoming233.hyperlink.ui;

import android.app.Activity;
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
import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppCatalogEntry;
import io.github.chenxiaoming233.hyperlink.domain.editor.AppSelectorModel;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleAndGroup;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleBranch;
import io.github.chenxiaoming233.hyperlink.domain.editor.RuleCondition;
import io.github.chenxiaoming233.hyperlink.domain.editor.RulePreset;
import io.github.chenxiaoming233.hyperlink.domain.editor.StructuredRuleDraft;
import io.github.chenxiaoming233.hyperlink.domain.write.CapabilityEvidence;
import io.github.chenxiaoming233.hyperlink.domain.write.ConfirmedTargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleCreationResult;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.write.SafeNotificationRuleWriter;
import io.github.chenxiaoming233.hyperlink.domain.write.SourceDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.StructuredRuleDsl;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelection;
import io.github.chenxiaoming233.hyperlink.domain.write.TargetSelectionFlow;
import io.github.chenxiaoming233.hyperlink.fake.FakeAppCatalog;
import io.github.chenxiaoming233.hyperlink.fake.FakeLastKnownGoodStore;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;
import io.github.chenxiaoming233.hyperlink.fake.FakeRuleStoreGateway;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RuleEditorPanel extends LinearLayout {
    private final Activity activity;
    private final NotificationFlowSnapshot snapshot;
    private final SourceDevice source;
    private final List<TargetDevice> targets;
    private final FakeAppCatalog appCatalog;
    private final List<AppCatalogEntry> catalogApps;
    private final FakeRuleStoreGateway gateway;
    private final SafeNotificationRuleWriter writer;
    private final TargetSelectionFlow targetSelectionFlow;
    private final Set<String> selectedPackages = new LinkedHashSet<>();
    private final Set<RulePreset> selectedPresets = new LinkedHashSet<>();
    private final List<CheckBox> presetChecks = new ArrayList<>();

    private StructuredRuleDraft draft;
    private String selectedTargetId;
    private String appQuery = "";
    private boolean showAllApps;
    private boolean updatingApps;
    private Spinner targetSelector;
    private EditText appSearch;
    private CheckBox showAllAppsCheck;
    private LinearLayout appItemsContainer;
    private LinearLayout structureContainer;
    private LinearLayout existingRulesContainer;
    private TextView summaryView;
    private TextView statusView;

    RuleEditorPanel(Activity activity) {
        super(activity);
        this.activity = activity;
        this.snapshot =
                FakeNotificationFlowService.demoSnapshot(ConnectionState.CONNECTED);
        this.source = sourceFrom(snapshot);
        this.targets = receivableTargets(snapshot);
        this.appCatalog = FakeAppCatalog.demo();
        this.catalogApps = readCatalog(appCatalog);
        this.gateway =
                new FakeRuleStoreGateway(
                        new RuleStoreSnapshot(
                                snapshot,
                                source,
                                CapabilityEvidence.demoComplete(),
                                false,
                                false));
        this.writer =
                new SafeNotificationRuleWriter(gateway, new FakeLastKnownGoodStore());
        this.targetSelectionFlow = new TargetSelectionFlow(snapshot);
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
        addView(appSearch, matchWrap());
        showAllAppsCheck = new CheckBox(activity);
        showAllAppsCheck.setText("显示全部已安装应用（默认仅可启动与规则已引用应用）");
        showAllAppsCheck.setOnCheckedChangeListener(
                (button, checked) -> {
                    showAllApps = checked;
                    refreshApps();
                });
        addView(showAllAppsCheck);
        appItemsContainer = new LinearLayout(activity);
        appItemsContainer.setOrientation(VERTICAL);
        addView(appItemsContainer, matchWrap());

        summaryView = text("", 15, Color.rgb(45, 91, 62));
        summaryView.setPadding(0, dp(16), 0, dp(6));
        addView(summaryView);

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

        TextView existingLabel = label("fake store 中的现有规则（可载入编辑）");
        existingLabel.setPadding(0, dp(18), 0, dp(4));
        addView(existingLabel);
        existingRulesContainer = new LinearLayout(activity);
        existingRulesContainer.setOrientation(VERTICAL);
        addView(existingRulesContainer, matchWrap());

        refreshApps();
        refreshStructure();
        refreshExistingRules();
    }

    private void expandPresets() {
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
                            new ArrayList<>(selectedPackages),
                            draft.createdAt());
            if (draft.hasRuleId()) {
                expanded = expanded.withRuleId(draft.ruleId());
            }
            expanded = expanded.withStatus(draft.status());
            draft = expanded;
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
                                new ArrayList<>(selectedPackages))
                        .withShowAllInstalled(showAllApps)
                        .withSearchQuery(appQuery);
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
                    if (checked) {
                        selectedPackages.add(item.packageName());
                    } else {
                        selectedPackages.remove(item.packageName());
                    }
                    refreshApps();
                });
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
                        "写入选中应用",
                        view -> applySelectedApps(groupIndex, branchIndex)));
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

    private void applySelectedApps(int groupIndex, int branchIndex) {
        if (selectedPackages.isEmpty()) {
            setStatus("请先在应用选择器中选择至少一个 fake 应用。");
            return;
        }
        RuleAndGroup group = draft.groups().get(groupIndex);
        RuleBranch branch = group.branches().get(branchIndex);
        int appConditionIndex = -1;
        for (int index = 0; index < branch.conditions().size(); index++) {
            if (branch.conditions().get(index).field() == RuleCondition.Field.APP) {
                appConditionIndex = index;
                break;
            }
        }
        RuleCondition appCondition =
                RuleCondition.appIn(new ArrayList<>(selectedPackages));
        RuleBranch updated =
                appConditionIndex < 0
                        ? branch.addCondition(appCondition)
                        : branch.replaceCondition(appConditionIndex, appCondition);
        replaceBranch(groupIndex, branchIndex, updated, "应用集合条件已更新");
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
        try {
            draft = change.apply();
            refreshStructure();
            refreshApps();
            setStatus(successMessage + "。自动摘要已按筛选结构更新。");
        } catch (RuntimeException exception) {
            setStatus("未执行操作：至少需要保留一个规则分支和一个条件。");
        }
    }

    private void save() {
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
        ConfirmedTargetSelection confirmed =
                selected.value().orElseThrow().confirmAuthorization();
        ServiceResult<RuleCreationResult> result =
                writer.saveStructuredRule(confirmed, draft);
        if (!result.isSuccess()) {
            setStatus(result.userMessage());
            return;
        }
        NotificationRule saved = result.value().orElseThrow().rule();
        draft = StructuredRuleDsl.parse(saved);
        selectedPackages.clear();
        selectedPackages.addAll(draft.referencedPackages());
        refreshStructure();
        refreshApps();
        refreshExistingRules();
        setStatus(
                "已通过 SafeNotificationRuleWriter 写入独立 fake store："
                        + saved.ruleId()
                        + "。未连接真实 VoiceAssist，通知不会真实发送。");
    }

    private void newRule() {
        draft = StructuredRuleDraft.empty(System.currentTimeMillis());
        selectedPackages.clear();
        selectedPresets.clear();
        for (CheckBox check : presetChecks) {
            check.setChecked(false);
        }
        refreshStructure();
        refreshApps();
        setStatus("已新建空白结构化草稿；不会自动保存。");
    }

    private void loadRule(NotificationRule rule) {
        try {
            draft = StructuredRuleDsl.parse(rule);
        } catch (IllegalArgumentException exception) {
            setStatus("该规则 DSL 无法表示为当前基础编辑器，不会改写它。");
            return;
        }
        selectTarget(rule.targetDeviceId());
        selectedPackages.clear();
        selectedPackages.addAll(draft.referencedPackages());
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

    private void refreshExistingRules() {
        if (existingRulesContainer == null) {
            return;
        }
        existingRulesContainer.removeAllViews();
        for (NotificationRule rule : gateway.currentRules()) {
            existingRulesContainer.addView(createExistingRuleCard(rule));
        }
    }

    private View createExistingRuleCard(NotificationRule rule) {
        LinearLayout card = card();
        TextView heading = text(rule.ruleId(), 15, Color.rgb(30, 38, 36));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);
        card.addView(
                text(
                        "状态：" + rule.status() + " · 创建时间：" + rule.createdAt(),
                        13,
                        Color.rgb(111, 119, 114)));
        card.addView(text(rule.summary(), 14, Color.rgb(50, 58, 54)));
        Button edit = actionButton("载入并编辑");
        edit.setOnClickListener(view -> loadRule(rule));
        card.addView(edit);
        return card;
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
            default:
                return condition.field().name();
        }
    }

    private void setStatus(String message) {
        statusView.setText(message + "\n" + fakeBoundaryNotice());
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(4), 0, 0);
        return row;
    }

    private Button actionButton(String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private Button smallButton(String label, OnClickListener listener) {
        Button button = actionButton(label);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(listener);
        return button;
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
