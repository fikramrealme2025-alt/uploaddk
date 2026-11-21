package top.nativex.blackboxa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/** UI Glassmorphism (Java murni, tanpa AppCompat): daftar app virtual, tambah dari HP / dari APK, buka, hentikan, hapus. */
public class MainActivity extends Activity {
    private static final int USER_ID = 0;
    private static final int REQ_PICK_APK = 1001;
    private static final int REQ_PERMS = 1002;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ApplicationInfo> virtualApps = new ArrayList<ApplicationInfo>();
    private final List<AppRow> rows = new ArrayList<AppRow>();
    private AppAdapter adapter;
    private View empty;
    private TextView countChip;

    /** Satu baris app virtual (label + ikon sudah dimuat di background thread). */
    private static class AppRow {
        String label;
        String pkg;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestBasicPermissions();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- Glass UI helpers

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private void roundClip(View v, final int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private void circleClip(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    /** Tombol kaca: ikon + label. */
    private View glassButton(String label, int iconRes, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackgroundResource(R.drawable.glass_button);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setClickable(true);
        b.setOnClickListener(l);

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setAlpha(0.95f);
        b.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView tv = text(label, 14, Color.WHITE, true);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        b.addView(tv, tlp);
        return b;
    }

    private LinearLayout buttonRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.rightMargin = dp(5);
        row.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        rp.leftMargin = dp(5);
        row.addView(right, rp);
        return row;
    }

    private void buildUi() {
        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF070B1F);

        // Latar: gambar yang sudah di-blur + scrim gelap supaya kartu kaca kebaca
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setImageResource(R.drawable.bg_glass);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackgroundResource(R.drawable.bg_scrim);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // ---- Header (kartu kaca): avatar + judul
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.glass_panel);
        header.setPadding(dp(16), dp(16), dp(16), dp(16));

