package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.List;

public interface RuleStoreGateway {
    ServiceResult<RuleStoreSnapshot> read();

    ServiceResult<RuleWriteReceipt> write(List<NotificationRule> rules);
}
