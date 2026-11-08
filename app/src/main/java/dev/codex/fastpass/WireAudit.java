package dev.codex.fastpass;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Redacted evidence from the actual native HTTP object, without changing its contents. */
public final class WireAudit {
    public static JSONObject json(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) try {
            o.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
        } catch (Exception ignored) {}
        return o;
    }
    private static String key(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    public static boolean counterKey(String s) {
        String k = key(s);
        return !k.contains("account") && (k.equals("base") || k.equals("manual") || k.equals("fail") || k.contains("count")
            || k.contains("quota") || k.contains("remaining") || k.contains("limit")
            || k.contains("intertimes") || k.contains("scenetime") || k.contains("interacttimes"));
    }
    private static boolean secret(String s) {
        String k = key(s);
        return k.contains("token") || k.contains("authorization") || k.contains("signature")
            || k.contains("deviceid") || k.contains("imei") || k.contains("imsi")
            || k.contains("iccid") || k.contains("msisdn") || k.contains("phonenumber")
            || k.contains("subscriberid") || k.contains("password") || k.contains("secret")
            || k.contains("apikey") || k.contains("cookie") || k.contains("account");
    }
    public static String clean(String value) {
        if (value == null) return "";
        String v = value.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~-]+", "$1[redacted]")
            .replaceAll("(?i)(\"?(?:token|authorization|signature|device[-_]?id|imei|imsi|iccid|msisdn|password|secret)\"?\\s*[=:]\\s*\"?)[^,\\s;\"}]+", "$1[redacted]");
        return v.length() > 1800 ? v.substring(0, 1800) : v;
    }
    private static Object presence(Object v) {
        return v == null || v == JSONObject.NULL || "".equals(v) ? v : "[present, redacted]";
    }
    private static boolean safeBody(String name) {
        String k = key(name);
        return k.equals("plmn") || k.equals("rat") || k.equals("qcitype") || k.equals("qci")
            || k.equals("scene") || k.equals("qoscause") || k.equals("duration") || k.equals("applyid");
    }
    private static boolean safeResponse(String name) {
        String k = key(name);
        return k.equals("code") || k.equals("status") || k.equals("duration") || k.equals("msg")
            || k.equals("message") || k.equals("remotemessage") || k.equals("reason") || k.equals("errorcode")
            || k.equals("errormessage") || k.equals("success") || counterKey(name);
    }
    private static Object redact(Object v, String name, boolean request, int depth) throws Exception {
        if (secret(name)) return presence(v);
        if (depth > 7) return "[depth limit]";
        if (v instanceof JSONObject) {
            JSONObject input = (JSONObject) v, out = new JSONObject();
            Iterator<String> it = input.keys();
            int n = 0;
            while (it.hasNext() && n++ < 80) { String k = it.next(); out.put(k, redact(input.get(k), k, request, depth + 1)); }
            return out;
        }
        if (v instanceof JSONArray) {
            JSONArray input = (JSONArray) v, out = new JSONArray();
            for (int i = 0; i < Math.min(input.length(), 32); i++) out.put(redact(input.get(i), name, request, depth + 1));
            return out;
        }
        if ((request ? safeBody(name) || counterKey(name) : safeResponse(name)))
            return v instanceof String ? clean((String) v) : v;
        return presence(v);
    }
    private static void paths(Object value, String path, JSONArray out, int depth) throws Exception {
        if (depth > 7 || out.length() >= 80) return;
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            Iterator<String> it = o.keys();
            int n = 0;
            while (it.hasNext() && n++ < 80) {
                String k = it.next(), p = path + "." + k;
                if (counterKey(k)) out.put(p);
                if (!secret(k)) paths(o.get(k), p, out, depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < Math.min(a.length(), 32); i++) paths(a.get(i), path + "[" + i + "]", out, depth + 1);
        }
    }
    public static boolean matches(String body, String applyId) {
        try {
            JSONObject r = new JSONObject(body).optJSONObject("requestInfo");
            return r != null && applyId != null && !applyId.isEmpty() && applyId.equals(r.optString("applyId"));
        } catch (Exception ignored) { return false; }
    }
    public static JSONObject request(String body, Map<?, ?> heads, boolean hasAuth, String url) throws Exception {
        JSONObject original;
        try { original = new JSONObject(body); }
        catch (Exception ignored) { throw new IllegalArgumentException("Native HTTP body is not valid JSON; raw contents omitted"); }
        JSONObject headers = new JSONObject();
        JSONArray counters = new JSONArray();
        paths(original, "body", counters, 0);
        List<String> names = new ArrayList<>();
        if (heads != null) for (Object k : heads.keySet()) names.add(String.valueOf(k));
        Collections.sort(names);
        for (String k : names) {
            Object v = heads.get(k);
            if (counterKey(k)) counters.put("headers." + k);
            headers.put(k, secret(k) ? presence(v) : clean(String.valueOf(v)));
        }
        headers.put("authorization", hasAuth ? "[present, redacted]" : "");
        return json("captured", true, "endpoint", endpoint(url), "method", "POST",
            "body", redact(original, "", true, 0), "headers", headers, "counterFieldPaths", counters,
            "scope", "实际原生 HTTP 对象；不包含单独的 CHR 统计上报或服务端内部状态");
    }
    public static Object response(String body) {
        try {
            JSONObject input = new JSONObject(body);
            Object safe = redact(input, "", false, 0);
            if (safe.toString().length() <= 24000) return safe;
            JSONObject summary = json("truncated", true, "limitChars", 24000,
                "note", "脱敏 JSON 超过诊断大小上限，仅保留主要业务字段");
            for (String k : new String[]{"code", "duration", "msg", "remoteMessage"}) {
                Object v = input.opt(k);
                if (v instanceof Number || v instanceof String || v instanceof Boolean) summary.put(k, redact(v, k, false, 0));
            }
            return summary;
        }
        catch (Exception ignored) { return json("parsed", false, "note", "响应不是可解析的 JSON，未保存原始字节"); }
    }
    public static String endpoint(String url) {
        try { URI u = new URI(url); return u.getScheme() + "://" + u.getHost() + u.getPath(); }
        catch (Exception ignored) { return "[invalid URL]"; }
    }
    private WireAudit() {}
}
