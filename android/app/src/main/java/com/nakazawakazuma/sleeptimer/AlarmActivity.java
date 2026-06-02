package com.nakazawakazuma.sleeptimer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AlarmActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.rgb(13, 17, 23));

        TextView icon = new TextView(this);
        icon.setText("⏰");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("起きる時間です");
        title.setTextColor(Color.rgb(63, 185, 80));
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(this);
        message.setText("睡眠タイマーのアラームが鳴っています");
        message.setTextColor(Color.rgb(230, 237, 243));
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 16, 0, 32);

        Button stop = new Button(this);
        stop.setText("止める");
        stop.setTextSize(18);
        stop.setOnClickListener(v -> dismiss());

        root.addView(icon);
        root.addView(title);
        root.addView(message);
        root.addView(stop);
        setContentView(root);
    }

    private void dismiss() {
        startService(new Intent(this, AlarmRingService.class).setAction(AlarmScheduler.ACTION_DISMISS));
        finish();
    }
}
