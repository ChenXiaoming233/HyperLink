package io.github.chenxiaoming233.hyperlink.domain.write;

public interface LastKnownGoodStore {
    boolean save(RuleLkg snapshot);

    RuleLkg load();
}
