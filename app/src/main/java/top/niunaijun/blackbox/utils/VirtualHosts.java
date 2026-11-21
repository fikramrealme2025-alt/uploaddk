package top.niunaijun.blackbox.utils;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Hosts khusus app virtual, disimpan di filesDir host (dibaca proses virtual saat start). */
public final class VirtualHosts {
    private static final String FILE = "vhosts.txt";

    private VirtualHosts() {
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    public static String read(Context c) {
        File f = file(c);
        if (!f.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean write(Context c, String text) {
        try {
            FileOutputStream out = new FileOutputStream(file(c));
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
