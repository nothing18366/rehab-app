package com.robint.ui.component;



import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.LinearLayout;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/** Explicit nullable choices; index zero represents unanswered. */
public final class ChoiceView extends LinearLayout {
    private final List<MaterialButton> buttons = new ArrayList<>();
    private int selected;
    public ChoiceView(Context context, String label, String[] options, int columns) {
        super(context); setOrientation(VERTICAL); setLayoutParams(new LayoutParams(-1, -2));
        addView(UiComponents.text(context, label, 18, UiComponents.INK));
        LinearLayout row = null;
        for (int i = 0; i < options.length; i++) {
            if (i % columns == 0) { row = UiComponents.row(context); addView(row); }
            final int choice = i;
            MaterialButton button = UiComponents.compact(context, options[i], false, v -> setSelection(choice));
            button.setTextSize(14); button.setPadding(2, 0, 2, 0); button.setMinWidth(0);
            button.setContentDescription(label + "：" + options[i]);
            LayoutParams size = new LayoutParams(0, UiComponents.dp(context, 48), 1);
            size.setMargins(0, UiComponents.dp(context, 4), UiComponents.dp(context, 5), UiComponents.dp(context, 4));
            row.addView(button, size); buttons.add(button);
        }
        setPadding(0, UiComponents.dp(context, 12), 0, UiComponents.dp(context, 14)); setSelection(0);
    }
    public int getSelectedItemPosition() { return selected; }
    public void setSelection(int value) {
        selected = value;
        for (int i = 0; i < buttons.size(); i++) {
            MaterialButton button = buttons.get(i); boolean active = i == value && value != 0;
            button.setBackgroundTintList(ColorStateList.valueOf(active ? UiComponents.PRIMARY : Color.WHITE));
            button.setTextColor(active ? Color.WHITE : UiComponents.MUTED); button.setSelected(active);
        }
    }
}
