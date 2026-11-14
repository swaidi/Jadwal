package io.github.swaidi.jadwal;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup file: "MTBK", version byte, flag byte (0 = plain, 1 = password-protected), then either a ZIP
 * or salt(16) + iv(12) + AES-256-GCM ciphertext of that ZIP. The ZIP holds backup.json and audio/*.
 */
final class BackupFile {
    static final byte[] MAGIC = {'M', 'T', 'B', 'K'};
    private static final int ITER = 150000;

    static class WrongPassword extends Exception {}
    static class NotABackup extends Exception {}

    private BackupFile() {}

    static File notesDir(Context c) {
        File d = new File(c.getFilesDir(), "notes");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Writes a backup of the app data (JSON text) plus the listed voice-note files. */
    static void write(Context c, String dataJson, JSONArray audioPaths, String password, File out) throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("backup.json"));
            zos.write(dataJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            String notes = notesDir(c).getCanonicalPath();
            for (int i = 0; i < audioPaths.length(); i++) {
                File f = new File(audioPaths.optString(i));
                if (!f.exists() || !f.getCanonicalPath().startsWith(notes)) continue;
                zos.putNextEntry(new ZipEntry("audio/" + f.getName()));
                try (FileInputStream in = new FileInputStream(f)) { copy(in, zos); }
                zos.closeEntry();
            }
        }
        byte[] zip = zipBytes.toByteArray();
        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(MAGIC);
            fo.write(1);
            if (password == null || password.isEmpty()) {
                fo.write(0);
                fo.write(zip);
            } else {
                SecureRandom rnd = new SecureRandom();
                byte[] salt = new byte[16], iv = new byte[12];
                rnd.nextBytes(salt);
                rnd.nextBytes(iv);
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
                fo.write(1);
                fo.write(salt);
                fo.write(iv);
                fo.write(ci.doFinal(zip));
            }
        }
    }

    static boolean isEncrypted(File f) throws Exception {
        byte[] head = new byte[6];
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read(head) != 6) throw new NotABackup();
        }
        if (!Arrays.equals(Arrays.copyOf(head, 4), MAGIC)) throw new NotABackup();
        return head[5] == 1;
    }

    /**
     * Opens a backup: returns the backup.json text and extracts voice notes into stageDir.
     * Throws WrongPassword or NotABackup.
     */
    static String open(File f, String password, File stageDir) throws Exception {
        byte[] all = readAll(f);
        if (all.length < 6 || !Arrays.equals(Arrays.copyOf(all, 4), MAGIC)) throw new NotABackup();
        byte[] zip;
        if (all[5] == 1) {
            if (password == null || password.isEmpty() || all.length < 6 + 28) throw new WrongPassword();
            byte[] salt = Arrays.copyOfRange(all, 6, 22), iv = Arrays.copyOfRange(all, 22, 34);
            try {
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
                zip = ci.doFinal(all, 34, all.length - 34);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new WrongPassword();
            }
        } else {
            zip = Arrays.copyOfRange(all, 6, all.length);
        }
        deleteTree(stageDir);
        stageDir.mkdirs();
        String json = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if ("backup.json".equals(name)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    copy(zis, b);
                    json = new String(b.toByteArray(), StandardCharsets.UTF_8);
                } else if (name.startsWith("audio/") && !name.contains("..") && name.indexOf('/', 6) < 0 && name.length() > 6) {
                    try (FileOutputStream fo = new FileOutputStream(new File(stageDir, name.substring(6)))) { copy(zis, fo); }
                }
            }
        }
        if (json == null) throw new NotABackup();
        new JSONObject(json); // must be valid
        return json;
    }

    /** Moves staged voice notes into the app's notes folder. */
    static int installAudio(Context c, File stageDir) {
        File[] files = stageDir.listFiles();
        int n = 0;
        if (files == null) return 0;
        File dest = notesDir(c);
        for (File f : files) {
            File target = new File(dest, f.getName());
            if (target.exists()) target.delete();
            if (f.renameTo(target)) n++;
        }
        return n;
    }

    private static javax.crypto.SecretKey key(String password, byte[] salt) throws Exception {
        String alg = Build.VERSION.SDK_INT >= 26 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITER, 256);
        byte[] k = SecretKeyFactory.getInstance(alg).generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(k, "AES");
    }

    static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return b.toByteArray();
        }
    }

    static void copy(InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
