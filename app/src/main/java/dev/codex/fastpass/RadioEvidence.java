package dev.codex.fastpass;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps LTE QCI and NR 5QI separate; a missing modem field never gets a guessed value. */
public final class RadioEvidence {
    public interface Reader { Object read(Object target, String method) throws Throwable; }
    private static Object optional(Reader reader, Object target, String method) {
        try { return reader.read(target, method); } catch (Throwable ignored) { return null; }
    }
    public static JSONObject qos(Object object, Reader reader) {
        if (object == null) return WireAudit.json("available", false, "reason", "基带/框架未提供 QoS 对象");
        Object value = optional(reader, object, "get5Qi");
        String kind = "NR_5QI";
        if (!(value instanceof Number)) { value = optional(reader, object, "getQci"); kind = "LTE_QCI"; }
        if (!(value instanceof Number)) return WireAudit.json("available", false, "class", object.getClass().getName(), "reason", "未找到可读取的 QCI/5QI 字段");
        int n = ((Number) value).intValue();
        return WireAudit.json("available", n > 0, "kind", kind, "value", n,
            "class", object.getClass().getName(), "reason", n > 0 ? "" : "返回值不是有效的正数，保留原值供核对");
    }
    public static JSONObject dataCall(Object call, Reader reader) {
        JSONObject out = WireAudit.json("cid", optional(reader, call, "getId"),
            "linkStatus", optional(reader, call, "getLinkStatus"), "cause", optional(reader, call, "getCause"),
            "interface", optional(reader, call, "getInterfaceName"));
        try {
            Object defaultQos = reader.read(call, "getDefaultQos");
            out.put("defaultQos", qos(defaultQos, reader));
        } catch (Throwable t) { try { out.put("defaultQos", WireAudit.json("available", false, "reason", "getDefaultQos 不可用：" + t.getClass().getSimpleName())); } catch (Exception ignored) {} }
        JSONArray sessions = new JSONArray();
        try {
            Object list = reader.read(call, "getQosBearerSessions");
            if (list instanceof List) for (Object session : (List<?>) list) {
                if (sessions.length() >= 8) break;
                sessions.put(WireAudit.json("sessionId", optional(reader, session, "getQosBearerSessionId"),
                    "qos", qos(optional(reader, session, "getQos"), reader)));
            }
            out.put("qosSessions", sessions).put("sessionsApiAvailable", true);
            out.put("qosSessionsTotal", list instanceof List ? ((List<?>) list).size() : 0);
        } catch (Throwable t) { try { out.put("qosSessions", sessions).put("sessionsApiAvailable", false); } catch (Exception ignored) {} }
        return out;
    }
    private RadioEvidence() {}
}
