package com.galiehneh.gridcraft;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Lightweight snackbar-style bar: a message plus text actions, auto-dismissed. */
final class SaveBar extends LinearLayout {
    private static final long SHOW_MS = 8000;

    record Action(String label, Runnable run) {}

    private final TextView message;
    private final LinearLayout actions;
    private final Runnable hide = () -> setVisibility(View.GONE);

    SaveBar(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setVisibility(View.GONE);
        setClickable(true);
        int pad = dp(16);
        setPadding(pad, dp(12), dp(8), dp(4));
        setElevation(dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(context.getColor(R.color.fab_background));
        setBackground(bg);

        message = new TextView(context);
        message.setTextColor(context.getColor(R.color.fab_text));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        addView(message);

        actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        addView(actions);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        params.bottomMargin = dp(88);
        setLayoutParams(params);
    }

    void show(String text, Action... items) {
        removeCallbacks(hide);
        message.setText(text);
        actions.removeAllViews();
        for (Action item : items) {
            TextView button = new TextView(getContext());
            button.setText(item.label());
            button.setTextColor(getContext().getColor(R.color.fab_text));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setTypeface(null, android.graphics.Typeface.BOLD);
            button.setPadding(dp(12), dp(10), dp(12), dp(10));
            button.setOnClickListener(v -> {
                hide.run();
                item.run().run();
            });
            actions.addView(button);
        }
        setVisibility(View.VISIBLE);
        postDelayed(hide, SHOW_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hide);
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
