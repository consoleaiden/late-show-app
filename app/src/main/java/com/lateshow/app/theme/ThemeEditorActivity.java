package com.lateshow.app.theme;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.lateshow.app.MainActivity;
import com.lateshow.app.R;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.theme.ThemeEngine.ThemeScheme;
import com.lateshow.app.util.ThemeManager;

import java.util.List;

/**
 * 主题编辑器：自定义配色方案的全参数编辑 + 实时预览。
 *
 * 可配置项（与需求一一对应）：
 *  - 名称；
 *  - 主色（点击打开取色板，含推荐色板 + 自动生成辅助色）；
 *  - 辅助色（默认跟随主色自动计算，可单独修改）；
 *  - 背景：纯色 / 渐变（两个色 + 角度）；
 *  - 表面色 / 一级文字 / 二级文字；
 *  - 导航栏 / 搜索栏 / 分类栏不透明度；
 *  - 玻璃模糊强度 / 白雾强度。
 */
public class ThemeEditorActivity extends AppCompatActivity {

    public static final String EXTRA_SCHEME_JSON = "scheme_json";

    private ThemeScheme scheme;
    private boolean isNew;

    // 预览控件
    private LinearLayout previewRoot;
    private View pvPrimary, pvSecondary, pvSurface, pvBg;
    private TextView pvName, pvText1, pvText2;
    // 表单控件（编辑过程中实时联动）
    private EditText etName;
    private TextView swPrimary, swSecondary, swBgFrom, swBgTo, swSurface, swText1, swText2;
    private TextView tvAutoSec, tvAngleVal;
    private LinearLayout paletteBox;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        PrefStore.init(this);
        ThemeEngine.init(this);
        super.onCreate(savedInstanceState);

        String json = getIntent().getStringExtra(EXTRA_SCHEME_JSON);
        if (json != null) {
            try {
                scheme = new Gson().fromJson(json, ThemeScheme.class);
            } catch (Exception ignored) { }
        }
        if (scheme == null) {
            // 新方案：以当前生效方案为底稿
            scheme = ThemeEngine.get()
                    .current(ThemeManager.actualNight(this)).copy();
            scheme.id = "custom_" + System.currentTimeMillis();
            scheme.name = "我的主题";
            isNew = true;
        } else {
            isNew = false;
        }

