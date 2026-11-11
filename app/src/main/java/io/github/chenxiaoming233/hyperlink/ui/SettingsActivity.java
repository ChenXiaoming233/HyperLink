package io.github.chenxiaoming233.hyperlink.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import io.github.chenxiaoming233.hyperlink.domain.ConnectionState;
import io.github.chenxiaoming233.hyperlink.domain.NotificationFlowSnapshot;
import io.github.chenxiaoming233.hyperlink.domain.ServiceResult;
import io.github.chenxiaoming233.hyperlink.fake.FakeNotificationFlowService;

public final class SettingsActivity extends Activity {
    private final FakeNotificationFlowService fakeService =
            new FakeNotificationFlowService(ConnectionState.NOT_ACTIVATED);
    private TextView stateTitle;
    private TextView stateMessage;
    private TextView compatibilityBadge;
    private TextView stateVersion;
    private Button retryButton;
    private Spinner stateSelector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
    }

    private View createContent() {
        int pagePadding = dp(24);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pagePadding, pagePadding, pagePadding, pagePadding);
        page.setBackgroundColor(Color.rgb(248, 246, 240));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("HYPERLINK / DEMO");
        eyebrow.setTextColor(Color.rgb(165, 72, 32));
        eyebrow.setTextSize(12);
        eyebrow.setGravity(Gravity.START);
        page.addView(eyebrow);

        TextView heading = new TextView(this);
        heading.setText("通知流转设置");
        heading.setTextColor(Color.rgb(30, 38, 36));
        heading.setTextSize(30);
        heading.setPadding(0, dp(6), 0, dp(8));
        page.addView(heading);

        TextView demoNotice = new TextView(this);
        demoNotice.setText(
                "演示模式：本页面只读取 fake 服务。未连接 VoiceAssist，"
                        + "不会接触真实规则、权限或 Provider。");
        demoNotice.setTextColor(Color.rgb(75, 82, 78));
        demoNotice.setTextSize(15);
        demoNotice.setPadding(0, 0, 0, dp(20));
        page.addView(demoNotice);

        stateSelector = new Spinner(this);
        ArrayAdapter<ConnectionState> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        ConnectionState.values());
        stateSelector.setAdapter(adapter);
        stateSelector.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        render(ConnectionState.values()[position]);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
        page.addView(stateSelector, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        compatibilityBadge = new TextView(this);
        compatibilityBadge.setText("兼容性未经完整验收");
        compatibilityBadge.setTextColor(Color.rgb(132, 54, 24));
        compatibilityBadge.setTextSize(13);
        compatibilityBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        compatibilityBadge.setBackgroundColor(Color.rgb(250, 218, 190));
        LinearLayout.LayoutParams badgeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.topMargin = dp(18);
        page.addView(compatibilityBadge, badgeParams);

        stateTitle = new TextView(this);
        stateTitle.setTextColor(Color.rgb(30, 38, 36));
        stateTitle.setTextSize(24);
        stateTitle.setPadding(0, dp(16), 0, dp(8));
        page.addView(stateTitle);

        stateMessage = new TextView(this);
        stateMessage.setTextColor(Color.rgb(75, 82, 78));
        stateMessage.setTextSize(16);
        stateMessage.setLineSpacing(0f, 1.2f);
        page.addView(stateMessage);

        stateVersion = new TextView(this);
        stateVersion.setTextColor(Color.rgb(111, 119, 114));
        stateVersion.setTextSize(13);
        stateVersion.setPadding(0, dp(14), 0, dp(20));
        page.addView(stateVersion);

        retryButton = new Button(this);
        retryButton.setText("重新读取演示状态");
        retryButton.setOnClickListener(view -> render(selectedState()));
        page.addView(retryButton);

        return page;
    }

    private void render(ConnectionState state) {
        fakeService.setConnectionState(state);
        ServiceResult<NotificationFlowSnapshot> result = fakeService.readSnapshot();
        if (!result.isSuccess()) {
            PanelDisplayModel failure = PanelStateMapper.mapFailure(result.userMessage());
            stateTitle.setText(failure.title());
            stateMessage.setText(failure.message());
            compatibilityBadge.setVisibility(View.GONE);
            stateVersion.setText("状态版本：未知");
            retryButton.setEnabled(failure.retryEnabled());
            return;
        }

        NotificationFlowSnapshot snapshot = result.value().get();
        PanelDisplayModel model =
                PanelStateMapper.map(snapshot.connectionState(), snapshot.compatibilityUnverified());

        stateTitle.setText(model.title());
        stateMessage.setText(model.message());
        compatibilityBadge.setVisibility(
                model.compatibilityBadgeVisible() ? View.VISIBLE : View.GONE);
        stateVersion.setText("状态版本：" + snapshot.stateVersion());
        retryButton.setEnabled(model.retryEnabled());
    }

    private ConnectionState selectedState() {
        return ConnectionState.values()[stateSelector.getSelectedItemPosition()];
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
