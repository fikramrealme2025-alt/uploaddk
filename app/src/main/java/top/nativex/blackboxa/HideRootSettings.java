package top.nativex.blackboxa;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Pengaturan Hide Root, disimpan di file kecil (bukan SharedPreferences) supaya
 * bisa dibaca juga oleh proses app virtual yang berjalan di proses terpisah.
 * Perubahan berlaku saat app virtual dibuka ulang.
 */
public final class HideRootSettings {
    private static final String FILE = "hide_root.cfg";
    private static final String K_HIDE = "hide_root";

    private static boolean sLoaded;
    private static boolean sHide;

    private HideRootSettings() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private static synchronized void load(Context c) {
        if (sLoaded || c == null) return;
        try {
            File f = file(c);
            if (f.exists()) {
                Properties p = new Properties();
                FileInputStream in = new FileInputStream(f);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
                sHide = "1".equals(p.getProperty(K_HIDE, "0"));
            }
        } catch (Throwable ignored) {
        }
        sLoaded = true;
    }

    private static synchronized void save(Context c) {
        try {
            Properties p = new Properties();
            p.setProperty(K_HIDE, sHide ? "1" : "0");
            FileOutputStream out = new FileOutputStream(file(c));
            try {
                p.store(out, null);
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isHideRoot(Context c) {
        load(c);
        return sHide;
    }

    /** Dipakai mesin Hide Root (ClientConfiguration#isHideRoot). */
    public static boolean isActive(Context c) {
        return isHideRoot(c);
    }

    public static void setHideRoot(Context c, boolean on) {
        load(c);
        sHide = on;
        save(c);
    }
}
