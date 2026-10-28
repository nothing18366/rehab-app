package com.robint.data.local;

import com.robint.data.remote.RehabApiClient;
import com.robint.feature.login.LoginActivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import org.json.JSONObject;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Calendar;
import javax.crypto.Cipher;
import javax.security.auth.x500.X500Principal;

/** The token is encrypted by a non-exportable Android Keystore private key, including API 21. */
public final class SessionStore {
    private static final String ALIAS = "rehab.login";
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("rehab", 0); }
    public static synchronized void save(Context c, JSONObject login) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance(), end = Calendar.getInstance(); end.add(Calendar.YEAR, 30);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(c.getApplicationContext()).setAlias(ALIAS)
                .setSubject(new X500Principal("CN=Rehab Login")).setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime()).setEndDate(end.getTime()).setKeySize(2048).build());
            generator.generateKeyPair();
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getCertificate(ALIAS).getPublicKey());
        prefs(c).edit().putString("auth_token", Base64.encodeToString(cipher.doFinal(login.getString("token").getBytes("UTF-8")), Base64.NO_WRAP))
            .putString("auth_base", prefs(c).getString("server_url", ""))
            .putString("auth_role", login.getString("role"))
            .putString("auth_patient", login.optString("patient_id", ""))
            .putLong("auth_expires", (long) (login.optDouble("expires", 0) * 1000)).remove("patient_id")
            .remove("next_remote_diagnostic").commit();
    }
    public static synchronized String token(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getString("auth_base", "").equals(p.getString("server_url", "")) || p.getLong("auth_expires", 0) <= System.currentTimeMillis()) return "";
        try {
            String text = p.getString("auth_token", ""); if (text.isEmpty()) return "";
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keys.getKey(ALIAS, null));
            return new String(cipher.doFinal(Base64.decode(text, Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) { return ""; }
    }
    public static boolean staff(Context c) { return "staff".equals(prefs(c).getString("auth_role", "")); }
    public static void clear(Context c) {
        prefs(c).edit().remove("auth_token").remove("auth_role").remove("auth_patient").remove("auth_expires")
            .remove("auth_base").remove("patient_id").remove("active_run").remove("next_remote_diagnostic").apply();
    }
    public static void login(Context c) {
        clear(c);
        c.startActivity(new Intent(c, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    public static boolean require(Activity c) {
        if (!token(c).isEmpty()) return true;
        login(c); return false;
    }
    public static void logout(Activity c) {
        RehabApiClient api = new RehabApiClient(c.getApplicationContext());
        String run = prefs(c).getString("active_run", "");
        // Server also watches revocation and stops a connected RTC session within two seconds.
        if (!run.isEmpty()) api.post("/api/v1/training-runs/" + run + "/finish", RehabApiClient.object(), (d, e) -> {});
        api.post("/api/v1/auth/logout", RehabApiClient.object(), (d, e) -> api.close());
        login(c);
    }
}
