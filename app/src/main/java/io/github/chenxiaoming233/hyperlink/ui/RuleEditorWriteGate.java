package io.github.chenxiaoming233.hyperlink.ui;

final class RuleEditorWriteGate {
    private boolean writesAvailable = true;

    boolean apply(RuleEditorLoadState loadState) {
        writesAvailable = loadState.writable();
        return writesAvailable;
    }

    boolean writesAvailable() {
        return writesAvailable;
    }
}
