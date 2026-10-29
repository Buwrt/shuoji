package com.violet.toolbox.ui;

import android.os.Bundle;
import android.widget.TextView;

import com.violet.toolbox.R;

/**
 * 使用说明与免责声明（中文）。
 */
public class HelpActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);
        TextView tv = findViewById(R.id.tv_help);
        if (tv != null) {
            tv.setText(getString(R.string.help_steps)
                    + "\n\n" + getString(R.string.help_disclaimer)
                    + "\n\n" + getString(R.string.help_refs));
        }
    }
}
