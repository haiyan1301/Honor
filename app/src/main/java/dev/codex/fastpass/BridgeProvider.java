package dev.codex.fastpass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only this app and the platform UID can use the bridge. No arbitrary caller control. */
public final class BridgeProvider extends ContentProvider {
    private SharedPreferences prefs;
    @Override public boolean onCreate() {
        prefs = getContext().createDeviceProtectedStorageContext().getSharedPreferences("bridge", 0);
        return true;
    }
    private void trusted(boolean ownerOnly) {
        int uid = Binder.getCallingUid();
        boolean owner = uid == getContext().getApplicationInfo().uid;
        if (!owner && (ownerOnly || (uid != 1000 && uid != 0))) throw new SecurityException("Private control bridge");
    }
    private JSONObject state() throws Exception { return new JSONObject(prefs.getString("state", "{}")); }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        trusted("enqueue".equals(method) || "clearLog".equals(method));
        Bundle result = new Bundle();
        try {
            if ("snapshot".equals(method)) {
                result.putString("state", prefs.getString("state", "{}"));
                result.putString("events", prefs.getString("events", "[]"));
            } else if ("publish".equals(method)) {
                if (Binder.getCallingUid() != 1000 && Binder.getCallingUid() != 0) throw new SecurityException("System publisher only");
                JSONObject base = state();
                JSONObject patch = new JSONObject(extras == null ? "{}" : extras.getString("patch", "{}"));
                java.util.Iterator<String> it = patch.keys();
                while (it.hasNext()) { String k = it.next(); base.put(k, patch.get(k)); }
                base.put("updatedAt", System.currentTimeMillis());
                SharedPreferences.Editor edit = prefs.edit().putString("state", base.toString());
                if (extras != null && extras.containsKey("event")) {
                    JSONArray log = new JSONArray(prefs.getString("events", "[]"));
                    JSONObject row = new JSONObject();
                    row.put("time", System.currentTimeMillis());
                    row.put("text", extras.getString("event", ""));
                    log.put(row);
                    JSONArray trimmed = new JSONArray();
                    for (int i = Math.max(0, log.length() - 160); i < log.length(); i++) trimmed.put(log.get(i));
                    edit.putString("events", trimmed.toString());
                }
                edit.apply();
                result.putBoolean("ok", true);
            } else if ("enqueue".equals(method)) {
                if (!"request".equals(arg) && !"refresh".equals(arg) && !"stop".equals(arg)) throw new IllegalArgumentException("Unknown command");
                String pending = prefs.getString("command", "");
                if (!pending.isEmpty() && !"stop".equals(arg)) {
                    JSONObject old = new JSONObject(pending);
                    if ("request".equals(old.optString("type")) && System.currentTimeMillis() - old.optLong("createdAt") < 30000L)
                        throw new IllegalStateException("已有待处理的请求，请等待系统接收");
                }
                JSONObject c = new JSONObject();
                c.put("type", arg);
                c.put("id", java.util.UUID.randomUUID().toString());
                c.put("createdAt", System.currentTimeMillis());
                c.put("bypass", extras != null && extras.getBoolean("bypass", true));
                prefs.edit().putString("command", c.toString()).apply();
                result.putString("id", c.getString("id"));
            } else if ("take".equals(method)) {
                if (Binder.getCallingUid() != 1000 && Binder.getCallingUid() != 0) throw new SecurityException("System consumer only");
                result.putString("command", prefs.getString("command", ""));
                prefs.edit().remove("command").apply();
            } else if ("clearLog".equals(method)) {
                prefs.edit().putString("events", "[]").apply();
                result.putBoolean("ok", true);
            } else throw new IllegalArgumentException("Unknown bridge method");
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) { result.putString("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
        return result;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { trusted(false); return null; }
    @Override public String getType(Uri u) { trusted(false); return "application/json"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
