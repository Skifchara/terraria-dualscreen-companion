package com.terraduo.companion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.function.Consumer;

final class TlProPanel extends LinearLayout {
    private static final int BACKGROUND = 0xff121b25;
    private static final int SURFACE = 0xff243243;
    private static final int ACTIVE = 0xff347461;
    private static final int TEXT = 0xfff2f6f8;
    private static final int MUTED = 0xffb5c5d4;
    private static final String[] PANES = {"inventory", "equipment", "craft", "map"};
    private static final String[] TITLES = {"Инвентарь", "Снаряжение", "Крафт", "Карта"};
    private static final String[] GEAR = {"Шлем", "Нагрудник", "Поножи", "Аксессуар 1",
        "Аксессуар 2", "Аксессуар 3", "Аксессуар 4", "Аксессуар 5", "Аксессуар 6", "Аксессуар 7"};
    private static final String[] UTILITY = {"Питомец", "Свет", "Вагонетка", "Ездовое", "Крюк"};

    private final Consumer<JSONObject> send;
    private final Runnable refresh;
    private final TextView[] hotbar = new TextView[10];
    private final TextView[] tabs = new TextView[PANES.length];
    private final TextView hp;
    private final TextView mana;
    private final ProgressBar hpBar;
    private final ProgressBar manaBar;
    private final TextView status;
    private final TextView hideHud;
    private final LinearLayout body;
    private final TerrariaMapView mapView;

    private LinearLayout pageContent;
    private ScrollView scroll;
    private JSONObject latest;
    private String rendered = "";
    private String feedback = "";
    private long feedbackUntil;
    private Slot chosen;
    private int pane;
    private int revision;
    private int recipePage;
    private boolean hideRequested;

    TlProPanel(Context context, Consumer<JSONObject> send, Runnable refresh) {
        super(context);
        this.send = send;
        this.refresh = refresh;
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(8), dp(10), dp(6));
        setBackgroundColor(BACKGROUND);

        LinearLayout meters = row();
        hp = label("HP —", 14, TEXT);
        mana = label("Мана —", 14, TEXT);
        hpBar = meter(0xffe06972);
        manaBar = meter(0xff7baaff);
        addMeter(meters, hp, hpBar);
        addMeter(meters, mana, manaBar);
        hideHud = control("Скрыть HUD", 11);
        hideHud.setOnClickListener(view -> {
            hideRequested = !hideRequested;
            repaint();
            refresh.run();
        });
        LayoutParams hudLayout = new LayoutParams(dp(103), dp(36));
        hudLayout.setMargins(dp(6), 0, dp(2), 0);
        meters.addView(hideHud, hudLayout);
        meters.setMinimumHeight(dp(42));
        addView(meters);

        LinearLayout navigation = row();
        for (int index = 0; index < tabs.length; index++) {
            final int target = index;
            tabs[index] = control(TITLES[index], 12);
            tabs[index].setOnClickListener(view -> open(target));
            addWeighted(navigation, tabs[index], 34);
        }
        addView(navigation);

        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        addView(body, new LayoutParams(-1, 0, 1));

        status = label("", 12, MUTED);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        addView(status, new LayoutParams(-1, dp(23)));

        LinearLayout quick = row();
        for (int index = 0; index < hotbar.length; index++) {
            final int slot = index;
            TextView cell = control(Integer.toString(index + 1), 10);
            cell.setMaxLines(3);
            cell.setOnClickListener(view -> {
                Slot item = inventorySlot(slot);
                if (item == null) return;
                if (chosen == null) {
                    JSONObject action = action("select");
                    put(action, "slot", slot);
                    send.accept(action);
                } else {
                    tapSlot(item);
                }
            });
            cell.setOnLongClickListener(view -> {
                Slot item = inventorySlot(slot);
                if (item != null && item.type() != 0) {
                    chosen = item;
                    repaint();
                }
                return true;
            });
            hotbar[index] = cell;
            addWeighted(quick, cell, 46);
        }
        addView(quick);

