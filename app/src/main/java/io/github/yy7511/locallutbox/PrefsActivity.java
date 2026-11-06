package io.github.yy7511.locallutbox;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.ui.Ui;

/** Preferences: appearance, editor defaults and export defaults. */
public class PrefsActivity extends BaseActivity {

    private interface Pick {
        void onPick(int index);
    }

    private EditText sizeValue;
    private EditText watermarkText;
    private LinearLayout watermarkPosRow;
    private SeekBar watermarkOpacityBar;
    private TextView watermarkOpacityValue;
    private int exportMode;
    private boolean syncing;

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
        watermarkText = (EditText) findViewById(R.id.watermarkText);
        watermarkPosRow = (LinearLayout) findViewById(R.id.watermarkPosRow);
        watermarkOpacityBar = (SeekBar) findViewById(R.id.watermarkOpacityBar);
        watermarkOpacityValue = (TextView) findViewById(R.id.watermarkOpacityValue);

        wmCheck.setChecked(Store.watermarkOn(this));
        watermarkText.setText(Store.watermarkText(this));
        watermarkText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Store.setWatermarkText(PrefsActivity.this, s == null ? "" : s.toString());
            }
        });

        fillRow(watermarkPosRow, new String[]{"右下", "左下", "右上", "左上", "居中"},
                Store.watermarkPos(this), new Pick() {
                    @Override
                    public void onPick(int index) {
                        Store.setWatermarkPos(PrefsActivity.this, index);
                    }
                });

        wmCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Store.setWatermarkOn(PrefsActivity.this, isChecked);
                syncWatermarkEnabled(isChecked);
            }
        });
        syncWatermarkEnabled(wmCheck.isChecked());

        watermarkOpacityBar.setProgress(Store.watermarkOpacity(this));
        watermarkOpacityValue.setText(Store.watermarkOpacity(this) + "%");
        watermarkOpacityBar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = Math.max(10, progress);
                watermarkOpacityValue.setText(pct + "%");
                Store.setWatermarkOpacity(PrefsActivity.this, pct);
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

    private void syncWatermarkEnabled(boolean on) {
        int vis = on ? View.VISIBLE : View.GONE;
        watermarkText.setVisibility(vis);
        watermarkPosRow.setVisibility(vis);
        watermarkOpacityBar.setVisibility(vis);
        watermarkOpacityValue.setVisibility(vis);
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
