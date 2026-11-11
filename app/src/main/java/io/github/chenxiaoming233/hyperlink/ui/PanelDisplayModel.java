package io.github.chenxiaoming233.hyperlink.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PanelDisplayModel {
    private final String title;
    private final String message;
    private final boolean retryEnabled;
    private final boolean compatibilityBadgeVisible;
    private final boolean staleCacheVisible;
    private final boolean emptyStateVisible;
    private final String targetSectionPlaceholder;
    private final String ruleSectionPlaceholder;
    private final String notificationAccessText;
    private final boolean notificationAccessBlocked;
    private final List<TargetItem> targets;
    private final List<RuleItem> rules;

    PanelDisplayModel(
            String title,
            String message,
            boolean retryEnabled,
            boolean compatibilityBadgeVisible,
            boolean staleCacheVisible,
            boolean emptyStateVisible,
            String targetSectionPlaceholder,
            String ruleSectionPlaceholder,
            String notificationAccessText,
            boolean notificationAccessBlocked,
            List<TargetItem> targets,
            List<RuleItem> rules) {
        this.title = Objects.requireNonNull(title, "title");
        this.message = Objects.requireNonNull(message, "message");
        this.retryEnabled = retryEnabled;
        this.compatibilityBadgeVisible = compatibilityBadgeVisible;
        this.staleCacheVisible = staleCacheVisible;
        this.emptyStateVisible = emptyStateVisible;
        this.targetSectionPlaceholder =
                Objects.requireNonNull(targetSectionPlaceholder, "targetSectionPlaceholder");
        this.ruleSectionPlaceholder =
                Objects.requireNonNull(ruleSectionPlaceholder, "ruleSectionPlaceholder");
        this.notificationAccessText =
                Objects.requireNonNull(notificationAccessText, "notificationAccessText");
        this.notificationAccessBlocked = notificationAccessBlocked;
        this.targets = immutableCopy(targets, "targets");
        this.rules = immutableCopy(rules, "rules");
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    public boolean retryEnabled() {
        return retryEnabled;
    }

    public boolean compatibilityBadgeVisible() {
        return compatibilityBadgeVisible;
    }

    public boolean staleCacheVisible() {
        return staleCacheVisible;
    }

    public boolean emptyStateVisible() {
        return emptyStateVisible;
    }

    public String targetSectionPlaceholder() {
        return targetSectionPlaceholder;
    }

    public String ruleSectionPlaceholder() {
        return ruleSectionPlaceholder;
    }

    public String notificationAccessText() {
        return notificationAccessText;
    }

    public boolean notificationAccessBlocked() {
        return notificationAccessBlocked;
    }

    public List<TargetItem> targets() {
        return targets;
    }

    public List<RuleItem> rules() {
        return rules;
    }

    public boolean readOnly() {
        return true;
    }

    public static final class TargetItem {
        private final String deviceId;
        private final String displayName;
        private final String typeLabel;
        private final String onlineLabel;
        private final String reachableLabel;
        private final String authorizationLabel;
        private final String receiveCapabilityLabel;
        private final String notice;

        TargetItem(
                String deviceId,
                String displayName,
                String typeLabel,
                String onlineLabel,
                String reachableLabel,
                String authorizationLabel,
                String receiveCapabilityLabel,
                String notice) {
            this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            this.typeLabel = Objects.requireNonNull(typeLabel, "typeLabel");
            this.onlineLabel = Objects.requireNonNull(onlineLabel, "onlineLabel");
            this.reachableLabel = Objects.requireNonNull(reachableLabel, "reachableLabel");
            this.authorizationLabel =
                    Objects.requireNonNull(authorizationLabel, "authorizationLabel");
            this.receiveCapabilityLabel =
                    Objects.requireNonNull(receiveCapabilityLabel, "receiveCapabilityLabel");
            this.notice = Objects.requireNonNull(notice, "notice");
        }

        public String deviceId() {
            return deviceId;
        }

        public String displayName() {
            return displayName;
        }

        public String typeLabel() {
            return typeLabel;
        }

        public String onlineLabel() {
            return onlineLabel;
        }

        public String reachableLabel() {
            return reachableLabel;
        }

        public String authorizationLabel() {
            return authorizationLabel;
        }

        public String receiveCapabilityLabel() {
            return receiveCapabilityLabel;
        }

        public String notice() {
            return notice;
        }
    }

    public static final class RuleItem {
        private final int position;
        private final String ruleId;
        private final String sourceDeviceId;
        private final String sourceDeviceName;
        private final String targetDeviceId;
        private final String targetDeviceName;
        private final String storedStatus;
        private final String statusLabel;
        private final String summary;
        private final String dslJson;
        private final String notice;

        RuleItem(
                int position,
                String ruleId,
                String sourceDeviceId,
                String sourceDeviceName,
                String targetDeviceId,
                String targetDeviceName,
                String storedStatus,
                String statusLabel,
                String summary,
                String dslJson,
                String notice) {
            this.position = position;
            this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
            this.sourceDeviceId = Objects.requireNonNull(sourceDeviceId, "sourceDeviceId");
            this.sourceDeviceName =
                    Objects.requireNonNull(sourceDeviceName, "sourceDeviceName");
            this.targetDeviceId = Objects.requireNonNull(targetDeviceId, "targetDeviceId");
            this.targetDeviceName =
                    Objects.requireNonNull(targetDeviceName, "targetDeviceName");
            this.storedStatus = Objects.requireNonNull(storedStatus, "storedStatus");
            this.statusLabel = Objects.requireNonNull(statusLabel, "statusLabel");
            this.summary = Objects.requireNonNull(summary, "summary");
            this.dslJson = Objects.requireNonNull(dslJson, "dslJson");
            this.notice = Objects.requireNonNull(notice, "notice");
        }

        public int position() {
            return position;
        }

        public String ruleId() {
            return ruleId;
        }

        public String sourceDeviceId() {
            return sourceDeviceId;
        }

        public String sourceDeviceName() {
            return sourceDeviceName;
        }

        public String targetDeviceId() {
            return targetDeviceId;
        }

        public String targetDeviceName() {
            return targetDeviceName;
        }

        public String storedStatus() {
            return storedStatus;
        }

        public String statusLabel() {
            return statusLabel;
        }

        public String summary() {
            return summary;
        }

        public String dslJson() {
            return dslJson;
        }

        public String notice() {
            return notice;
        }
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " must not contain null"));
        }
        return Collections.unmodifiableList(copy);
    }
}