        View root = buildContent();
        setContentView(root);
        bindValues();
    }

    // ==================== 构建 ====================

    private View buildContent() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * d);
        page.setPadding(pad, Math.round(18 * d), pad, Math.round(28 * d));

        // 标题
        TextView title = new TextView(this);
        title.setText(isNew ? "新建自定义主题" : "编辑主题");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(color(R.color.t1));
        page.addView(title);

        // 预览卡
        previewRoot = new LinearLayout(this);
        previewRoot.setOrientation(LinearLayout.VERTICAL);
        previewRoot.setPadding(Math.round(12 * d), Math.round(12 * d),
                Math.round(12 * d), Math.round(12 * d));
        previewRoot.setBackgroundResource(R.drawable.bg_card_lg);
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prp.bottomMargin = Math.round(12 * d);
        previewRoot.setLayoutParams(prp);
        page.addView(previewRoot);

        pvName = new TextView(this);
        pvName.setTextSize(14);
        pvName.setTypeface(null, android.graphics.Typeface.BOLD);
        previewRoot.addView(pvName);

        LinearLayout sampleRow = new LinearLayout(this);
        sampleRow.setOrientation(LinearLayout.HORIZONTAL);
        sampleRow.setGravity(Gravity.CENTER_VERTICAL);
        sampleRow.setPadding(0, Math.round(10 * d), 0, Math.round(8 * d));
        previewRoot.addView(sampleRow);
        int sz = Math.round(38 * d);
        pvPrimary = swatch(); pvSecondary = swatch(); pvSurface = swatch();
        sampleRow.addView(pvPrimary);
        sampleRow.addView(pvSecondary);
        sampleRow.addView(pvSurface);

        pvText1 = new TextView(this);
        pvText1.setText("一级文字 · 片名标题");
        pvText1.setTextSize(13);
        previewRoot.addView(pvText1);
        pvText2 = new TextView(this);
        pvText2.setText("二级文字 · 简介与元信息");
        pvText2.setTextSize(11);
        previewRoot.addView(pvText2);
        previewRoot.setBackgroundDrawable(backgroundDrawable());

        // 名称输入
        page.addView(label("主题名称"));
        etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setHint("给这套配色起个名字");
        etName.setTextSize(13);
        etName.setTextColor(color(R.color.t1));
        etName.setHintTextColor(color(R.color.t3));
        styleInput(etName);
        page.addView(etName);

        // 颜色组
        page.addView(section("颜色"));
        page.addView(colorRow("主色", swPrimary = colorSwatch()));
        page.addView(colorRow("辅助色", swSecondary = colorSwatch()));
        tvAutoSec = new TextView(this);
        tvAutoSec.setText("辅助色跟随主色自动计算（可点色块单独指定）");
        tvAutoSec.setTextSize(9.5f);
        tvAutoSec.setTextColor(color(R.color.t3));
        tvAutoSec.setPadding(0, Math.round(2 * d), 0, Math.round(6 * d));
        page.addView(tvAutoSec);

        page.addView(section("背景"));
        page.addView(segRow());
        page.addView(colorRow("背景色（纯色）/ 起始色（渐变）", swBgFrom = colorSwatch()));
        page.addView(colorRow("结束色（仅渐变）", swBgTo = colorSwatch()));
        page.addView(angleRow());

        page.addView(section("文字与表面"));
        page.addView(colorRow("卡片表面色", swSurface = colorSwatch()));
        page.addView(colorRow("一级文字", swText1 = colorSwatch()));
        page.addView(colorRow("二级文字", swText2 = colorSwatch()));

        page.addView(section("玻璃参数"));
        page.addView(sliderRow("导航栏不透明度", SLIDER_NAV));
        page.addView(sliderRow("搜索栏不透明度", SLIDER_SEARCH));
        page.addView(sliderRow("分类栏不透明度", SLIDER_CATEGORY));
        page.addView(sliderRow("玻璃模糊强度", SLIDER_BLUR));
        page.addView(sliderRow("玻璃白雾强度", SLIDER_FROST));

        // 保存按钮
        TextView btnSave = new TextView(this);
        btnSave.setText("保存并应用");
        btnSave.setGravity(Gravity.CENTER);
        btnSave.setTextSize(13);
        btnSave.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSave.setTextColor(Color.WHITE);
        int ph = Math.round(14 * d);
        btnSave.setPadding(ph, Math.round(12 * d), ph, Math.round(12 * d));
        btnSave.setBackground(com.lateshow.app.theme.ThemeEngine.accentButtonBg(this));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Math.round(16 * d);
        btnSave.setLayoutParams(slp);
        btnSave.setOnClickListener(v -> save());
        page.addView(btnSave);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(page);
        return scroll;
    }

    /** 实时预览背景（纯色 / 渐变） */
    private android.graphics.drawable.Drawable backgroundDrawable() {
        if (scheme.bgMode == 1) {
            return new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{scheme.bgFrom, scheme.bgTo});
        }
        return new android.graphics.drawable.ColorDrawable(scheme.bgColor);
    }

    // ==================== 控件工厂 ====================

    private static final int SLIDER_NAV = 1, SLIDER_SEARCH = 2, SLIDER_CATEGORY = 3,
            SLIDER_BLUR = 4, SLIDER_FROST = 5;

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(10.5f);
        tv.setTextColor(color(R.color.t3));
        tv.setPadding(0, Math.round(6 * d()), 0, Math.round(4 * d()));
        return tv;
    }

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(color(R.color.t1));
        tv.setPadding(0, Math.round(12 * d()), 0, Math.round(4 * d()));
        return tv;
    }

    private LinearLayout colorRow(String labelText, TextView swatch) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d());
        row.setLayoutParams(lp);

        TextView lb = new TextView(this);
        lb.setText(labelText);
        lb.setTextSize(11.5f);
        lb.setTextColor(color(R.color.t2));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(lb, llp);

        swatch.setId(View.generateViewId());
        row.addView(swatch);
        return row;
    }

    private TextView colorSwatch() {
        TextView sw = new TextView(this);
        int sz = Math.round(32 * d());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        sw.setLayoutParams(lp);
        sw.setText(" ");
        sw.setBackgroundColor(Color.GRAY);
        sw.setOnClickListener(v -> openPicker((TextView) v));
        return sw;
    }

    private View swatch() {
        View v = new View(this);
        int sz = Math.round(38 * d());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.rightMargin = Math.round(8 * d());
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout segRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d());
        row.setLayoutParams(lp);

        segSolid = smallBtn("纯色");
        segGradient = smallBtn("渐变");
        row.addView(segSolid);
        View gap = new View(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Math.round(8 * d()), 1);
        gap.setLayoutParams(glp);
        row.addView(gap);
        row.addView(segGradient);
        return row;
    }

    private TextView segSolid, segGradient;

    private LinearLayout angleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d());
        row.setLayoutParams(lp);

        TextView lb = new TextView(this);
        lb.setText("渐变角度");
        lb.setTextSize(11.5f);
        lb.setTextColor(color(R.color.t2));
        row.addView(lb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvAngleVal = new TextView(this);
        tvAngleVal.setTextSize(11.5f);
        tvAngleVal.setTextColor(color(R.color.t1));
        tvAngleVal.setMinWidth(Math.round(40 * d()));
        row.addView(tvAngleVal);

        TextView minus = smallBtn("－");
        TextView plus = smallBtn("＋");
        minus.setOnClickListener(v -> { scheme.bgAngle = ((scheme.bgAngle - 45) % 360 + 360) % 360; bindAngle(); refreshPreview(); });
        plus.setOnClickListener(v -> { scheme.bgAngle = (scheme.bgAngle + 45) % 360; bindAngle(); refreshPreview(); });
        row.addView(minus);
        row.addView(plus);
        return row;
    }

    private LinearLayout sliderRow(String name, final int type) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(6 * d());
        row.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = new TextView(this);
        lb.setText(name);
        lb.setTextSize(11.5f);
        lb.setTextColor(color(R.color.t2));
        head.addView(lb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setTextSize(11.5f);
        val.setTextColor(color(R.color.t1));
        head.addView(val);
        row.addView(head);

        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(100);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                val.setText(p + "%");
                if (!user) return;
                switch (type) {
                    case SLIDER_NAV: scheme.navAlpha = p; break;
                    case SLIDER_SEARCH: scheme.searchAlpha = p; break;
                    case SLIDER_CATEGORY: scheme.categoryAlpha = p; break;
                    case SLIDER_BLUR: scheme.barBlur = p; break;
                    case SLIDER_FROST: scheme.barFrost = p; break;
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        sb.setTag(type);
        row.addView(sb);
        sliders.put(type, sb);
        return row;
    }

    private final java.util.Map<Integer, android.widget.SeekBar> sliders = new java.util.HashMap<>();

    private void styleInput(EditText et) {
        int ph = Math.round(12 * d());
        et.setPadding(ph, Math.round(10 * d()), ph, Math.round(10 * d()));
        et.setBackgroundResource(R.drawable.bg_field);
    }

    private TextView smallBtn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setGravity(Gravity.CENTER);
        int ph = Math.round(12 * d());
        tv.setPadding(ph, Math.round(8 * d()), ph, Math.round(8 * d()));
        tv.setBackgroundResource(R.drawable.bg_smbtn);
        tv.setTextColor(color(R.color.t1));
        return tv;
    }

    // ==================== 取色器 ====================

    private void openPicker(final TextView target) {
        // 简易取色板：预设推荐色板（含色相条式基础色），点选即写回对应字段
        List<Integer> palette = ThemeEngine.palette();
        float dd = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(10 * dd), Math.round(10 * dd),
                Math.round(10 * dd), Math.round(10 * dd));
        paletteBox = new LinearLayout(this);
        paletteBox.setOrientation(LinearLayout.VERTICAL);

        // 每行 8 个
        LinearLayout row = null;
        for (int i = 0; i < palette.size(); i++) {
            if (i % 8 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                paletteBox.addView(row);
            }
            final int c = palette.get(i);
            TextView cell = new TextView(this);
            int csz = Math.round(30 * dd);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(csz, csz);
            clp.rightMargin = Math.round(4 * dd);
            clp.bottomMargin = Math.round(4 * dd);
            cell.setLayoutParams(clp);
            cell.setBackgroundColor(c);
            cell.setOnClickListener(v -> {
                applyColorTo(target, c);
                currentDialog.dismiss();
            });
            row.addView(cell);
        }
        box.addView(paletteBox);

        // 自定义 HEX 输入
        final EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setHint("或输入 HEX，如 #FF5A36");
        hex.setTextSize(12);
        hex.setPadding(Math.round(10 * dd), Math.round(8 * dd),
                Math.round(10 * dd), Math.round(8 * dd));
        styleInput(hex);
        box.addView(hex);
        hex.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                Integer parsed = parseHex(hex.getText().toString());
                if (parsed != null) {
                    applyColorTo(target, parsed);
                    currentDialog.dismiss();
                }
                return true;
            }
            return false;
        });

        currentDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("选择颜色")
                .setView(box)
                .setNegativeButton("取消", null)
                .show();
    }

    private android.app.Dialog currentDialog;

    private void applyColorTo(TextView target, int color) {
        target.setBackgroundColor(color);
        if (target == swPrimary) {
            scheme.primary = color;
            if (scheme.secondaryAuto) {
                scheme.secondary = ThemeEngine.autoSecondary(color);
                swSecondary.setBackgroundColor(scheme.secondary);
            }
        } else if (target == swSecondary) {
            scheme.secondary = color;
            scheme.secondaryAuto = false;
            tvAutoSec.setText("辅助色已单独指定（点「重置」可恢复跟随主色）");
        } else if (target == swBgFrom) {
            if (scheme.bgMode == 0) scheme.bgColor = color;
            else scheme.bgFrom = color;
        } else if (target == swBgTo) {
            scheme.bgTo = color;
        } else if (target == swSurface) {
            scheme.surface = color;
        } else if (target == swText1) {
            scheme.textPrimary = color;
        } else if (target == swText2) {
            scheme.textSecondary = color;
        }
        refreshPreview();
    }

    private void resetSecondaryAuto() {
        scheme.secondaryAuto = true;
        scheme.secondary = ThemeEngine.autoSecondary(scheme.primary);
        swSecondary.setBackgroundColor(scheme.secondary);
        tvAutoSec.setText("辅助色跟随主色自动计算（可点色块单独指定）");
        refreshPreview();
    }

    @Nullable
    private static Integer parseHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6 || s.length() == 8) {
            try {
                long v = Long.parseLong(s, 16);
                return s.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ==================== 绑定与刷新 ====================

    private void bindValues() {
        etName.setText(scheme.name);
        etName.setSelection(scheme.name == null ? 0 : scheme.name.length());
        etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                scheme.name = s.toString().trim().isEmpty() ? "我的主题" : s.toString().trim();
                pvName.setText(scheme.name);
            }
        });

        swPrimary.setBackgroundColor(scheme.primary);
        swSecondary.setBackgroundColor(
                scheme.secondaryAuto ? ThemeEngine.autoSecondary(scheme.primary) : scheme.secondary);
        swBgFrom.setBackgroundColor(scheme.bgMode == 0 ? scheme.bgColor : scheme.bgFrom);
        swBgTo.setBackgroundColor(scheme.bgTo);
        swSurface.setBackgroundColor(scheme.surface);
        swText1.setBackgroundColor(scheme.textPrimary);
        swText2.setBackgroundColor(scheme.textSecondary);
        tvAngleVal.setText(scheme.bgAngle + "°");

        bindSeg();
        bindSliders();
        refreshPreview();
    }

    private void bindSeg() {
        segSolid.setOnClickListener(v -> { scheme.bgMode = 0; bindSeg(); refreshPreview(); });
        segGradient.setOnClickListener(v -> { scheme.bgMode = 1; bindSeg(); refreshPreview(); });
        segSolid.setBackground(scheme.bgMode == 0
                ? com.lateshow.app.theme.ThemeEngine.accentPillBg(this)
                : ContextCompat.getDrawable(this, R.drawable.bg_seg_off));
        segGradient.setBackground(scheme.bgMode == 1
                ? com.lateshow.app.theme.ThemeEngine.accentPillBg(this)
                : ContextCompat.getDrawable(this, R.drawable.bg_seg_off));
        segSolid.setTextColor(scheme.bgMode == 0
                ? com.lateshow.app.theme.ThemeEngine.accentInkColor(this, color(R.color.accInk))
                : color(R.color.t2));
        segGradient.setTextColor(scheme.bgMode == 1
                ? com.lateshow.app.theme.ThemeEngine.accentInkColor(this, color(R.color.accInk))
                : color(R.color.t2));
    }

    private void bindAngle() {
        tvAngleVal.setText(scheme.bgAngle + "°");
    }

    private void bindSliders() {
        setSlider(SLIDER_NAV, scheme.navAlpha);
        setSlider(SLIDER_SEARCH, scheme.searchAlpha);
        setSlider(SLIDER_CATEGORY, scheme.categoryAlpha);
        setSlider(SLIDER_BLUR, scheme.barBlur);
        setSlider(SLIDER_FROST, scheme.barFrost);
    }

    private void setSlider(int type, int value) {
        android.widget.SeekBar sb = sliders.get(type);
        if (sb != null) sb.setProgress(value);
    }

    private void refreshPreview() {
        int bg = scheme.bgMode == 0 ? scheme.bgColor : scheme.bgFrom;
        previewRoot.setBackgroundDrawable(backgroundDrawable());
        pvPrimary.setBackgroundColor(scheme.primary);
        pvSecondary.setBackgroundColor(
                scheme.secondaryAuto ? ThemeEngine.autoSecondary(scheme.primary) : scheme.secondary);
        pvSurface.setBackgroundColor(scheme.surface);
        pvText1.setTextColor(scheme.textPrimary);
        pvText2.setTextColor(scheme.textSecondary);
        pvName.setTextColor(scheme.textPrimary);
        pvName.setText(scheme.name);
        // 页面自身底色同步预览（实时所见即所得）
        findViewById(android.R.id.content).setBackgroundDrawable(backgroundDrawable());
    }

    // ==================== 保存 ====================

    private void save() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请填写主题名称", Toast.LENGTH_SHORT).show();
            return;
        }
        scheme.name = name;
        ThemeEngine engine = ThemeEngine.get();
        engine.save(scheme);
        // 保存后立即绑到当前模式，保证「保存并应用」的预期
        boolean night = ThemeManager.actualNight(this);
        if (night) engine.setDarkId(scheme.id); else engine.setLightId(scheme.id);
        PrefStore.get().setThemeEngineOn(true);
        Toast.makeText(this, "已保存并应用「" + scheme.name + "」", Toast.LENGTH_SHORT).show();

        // 回主页应用新主题
        android.content.Intent it = new android.content.Intent(this, MainActivity.class);
        it.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        finish();
    }

    // ==================== 工具 ====================

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }
}