        FrameLayout avatarWrap = new FrameLayout(this);
        avatarWrap.setBackgroundResource(R.drawable.glass_circle);
        avatarWrap.setPadding(dp(3), dp(3), dp(3), dp(3));
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageResource(R.drawable.avatar);
        circleClip(avatar);
        avatarWrap.addView(avatar, new FrameLayout.LayoutParams(dp(60), dp(60)));
        header.addView(avatarWrap, new LinearLayout.LayoutParams(dp(66), dp(66)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Aethel Vortix", 22, Color.WHITE, true);
        title.setLetterSpacing(0.12f);
        titles.addView(title);
        titles.addView(text("Ruang virtual kamu", 13, 0xB3FFFFFF, false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(14);
        header.addView(titles, tl);
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ---- Tombol aksi (2 x 2)
        View bAdd = glassButton("Tambah dari HP", R.drawable.ic_add, new View.OnClickListener() {
            @Override public void onClick(View v) { showHostApps(); }
        });
        View bApk = glassButton("Pilih APK", R.drawable.ic_apk, new View.OnClickListener() {
            @Override public void onClick(View v) { pickApk(); }
        });
        View bFiles = glassButton("Izin semua file", R.drawable.ic_folder, new View.OnClickListener() {
            @Override public void onClick(View v) { openAllFilesAccess(); }
        });
        View bOverlay = glassButton("Izin overlay", R.drawable.ic_layers, new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlayPermission(); }
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(12);
        content.addView(buttonRow(bAdd, bApk), rowLp);
        LinearLayout.LayoutParams rowLp2 = new LinearLayout.LayoutParams(-1, -2);
        rowLp2.topMargin = dp(10);
        content.addView(buttonRow(bFiles, bOverlay), rowLp2);

        View bSettings = glassButton("Pengaturan", R.drawable.ic_settings, new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        LinearLayout.LayoutParams rowLp3 = new LinearLayout.LayoutParams(-1, dp(52));
        rowLp3.topMargin = dp(10);
        content.addView(bSettings, rowLp3);

        // ---- Label section + chip jumlah
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        TextView secTitle = text("APP VIRTUAL", 12, 0xB3FFFFFF, true);
        secTitle.setLetterSpacing(0.15f);
        sec.addView(secTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        countChip = text("0", 12, Color.WHITE, true);
        countChip.setBackgroundResource(R.drawable.glass_chip);
        countChip.setPadding(dp(12), dp(4), dp(12), dp(4));
        sec.addView(countChip, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-1, -2);
        secLp.topMargin = dp(22);
        secLp.bottomMargin = dp(10);
        secLp.leftMargin = dp(4);
        content.addView(sec, secLp);

        // ---- Daftar + empty state
        FrameLayout listArea = new FrameLayout(this);

        LinearLayout emptyCard = new LinearLayout(this);
        emptyCard.setOrientation(LinearLayout.VERTICAL);
        emptyCard.setGravity(Gravity.CENTER);
        emptyCard.setBackgroundResource(R.drawable.glass_panel);
        emptyCard.setPadding(dp(24), dp(32), dp(24), dp(32));
        ImageView emptyIc = new ImageView(this);
        emptyIc.setImageResource(R.drawable.ic_add);
        emptyIc.setAlpha(0.7f);
        emptyCard.addView(emptyIc, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView emptyTv = text("Belum ada app virtual.\nTambahkan Game Guardian dan game-nya ke sini.",
                14, 0xCCFFFFFF, false);
        emptyTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etl = new LinearLayout.LayoutParams(-2, -2);
        etl.topMargin = dp(12);
        emptyCard.addView(emptyTv, etl);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-1, -2);
        elp.gravity = Gravity.TOP;
        listArea.addView(emptyCard, elp);
        empty = emptyCard;

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(dp(10));
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(virtualApps.get(position).packageName);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showAppMenu(virtualApps.get(position));
                return true;
            }
        });
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Inset status bar / nav bar (edge-to-edge)
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                content.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(),
                        dp(16), dp(16) + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        setContentView(root);
    }

    /** Adapter kartu kaca untuk app virtual. */
    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            ImageView icon;
            TextView name, pkg;
            if (convertView == null) {
                card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setBackgroundResource(R.drawable.glass_item);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));

                icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setBackgroundResource(R.drawable.glass_chip);
                roundClip(icon, 14);
                card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                name = text("", 16, Color.WHITE, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                pkg = text("", 12, 0x99FFFFFF, false);
                pkg.setSingleLine(true);
                pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(name);
                col.addView(pkg);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.leftMargin = dp(14);
                card.addView(col, cl);

                ImageView play = new ImageView(MainActivity.this);
                play.setImageResource(R.drawable.ic_play);
                play.setAlpha(0.8f);
                play.setBackgroundResource(R.drawable.glass_circle);
                play.setPadding(dp(8), dp(8), dp(8), dp(8));
                card.addView(play, new LinearLayout.LayoutParams(dp(36), dp(36)));

                card.setTag(new View[]{icon, name, pkg});
            } else {
                card = (LinearLayout) convertView;
            }
            View[] tags = (View[]) card.getTag();
            AppRow r = rows.get(position);
            ((ImageView) tags[0]).setImageDrawable(r.icon);
            ((TextView) tags[1]).setText(r.label);
            ((TextView) tags[2]).setText(r.pkg);
            return card;
        }
    }

    // ---------------------------------------------------------------- Pengaturan

