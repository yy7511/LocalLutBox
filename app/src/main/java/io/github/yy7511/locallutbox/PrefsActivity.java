package io.github.yy7511.locallutbox;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.core.Watermark;
import io.github.yy7511.locallutbox.ui.Ui;

import java.util.ArrayList;

/** Preferences: appearance, editor defaults and export defaults. */
public class PrefsActivity extends BaseActivity {

    private interface Pick {
        void onPick(int index);
    }

    private EditText sizeValue;
    private LinearLayout watermarkPresetList;
    private int exportMode;
    private boolean syncing;

    private static final String[] WM_POSITIONS = {"右下", "左下", "右上", "左上", "居中", "下中"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prefs);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        buildTheme();
        buildLutSize();
        buildGraph();
        buildExport();
        buildWatermarks();
    }

    private void buildTheme() {
        LinearLayout row = (LinearLayout) findViewById(R.id.themeRow);
        fillRow(row, new String[]{"深色", "浅色"}, darkMode ? 0 : 1, new Pick() {
            @Override
            public void onPick(int index) {
                Store.setDarkMode(PrefsActivity.this, index == 0);
                recreate();
            }
        });
    }

    private void buildLutSize() {
        LinearLayout row = (LinearLayout) findViewById(R.id.lutSizeRow);
        fillRow(row, new String[]{"小", "中", "大"}, Store.lutThumbSize(this), new Pick() {
            @Override
            public void onPick(int index) {
                Store.setLutThumbSize(PrefsActivity.this, index);
            }
        });
    }

    private void buildGraph() {
        LinearLayout row = (LinearLayout) findViewById(R.id.graphRow);
        fillRow(row, new String[]{"直方图", "矢量图", "两者", "关闭"}, Store.graphChoice(this), new Pick() {
            @Override
            public void onPick(int index) {
                Store.setGraphChoice(PrefsActivity.this, index);
            }
        });
    }

    private void buildExport() {
        exportMode = Store.exportSizeMode(this);
        sizeValue = (EditText) findViewById(R.id.exportSizeValue);

        LinearLayout modeRow = (LinearLayout) findViewById(R.id.exportModeRow);
        fillRow(modeRow, new String[]{"原图尺寸", "长边像素", "缩放比例"}, exportMode, new Pick() {
            @Override
            public void onPick(int index) {
                exportMode = index;
                Store.setExportSizeMode(PrefsActivity.this, index);
                syncSizeValueField();
            }
        });
        syncSizeValueField();
        sizeValue.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (syncing) {
                    return;
                }
                String raw = s.toString().trim();
                if (raw.length() == 0) {
                    return;
                }
                try {
                    int v = Integer.parseInt(raw);
                    if (exportMode == Store.SIZE_LONG_EDGE) {
                        Store.setExportLongEdge(PrefsActivity.this, v);
                    } else if (exportMode == Store.SIZE_RATIO) {
                        Store.setExportRatio(PrefsActivity.this, v);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        });

        final TextView qualityValue = (TextView) findViewById(R.id.qualityValue);
        SeekBar qualityBar = (SeekBar) findViewById(R.id.qualityBar);
        qualityBar.setProgress(Store.exportQuality(this));
        qualityValue.setText("质量 " + Store.exportQuality(this));
        qualityBar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int q = Math.max(1, progress);
                qualityValue.setText("质量 " + q);
                Store.setExportQuality(PrefsActivity.this, q);
            }
        });

        CheckBox wmCheck = (CheckBox) findViewById(R.id.watermarkCheck);
        wmCheck.setChecked(Store.watermarkOn(this));
        wmCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Store.setWatermarkOn(PrefsActivity.this, isChecked);
            }
        });
    }

    private void syncSizeValueField() {
        syncing = true;
        if (exportMode == Store.SIZE_ORIGINAL) {
            sizeValue.setVisibility(View.GONE);
        } else {
            sizeValue.setVisibility(View.VISIBLE);
            if (exportMode == Store.SIZE_LONG_EDGE) {
                sizeValue.setHint("长边像素，例如 2048");
                sizeValue.setText(String.valueOf(Store.exportLongEdge(this)));
            } else {
                sizeValue.setHint("比例 %（1-100）");
                sizeValue.setText(String.valueOf(Store.exportRatio(this)));
            }
        }
        syncing = false;
    }

    private void buildWatermarks() {
        watermarkPresetList = (LinearLayout) findViewById(R.id.watermarkPresetList);
        findViewById(R.id.btnAddWmPreset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.WmPreset p = new Store.WmPreset("wm_" + System.currentTimeMillis(), "新预设");
                p.items.add(new Store.WmItem("", Watermark.BOTTOM_RIGHT, 65));
                showPresetEditor(p, true);
            }
        });
        refreshWatermarkPresets();
    }

    private void refreshWatermarkPresets() {
        watermarkPresetList.removeAllViews();
        ArrayList<Store.WmPreset> list = Store.wmPresets(this);
        String selectedId = Store.wmSelectedId(this);
        for (int i = 0; i < list.size(); i++) {
            final Store.WmPreset p = list.get(i);
            final boolean isDefault = Store.WM_DEFAULT_ID.equals(p.id);
            final boolean isSelected = selectedId.equals(p.id);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_chip);
            card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(this, 8);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(this, p.name, 12, Ui.TEXT);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (isDefault) {
                TextView tag = Ui.text(this, "默认", 9, Ui.ACCENT);
                tag.setBackgroundResource(R.drawable.bg_chip);
                tag.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
                head.addView(tag);
            } else {
                TextView del = Ui.text(this, "删除", 10, Ui.MUTED);
                del.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), 0, Ui.dp(this, 2));
                del.setClickable(true);
                del.setFocusable(true);
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmDeletePreset(p);
                    }
                });
                head.addView(del);
            }
            card.addView(head);

            StringBuilder sb = new StringBuilder();
            sb.append(p.items.size()).append(" 个水印");
            for (int k = 0; k < p.items.size() && k < 3; k++) {
                String t = p.items.get(k).text.trim();
                if (t.length() > 0) {
                    sb.append(k == 0 ? " · " : " / ").append(t);
                }
            }
            TextView sub = Ui.text(this, sb.toString(), 10, Ui.MUTED);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, Ui.dp(this, 3), 0, 0);
            card.addView(sub);

            TextView action = Ui.text(this, isSelected ? "导出默认" : "设为导出默认", 10,
                    isSelected ? Ui.ACCENT : Ui.MUTED);
            action.setPadding(0, Ui.dp(this, 6), 0, 0);
            if (!isSelected) {
                action.setClickable(true);
                action.setFocusable(true);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Store.setWmSelectedId(PrefsActivity.this, p.id);
                        refreshWatermarkPresets();
                    }
                });
            }
            card.addView(action);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPresetEditor(p, false);
                }
            });
            watermarkPresetList.addView(card, clp);
        }
    }

    private void confirmDeletePreset(final Store.WmPreset preset) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除水印预设")
                .setMessage("删除「" + preset.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        ArrayList<Store.WmPreset> list = Store.wmPresets(PrefsActivity.this);
                        for (int i = list.size() - 1; i >= 0; i--) {
                            if (list.get(i).id.equals(preset.id)) {
                                list.remove(i);
                            }
                        }
                        Store.saveWmPresets(PrefsActivity.this, list);
                        if (Store.wmSelectedId(PrefsActivity.this).equals(preset.id)) {
                            Store.setWmSelectedId(PrefsActivity.this, Store.WM_DEFAULT_ID);
                        }
                        refreshWatermarkPresets();
                    }
                })
                .show();
    }

    /** Editor for one preset: name plus a list of watermark stamps. */
    private void showPresetEditor(final Store.WmPreset source, final boolean isNew) {
        final Store.WmPreset draft = source.copy();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6));

        EditText nameInput = new EditText(this);
        nameInput.setBackgroundResource(R.drawable.bg_chip);
        nameInput.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        nameInput.setTextColor(Ui.TEXT);
        nameInput.setTextSize(12);
        nameInput.setHint("预设名称");
        nameInput.setSingleLine(true);
        nameInput.setText(draft.name);
        box.addView(nameInput);

        TextView itemsLabel = Ui.text(this, "水印（一个预设可含多个）", 10, Ui.MUTED);
        itemsLabel.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        box.addView(itemsLabel);

        final LinearLayout itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        box.addView(itemList);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override
            public void run() {
                itemList.removeAllViews();
                for (int i = 0; i < draft.items.size(); i++) {
                    final Store.WmItem item = draft.items.get(i);
                    final int index = i;
                    LinearLayout row = new LinearLayout(PrefsActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    String pos = item.pos >= 0 && item.pos < WM_POSITIONS.length
                            ? WM_POSITIONS[item.pos] : "右下";
                    String label = "「" + (item.text.length() == 0 ? "未命名" : item.text)
                            + "」 · " + pos + " · " + item.opacity + "%";
                    TextView summary = Ui.text(PrefsActivity.this, label, 11, Ui.TEXT);
                    summary.setBackgroundResource(R.drawable.bg_chip);
                    summary.setPadding(Ui.dp(PrefsActivity.this, 10), Ui.dp(PrefsActivity.this, 7),
                            Ui.dp(PrefsActivity.this, 10), Ui.dp(PrefsActivity.this, 7));
                    summary.setMaxLines(1);
                    summary.setEllipsize(TextUtils.TruncateAt.END);
                    summary.setClickable(true);
                    summary.setFocusable(true);
                    summary.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showItemEditor(item, rebuild[0]);
                        }
                    });
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    slp.bottomMargin = Ui.dp(PrefsActivity.this, 6);
                    row.addView(summary, slp);

                    TextView del = Ui.text(PrefsActivity.this, "×", 14, Ui.MUTED);
                    del.setGravity(Gravity.CENTER);
                    del.setPadding(Ui.dp(PrefsActivity.this, 10), Ui.dp(PrefsActivity.this, 4),
                            Ui.dp(PrefsActivity.this, 2), Ui.dp(PrefsActivity.this, 4));
                    del.setClickable(true);
                    del.setFocusable(true);
                    del.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            draft.items.remove(index);
                            rebuild[0].run();
                        }
                    });
                    row.addView(del);
                    itemList.addView(row);
                }
            }
        };
        rebuild[0].run();

        TextView add = Ui.text(this, "＋ 添加水印", 11, Ui.ACCENT);
        add.setBackgroundResource(R.drawable.bg_chip);
        add.setGravity(Gravity.CENTER);
        add.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        add.setClickable(true);
        add.setFocusable(true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                draft.items.add(new Store.WmItem("", Watermark.BOTTOM_RIGHT, 65));
                rebuild[0].run();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 2);
        box.addView(add, alp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(isNew ? "新建水印预设" : "编辑水印预设")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String name = nameInput.getText().toString().trim();
                                if (name.length() == 0) {
                                    Ui.toast(PrefsActivity.this, "请输入预设名称");
                                    return;
                                }
                                boolean any = false;
                                for (int i = draft.items.size() - 1; i >= 0; i--) {
                                    String t = draft.items.get(i).text.trim();
                                    if (t.length() == 0) {
                                        draft.items.remove(i);
                                    } else {
                                        any = true;
                                    }
                                }
                                if (!any) {
                                    Ui.toast(PrefsActivity.this, "请至少添加一个水印文字");
                                    return;
                                }
                                draft.name = name;
                                ArrayList<Store.WmPreset> list = Store.wmPresets(PrefsActivity.this);
                                boolean found = false;
                                for (int i = 0; i < list.size(); i++) {
                                    if (list.get(i).id.equals(draft.id)) {
                                        list.set(i, draft);
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    list.add(draft);
                                }
                                Store.saveWmPresets(PrefsActivity.this, list);
                                refreshWatermarkPresets();
                                dialog.dismiss();
                            }
                        });
            }
        });
        dialog.show();
    }

    /** Editor for a single watermark stamp: text, position and opacity. */
    private void showItemEditor(final Store.WmItem item, final Runnable onDone) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6));

        final EditText textInput = new EditText(this);
        textInput.setBackgroundResource(R.drawable.bg_chip);
        textInput.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        textInput.setTextColor(Ui.TEXT);
        textInput.setTextSize(12);
        textInput.setHint("水印文字");
        textInput.setSingleLine(true);
        textInput.setText(item.text);
        box.addView(textInput);

        TextView posLabel = Ui.text(this, "位置", 10, Ui.MUTED);
        posLabel.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        box.addView(posLabel);
        final LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView posScroll = new HorizontalScrollView(this);
        posScroll.setHorizontalScrollBarEnabled(false);
        posScroll.addView(posRow);
        box.addView(posScroll);
        final int[] pos = {item.pos};
        fillRow(posRow, WM_POSITIONS, pos[0], new Pick() {
            @Override
            public void onPick(int index) {
                pos[0] = index;
            }
        });

        TextView opLabel = Ui.text(this, "不透明度", 10, Ui.MUTED);
        opLabel.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        box.addView(opLabel);
        final TextView opValue = Ui.text(this, item.opacity + "%", 11, Ui.TEXT);
        opValue.setGravity(Gravity.END);
        box.addView(opValue);
        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.max(10, Math.min(100, item.opacity)));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.STROKE));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        bar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = Math.max(10, progress);
                opValue.setText(pct + "%");
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 30)));

        new android.app.AlertDialog.Builder(this)
                .setTitle("编辑水印")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String t = textInput.getText().toString().trim();
                        if (t.length() == 0) {
                            Ui.toast(PrefsActivity.this, "请输入水印文字");
                            return;
                        }
                        item.text = t;
                        item.pos = pos[0];
                        item.opacity = Math.max(10, bar.getProgress());
                        onDone.run();
                    }
                })
                .show();
    }

    /** Builds a single-choice chip row. */
    private void fillRow(LinearLayout row, String[] labels, int selected, final Pick pick) {
        row.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = chip(row, labels[i]);
            setChipSelected(chip, i == selected);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.onPick(index);
                    selectInRow(row, index);
                }
            });
        }
    }

    private TextView chip(LinearLayout row, String label) {
        TextView t = Ui.text(this, label, 11, Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        row.addView(t, lp);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private void selectInRow(LinearLayout row, int index) {
        for (int i = 0; i < row.getChildCount(); i++) {
            setChipSelected((TextView) row.getChildAt(i), i == index);
        }
    }

    private void setChipSelected(TextView t, boolean selected) {
        t.setBackgroundResource(selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
        t.setTextColor(selected ? Ui.ACCENT : Ui.MUTED);
        t.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }
}
