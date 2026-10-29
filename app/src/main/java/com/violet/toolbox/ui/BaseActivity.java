package com.violet.toolbox.ui;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.violet.toolbox.R;
import com.violet.toolbox.core.LogBus;

/**
 * 基类：统一状态面板、中文日志区与通用控件创建。
 */
public abstract class BaseActivity extends AppCompatActivity implements LogBus.Listener {

    protected TextView tvStatus;
    protected TextView tvLog;
    private ScrollView logScroll;
    private LinearLayout buttonContainer;
    private View statusBar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /** 绑定通用布局中的状态与日志控件。 */
    protected void bindCommonViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        logScroll = findViewById(R.id.log_scroll);
        statusBar = findViewById(R.id.view_status_bar);
        if (tvLog != null) {
            tvLog.setMovementMethod(new ScrollingMovementMethod());
        }
        View clear = findViewById(R.id.btn_clear_log);
        if (clear != null) {
            clear.setOnClickListener(v -> LogBus.get().clear());
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
        // 状态条随文案自动变色：成功绿、失败红、进行中橙
        if (statusBar != null) {
            int color = R.color.violet_primary;
            if (text != null) {
                if (text.contains("失败") || text.contains("错误") || text.contains("拒绝")
                        || text.contains("无法") || text.contains("异常")) {
                    color = R.color.state_error;
                } else if (text.contains("成功") || text.contains("已连接")
                        || text.contains("完成") || text.contains("正常")) {
                    color = R.color.state_ok;
                } else if (text.contains("正在") || text.contains("执行中")
                        || text.contains("等待") || text.contains("……")) {
                    color = R.color.state_warn;
                }
            }
            statusBar.setBackgroundColor(ContextCompat.getColor(this, color));
        }
    }

    /** 状态条配色：ok=绿、error=红、warn=橙、idle=灰。 */
    protected void setStatusLevel(String level) {
        if (statusBar == null) {
            return;
        }
        int color;
        switch (level) {
            case "ok":
                color = R.color.state_ok;
                break;
            case "error":
                color = R.color.state_error;
                break;
            case "warn":
                color = R.color.state_warn;
                break;
            default:
                color = R.color.violet_primary;
                break;
        }
        statusBar.setBackgroundColor(ContextCompat.getColor(this, color));
    }

    protected void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** 统一输入框外观：圆角深色底 + 主题文字色。 */
    protected void styleInput(android.widget.EditText et) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.Drawable bg =
                ContextCompat.getDrawable(this, R.drawable.bg_edittext);
        if (bg != null) {
            et.setBackground(bg);
        }
        et.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        et.setTextSize(14f);
    }

    /** 添加一个分区小标题。 */
    protected void addSection(String title) {
        if (buttonContainer == null) {
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        tv.setTextSize(11.5f);
        tv.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int) (4 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        buttonContainer.addView(tv);
    }

    /** 添加一行说明文字。 */
    protected void addNote(String text) {
        if (buttonContainer == null) {
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tv.setTextSize(12f);
        tv.setLineSpacing(4f, 1.15f);
        tv.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonContainer.addView(tv);
    }

    /** 动态创建一个主操作按钮（中文文案）。 */
    protected MaterialButton addButton(String text, View.OnClickListener listener) {
        return addButton(text, listener, true);
    }

    /** 动态创建一个次要操作按钮。 */
    protected MaterialButton addButtonSecondary(String text, View.OnClickListener listener) {
        return addButton(text, listener, false);
    }

    private MaterialButton addButton(String text, View.OnClickListener listener, boolean primary) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(14f);
        button.setMinHeight((int) (52 * getResources().getDisplayMetrics().density));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding((int) (14 * getResources().getDisplayMetrics().density), 0,
                (int) (14 * getResources().getDisplayMetrics().density), 0);
        button.setCornerRadius((int) (14 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        button.setLayoutParams(lp);
        if (primary) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.violet_primary_dark));
            button.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.violet_primary_dark)));
            button.setStrokeWidth((int) (1 * getResources().getDisplayMetrics().density));
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
            button.setTextColor(ContextCompat.getColor(this, R.color.violet_primary));
        }
        button.setOnClickListener(listener);
        if (buttonContainer != null) {
            buttonContainer.addView(button);
        }
        return button;
    }
}
