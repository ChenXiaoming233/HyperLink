package io.github.chenxiaoming233.hyperlink.fake;

import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowService;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import java.util.Objects;

public final class FakeNotificationFlowService implements NotificationFlowService {
    private ConnectionState connectionState;
    private long stateVersion = 1L;
    private int readCount;

    public FakeNotificationFlowService(ConnectionState connectionState) {
        this.connectionState = Objects.requireNonNull(connectionState, "connectionState");
    }

    @Override
    public synchronized ServiceResult<NotificationFlowSnapshot> readSnapshot() {
        readCount++;
        NotificationFlowSnapshot snapshot =
                new NotificationFlowSnapshot(
                        connectionState,
                        stateVersion,
                        connectionState == ConnectionState.COMPATIBILITY_UNVERIFIED);
        return ServiceResult.success(snapshot);
    }

    public synchronized void setConnectionState(ConnectionState connectionState) {
        ConnectionState nextState = Objects.requireNonNull(connectionState, "connectionState");
        if (nextState != this.connectionState) {
            stateVersion++;
            this.connectionState = nextState;
        }
    }

    public synchronized int readCount() {
        return readCount;
    }
}
