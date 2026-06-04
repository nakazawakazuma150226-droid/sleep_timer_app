package com.nakazawakazuma.sleeptimer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

public class AlarmActivity extends Activity {
    private static final String PREFS = "sleep_timer_settings";
    private static final String KEY_ALARM_CHALLENGE_ENABLED = "alarmChallengeEnabled";

    private int challengeAnswer;
    private TextView challengeFeedback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
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
        boolean challengeEnabled = isChallengeEnabled();
        message.setText(challengeEnabled
            ? "計算に答えるとアラームを止められます"
            : "睡眠タイマーのアラームが鳴っています");
        message.setTextColor(Color.rgb(230, 237, 243));
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 16, 0, 32);

        root.addView(icon);
        root.addView(title);
        root.addView(message);
        if (challengeEnabled) {
            addChallenge(root);
        } else {
            root.addView(stopButton());
        }
        setContentView(root);
    }

    private boolean isChallengeEnabled() {
        return getApplicationContext()
            .getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ALARM_CHALLENGE_ENABLED, false);
    }

    private Button stopButton() {
        Button stop = new Button(this);
        stop.setText("止める");
        stop.setTextSize(18);
        stop.setOnClickListener(v -> dismiss());
        return stop;
    }

    private void addChallenge(LinearLayout root) {
        Random random = new Random();
        int first = 4 + random.nextInt(16);
        int second = 4 + random.nextInt(16);
        challengeAnswer = first + second;

        TextView question = new TextView(this);
        question.setText(first + " + " + second + " = ?");
        question.setTextColor(Color.rgb(230, 237, 243));
        question.setTextSize(30);
        question.setGravity(Gravity.CENTER);
        question.setPadding(0, 0, 0, 16);
        root.addView(question);

        LinearLayout answers = new LinearLayout(this);
        answers.setOrientation(LinearLayout.HORIZONTAL);
        answers.setGravity(Gravity.CENTER);
        answers.setPadding(0, 0, 0, 12);

        ArrayList<Integer> choices = new ArrayList<>();
        choices.add(challengeAnswer);
        choices.add(challengeAnswer + 1 + random.nextInt(4));
        choices.add(Math.max(1, challengeAnswer - 1 - random.nextInt(4)));
        Collections.shuffle(choices);

        for (int value : choices) {
            Button button = new Button(this);
            button.setText(String.valueOf(value));
            button.setTextSize(18);
            button.setOnClickListener(v -> {
                if (value == challengeAnswer) {
                    dismiss();
                } else if (challengeFeedback != null) {
                    challengeFeedback.setText("もう一度");
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(6, 0, 6, 0);
            answers.addView(button, params);
        }
        root.addView(answers);

        challengeFeedback = new TextView(this);
        challengeFeedback.setText("");
        challengeFeedback.setTextColor(Color.rgb(248, 81, 73));
        challengeFeedback.setTextSize(14);
        challengeFeedback.setGravity(Gravity.CENTER);
        challengeFeedback.setPadding(0, 0, 0, 16);
        root.addView(challengeFeedback);
    }

    private void dismiss() {
        startService(new Intent(this, AlarmRingService.class).setAction(AlarmScheduler.ACTION_DISMISS));
        finish();
    }
}
