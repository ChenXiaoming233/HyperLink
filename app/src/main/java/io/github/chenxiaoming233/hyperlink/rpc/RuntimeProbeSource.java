package io.github.chenxiaoming233.hyperlink.rpc;

import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;

public interface RuntimeProbeSource {
    ServiceResult<RuntimeProbeSnapshot> read();
}
