package dev.codex.fastpass;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class AuditRadioTest {
    private static int checks;
    private static void check(String name, boolean value) { if (!value) throw new AssertionError(name); checks++; }
    public static final class Eps {
        final int value;
        Eps(int value) { this.value = value; }
        public int getQci() { return value; }
    }
    public static final class Nr {
        public int get5Qi() { return 7; }
    }
    public static final class Session {
        public int getQosBearerSessionId() { return 12; }
        public Object getQos() { return new Eps(6); }
    }
    public static final class Call {
        public int getId() { return 3; }
        public int getLinkStatus() { return 2; }
        public int getCause() { return 0; }
        public String getInterfaceName() { return "rmnet_data0"; }
        public Object getDefaultQos() { return null; }
        public List<Session> getQosBearerSessions() { return Arrays.asList(new Session()); }
    }
    public static void main(String[] args) throws Exception {
        String body = "{\"requestInfo\":{\"plmn\":\"46011\",\"rat\":2,\"qciType\":6,\"scene\":\"Manual\",\"qosCause\":\"Scene\",\"duration\":30,\"applyId\":\"this-request\",\"token\":\"TOKEN_DO_NOT_LOG\",\"ip\":\"192.0.2.99\",\"ipv6\":\"2001:db8::1234\",\"destinationIpList\":\"\"}}";
        Map<String,String> headers = new HashMap<>();
        headers.put("Device-Id", "DEVICE_DO_NOT_LOG"); headers.put("Batch-ID", "37"); headers.put("Is-Test", "false");
        JSONObject audit = WireAudit.request(body, headers, true, "https://example.invalid/dedicatedbearer/v1/apply?token=URL_DO_NOT_LOG");
        String serialized = audit.toString();
        check("request token redacted", !serialized.contains("TOKEN_DO_NOT_LOG"));
        check("device identifier redacted", !serialized.contains("DEVICE_DO_NOT_LOG"));
        check("IPv4 redacted", !serialized.contains("192.0.2.99"));
        check("IPv6 redacted", !serialized.contains("2001:db8::1234"));
        check("query string not logged", !serialized.contains("URL_DO_NOT_LOG"));
        check("real qci request preserved", audit.getJSONObject("body").getJSONObject("requestInfo").getInt("qciType") == 6);
        check("scene Manual value is not a quota field", audit.getJSONArray("counterFieldPaths").length() == 0);
        check("Batch-ID is not a request counter", !WireAudit.counterKey("Batch-ID"));
        check("account is not a quota", !WireAudit.counterKey("accountId"));
        check("matching actual applyId accepted", WireAudit.matches(body, "this-request"));
        check("other request rejected", !WireAudit.matches(body, "other-request"));
        check("empty applyId rejected", !WireAudit.matches(body, ""));
        check("malformed JSON rejected", !WireAudit.matches("not JSON", "this-request"));
        JSONObject modified = new JSONObject(body); modified.getJSONObject("requestInfo").put("remainingCount", 3);
        JSONObject withCount = WireAudit.request(modified.toString(), headers, true, "https://example.invalid/dedicatedbearer/v1/apply");
        check("unexpected actual counter detected", withCount.getJSONArray("counterFieldPaths").toString().contains("remainingCount"));
        check("actual counter value preserved for audit", withCount.getJSONObject("body").getJSONObject("requestInfo").getInt("remainingCount") == 3);
        String response = WireAudit.response("{\"code\":202,\"msg\":\"token=MSG_SECRET\",\"remoteMessage\":\"expired\",\"duration\":0,\"token\":\"REPLY_SECRET\",\"result\":\"UNKNOWN_SECRET\"}").toString();
        check("raw business202 retained", new JSONObject(response).getInt("code") == 202);
        check("server zero duration retained", new JSONObject(response).getInt("duration") == 0);
        check("response token redacted", !response.contains("REPLY_SECRET"));
        check("embedded token redacted", !response.contains("MSG_SECRET"));
        check("unknown response string withheld", !response.contains("UNKNOWN_SECRET"));
        org.json.JSONArray messages = new org.json.JSONArray();
        String longMessage = new String(new char[2000]).replace('\0', 'x');
        for (int i = 0; i < 32; i++) messages.put(new JSONObject().put("message", longMessage));
        JSONObject capped = (JSONObject) WireAudit.response(new JSONObject().put("code", 202).put("messages", messages).toString());
        check("large response is explicitly bounded for Binder", capped.optBoolean("truncated") && capped.getInt("code") == 202 && capped.toString().length() < 24000);
        RadioEvidence.Reader reader = (target, method) -> target.getClass().getMethod(method).invoke(target);
        JSONObject eps = RadioEvidence.qos(new Eps(9), reader);
        check("read actual default LTE QCI9", eps.getBoolean("available") && eps.getInt("value") == 9);
        check("LTE labelled QCI", "LTE_QCI".equals(eps.getString("kind")));
        JSONObject nr = RadioEvidence.qos(new Nr(), reader);
        check("NR5QI stays distinct", nr.getInt("value") == 7 && "NR_5QI".equals(nr.getString("kind")));
        JSONObject missing = RadioEvidence.qos(null, reader);
        check("absent QoS remains unknown", !missing.getBoolean("available") && !missing.has("value"));
        JSONObject zero = RadioEvidence.qos(new Eps(0), reader);
        check("zero QCI is not fabricated as valid", !zero.getBoolean("available") && zero.getInt("value") == 0);
        check("unknown QoS class remains unknown", !RadioEvidence.qos(new Object(), reader).getBoolean("available"));
        JSONObject call = RadioEvidence.dataCall(new Call(), reader);
        check("dedicated QCI6 does not become default QCI", !call.getJSONObject("defaultQos").getBoolean("available"));
        check("dedicated session actual QCI6 preserved", call.getJSONArray("qosSessions").getJSONObject(0).getJSONObject("qos").getInt("value") == 6);
        check("CID and interface retained", call.getInt("cid") == 3 && "rmnet_data0".equals(call.getString("interface")));
        JSONObject unavailable = RadioEvidence.dataCall(new Object(), reader);
        check("missing APIs are explicit", !unavailable.getJSONObject("defaultQos").getBoolean("available") && !unavailable.getBoolean("sessionsApiAvailable"));
        System.out.println("PASS: " + checks + " wire/radio evidence checks");
    }
}
