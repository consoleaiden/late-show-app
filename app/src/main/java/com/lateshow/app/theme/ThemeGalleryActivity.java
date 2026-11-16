package com.lateshow.app.theme;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lateshow.app.MainActivity;
import com.lateshow.app.R;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.theme.ThemeEngine.ThemeScheme;
import com.lateshow.app.util.ThemeManager;

import java.util.List;

/**
 * 主题管理主页：预设主题选用 + 自定义方案入口 + 明暗双绑定。
 *
 * 行为约定：
 *  - 顶部「当前模式预览」实时反映亮/暗两套绑定（点按钮切换预览模式）；
 *  - 选中某方案 = 把它绑到当前预览的模式上，并立即开启主题引擎；
 *  - 「新建自定义」进入编辑器；长按已选方案可编辑 / 删除；
 *  - 关闭主题引擎回到内置「方寸」明暗配色（原系统）。
 */
public class ThemeGalleryActivity extends AppCompatActivity {

    /** 预览模式：false=亮色 true=暗色 */
    private boolean previewNight = false;

    private LinearLayout previewCard;
    private TextView tvPreviewTitle, tvLightBind, tvDarkBind;
    private RecyclerView grid;
    private SchemeAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        PrefStore.init(this);
        ThemeEngine.init(this);
        super.onCreate(savedInstanceState);

