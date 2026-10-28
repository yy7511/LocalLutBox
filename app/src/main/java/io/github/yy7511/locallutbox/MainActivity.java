package io.github.yy7511.locallutbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.yy7511.locallutbox.core.ImageUtil;
import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.ui.ThumbLoader;
import io.github.yy7511.locallutbox.ui.Ui;

import java.util.ArrayList;

public class MainActivity extends BaseActivity {

    private static final int REQ_IMAGES = 101;

    private final ArrayList<Store.ImageEntry> entries = new ArrayList<Store.ImageEntry>();
    private GridView grid;
    private ImageAdapter adapter;
    private TextView imgCount;
    private TextView lutCount;
    private TextView autoState;
    private LinearLayout emptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imgCount = (TextView) findViewById(R.id.imgCount);
        lutCount = (TextView) findViewById(R.id.lutCount);
        autoState = (TextView) findViewById(R.id.autoState);
        emptyState = (LinearLayout) findViewById(R.id.emptyState);
        grid = (GridView) findViewById(R.id.imageGrid);

        findViewById(R.id.cardImport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImages();
            }
        });
        findViewById(R.id.cardLut).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LutLibraryActivity.class));
            }
        });
        findViewById(R.id.cardAuto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AutoActivity.class));
            }
        });

        TextView btnTheme = (TextView) findViewById(R.id.btnTheme);
        btnTheme.setText(darkMode ? "浅色" : "深色");
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setDarkMode(MainActivity.this, !darkMode);
                recreate();
            }
        });

        adapter = new ImageAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(MainActivity.this, EditorActivity.class);
                intent.putExtra("index", position);
                startActivity(intent);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmRemove(position);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        entries.clear();
        entries.addAll(Store.images(this));
        adapter.notifyDataSetChanged();
        int luts = Store.luts(this).size();
        lutCount.setText(luts + " 个 LUT");
        imgCount.setText(entries.size() + " 张");
        boolean running = Store.autoRunning(this);
        autoState.setText(running ? "运行中" : "已关闭");
        autoState.setTextColor(running ? Ui.ACCENT : Ui.MUTED);
        emptyState.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_IMAGES);
        } catch (Exception e) {
            Ui.toast(this, "无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMAGES || resultCode != RESULT_OK || data == null) {
            return;
        }
        ArrayList<Uri> uris = collectUris(data);
        if (uris.isEmpty()) {
            return;
        }
        ArrayList<Store.ImageEntry> list = Store.images(this);
        int added = 0;
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            String s = uri.toString();
            boolean dup = false;
            for (int k = 0; k < list.size(); k++) {
                if (s.equals(list.get(k).uri)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            String name = ImageUtil.getDisplayName(getContentResolver(), uri);
            list.add(new Store.ImageEntry(s, name));
            added++;
        }
        Store.saveImages(this, list);
        reload();
        Ui.toast(this, added > 0 ? "已导入 " + added + " 张图片" : "这些图片已在列表中");
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

    private void confirmRemove(final int position) {
        if (position < 0 || position >= entries.size()) {
            return;
        }
        final Store.ImageEntry e = entries.get(position);
        new AlertDialog.Builder(this)
                .setTitle("移除图片")
                .setMessage("从列表移除「" + e.name + "」？\n原文件不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ArrayList<Store.ImageEntry> list = Store.images(MainActivity.this);
                        if (position < list.size()) {
                            list.remove(position);
                            Store.saveImages(MainActivity.this, list);
                            reload();
                        }
                    }
                })
                .show();
    }

    private class ImageAdapter extends BaseAdapter {
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
                view = getLayoutInflater().inflate(R.layout.item_image, parent, false);
            }
            Store.ImageEntry e = entries.get(position);
            ImageView thumb = (ImageView) view.findViewById(R.id.thumb);
            TextView name = (TextView) view.findViewById(R.id.thumbName);
            TextView badge = (TextView) view.findViewById(R.id.thumbBadge);
            name.setText(e.name);
            boolean hasLut = e.lutId != null && e.lutId.length() > 0;
            badge.setVisibility(hasLut ? View.VISIBLE : View.GONE);
            badge.setText("LUT");
            ThumbLoader.load(thumb, e.uri, 110);
            return view;
        }
    }
}
