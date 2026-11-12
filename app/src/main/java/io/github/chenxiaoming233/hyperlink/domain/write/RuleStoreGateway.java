package io.github.chenxiaoming233.hyperlink.domain.write;

import io.github.chenxiaoming233.hyperlink.domain.NotificationRule;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.List;

public interface RuleStoreGateway {
    ServiceResult<RuleStoreSnapshot> read();

    ServiceResult<RuleWriteReceipt> write(
            List<NotificationRule> rules,
            long expectedStoreVersion,
            List<NotificationRule> expectedRules);

    ServiceResult<RuleWriteReceipt> restoreRules(
            RuleLkg lkg, long expectedStoreVersion, List<NotificationRule> expectedRules);

    ServiceResult<RuleWriteReceipt> restoreRulesForVerifiedRecovery(
            RuleLkg lkg, long expectedStoreVersion, List<NotificationRule> expectedRules);

    String readOnlyFaultMessage();

    void enterReadOnlyFault(String message);
}