    /** Satu baris pengaturan: judul + keterangan di kiri, M3Switch di kanan. */
    private View settingRow(String title, String subtitle, boolean checked, M3Switch.OnChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.glass_item);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 15, Color.WHITE, true));
        TextView sub = text(subtitle, 12, 0x99FFFFFF, false);
        col.addView(sub);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.rightMargin = dp(12);
        row.addView(col, cl);

        final M3Switch sw = new M3Switch(this);
        sw.setChecked(checked, false);
        sw.setOnChangeListener(l);
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.performClick(); }
        });
        return row;
    }

    /** Satu baris aksi (tanpa switch): judul + keterangan, bisa diketuk. */
    private View actionRow(String title, String subtitle, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.glass_item);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.addView(text(title, 15, Color.WHITE, true));
        row.addView(text(subtitle, 12, 0x99FFFFFF, false));
        row.setClickable(true);
        row.setOnClickListener(l);
        return row;
    }

    private TextView sectionTitle(String t) {
        TextView tv = text(t, 12, 0xCCFFFFFF, true);
        tv.setPadding(dp(4), dp(6), 0, dp(6));
        return tv;
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(4));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);

        box.addView(sectionTitle("UMUM"), lp);
        box.addView(settingRow("Hide Root", "Sembunyikan root dari app virtual",
                HideRootSettings.isHideRoot(this), new M3Switch.OnChangeListener() {
                    @Override
                    public void onChanged(M3Switch s, boolean on) {
                        HideRootSettings.setHideRoot(MainActivity.this, on);
                        toast("Hide Root " + (on ? "aktif" : "mati") + " - berlaku saat app virtual dibuka ulang");
                    }
                }), lp);

        box.addView(sectionTitle("JARINGAN"), lp);
        box.addView(actionRow("Hosts virtual",
                "Atur hostname -> IP khusus app virtual, mendukung wildcard (*.youngjoygame.com)",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showVirtualHosts(); }
                }), lp);

        box.addView(sectionTitle("IZIN"), lp);
        box.addView(actionRow("Izin semua file", "Buka pengaturan akses penyimpanan",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openAllFilesAccess(); }
                }), lp);
        box.addView(actionRow("Izin overlay", "Buka pengaturan tampil di atas app lain",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openOverlayPermission(); }
                }), lp);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);

        showGlassDialog(new AlertDialog.Builder(this, R.style.GlassDialog)
                .setTitle("Pengaturan")
                .setView(sv)
                .setPositiveButton("Tutup", null));
    }

    /** Dialog kaca: tema transparan + blur latar di Android 12+. */
    private void showGlassDialog(AlertDialog.Builder b) {
        final AlertDialog d = b.create();
        d.show();
        blurBehind(d);
    }

    private void blurBehind(Dialog d) {
        if (Build.VERSION.SDK_INT < 31) return;
        Window w = d.getWindow();
        if (w == null) return;
        try {
            w.getClass().getMethod("setBackgroundBlurRadius", int.class).invoke(w, dp(40));
        } catch (Throwable ignored) {
        }
        try {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.getClass().getField("blurBehindRadius").setInt(lp, dp(16));
            lp.flags |= 0x00000004; // FLAG_BLUR_BEHIND
            lp.dimAmount = 0.35f;
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    private void requestBasicPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_PERMS);
        }
    }

    private void openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                toast("Izin semua file sudah aktif");
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestBasicPermissions();
        }
    }

    private void openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (Settings.canDrawOverlays(this)) {
                toast("Izin overlay sudah aktif");
                return;
            }
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private String labelOf(ApplicationInfo ai) {
        try {
            CharSequence l = BlackBoxCore.getPackageManager().getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) {
        }
        return ai.packageName;
    }

    private void refresh() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
                try {
                    List<ApplicationInfo> got = BlackBoxCore.get().getInstalledApplications(0, USER_ID);
                    if (got != null) apps.addAll(got);
                } catch (Throwable ignored) {
                }
                final List<AppRow> built = new ArrayList<AppRow>();
                for (ApplicationInfo ai : apps) {
                    AppRow r = new AppRow();
                    r.label = labelOf(ai);
                    r.pkg = ai.packageName;
                    try {
                        r.icon = BlackBoxCore.getPackageManager().getApplicationIcon(ai);
                    } catch (Throwable ignored) {
                    }
                    if (r.icon == null) r.icon = getResources().getDrawable(R.drawable.avatar);
                    built.add(r);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        virtualApps.clear();
                        virtualApps.addAll(apps);
                        rows.clear();
                        rows.addAll(built);
                        adapter.notifyDataSetChanged();
                        countChip.setText(String.valueOf(rows.size()));
                        empty.setVisibility(virtualApps.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    private void installGoogleApps() {
        if (!BlackBoxCore.get().isSupportGms()) {
            toast("HP ini tidak punya Google Play Services / Play Store, jadi tidak bisa dipasang ke virtual");
            return;
        }
        toast("Memasang Google Play ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                InstallResult r;
                try {
                    r = BlackBoxCore.get().installGms(USER_ID);
                } catch (Throwable t) {
                    r = new InstallResult().installError(String.valueOf(t.getMessage()));
                }
                final InstallResult res = r;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (res != null && res.success) {
                            toast("Google Play terpasang. Pilih 'Login akun Google' untuk masuk.");
                        } else {
                            toast("Gagal: " + (res == null ? "?" : res.msg));
                        }
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void openGoogleLogin() {
        if (!BlackBoxCore.get().isInstallGms(USER_ID)) {
            toast("Pasang Google Play dulu");
            return;
        }
        launch(GmsCore.VENDING_PKG);
        toast("Di Play Store, ketuk 'Sign in' lalu masuk dengan akun Google kamu");
    }

    private void runGoogleDiagnose() {
        toast("Mendiagnosa ... (sampai 10 detik)");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    out = top.niunaijun.blackbox.fake.frameworks.BAccountManager.get().diagnose(USER_ID);
                } catch (Throwable t) {
                    out = "Error: " + t;
                }
                final String text = out;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(text);
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(12);
                        tv.setTextIsSelectable(true);
                        tv.setPadding(dp(20), dp(8), dp(20), dp(8));
                        android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                        sv.addView(tv);
                        showGlassDialog(new AlertDialog.Builder(MainActivity.this, R.style.GlassDialog)
                                .setTitle("Diagnosa login Google")
                                .setView(sv)
                                .setPositiveButton("Tutup", null));
                    }
                });
            }
        }).start();
    }

    private void showVirtualHosts() {
        String cur = top.niunaijun.blackbox.utils.VirtualHosts.read(this);
        if (cur == null || cur.trim().length() == 0) {
            cur = "# Format: <ip> <hostname>   (wildcard * dan ? didukung)\n# Contoh:\n# 127.0.0.1 youngjoygame.com\n# 127.0.0.1 *.youngjoygame.com\n";
        }
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cur);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.GRAY);
        et.setTextSize(13);
        et.setTypeface(Typeface.MONOSPACE);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(8);
        et.setHorizontallyScrolling(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISUAL_PASSWORD);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), dp(8));
        wrap.addView(et, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showGlassDialog(new AlertDialog.Builder(this, R.style.GlassDialog)
                .setTitle("Hosts virtual")
                .setView(wrap)
                .setPositiveButton("Simpan", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        boolean ok = top.niunaijun.blackbox.utils.VirtualHosts.write(
                                MainActivity.this, et.getText().toString());
                        toast(ok ? "Tersimpan - berlaku saat app virtual dibuka ulang (tutup paksa dulu)"
                                : "Gagal menyimpan");
                    }
                })
                .setNeutralButton("Kosongkan", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        top.niunaijun.blackbox.utils.VirtualHosts.write(MainActivity.this, "");
                        toast("Hosts virtual dikosongkan");
                    }
                })
                .setNegativeButton("Batal", null));
    }

    private static final String[] LOGCAT_FILTERS = {"AccountManagerService", "ProxyService", "StubService"};

    private String readFilteredLogcat(boolean all) {
        StringBuilder out = new StringBuilder();
        java.util.ArrayList<String> keep = new java.util.ArrayList<String>();
        Process proc = null;
        try {
            proc = all
                    ? Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", "3000", "*:W"})
                    : Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime"});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (all) {
                    keep.add(line);
                    continue;
                }
                for (String f : LOGCAT_FILTERS) {
                    if (line.contains(f)) {
                        keep.add(line);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            out.append("Gagal baca logcat: ").append(t).append('\n');
        } finally {
            if (proc != null) proc.destroy();
        }
        int from = Math.max(0, keep.size() - (all ? 600 : 400));
        for (int i = from; i < keep.size(); i++) out.append(keep.get(i)).append('\n');
        if (keep.isEmpty()) {
            out.append("(tidak ada baris untuk filter: AccountManagerService / ProxyService / StubService)\n")
               .append("Coba tekan 'Login akun Google' dulu, lalu buka menu ini lagi.");
        }
        return out.toString();
    }

    private void showGoogleLogcat() { showGoogleLogcat(false); }

    private void showGoogleLogcat(final boolean all) {
        toast("Membaca logcat ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = readFilteredLogcat(all);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(text);
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(10);
                        tv.setTypeface(Typeface.MONOSPACE);
                        tv.setTextIsSelectable(true);
                        tv.setPadding(dp(20), dp(8), dp(20), dp(8));
                        android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                        sv.addView(tv);
                        showGlassDialog(new AlertDialog.Builder(MainActivity.this, R.style.GlassDialog)
                                .setTitle(all ? "Logcat: semua warning/error" : "Logcat login Google")
                                .setView(sv)
                                .setPositiveButton("Salin", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("logcat", text));
                                        toast("Logcat disalin");
                                    }
                                })
                                .setNeutralButton(all ? "Mode: filter" : "Mode: semua error", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        showGoogleLogcat(!all);
                                    }
                                })
                                .setNegativeButton("Tutup", null));
                    }
                });
            }
        }).start();
    }

    private void removeGoogleApps() {
        try {
            BlackBoxCore.get().uninstallGms(USER_ID);
            toast("Google Play dihapus dari virtual");
        } catch (Throwable t) {
            toast("Error: " + t.getMessage());
        }
        refresh();
    }

    private void showHostApps() {
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> host = new ArrayList<ApplicationInfo>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            host.add(ai);
        }
        Collections.sort(host, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        String[] names = new String[host.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = host.get(i).loadLabel(pm) + "\n" + host.get(i).packageName;
        }
        showGlassDialog(new AlertDialog.Builder(this, R.style.GlassDialog)
                .setTitle("Pilih app untuk ditambahkan")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        installHostPackage(host.get(which).packageName);
                    }
                }));
    }

    private void installHostPackage(final String pkg) {
        toast("Memasang " + pkg + " ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                InstallResult r;
                try {
                    r = BlackBoxCore.get().installPackageAsUser(pkg, USER_ID);
                } catch (Throwable t) {
                    r = new InstallResult().installError(String.valueOf(t.getMessage()));
                }
                reportInstall(r);
            }
        }).start();
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pilih APK"), REQ_PICK_APK);
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada file picker");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        toast("Memasang APK ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                InstallResult r;
                try {
                    r = BlackBoxCore.get().installPackageAsUser(uri, USER_ID);
                } catch (Throwable t) {
                    r = new InstallResult().installError(String.valueOf(t.getMessage()));
                }
                reportInstall(r);
            }
        }).start();
    }

    private void reportInstall(final InstallResult r) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (r != null && r.success) {
                    toast("Berhasil: " + r.packageName);
                } else {
                    toast("Gagal: " + (r == null ? "?" : r.msg));
                }
                refresh();
            }
        });
    }

    private void launch(String pkg) {
        try {
            if (!BlackBoxCore.get().launchApk(pkg, USER_ID)) {
                toast("Gagal membuka " + pkg);
            }
        } catch (Throwable t) {
            toast("Error: " + t.getMessage());
        }
    }

    private void showAppMenu(final ApplicationInfo ai) {
        showGlassDialog(new AlertDialog.Builder(this, R.style.GlassDialog)
                .setTitle(labelOf(ai))
                .setItems(new String[]{"Buka", "Hentikan", "Hapus"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            if (which == 0) {
                                launch(ai.packageName);
                            } else if (which == 1) {
                                BlackBoxCore.get().stopPackage(ai.packageName, USER_ID);
                                toast("Dihentikan");
                            } else {
                                BlackBoxCore.get().uninstallPackageAsUser(ai.packageName, USER_ID);
                                refresh();
                            }
                        } catch (Throwable t) {
                            toast("Error: " + t.getMessage());
                        }
                    }
                }));
    }
}