        View root = buildContent();
        setContentView(root);
        // 页面自身背景跟随「当前生效方案」（必须在校 setContentView 之后取 content）
        refreshAll();
    }

    // ==================== 内容构建 ====================

    private View buildContent() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * d);
        page.setPadding(pad, 0, pad, Math.round(28 * d));

        // 标题行
        TextView title = new TextView(this);
        title.setText("自定义主题");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(color(R.color.t1));
        page.addView(title);

        TextView sub = new TextView(this);
        sub.setText("为亮色与暗色分别绑定一套配色；预设一键套用，也可创建你自己的方案");
        sub.setTextSize(11);
        sub.setTextColor(color(R.color.t2));
        sub.setLineSpacing(0f, 1.5f);
        sub.setPadding(0, Math.round(4 * d), 0, Math.round(12 * d));
        page.addView(sub);

        // 预览卡（实时预览当前绑定）
        previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setPadding(Math.round(12 * d), Math.round(12 * d),
                Math.round(12 * d), Math.round(12 * d));
        previewCard.setBackground(cardBg());
        LinearLayout.LayoutParams pcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcp.bottomMargin = Math.round(10 * d);
        previewCard.setLayoutParams(pcp);
        page.addView(previewCard);

        tvPreviewTitle = new TextView(this);
        tvPreviewTitle.setTextSize(13);
        tvPreviewTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPreviewTitle.setPadding(0, 0, 0, Math.round(8 * d));
        previewCard.addView(tvPreviewTitle);

        // 预览样本：主色块 / 辅助色块 / 文字
        LinearLayout sampleRow = new LinearLayout(this);
        sampleRow.setOrientation(LinearLayout.HORIZONTAL);
        sampleRow.setGravity(Gravity.CENTER_VERTICAL);
        previewCard.addView(sampleRow);
        previewSwatches = new View[3];
        for (int i = 0; i < 3; i++) {
            View sw = new View(this);
            int sz = Math.round(40 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.rightMargin = Math.round(10 * d);
            sw.setLayoutParams(lp);
            sampleRow.addView(sw);
            previewSwatches[i] = sw;
        }

        // 模式切换（一行）：切换预览的亮/暗模式
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(0, Math.round(10 * d), 0, 0);
        previewCard.addView(modeRow);

        TextView btnMode = smallBtn("预览：亮色");
        btnMode.setOnClickListener(v -> { previewNight = !previewNight; refreshAll(); });
        modeRow.addView(btnMode);

        TextView modeHint = new TextView(this);
        modeHint.setText("点一下切换预览模式；选中方案后绑到亮色或暗色");
        modeHint.setTextSize(9.5f);
        modeHint.setTextColor(color(R.color.t3));
        modeHint.setLineSpacing(0f, 1.4f);
        LinearLayout.LayoutParams mhlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mhlp.leftMargin = Math.round(8 * d);
        modeRow.addView(modeHint, mhlp);

        // 绑定行（一行两个，避免横向溢出屏幕）
        LinearLayout bindRow = new LinearLayout(this);
        bindRow.setOrientation(LinearLayout.HORIZONTAL);
        bindRow.setPadding(0, Math.round(8 * d), 0, 0);
        previewCard.addView(bindRow);

        tvLightBind = smallBtn("亮色：未绑定");
        tvLightBind.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = Math.round(4 * d);
        tvLightBind.setLayoutParams(blp);
        tvLightBind.setOnClickListener(v -> bindCurrentTo(false));
        bindRow.addView(tvLightBind);

        tvDarkBind = smallBtn("暗色：未绑定");
        tvDarkBind.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp2.leftMargin = Math.round(4 * d);
        tvDarkBind.setLayoutParams(blp2);
        tvDarkBind.setOnClickListener(v -> bindCurrentTo(true));
        bindRow.addView(tvDarkBind);

        // 操作行
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, Math.round(10 * d), 0, 0);
        previewCard.addView(actionRow);

        TextView btnNew = smallBtn("＋ 新建自定义");
        btnNew.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.rightMargin = Math.round(4 * d);
        btnNew.setLayoutParams(nlp);
        btnNew.setOnClickListener(v -> openEditor(null));
        actionRow.addView(btnNew);

        TextView btnReset = smallBtn("恢复内置");
        btnReset.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = Math.round(4 * d);
        btnReset.setLayoutParams(rlp);
        btnReset.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("恢复内置主题")
                    .setMessage("将关闭自定义主题引擎，回到应用内置的「方寸」明暗配色。\n"
                            + "你创建的自定义方案会保留，随时可以再用。")
                    .setPositiveButton("恢复", (dlg, w) -> {
                        PrefStore.get().setThemeEngineOn(false);
                        finishToMain();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        actionRow.addView(btnReset);

        // 主题列表
        TextView listTitle = new TextView(this);
        listTitle.setText("全部方案");
        listTitle.setTextSize(14);
        listTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        listTitle.setTextColor(color(R.color.t1));
        listTitle.setPadding(0, Math.round(14 * d), 0, Math.round(6 * d));
        page.addView(listTitle);

        grid = new RecyclerView(this);
        GridLayoutManager glm = new GridLayoutManager(this, 2);
        grid.setLayoutManager(glm);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new SchemeAdapter();
        grid.setAdapter(adapter);
        page.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 外层滚动
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(page);
        return scroll;
    }

    private View[] previewSwatches;

    // ==================== 交互 ====================

    /** 把当前预览的方案绑到指定模式 */
    private void bindCurrentTo(boolean night) {
        ThemeScheme s = adapter != null ? adapter.selected : null;
        if (s == null) {
            Toast.makeText(this, "请先选择一套方案", Toast.LENGTH_SHORT).show();
            return;
        }
        if (night) ThemeEngine.get().setDarkId(s.id);
        else ThemeEngine.get().setLightId(s.id);
        PrefStore.get().setThemeEngineOn(true);
        Toast.makeText(this, "已绑定「" + s.name + "」到" + (night ? "暗色" : "亮色"),
                Toast.LENGTH_SHORT).show();
        refreshAll();
    }

    /**
     * 返回主页并让 MainActivity 立即应用新绑定。
     *
     * 关键点：不能只靠 onNewIntent —— MainActivity 已在栈底且启动模式是 singleTop，
     * 它会被复用（不会重建），onResume 里统一走 applyRuntimeTheme + Fragment 重绘回调。
     */
    private void openEditor(@Nullable ThemeScheme base) {
        Intent it = new Intent(this, ThemeEditorActivity.class);
        if (base != null) it.putExtra(ThemeEditorActivity.EXTRA_SCHEME_JSON,
                new com.google.gson.Gson().toJson(base));
        startActivity(it);
    }

    /** 删除自定义方案（内置方案不可删，长按时已拦截） */
    private void confirmDelete(ThemeScheme s) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除「" + s.name + "」")
                .setMessage("删除后无法恢复；若它正被亮色或暗色绑定，会自动回退到「方寸」。")
                .setPositiveButton("删除", (dlg, w) -> {
                    ThemeEngine.get().delete(s.id);
                    if (adapter != null) {
                        if (s.id.equals(adapter.selected == null ? null : adapter.selected.id)) {
                            adapter.selected = null;
                        }
                        adapter.notifyDataSetChanged();
                    }
                    refreshAll();
                    Toast.makeText(this, "已删除「" + s.name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void finishToMain() {
        // 回主页并立刻应用新主题
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        finish();
    }

    // ==================== 刷新 ====================

    private void refreshAll() {
        boolean on = PrefStore.get().themeEngineOn();
        ThemeEngine engine = ThemeEngine.get();

        // 预览卡
        String lightName = nameOf(engine.lightId());
        String darkName = nameOf(engine.darkId());
        tvPreviewTitle.setText(on
                ? ("预览模式：" + (previewNight ? "暗色" : "亮色") + " · 实时预览")
                : "自定义主题未启用（当前为内置「方寸」）");
        tvLightBind.setText("亮色：" + lightName);
        tvDarkBind.setText("暗色：" + darkName);

        ThemeEngine.Resolved r = engine.resolve(previewNight);
        int[] sw = {r.primary, r.secondary, r.surface};
        for (int i = 0; i < previewSwatches.length; i++) {
            setSwatchColor(previewSwatches[i], sw[i]);
        }
        previewCard.setBackground(ThemeEngine.get().backgroundDrawable(r));
        tvPreviewTitle.setTextColor(r.textPrimary);
        tvLightBind.setTextColor(previewNight ? color(R.color.t2) : r.onPrimary);
        tvLightBind.setBackgroundColor(previewNight ? withAlpha(r.surface, 0.7f) : r.primary);
        tvDarkBind.setTextColor(!previewNight ? color(R.color.t2) : r.onPrimary);
        tvDarkBind.setBackgroundColor(!previewNight ? withAlpha(r.surface, 0.7f) : r.primary);

        // 页面背景随预览模式联动（真正的「所见即所得」）
        if (on) {
            View content = findViewById(android.R.id.content);
            content.setBackground(ThemeEngine.get().backgroundDrawable(r));
        } else {
            findViewById(android.R.id.content).setBackgroundColor(color(R.color.ink900));
        }

        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private String nameOf(String id) {
        ThemeScheme s = ThemeEngine.get().findById(id);
        return s == null ? "方寸" : s.name;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    public void onBackPressed() {
        // 退出主题页时把当前绑定方案应用到主页（实时生效）
        applyToMain();
        super.onBackPressed();
    }

    /** 回主页并通知其应用新主题 */
    private void applyToMain() {
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        finish();
    }

    // ==================== 小工具 ====================

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    private static int withAlpha(int c, float a) {
        return ThemeEngine.withAlpha(c, a);
    }

    private android.graphics.drawable.Drawable cardBg() {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setColor(color(R.color.ink800));
        gd.setCornerRadius(getResources().getDisplayMetrics().density * 14f);
        return gd;
    }

    /**
     * 选中卡片底：复制 bg_card 的形状（12dp 圆角 + 1dp 描边），仅替换底色为方案主色。
     * 不用自绘圆角，避免和未选中卡片形状不一致。
     */
    private android.graphics.drawable.Drawable tintedCardBg(int color) {
        android.graphics.drawable.Drawable src =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_card);
        if (src == null) return cardBg();
        android.graphics.drawable.GradientDrawable gd =
                (android.graphics.drawable.GradientDrawable) src.mutate();
        gd.setColor(color);
        return gd;
    }

    /** 色块统一圆形（原先 setBackgroundColor 会画成方形）；白环保证选中时主色圆块不被卡片底色吞掉 */
    private void setSwatchColor(View v, int color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        // 选中卡片底色 = 方案主色，主色圆块与背景同色会糊在一起；加一圈白色描边即可区分
        gd.setStroke(Math.max(2, Math.round(getResources().getDisplayMetrics().density * 2f)),
                android.graphics.Color.WHITE);
        v.setBackground(gd);
    }

    /** 绑定角标圆点：方案主色 */
    private android.graphics.drawable.Drawable dotBg(int color) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        int sz = Math.round(9 * getResources().getDisplayMetrics().density);
        gd.setSize(sz, sz);
        return gd;
    }

    private TextView smallBtn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setGravity(Gravity.CENTER);
        int ph = Math.round(12 * getResources().getDisplayMetrics().density);
        tv.setPadding(ph, Math.round(8 * getResources().getDisplayMetrics().density), ph,
                Math.round(8 * getResources().getDisplayMetrics().density));
        tv.setBackgroundResource(R.drawable.bg_smbtn);
        tv.setTextColor(color(R.color.t1));
        return tv;
    }

    // ==================== 列表适配器 ====================

    private class SchemeAdapter extends RecyclerView.Adapter<VH> {

        ThemeScheme selected;

        SchemeAdapter() {
            String cur = ThemeEngine.get()
                    .current(ThemeManager.actualNight(ThemeGalleryActivity.this)).id;
            selected = findSelected(cur);
        }

        private ThemeScheme findSelected(String id) {
            List<ThemeScheme> list = ThemeEngine.get().schemes();
            for (ThemeScheme s : list) if (s.id.equals(id)) return s;
            return list.isEmpty() ? null : list.get(0);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 注意：buildItem 内部已创建 VH 并填好 name/bindTag 字段，
            // 不能再 new VH(item) 包一层（那会丢字段导致 onBind 空指针）。
            return buildItem(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final ThemeScheme s = ThemeEngine.get().schemes().get(pos);
            boolean boundLight = s.id.equals(ThemeEngine.get().lightId());
            boolean boundDark = s.id.equals(ThemeEngine.get().darkId());
            boolean sel = selected != null && s.id.equals(selected.id);

            setSwatchColor(h.swPrimary, s.primary);
            setSwatchColor(h.swSecondary,
                    s.secondaryAuto ? ThemeEngine.autoSecondary(s.primary) : s.secondary);
            setSwatchColor(h.swBg, s.bgColor);
            h.name.setText(s.name);
            h.bindTag.setVisibility((boundLight || boundDark) ? View.VISIBLE : View.GONE);
            h.bindTag.setText(boundLight && boundDark ? "亮+暗"
                    : boundLight ? "亮" : "暗");
            h.itemView.setActivated(sel);
            // 选中态：沿用 bg_card 的形状（12dp 圆角 + 1dp 描边），只把底色换成方案主色；
            // 未选中回到资源背景。形状不变，避免变成胶囊。
            if (sel) {
                h.itemView.setBackground(tintedCardBg(s.primary));
                // 卡片底换成方案主色后，标题文字也要随之对比，否则深主色上深字读不出来
                h.name.setTextColor(com.lateshow.app.theme.ThemeEngine.inkOn(s.primary));
            } else {
                h.itemView.setBackgroundResource(R.drawable.bg_card);
                h.name.setTextColor(color(R.color.t1));
            }
            h.bindTag.setBackground(dotBg(s.primary));
            // 标签底 = 方案主色，文字按主色亮度自动对比（深主色给浅字，浅主色给深字）
            h.bindTag.setTextColor(com.lateshow.app.theme.ThemeEngine.inkOn(s.primary));
            h.itemView.setOnClickListener(v -> {
                selected = s;
                notifyDataSetChanged();
            });
            h.itemView.setOnLongClickListener(v -> {
                if (ThemeEngine.ID_FANG_CUN.equals(s.id)) {
                    Toast.makeText(ThemeGalleryActivity.this, "内置方案不可修改",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                new MaterialAlertDialogBuilder(ThemeGalleryActivity.this)
                        .setTitle(s.name)
                        .setItems(new CharSequence[]{"编辑", "删除"}, (d, which) -> {
                            if (which == 0) openEditor(s);
                            else confirmDelete(s);
                        })
                        .show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return ThemeEngine.get().schemes().size();
        }

        private VH buildItem(ViewGroup parent) {
            float d = getResources().getDisplayMetrics().density;
            LinearLayout item = new LinearLayout(parent.getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(10 * d);
            item.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Math.round(5 * d);
            lp.bottomMargin = Math.round(5 * d);
            item.setLayoutParams(lp);

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            item.addView(row);

            VH vh = new VH(item);
            int sz = Math.round(30 * d);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
            slp.rightMargin = Math.round(6 * d);
            vh.swPrimary.setLayoutParams(slp);
            vh.swSecondary.setLayoutParams(new LinearLayout.LayoutParams(
                    sz, sz));
            ((LinearLayout.LayoutParams) vh.swSecondary.getLayoutParams()).rightMargin = Math.round(6 * d);
            vh.swBg.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));

            row.addView(vh.swPrimary);
            row.addView(vh.swSecondary);
            row.addView(vh.swBg);

            View box = new View(parent.getContext());
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, 1);
            blp.weight = 1;
            box.setLayoutParams(blp);
            row.addView(box);

            vh.bindTag = new TextView(parent.getContext());
            vh.bindTag.setTextSize(9);
            vh.bindTag.setPadding(Math.round(5 * d), Math.round(2 * d),
                    Math.round(5 * d), Math.round(2 * d));
            vh.bindTag.setTextColor(color(R.color.accInk));
            vh.bindTag.setVisibility(View.GONE);
            row.addView(vh.bindTag);

            vh.name = new TextView(parent.getContext());
            vh.name.setTextSize(12);
            vh.name.setTextColor(color(R.color.t1));
            vh.name.setPadding(0, Math.round(8 * d), 0, 0);
            item.addView(vh.name);
            return vh;
        }
    }

    private static class VH extends RecyclerView.ViewHolder {
        View swPrimary, swSecondary, swBg;
        TextView name, bindTag;
        VH(@NonNull View v) {
            super(v);
            swPrimary = new View(v.getContext());
            swSecondary = new View(v.getContext());
            swBg = new View(v.getContext());
        }
    }
}
