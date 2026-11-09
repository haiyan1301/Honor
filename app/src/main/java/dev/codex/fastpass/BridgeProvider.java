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
        boolean radioPublisher = "publishRadio".equals(method);
        if (radioPublisher) {
            int uid = Binder.getCallingUid();
            if (uid != 1001 && uid != 1000 && uid != 0) throw new SecurityException("Phone publisher only");
        } else trusted("enqueue".equals(method) || "clearLog".equals(method));
        Bundle result = new Bundle();
        try {
            if ("controls".equals(method)) {
                result.putBoolean("autoEnabled", prefs.getBoolean("autoEnabled", false));
            } else if ("snapshot".equals(method)) {
                result.putString("state", prefs.getString("state", "{}"));
                result.putString("events", prefs.getString("events", "[]"));
                result.putString("history", prefs.getString("history", "[]"));
            } else if ("publish".equals(method) || "archive".equals(method) || radioPublisher) {
                if (!radioPublisher && Binder.getCallingUid() != 1000 && Binder.getCallingUid() != 0) throw new SecurityException("System publisher only");
                JSONObject base = state();
                JSONObject patch = new JSONObject(extras == null ? "{}" : extras.getString("patch", "{}"));
                boolean archiveOnly = "archive".equals(method);
                java.util.Iterator<String> it = patch.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (radioPublisher && !k.startsWith("radio")) throw new SecurityException("Radio namespace only");
                    if (!archiveOnly) base.put(k, patch.get(k));
                }
                base.put("updatedAt", System.currentTimeMillis());
                SharedPreferences.Editor edit = prefs.edit().putString("state", base.toString());
                if (!radioPublisher && !patch.optString("sessionId", "").isEmpty()) {
                    JSONArray previous = new JSONArray(prefs.getString("history", "[]")), history = new JSONArray();
                    for (int i = 0; i < previous.length(); i++) {
                        JSONObject old = previous.optJSONObject(i);
                        if (old != null && !patch.optString("sessionId").equals(old.optString("sessionId"))) history.put(old);
                    }
                    history.put(patch);
                    java.util.List<JSONObject> ordered = new java.util.ArrayList<>();
                    for (int i = 0; i < history.length(); i++) ordered.add(history.getJSONObject(i));
                    ordered.sort((a, b) -> Long.compare(a.optLong("startedAt"), b.optLong("startedAt")));
                    history = new JSONArray(); for (JSONObject row : ordered) history.put(row);
                    JSONArray recent = new JSONArray();
                    int start = history.length(), characters = 0;
                    while (start > 0 && history.length() - start < 6) {
                        int n = history.getJSONObject(start - 1).toString().length();
                        if (characters + n > 120000) break;
                        characters += n; start--;
                    }
                    for (int i = start; i < history.length(); i++) recent.put(history.get(i));
                    edit.putString("history", recent.toString());
                }
                if (extras != null && extras.containsKey("event")) {
                    JSONArray log = new JSONArray(prefs.getString("events", "[]"));
                    JSONObject row = new JSONObject();
                    row.put("time", System.currentTimeMillis());
                    row.put("text", extras.getString("event", ""));
                    log.put(row);
                    JSONArray trimmed = new JSONArray();
                    int characters = 0;
                    int start = log.length();
                    while (start > 0 && log.length() - start < 300) {
                        int n = log.getJSONObject(start - 1).toString().length();
                        if (characters + n > 180000) break;
                        characters += n; start--;
                    }
                    for (int i = start; i < log.length(); i++) trimmed.put(log.get(i));
                    edit.putString("events", trimmed.toString());
                }
                edit.apply();
                result.putBoolean("ok", true);
            } else if ("enqueue".equals(method)) {
                if (!"setAuto".equals(arg) && !"refresh".equals(arg)) throw new IllegalArgumentException("Only native-auto control and refresh are supported");
                String pending = prefs.getString("command", "");
                if (!pending.isEmpty() && "refresh".equals(arg)) {
                    JSONObject old = new JSONObject(pending);
                    if ("setAuto".equals(old.optString("type")) && System.currentTimeMillis() - old.optLong("createdAt") < 30000L)
                        throw new IllegalStateException("自动模式设置尚待后端接收");
                }
                JSONObject c = new JSONObject();
                c.put("type", arg);
                c.put("id", java.util.UUID.randomUUID().toString());
                c.put("createdAt", System.currentTimeMillis());
                boolean on = extras != null && extras.getBoolean("enabled", false);
                c.put("enabled", on);
                SharedPreferences.Editor edit = prefs.edit().putString("command", c.toString());
                if ("setAuto".equals(arg)) edit.putBoolean("autoEnabled", on);
                edit.apply();
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
