package io.github.chenxiaoming233.hyperlink.domain.write;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CapabilityReport {
    private final List<CapabilityGap> gaps;
    private final boolean compatibilityUnverified;

    CapabilityReport(List<CapabilityGap> gaps, boolean compatibilityUnverified) {
        this.gaps = immutableCopy(gaps);
        this.compatibilityUnverified = compatibilityUnverified;
    }

    public boolean passed() {
        return gaps.isEmpty();
    }

    public boolean compatibilityUnverified() {
        return compatibilityUnverified;
    }

    public List<CapabilityGap> gaps() {
        return gaps;
    }

    public String readOnlyMessage() {
        if (passed()) {
            return "";
        }
        StringBuilder message = new StringBuilder("能力探测失败：缺少 ");
        for (int index = 0; index < gaps.size(); index++) {
            if (index > 0) {
                message.append("、");
            }
            CapabilityGap gap = gaps.get(index);
            message.append(gap.kind().name()).append('[').append(gap.descriptor()).append(']');
        }
        return message.append("，规则写入保持只读。").toString();
    }

    private static List<CapabilityGap> immutableCopy(List<CapabilityGap> values) {
        Objects.requireNonNull(values, "gaps");
        ArrayList<CapabilityGap> copy = new ArrayList<>(values.size());
        for (CapabilityGap value : values) {
            copy.add(Objects.requireNonNull(value, "gap"));
        }
        return Collections.unmodifiableList(copy);
    }
}
