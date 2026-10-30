package io.github.yy7511.locallutbox.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
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

import io.github.yy7511.locallutbox.R;
import io.github.yy7511.locallutbox.core.ColorSpace;
import io.github.yy7511.locallutbox.core.LutManager;
import io.github.yy7511.locallutbox.core.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-select LUT grid modelled on {@link io.github.yy7511.locallutbox.LutLibraryActivity}:
 * tapping a tile appends it to the stack, tapping again removes it.
 */
public final class LutPicker {

    public interface Callback {
        void onPick(ArrayList<String> ids);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private LutPicker() {
    }

    public static void show(final Activity a, List<String> initial, final Callback cb) {
        final ArrayList<Store.LutEntry> all = Store.luts(a);
        final ArrayList<String> picked = new ArrayList<String>();
        if (initial != null) {
            for (String id : initial) {
                if (contains(all, id) && !picked.contains(id)) {
                    picked.add(id);
                }
            }
        }

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 4));

        final TextView hint = Ui.text(a, "", 10, Ui.MUTED);
        hint.setText("按点击顺序叠加，先点的在下层（可多选）");
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView summary = Ui.text(a, "", 11, Ui.ACCENT);
        summary.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 4));
        root.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final GridView grid = new GridView(a);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(Ui.dp(a, 2));
        grid.setHorizontalSpacing(Ui.dp(a, 2));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        int maxH = Math.max(Ui.dp(a, 150),
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.45f));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        final PickerAdapter adapter = new PickerAdapter(a, all, picked);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String lutId = all.get(position).id;
                if (picked.contains(lutId)) {
                    picked.remove(lutId);
                } else {
                    picked.add(lutId);
                }
                adapter.notifyDataSetChanged();
                summary.setText(summary(picked, all));
            }
        });

        summary.setText(summary(picked, all));
        if (all.isEmpty()) {
            hint.setText("LUT 库为空，请先在 LUT 管理中导入");
            grid.setVisibility(View.GONE);
        }

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("选择自动套用的 LUT（可叠加）")
                .setView(root)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cb.onPick(new ArrayList<String>(picked));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                grid.setAdapter(null);
            }
        });
    }

    private static String summary(ArrayList<String> picked, ArrayList<Store.LutEntry> all) {
        if (picked.isEmpty()) {
            return "未选择 LUT（仅用基础参数调色）";
        }
        StringBuilder sb = new StringBuilder("已选 ").append(picked.size()).append(" 层：");
        for (int i = 0; i < picked.size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(nameOf(all, picked.get(i)));
        }
        return sb.toString();
    }

    private static String nameOf(ArrayList<Store.LutEntry> all, String id) {
        for (Store.LutEntry e : all) {
            if (e.id.equals(id)) {
                return e.name;
            }
        }
        return "?";
    }

    private static boolean contains(ArrayList<Store.LutEntry> all, String id) {
        for (Store.LutEntry e : all) {
            if (e.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static final class PickerAdapter extends BaseAdapter {
        private final Activity a;
        private final ArrayList<Store.LutEntry> all;
        private final ArrayList<String> picked;
        private final Handler main = new Handler(Looper.getMainLooper());

        PickerAdapter(Activity a, ArrayList<Store.LutEntry> all, ArrayList<String> picked) {
            this.a = a;
            this.all = all;
            this.picked = picked;
        }

        @Override
        public int getCount() {
            return all.size();
        }

        @Override
        public Object getItem(int position) {
            return all.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = a.getLayoutInflater().inflate(R.layout.item_lut, parent, false);
            }
            Store.LutEntry e = all.get(position);
            ImageView iv = (ImageView) view.findViewById(R.id.lutThumb);
            TextView name = (TextView) view.findViewById(R.id.lutName);
            TextView meta = (TextView) view.findViewById(R.id.lutMeta);
            TextView kind = (TextView) view.findViewById(R.id.lutKind);
            TextView order = (TextView) view.findViewById(R.id.lutOrder);

            int index = picked.indexOf(e.id);
            name.setText(e.name);
            meta.setText(e.label + " · " + ColorSpace.name(e.space));
            kind.setText(e.kind == 0 ? "3D" : "1D");
            kind.setTextColor(e.kind == 0 ? Ui.TEAL : Ui.ACCENT);
            if (index >= 0) {
                order.setVisibility(View.VISIBLE);
                order.setText(String.valueOf(index + 1));
                view.setBackgroundResource(R.drawable.bg_chip_sel);
            } else {
                order.setVisibility(View.GONE);
                view.setBackgroundResource(0);
            }

            final String lutId = e.id;
            if (iv.getTag() == null || !iv.getTag().equals(lutId)) {
                iv.setTag(lutId);
                iv.setImageDrawable(null);
                POOL.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap b = LutManager.thumb(a, lutId);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (b != null && lutId.equals(iv.getTag())) {
                                    iv.setImageBitmap(b);
                                }
                            }
                        });
                    }
                });
            }
            return view;
        }
    }
}
