package io.github.chenxiaoming233.hyperlink.domain.lifecycle;

import io.github.chenxiaoming233.hyperlink.domain.NotificationAccessState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.TargetDevice;
import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RuleLifecycleModel {
    private final NotificationAccessState notificationAccess;
    private final String blockedError;
    private final List<RuleItem> rules;
    private final RuleUndoToken undoToken;
    private final NotificationAccessSettingsIntentSpec settingsIntent;

    private RuleLifecycleModel(
            NotificationAccessState notificationAccess,
            String blockedError,
            List<RuleItem> rules,
            RuleUndoToken undoToken,
            NotificationAccessSettingsIntentSpec settingsIntent) {
        this.notificationAccess =
                Objects.requireNonNull(notificationAccess, "notificationAccess");
        this.blockedError = Objects.requireNonNull(blockedError, "blockedError");
        this.rules = immutableRules(rules);
        this.undoToken = undoToken;
        this.settingsIntent = settingsIntent;
    }

    static RuleLifecycleModel from(
            RuleStoreSnapshot snapshot,
            RuleUndoToken undoToken,
            String transientBlockedError) {
        Objects.requireNonNull(snapshot, "snapshot");
        String blockedError =
                snapshot.readOnlyFaultMessage().isEmpty()
                        ? Objects.requireNonNull(transientBlockedError, "transientBlockedError")
                        : snapshot.readOnlyFaultMessage();
        NotificationAccessState access = snapshot.flowSnapshot().notificationAccess();
        List<RuleItem> items = new ArrayList<>();
        for (NotificationRule rule : snapshot.rules()) {
            TargetDevice target = findTarget(snapshot, rule.targetDeviceId());
            items.add(RuleItem.from(rule, target, access, !blockedError.isEmpty()));
        }
        NotificationAccessSettingsIntentSpec settingsIntent =
                access == NotificationAccessState.GRANTED
                        ? null
                        : NotificationAccessSettingsIntentSpec.systemSettings();
        return new RuleLifecycleModel(access, blockedError, items, undoToken, settingsIntent);
    }

    public NotificationAccessState notificationAccess() {
        return notificationAccess;
    }

    public String blockedError() {
        return blockedError;
    }

    public List<RuleItem> rules() {
        return rules;
    }

    public Optional<RuleUndoToken> undoToken() {
        return Optional.ofNullable(undoToken);
    }

    public Optional<NotificationAccessSettingsIntentSpec> notificationAccessSettingsIntent() {
        return Optional.ofNullable(settingsIntent);
    }

    public Optional<RuleItem> rule(String ruleId) {
        for (RuleItem rule : rules) {
            if (rule.ruleId().equals(ruleId)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public static final class RuleItem {
        private final String ruleId;
        private final String storedStatus;
        private final String displayStatus;
        private final String targetDeviceId;
        private final String targetDeviceName;
        private final boolean targetAuthorized;
        private final boolean blocked;
        private final String blockedMessage;
        private final boolean canPause;
        private final boolean canResume;
        private final boolean canDelete;

        private RuleItem(
                String ruleId,
                String storedStatus,
                String displayStatus,
                String targetDeviceId,
                String targetDeviceName,
                boolean targetAuthorized,
                boolean blocked,
                String blockedMessage,
                boolean canPause,
                boolean canResume,
                boolean canDelete) {
            this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
            this.storedStatus = Objects.requireNonNull(storedStatus, "storedStatus");
            this.displayStatus = Objects.requireNonNull(displayStatus, "displayStatus");
            this.targetDeviceId = Objects.requireNonNull(targetDeviceId, "targetDeviceId");
            this.targetDeviceName = Objects.requireNonNull(targetDeviceName, "targetDeviceName");
            this.targetAuthorized = targetAuthorized;
            this.blocked = blocked;
            this.blockedMessage = Objects.requireNonNull(blockedMessage, "blockedMessage");
            this.canPause = canPause;
            this.canResume = canResume;
            this.canDelete = canDelete;
        }

        private static RuleItem from(
                NotificationRule rule,
                TargetDevice target,
                NotificationAccessState access,
                boolean globalBlocked) {
            boolean active = "active".equals(rule.status());
            boolean paused = "paused".equals(rule.status());
            boolean targetAuthorized = target != null && target.targetAuthorized();
            String blockedMessage = "";
            if (active && access != NotificationAccessState.GRANTED) {
                blockedMessage =
                        "系统通知使用权"
                                + (access == NotificationAccessState.MISSING ? "缺失" : "已撤销")
                                + "，此规则受阻。";
            } else if (active && target == null) {
                blockedMessage = "目标不在当前规则快照中，无法确认目标授权。";
            } else if (active && !targetAuthorized) {
                blockedMessage = "目标授权已在外部变化，此规则受阻；不会自动重新授权。";
            }
            boolean blocked = !blockedMessage.isEmpty();
            String displayStatus;
            if (paused) {
                displayStatus = "已暂停";
            } else if (blocked) {
                displayStatus = "受阻";
            } else if (active) {
                displayStatus = "已启用";
            } else {
                displayStatus = rule.status();
            }
            boolean actionsAvailable = !globalBlocked;
            return new RuleItem(
                    rule.ruleId(),
                    rule.status(),
                    displayStatus,
                    rule.targetDeviceId(),
                    rule.targetDeviceName(),
                    targetAuthorized,
                    blocked,
                    blockedMessage,
                    actionsAvailable && active && !blocked,
                    actionsAvailable && paused && target != null && targetAuthorized,
                    actionsAvailable && target != null);
        }

        public String ruleId() {
            return ruleId;
        }

        public String storedStatus() {
            return storedStatus;
        }

        public String displayStatus() {
            return displayStatus;
        }

        public String targetDeviceId() {
            return targetDeviceId;
        }

        public String targetDeviceName() {
            return targetDeviceName;
        }

        public boolean targetAuthorized() {
            return targetAuthorized;
        }

        public boolean blocked() {
            return blocked;
        }

        public String blockedMessage() {
            return blockedMessage;
        }

        public boolean canPause() {
            return canPause;
        }

        public boolean canResume() {
            return canResume;
        }

        public boolean canDelete() {
            return canDelete;
        }
    }

    private static TargetDevice findTarget(RuleStoreSnapshot snapshot, String targetId) {
        for (TargetDevice target : snapshot.flowSnapshot().targets()) {
            if (target.deviceId().equals(targetId)) {
                return target;
            }
        }
        return null;
    }

    private static List<RuleItem> immutableRules(List<RuleItem> values) {
        Objects.requireNonNull(values, "rules");
        ArrayList<RuleItem> copy = new ArrayList<>(values.size());
        for (RuleItem value : values) {
            copy.add(Objects.requireNonNull(value, "rule"));
        }
        return Collections.unmodifiableList(copy);
    }
}
