package io.github.yy7511.locallutbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.Adjust;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Preview;
import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.ui.LutDetail;
import io.github.yy7511.locallutbox.ui.Ui;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LutLibraryActivity extends BaseActivity {

    private static final int REQ_LUT = 301;
    private static final int REQ_PREVIEW = 302;

    private final ArrayList<Store.LutEntry> entries = new ArrayList<Store.LutEntry>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private GridView grid;
    private LutAdapter adapter;
    private LinearLayout emptyState;
    private ImageView previewThumb;
    private TextView previewName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lut_library);

        grid = (GridView) findViewById(R.id.lutGrid);
        emptyState = (LinearLayout) findViewById(R.id.emptyState);
        previewThumb = (ImageView) findViewById(R.id.previewThumb);
        previewName = (TextView) findViewById(R.id.previewName);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnImportLut).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLutFiles();
            }
        });
        findViewById(R.id.btnChangePreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPreview();
            }
        });
        findViewById(R.id.btnResetPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Preview.reset(LutLibraryActivity.this);
                refresh();
                Ui.toast(LutLibraryActivity.this, "已恢复内置预览图");
            }
        });

        adapter = new LutAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= entries.size()) {
                    return;
                }
                LutDetail.show(LutLibraryActivity.this, entries.get(position),
                        Preview.get(LutLibraryActivity.this), new Adjust(), null,
                        "预览图：" + Preview.label(LutLibraryActivity.this));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showItemMenu(position);
                return true;
            }
        });
    }

    private void showItemMenu(final int position) {
        final Store.LutEntry e = entries.get(position);
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(new String[]{
                        "目标色彩空间：" + ColorSpace.name(e.space),
                        "删除 LUT"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickSpace(e);
                        } else {
                            confirmDelete(position);
                        }
                    }
                })
                .show();
    }

    private void pickSpace(final Store.LutEntry e) {
        final String[] names = new String[5];
        for (int i = 0; i < names.length; i++) {
            names[i] = ColorSpace.name(i);
        }
        new AlertDialog.Builder(this)
                .setTitle("LUT 目标色彩空间")
                .setSingleChoiceItems(names, ColorSpace.clampSpace(e.space),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                LutManager.setSpace(LutLibraryActivity.this, e.id, which);
                                refresh();
                                Ui.toast(LutLibraryActivity.this,
                                        "目标色彩空间已设为 " + ColorSpace.name(which));
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        entries.clear();
        entries.addAll(Store.luts(this));
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        previewName.setText(Preview.label(this));
        loadPreviewThumb();
    }

    private void loadPreviewThumb() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    b = Preview.square(LutLibraryActivity.this, 160);
                } catch (Throwable ignored) {
                }
                final Bitmap fb = b;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fb != null) {
                            previewThumb.setImageBitmap(fb);
                        }
                    }
                });
            }
        });
    }

    private void pickLutFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_LUT);
        } catch (Exception e) {
            Ui.toast(this, "无法打开文件选择器：" + e.getMessage());
        }
    }

    private void pickPreview() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQ_PREVIEW);
        } catch (Exception e) {
            Ui.toast(this, "无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_LUT) {
            ArrayList<Uri> uris = collectUris(data);
            if (!uris.isEmpty()) {
                importLuts(uris);
            }
        } else if (requestCode == REQ_PREVIEW) {
            Uri uri = data.getData();
            if (uri != null) {
                setPreview(uri);
            }
        }
    }

    private ArrayList<Uri> collectUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void importLuts(final ArrayList<Uri> uris) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在解析 LUT…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Exception ignored) {
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                StringBuilder errors = new StringBuilder();
                for (int i = 0; i < uris.size(); i++) {
                    try {
                        LutManager.importUri(LutLibraryActivity.this, uris.get(i));
                        ok++;
                    } catch (Throwable e) {
                        String n = ImageUtil.getDisplayName(getContentResolver(), uris.get(i));
                        if (errors.length() > 0) {
                            errors.append('\n');
                        }
                        errors.append(n).append("：").append(e.getMessage());
                    }
                }
                final int fok = ok;
                final String ferr = errors.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        refresh();
                        if (fok > 0) {
                            Ui.toast(LutLibraryActivity.this, "已导入 " + fok + " 个 LUT");
                        }
                        if (ferr.length() > 0) {
                            new AlertDialog.Builder(LutLibraryActivity.this)
                                    .setTitle("部分文件无法导入")
                                    .setMessage(ferr)
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    }
                });
            }
        });
    }

    private void setPreview(final Uri uri) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在设置预览图…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Exception ignored) {
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Preview.setFromUri(LutLibraryActivity.this, uri);
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (fe == null) {
                            refresh();
                            Ui.toast(LutLibraryActivity.this, "预览图已更新，缩略图重新生成中");
                        } else {
                            Ui.toast(LutLibraryActivity.this, "设置失败：" + fe);
                        }
                    }
                });
            }
        });
    }

    private void confirmDelete(final int position) {
        if (position < 0 || position >= entries.size()) {
            return;
        }
        final Store.LutEntry e = entries.get(position);
        new AlertDialog.Builder(this)
                .setTitle("删除 LUT")
                .setMessage("从库中删除「" + e.name + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        LutManager.delete(LutLibraryActivity.this, e.id);
                        refresh();
                    }
                })
                .show();
    }

    private class LutAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_lut, parent, false);
            }
            Store.LutEntry e = entries.get(position);
            final ImageView iv = (ImageView) view.findViewById(R.id.lutThumb);
            TextView name = (TextView) view.findViewById(R.id.lutName);
            TextView meta = (TextView) view.findViewById(R.id.lutMeta);
            TextView kind = (TextView) view.findViewById(R.id.lutKind);

            name.setText(e.name);
            meta.setText(e.label + " · " + ColorSpace.name(e.space));
            kind.setText(e.kind == 0 ? "3D" : "1D");
            kind.setTextColor(e.kind == 0 ? Ui.TEAL : Ui.ACCENT);

            final String id = e.id;
            iv.setTag(id);
            iv.setImageDrawable(null);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap b = LutManager.thumb(LutLibraryActivity.this, id);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (b != null && id.equals(iv.getTag())) {
                                iv.setImageBitmap(b);
                            }
                        }
                    });
                }
            });
            return view;
        }
    }
}