        mapView = new TerrariaMapView(context, () -> {
            revision++;
            refresh.run();
        });
        open(0);
    }

    int revision() { return revision; }

    long pollInterval() { return pane == 3 ? 550 : pane == 2 ? 650 : 260; }

    JSONObject request(JSONObject action) {
        JSONObject result = action != null ? action : action(pane == 3 ? "map" : "state");
        put(result, "pane", PANES[pane]);
        put(result, "page", recipePage);
        put(result, "hide", hideRequested);
        put(result, "mapVisible", pane == 3 && mapView.hasMap());
        if (pane == 3 && action == null) {
            mapView.addViewport(result);
        }
        return result;
    }

    void disconnected() {
        if (latest != null || chosen != null) {
            latest = null;
            chosen = null;
            feedback = "";
            mapView.clear();
        }
        repaint();
    }

    void accept(JSONObject response, int responseRevision, boolean action) {
        if (response.has("error")) {
            feedback = response.optString("error");
            feedbackUntil = android.os.SystemClock.uptimeMillis() + 2600;
        } else if (action) {
            feedback = "";
        }
        if (response.has("inventory") && responseRevision == revision) {
            latest = response;
            if (pane == 3 && response.has("map")) {
                mapView.update(response.optJSONObject("map"), response.optJSONArray("position"));
            }
        }
        repaint();
    }

    private void open(int target) {
        pane = target;
        revision++;
        rendered = "";
        body.removeAllViews();
        if (pane == 3) {
            LinearLayout controls = row();
            for (String title : new String[]{"−", "+", "К игроку"}) {
                TextView button = control(title, 13);
                button.setOnClickListener(view -> {
                    if (title.equals("К игроку")) mapView.center();
                    else mapView.zoom(title.equals("+") ? -1 : 1);
                });
                addWeighted(controls, button, 31);
            }
            body.addView(controls);
            body.addView(mapView, new LayoutParams(-1, 0, 1));
        } else {
            scroll = new ScrollView(getContext());
            pageContent = new LinearLayout(getContext());
            pageContent.setOrientation(VERTICAL);
            scroll.addView(pageContent);
            body.addView(scroll, new LayoutParams(-1, -1));
        }
        repaint();
        refresh.run();
    }

    private void repaint() {
        for (int index = 0; index < tabs.length; index++) tint(tabs[index], index == pane ? ACTIVE : SURFACE);
        JSONArray items = latest == null ? null : latest.optJSONArray("inventory");
        int selected = latest == null ? -1 : latest.optInt("selected", -1);
        updateMeter(hp, hpBar, "HP", latest == null ? null : latest.optJSONArray("hp"));
        updateMeter(mana, manaBar, "Мана", latest == null ? null : latest.optJSONArray("mana"));
        boolean canHide = latest != null && latest.optBoolean("hudAvailable");
        hideHud.setEnabled(canHide);
        hideHud.setAlpha(canHide ? 1f : 0.5f);
        hideHud.setText(hideRequested ? "Вернуть HUD" : "Скрыть HUD");
        tint(hideHud, hideRequested ? ACTIVE : SURFACE);
        for (int index = 0; index < hotbar.length; index++) {
            Slot slot = items == null ? null : new Slot(0, index, items.optJSONArray(index));
            TextView cell = hotbar[index];
            cell.setText((index + 1) + "\n" + (slot == null ? "·" : slot.shortName()));
            cell.setContentDescription("Хотбар " + (index + 1) + ", " + (slot == null ? "пусто" : slot.name()));
            tint(cell, matches(slot, chosen) ? 0xff8b6337 : index == selected ? ACTIVE : SURFACE);
        }
        if (!feedback.isEmpty() && android.os.SystemClock.uptimeMillis() < feedbackUntil) {
            status.setText(feedback);
        } else if (latest == null) {
            status.setText("Открой одиночный мир через TL Pro");
        } else if (chosen != null) {
            status.setText(chosen.name() + "  ·  выбери слот назначения");
        } else {
            Slot active = selected >= 0 && selected < hotbar.length ? inventorySlot(selected) : null;
            String item = active == null || active.type() == 0 ? "" : active.name();
            String hint = pane <= 1 ? "Удерживай ячейку хотбара для переноса" : "";
            status.setText(item.isEmpty() || hint.isEmpty() ? item + hint : item + "  ·  " + hint);
        }
        if (pane == 3 || pageContent == null) return;
        Object data = latest == null ? null : latest.opt(pane == 2 ? "recipes" : PANES[pane]);
        String key = String.valueOf(data) + "/" + (chosen == null ? "" : chosen.group + ":" + chosen.index);
        if (rendered.equals(key)) return;
        rendered = key;
        int scrollY = scroll.getScrollY();
        pageContent.removeAllViews();
        if (data != null) {
            if (pane == 0) inventory((JSONArray) data);
            else if (pane == 1) equipment((JSONArray) data, latest.optJSONArray("unlocked"));
            else recipes((JSONObject) data);
        }
        ScrollView currentScroll = scroll;
        currentScroll.post(() -> currentScroll.scrollTo(0, scrollY));
    }

    private void inventory(JSONArray items) {
        for (int index = 10; index < 50; index += 10) {
            slotRow(items, 0, index, 10, null, null);
        }
        heading("Монеты и боеприпасы");
        slotRow(items, 0, 50, 8, null, null);
    }

    private void equipment(JSONArray groups, JSONArray unlocked) {
        heading("Броня и аксессуары");
        slotRow(groups.optJSONArray(0), 1, 0, 10, GEAR, unlocked);
        heading("Внешний вид");
        slotRow(groups.optJSONArray(0), 1, 10, 10, GEAR, unlocked);
        heading("Красители");
        slotRow(groups.optJSONArray(1), 2, 0, 10, GEAR, unlocked);
        heading("Питомцы и транспорт");
        slotRow(groups.optJSONArray(2), 3, 0, 5, UTILITY, null);
        heading("Красители питомцев и транспорта");
        slotRow(groups.optJSONArray(3), 4, 0, 5, UTILITY, null);
    }

    private void slotRow(JSONArray items, int group, int start, int count, String[] names, JSONArray unlocked) {
        if (items == null) return;
        LinearLayout row = row();
        for (int index = start; index < start + count; index++) {
            Slot slot = new Slot(group, index, items.optJSONArray(index));
            boolean locked = unlocked != null && !unlocked.optBoolean(index % 10, true);
            String empty = names == null ? "·" : names[index - start];
            TextView cell = control(locked ? "Закрыт" : slot.type() == 0 ? empty : slot.shortName(), 10);
            cell.setContentDescription(slot.type() == 0 ? empty : slot.name());
            cell.setEnabled(!locked);
            tint(cell, locked ? BACKGROUND : matches(slot, chosen) ? 0xff8b6337 : SURFACE);
            cell.setOnClickListener(view -> tapSlot(slot));
            addWeighted(row, cell, 39);
        }
        pageContent.addView(row);
    }

    private void recipes(JSONObject list) {
        JSONArray entries = list.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            heading("Нет доступных рецептов");
            return;
        }
        if (list.optInt("total") > 24) {
            LinearLayout pagination = row();
            int page = list.optInt("page");
            TextView previous = control("‹", 17);
            TextView next = control("›", 17);
            TextView number = label("Страница " + (page + 1), 12, TEXT);
            number.setGravity(Gravity.CENTER);
            previous.setEnabled(page > 0);
            next.setEnabled((page + 1) * 24 < list.optInt("total"));
            previous.setOnClickListener(view -> changePage(page - 1));
            next.setOnClickListener(view -> changePage(page + 1));
            addWeighted(pagination, previous, 30);
            addWeighted(pagination, number, 30);
            addWeighted(pagination, next, 30);
            pageContent.addView(pagination);
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONArray entry = entries.optJSONArray(index);
            if (entry == null) continue;
            Slot result = new Slot(0, 0, entry.optJSONArray(1));
            JSONArray ingredients = entry.optJSONArray(2);
            StringBuilder details = new StringBuilder();
            for (int part = 0; ingredients != null && part < ingredients.length(); part++) {
                JSONArray ingredient = ingredients.optJSONArray(part);
                if (ingredient == null) continue;
                if (details.length() > 0) details.append("  ·  ");
                details.append(ingredient.optString(0)).append(" × ").append(ingredient.optInt(1));
            }
            TextView recipe = control("Создать  " + result.name() + " × " + result.amount()
                + (details.length() == 0 ? "" : "\n" + details), 12);
            recipe.setGravity(Gravity.CENTER_VERTICAL);
            recipe.setPadding(dp(12), dp(7), dp(12), dp(7));
            recipe.setMaxLines(3);
            recipe.setOnClickListener(view -> {
                JSONObject command = action("craft");
                put(command, "id", entry.optInt(0));
                put(command, "expect", result.expected());
                chosen = null;
                send.accept(command);
            });
            LayoutParams margin = new LayoutParams(-1, -2);
            margin.setMargins(0, dp(4), 0, 0);
            pageContent.addView(recipe, margin);
        }
    }

    private void changePage(int page) {
        recipePage = page;
        revision++;
        refresh.run();
    }

    private void tapSlot(Slot slot) {
        if (latest == null) return;
        if (matches(slot, chosen)) {
            chosen = null;
        } else if (chosen == null) {
            if (slot.type() != 0) chosen = slot;
        } else {
            JSONObject command = action("transfer");
            put(command, "from", chosen.address());
            put(command, "expectFrom", chosen.expected());
            put(command, "to", slot.address());
            put(command, "expectTo", slot.expected());
            chosen = null;
            send.accept(command);
        }
        repaint();
    }

    private Slot inventorySlot(int index) {
        JSONArray items = latest == null ? null : latest.optJSONArray("inventory");
        return items == null ? null : new Slot(0, index, items.optJSONArray(index));
    }

    private static boolean matches(Slot a, Slot b) {
        return a != null && b != null && a.group == b.group && a.index == b.index;
    }

    private void heading(String title) {
        TextView text = label(title, 12, MUTED);
        text.setPadding(dp(3), dp(6), 0, dp(1));
        pageContent.addView(text);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView control(String text, int size) {
        TextView view = label(text, size, TEXT);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tint(view, SURFACE);
        return view;
    }

    private void tint(View view, int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(6));
        view.setBackground(background);
    }

    private void addWeighted(LinearLayout row, View cell, int height) {
        LayoutParams layout = new LayoutParams(0, dp(height), 1);
        layout.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(cell, layout);
    }

    private void addMeter(LinearLayout meters, TextView label, ProgressBar bar) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setPadding(dp(4), 0, dp(4), 0);
        column.addView(label);
        column.addView(bar, new LayoutParams(-1, dp(7)));
        meters.addView(column, new LayoutParams(0, -2, 1));
    }

    private ProgressBar meter(int color) {
        ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(SURFACE));
        return bar;
    }

    private void updateMeter(TextView label, ProgressBar bar, String title, JSONArray value) {
        label.setText(title + (value == null ? " —" : " " + value.optInt(0) + " / " + value.optInt(1)));
        bar.setMax(value == null ? 1 : Math.max(1, value.optInt(1)));
        bar.setProgress(value == null ? 0 : value.optInt(0));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static JSONObject action(String operation) {
        JSONObject result = new JSONObject();
        put(result, "op", operation);
        return result;
    }

    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException(key, error);
        }
    }

    private static final class Slot {
        final int group;
        final int index;
        final JSONArray data;

        Slot(int group, int index, JSONArray data) {
            this.group = group;
            this.index = index;
            this.data = data == null ? new JSONArray() : data;
        }

        int type() { return data.optInt(0); }
        int amount() { return data.optInt(1); }

        String name() {
            return type() == 0 ? "Пусто" : data.optString(4, "#" + type());
        }

        String shortName() {
            if (type() == 0) return "·";
            return (data.optBoolean(3) ? "★ " : "") + name()
                + (amount() > 1 ? " ×" + amount() : "");
        }

        JSONArray address() { return new JSONArray().put(group).put(index); }

        JSONArray expected() {
            return new JSONArray().put(type()).put(amount()).put(data.optInt(2)).put(data.optBoolean(3));
        }
    }
}
