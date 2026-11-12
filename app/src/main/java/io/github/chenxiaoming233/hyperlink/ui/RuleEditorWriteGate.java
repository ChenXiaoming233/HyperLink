package io.github.chenxiaoming233.hyperlink.ui;

import io.github.chenxiaoming233.hyperlink.domain.write.RuleStoreGateway;

final class RuleEditorWriteGate {
    private boolean writesAvailable = true;

    RuleEditorLoadState refresh(RuleStoreGateway gateway) {
        RuleEditorLoadState loadState = RuleEditorLoadState.from(gateway);
        writesAvailable = loadState.writable();
        return loadState;
    }

    RuleEditorLoadState refreshAfterWriteFailure(
            RuleStoreGateway gateway, boolean writeAttempted) {
        RuleEditorLoadState loadState = refresh(gateway);
        if (!writeAttempted || !loadState.writable()) {
            return loadState;
        }
        writesAvailable = false;
        return RuleEditorLoadState.writeFailure();
    }

    boolean apply(RuleEditorLoadState loadState) {
        writesAvailable = loadState.writable();
        return writesAvailable;
    }

    boolean writesAvailable() {
        return writesAvailable;
    }
}
