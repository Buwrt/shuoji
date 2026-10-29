package com.violet.toolbox.ui;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.violet.toolbox.R;
import com.violet.toolbox.core.LogBus;

/**
 * 基类：统一状态栏、中文日志区与通用按钮创建。
 */
public abstract class BaseActivity extends AppCompatActivity implements LogBus.Listener {

    protected TextView tvStatus;
    protected TextView tvLog;
    private ScrollView logScroll;
    private LinearLayout buttonContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /** 绑定通用布局中的状态与日志控件。 */
    protected void bindCommonViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        logScroll = findViewById(R.id.log_scroll);
        if (tvLog != null) {
            tvLog.setMovementMethod(new ScrollingMovementMethod());
        }
    }

    protected void setContainer(int id) {
        buttonContainer = findViewById(id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogBus.get().addListener(this);
        if (tvLog != null) {
            tvLog.setText(LogBus.get().getText());
            scrollLogToEnd();
        }
    }

    @Override
    protected void onPause() {
        LogBus.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onLog(String line) {
        if (tvLog != null) {
            tvLog.append(line);
            tvLog.append("\n");
            scrollLogToEnd();
        }
    }

    private void scrollLogToEnd() {
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    protected void log(String message) {
        LogBus.get().log(message);
    }

    protected void setStatus(String text) {
        if (tvStatus != null) {
            tvStatus.setText(text);
        }
    }

    protected void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** 动态创建一个功能按钮（中文文案）。 */
    protected MaterialButton addButton(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * getResources().getDisplayMetrics().density);
        button.setLayoutParams(lp);
        button.setOnClickListener(listener);
        if (buttonContainer != null) {
            buttonContainer.addView(button);
        }
        return button;
    }
}
