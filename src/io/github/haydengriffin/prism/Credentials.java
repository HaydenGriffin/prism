package io.github.haydengriffin.prism;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Token encrypted with an app-specific, non-exportable Android Keystore key. */
final class Credentials {

    private static final String KEY_ALIAS = "prism-connection";

    final String url;
    final String token;

    Credentials(String url, String token) {
        this.url = HaHttp.normalizeBase(url);
        new HaHttp(this.url, token); // Validate before saving.
        this.token = token.trim();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            int purposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT;
            gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, purposes)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
            gen.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    void save(Context context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        boolean saved = context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("url", url)
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit();
        if (!saved) throw new IOException("Could not save the connection.");
    }

    /** Synchronized: the launcher, the service and the home-screen rows all load at start, and only one may provision. */
    static synchronized Credentials load(Context context) throws Exception {
        // Optional one-use provisioning through `adb shell -T run-as`, never an exported intent.
        File incoming = new File(context.getFilesDir(), "connection.json");
        if (incoming.exists()) return provision(context, incoming);
        SharedPreferences prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        if (!prefs.contains("token")) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] token = cipher.doFinal(Base64.decode(prefs.getString("token", ""), Base64.NO_WRAP));
        return new Credentials(prefs.getString("url", ""), new String(token, StandardCharsets.UTF_8));
    }

    private static Credentials provision(Context context, File incoming) throws Exception {
        try {
            if (incoming.length() > 16384) throw new IOException("Connection file is too large.");
            JSONObject obj = new JSONObject(new String(Files.readAllBytes(incoming.toPath()), StandardCharsets.UTF_8));
            Credentials credentials = new Credentials(obj.getString("url"), obj.getString("token"));
            credentials.save(context);
            return credentials;
        } finally {
            if (!incoming.delete()) throw new IOException("Could not remove the temporary connection file.");
        }
    }
}
