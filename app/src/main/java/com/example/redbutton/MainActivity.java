package com.example.redbutton;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.WHITE);

        Button button = new Button(this);
        button.setText("🔴");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);

        // Red circle background
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.RED);
        bg.setStroke(4, Color.parseColor("#AA0000"));
        button.setBackground(bg);
        button.setTextColor(Color.WHITE);

        int size = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 200, getResources().getDisplayMetrics());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER;
        button.setLayoutParams(params);

        button.setOnClickListener(v -> {
            Toast.makeText(this, "BOOM! 💥", Toast.LENGTH_SHORT).show();
        });

        layout.addView(button);
        setContentView(layout);
    }
}
