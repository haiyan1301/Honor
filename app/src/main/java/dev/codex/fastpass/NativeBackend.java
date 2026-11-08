package dev.codex.fastpass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs only inside com.hihonor.systemserver. Native server responses are observed, never rewritten. */
public final class NativeBackend {
    private static volatile NativeBackend instance;
    private final Context context;
    private final ClassLoader loader;
    private final Handler worker;
    private final ThreadLocal<Session> initiating = new ThreadLocal<>();
    private final ThreadLocal<Session> cloudChecking = new ThreadLocal<>();
    private final ThreadLocal<Session> completing = new ThreadLocal<>();
    private final Map<Integer, BearerSnapshot> lastReports = new java.util.HashMap<>();
    private volatile Session active;
    private Class<?> managerClass, requestClass, timerClass, stateClass, utilsClass, linkClass;
    private Object manager;
    private boolean ready, bypassHook, cloudHook, bearerHook, auditHook;
    private String version = "unknown", compatibilityError = "";
    private int installAttempts;

    private static final class BearerSnapshot {
        final JSONArray list;
        final long elapsed;
        BearerSnapshot(JSONArray list, long elapsed) { this.list = list; this.elapsed = elapsed; }
    }

    private static final class Session {
        final String id;
        final long startedAt = System.currentTimeMillis();
        final long startedElapsed = SystemClock.elapsedRealtime();
        final boolean bypass;
        final OutcomePolicy.Evidence e = new OutcomePolicy.Evidence();
        volatile Object requestInfo;
        long sentAt;
        long sentElapsed;
        int slot = -1;
        int qci = 6;
        int duration = 30;
        Integer returnedDuration;
        Integer nativeResult;
        boolean activeNow, monitorStarted, localBypassApplied;
        long bearerAt;
        String telecom = "尚未收到鉴权回调", transport = "", msg = "", remoteMsg = "", applyId = "";
        String cloudRaw = "", telecomErrorCode = "", telecomErrorMessage = "";
        String carrier = "", plmn = "";
        boolean telecomRequired;
        int telecomHttp = -1;
        JSONArray bearers = new JSONArray();
        JSONObject quotaBefore = new JSONObject(), quotaAfter = new JSONObject();
        JSONObject commonChecks = new JSONObject(), requestAudit = new JSONObject(), httpResponse = new JSONObject();
        JSONObject preflight = new JSONObject();
        Session(String id, boolean bypass) { this.id = id; this.bypass = bypass; e.requested = true; }
    }

    public static synchronized void attach(Context context, ClassLoader loader) {
        if (instance != null) return;
        try { instance = new NativeBackend(context, loader); }
        catch (Throwable t) { XposedBridge.log("FastPass attach failed: " + t); }
    }
    private NativeBackend(Context context, ClassLoader loader) {
        this.context = context;
        this.loader = loader;
        HandlerThread thread = new HandlerThread("FastPassObserver");
        thread.start();
        worker = new Handler(thread.getLooper());
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i != null && Protocol.ACTION.equals(i.getAction())) worker.post(() -> consumeCommand());
            }
        }, new IntentFilter(Protocol.ACTION), Protocol.PERMISSION, worker, Context.RECEIVER_EXPORTED);
        worker.post(() -> install());
    }
    private Class<?> cls(String suffix) { return XposedHelpers.findClass(Protocol.ROOT + suffix, loader); }
    private Object singleton(Class<?> c) { return XposedHelpers.callStaticMethod(c, "getInstance"); }
    private static String clean(Object object) {
        return WireAudit.clean(object == null ? "" : String.valueOf(object));
    }
    private void install() {
        if (ready) return;
        installAttempts++;
        try {
            version = context.getPackageManager().getPackageInfo(Protocol.TARGET, 0).versionName;
            managerClass = cls("ollie.intelligent.qos.QosManager");
            requestClass = cls("framework.intf.intelligent.DBRequestInfo");
            timerClass = cls("ollie.intelligent.qos.QosTimer");
            stateClass = cls("framework.intf.OllieStateManager");
            utilsClass = cls("framework.utils.BoosterUtils");
            linkClass = cls("ollie.transportlayer.framework.intf.link.LinkLayerProxy");
            manager = singleton(managerClass);
            if (!bypassHook) installValidationHook();
            if (!cloudHook) installCloudHooks();
            if (!bearerHook) installBearerHook();
            if (!auditHook) installAuditHooks();
            ready = bypassHook && cloudHook && bearerHook && auditHook;
            compatibilityError = "70.1.0.1".equals(version) ? "" : "后端版本与分析版本 70.1.0.1 不同，仅启用观察";
            publish(json("sessionId", "", "sessionOpen", false, "verdict", "IDLE", "startedAt", 0,
                "requestSentAt", 0, "localCode", null, "cloudCode", null, "cloudReceived", false,
                "cloudRaw", "", "cloudMessage", "", "remoteMessage", "", "cloudTransport", "",
                "nativeResult", null, "bearerFresh", false, "bearerActive", false, "bearerAt", 0,
                "observedAt", 0, "observedBearers", new JSONArray(), "sessionBearers", new JSONArray(),
                "lastCommandError", "", "refreshError", "", "operator", "", "plmn", "",
                "telecom", "", "telecomHttp", null, "telecomErrorCode", "", "telecomErrorMessage", ""), null);
            publish(json("requestAudit", new JSONObject(), "httpResponse", new JSONObject(),
                "quotaBefore", new JSONObject(), "quotaAfter", new JSONObject(), "commonCloudChecks", new JSONObject(),
                "vendorSlots", new JSONArray(), "httpStatus", null, "commonLocalCode", null, "returnedMinutes", null), null);
            publish(json("backendReady", ready, "backendVersion", version, "bypassReady", bypassHook,
                "cloudObserverReady", cloudHook, "bearerObserverReady", bearerHook,
                "wireAuditReady", auditHook, "moduleVersion", Protocol.VERSION,
                "compatibilityError", compatibilityError, "connectedAt", System.currentTimeMillis()),
                "系统服务 Hook 已加载，后端 " + version);
            refresh();
        } catch (Throwable t) {
            compatibilityError = describe(t);
            publish(json("backendReady", false, "backendVersion", version, "compatibilityError", compatibilityError), "安装观察点失败：" + compatibilityError);
            if (installAttempts < 6) worker.postDelayed(() -> install(), 1500L);
        }
    }
    private void installValidationHook() {
        XposedHelpers.findAndHookMethod(managerClass, "checkIfQosReqLegal", requestClass, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Session s = initiating.get();
                if (s == null || !s.bypass || p.args[0] != s.requestInfo) return;
                try {
                    int result = -1;
                    if (!(Boolean) XposedHelpers.callMethod(p.thisObject, "isStart")) result = 101;
                    else if ((Boolean) XposedHelpers.callMethod(p.args[0], "checkParamIllegal")) result = 106;
                    else if (XposedHelpers.getBooleanField(p.thisObject, "isTaskExecuting")) result = 107;
                    else if ((Boolean) XposedHelpers.callMethod(singleton(timerClass), "hasExistIp", p.args[0])) result = 203;
                    p.setResult(result);
                    s.localBypassApplied = true;
                } catch (Throwable t) {
                    // Fail closed: retain the original method if the known field layout has changed.
                    event("本次绕过未应用：" + describe(t));
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Session s = initiating.get();
                if (s != null && p.args[0] == s.requestInfo && !p.hasThrowable() && p.getResult() instanceof Integer) {
                    s.e.localCode = (Integer) p.getResult();
                    event("本地请求校验：" + s.e.localCode + (s.localBypassApplied ? "；仅本次跳过 QoS 冷却/场景次数" : "；沿用原生检查"));
                }
            }
        });
        // Private helpers may be inlined in an OEM AOT build. Ask LSPosed to
        // deoptimize the native caller when that optional runtime API is present.
        try {
            Method caller = managerClass.getDeclaredMethod("requestDedicatedBearer", requestClass, Message.class);
            for (Method m : XposedBridge.class.getDeclaredMethods()) {
                if (m.getName().equals("deoptimizeMethod") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isInstance(caller)) {
                    m.setAccessible(true);
                    Object result = m.invoke(null, caller);
                    event("已请求 LSPosed 去优化原生调用者：" + result);
                    break;
                }
            }
        } catch (Throwable t) { event("显式去优化接口不可用，将核对实际 Hook 命中：" + describe(t)); }
        bypassHook = true;
    }
    private boolean owns(Session s, Object possibleManager) {
        if (s == null || s.requestInfo == null || s != active) return false;
        try { return XposedHelpers.getObjectField(possibleManager, "dbRequestInfo") == s.requestInfo; }
        catch (Throwable ignored) { return false; }
    }
    private void installCloudHooks() {
        Class<?> taskResult = cls("framework.cloudinter.common.CloudTaskResult");
        XposedHelpers.findAndHookMethod(managerClass, "doWhenQosSuccess", taskResult, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                final Session s = active;
                if (!owns(s, p.thisObject)) return;
                completing.set(s);
                try {
                    Object task = p.args[0];
                    Object error = XposedHelpers.callMethod(task, "getErrorCode");
                    boolean transportOk = (Boolean) XposedHelpers.callMethod(error, "isSuccess");
                    Object response = XposedHelpers.callMethod(task, "getResponse");
                    boolean actualResponse = transportOk && response != null && cls("ollie.intelligent.qos.QosResponse").isInstance(response);
                    final Integer code = actualResponse ? XposedHelpers.getIntField(response, "code") : null;
                    final String message = actualResponse ? clean(XposedHelpers.getObjectField(response, "msg")) : "";
                    final String remote = actualResponse ? clean(XposedHelpers.getObjectField(response, "remoteMessage")) : "";
                    final int duration = actualResponse ? XposedHelpers.getIntField(response, "duration") : 0;
                    final String transport = clean(error);
                    worker.post(() -> {
                        if (active != s) return;
                        s.e.cloudReceived = actualResponse;
                        s.e.cloudCode = code;
                        s.msg = message; s.remoteMsg = remote; s.transport = transport;
                        s.returnedDuration = actualResponse ? duration : null;
                        if (duration > 0) s.duration = duration;
                        s.cloudRaw = json("hasServerResponse", actualResponse, "code", code,
                            "duration", duration, "msg", message, "remoteMessage", remote,
                            "transport", transport).toString();
                        emit(s, actualResponse ? "云端原始业务码：" + code : "云端响应未确认，传输结果：" + transport);
                    });
                } catch (Throwable t) { event("解析云端回调失败：" + describe(t)); }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Session s = completing.get(); completing.remove();
                if (s == null) return;
                JSONObject after = quotaSnapshot();
                worker.post(() -> { if (active == s) { s.quotaAfter = after; emit(s, "原生完成后本地计数/冷却：" + after); } });
            }
        });
        Class<?> async = XposedHelpers.findClass("android.os.AsyncResult", loader);
        XposedHelpers.findAndHookMethod(managerClass, "handleTelecomReturn", async, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                final Session s = active;
                if (!owns(s, p.thisObject) || p.args[0] == null) return;
                try {
                    Object flag = XposedHelpers.getObjectField(p.args[0], "userObj");
                    final boolean success = flag instanceof Integer && ((Integer) flag) == 1;
                    // Intentionally never read AsyncResult.result when it contains the returned token.
                    worker.post(() -> {
                        if (active != s) return;
                        s.e.telecomFailed = !success;
                        s.telecom = success ? "电信已返回令牌，已交由原生服务使用" : "电信鉴权失败";
                        emit(s, s.telecom);
                    });
                } catch (Throwable t) { event("解析电信回调失败：" + describe(t)); }
            }
        });
        // Optional HTTP evidence, selected by actual signature instead of a synthetic method's name.
        try {
            Class<?> telecomClass = cls("ollie.intelligent.qos.QosManager$QosTelecomExec");
            for (Method m : telecomClass.getDeclaredMethods()) {
                Class<?>[] a = m.getParameterTypes();
                if (a.length != 4 || a[0] != String[].class || a[1] != int.class || a[2] != String.class || a[3] != byte[].class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        final Session s = active;
                        if (!owns(s, manager)) return;
                        try {
                            final int http = (Integer) p.args[1];
                            byte[] body = (byte[]) p.args[3];
                            JSONObject obj = body == null ? new JSONObject() : new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                            JSONObject err = obj.optJSONObject("error");
                            final String code = err == null ? "" : clean(err.optString("code", ""));
                            final String msg = err == null ? "" : clean(err.optString("message", ""));
                            worker.post(() -> {
                                if (active != s) return;
                                s.telecomHttp = http; s.telecomErrorCode = code; s.telecomErrorMessage = msg;
                                emit(s, "电信鉴权 HTTP " + http + (code.isEmpty() ? "" : "，错误码 " + code));
                            });
                        } catch (Throwable ignored) {
                            // Native handling is kept untouched even for HTML/non-JSON responses.
                        }
                    }
                });
            }
        } catch (Throwable t) { event("电信 HTTP 细节观察点不可用，保留原生鉴权结果：" + describe(t)); }
        cloudHook = true;
    }
    private JSONObject quotaSnapshot() {
        try {
            Object timer = singleton(timerClass);
            synchronized (timer) {
                Map<?, ?> times = (Map<?, ?>) XposedHelpers.getObjectField(timer, "sceneTime");
                Object cache = XposedHelpers.getObjectField(timer, "punishTimerCache");
                return json("readAt", System.currentTimeMillis(), "base", times.get("Base"),
                    "manual", times.get("Manual"), "fail", times.get("Fail"),
                    "cooldown", XposedHelpers.getBooleanField(timer, "isPunishDuration"),
                    "cooldownCode", XposedHelpers.getIntField(cache, "error"),
                    "cooldownMinutes", XposedHelpers.getIntField(cache, "duration"),
                    "cooldownStart", clean(XposedHelpers.getObjectField(cache, "startTime")));
            }
        } catch (Throwable t) { return json("readError", describe(t)); }
    }
    private JSONObject commonBudget(Object executor) {
        try {
            Object config = XposedHelpers.callMethod(singleton(cls("framework.cloudinter.common.config.CloudInterConfigManager")), "getConfigInfo", 1004);
            String day = String.valueOf(XposedHelpers.callStaticMethod(cls("framework.utils.DateUtils"), "getCurrentDay"));
            Map<?, ?> map = (Map<?, ?>) XposedHelpers.getObjectField(executor, "INTER_TIMES_CACHE_MAP");
            Object count = map.get("1004_" + day);
            int current = count == null ? 0 : ((java.util.concurrent.atomic.AtomicInteger) count).get();
            return json("businessId", 1004, "day", day, "currentTimes", current,
                "configLoaded", config != null, "dailyMax", config == null ? null : XposedHelpers.callMethod(config, "getDailyMaxInteractTimes"),
                "oobeAgreed", Settings.Global.getInt(context.getContentResolver(), "oobe_statement_agreed", -1),
                "readAt", System.currentTimeMillis(), "bypassed", false);
        } catch (Throwable t) { return json("readError", describe(t)); }
    }
    private JSONObject networkSnapshot() {
        JSONObject out = json("readAt", System.currentTimeMillis());
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            out.put("hasDefaultNetwork", n != null);
            if (c != null) {
                out.put("cellular", c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                out.put("wifi", c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                out.put("vpn", c.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                out.put("validated", c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
            out.put("dataSlotAtSend", slot(state()));
        } catch (Throwable t) { try { out.put("readError", describe(t)); } catch (Exception ignored) {} }
        return out;
    }
    private Session ownCloudTask(Object task) {
        Session s = active;
        if (s == null) return null;
        try {
            Object request = XposedHelpers.getObjectField(task, "requestBody");
            if (cls("ollie.intelligent.qos.QosRequest").isInstance(request)
                && XposedHelpers.callMethod(request, "getDBRequestInfo") == s.requestInfo) return s;
        } catch (Throwable ignored) {}
        return null;
    }
    private void installAuditHooks() {
        Class<?> executor = cls("framework.intf.CloudReqTaskExecutor");
        XposedHelpers.findAndHookMethod(executor, "executeTask", cls("framework.cloudinter.common.RequestBody"), Message.class,
            cls("framework.intf.ISaveCloudData"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    final Session s = active;
                    if (s == null || !cls("ollie.intelligent.qos.QosRequest").isInstance(p.args[0])) return;
                    try {
                        if (XposedHelpers.callMethod(p.args[0], "getDBRequestInfo") != s.requestInfo) return;
                        cloudChecking.set(s);
                        JSONObject before = commonBudget(p.thisObject);
                        worker.post(() -> { if (active == s) { s.commonChecks = json("before", before, "bypassed", false); emit(s, "进入通用云请求本地检查，业务 1004：" + before); } });
                    } catch (Throwable t) { event("通用检查观察异常：" + describe(t)); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    final Session s = cloudChecking.get(); cloudChecking.remove();
                    if (s == null) return;
                    final int code = ((Message) p.args[1]).arg1;
                    JSONObject after = commonBudget(p.thisObject);
                    worker.post(() -> {
                        if (active != s) return;
                        s.e.commonLocalCode = code;
                        try { s.commonChecks.put("after", after).put("executorCode", code); } catch (Exception ignored) {}
                        emit(s, "通用本地检查返回 " + code + "；1004=继续，101=本地次数，102=协议同意，103=并发");
                    });
                }
            });
        XposedHelpers.findAndHookMethod(executor, "isExceedMaxInterTimes", int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                final Session s = cloudChecking.get();
                if (s == null || ((Integer) p.args[0]) != 1004) return;
                final boolean hit = !p.hasThrowable() && Boolean.TRUE.equals(p.getResult());
                worker.post(() -> {
                    if (active != s) return;
                    try { s.commonChecks.put("dailyCheckObserved", true).put("exceeded", hit); } catch (Exception ignored) {}
                    emit(s, "通用接口每日次数检查：" + (hit ? "已拦截，本次不会进入 HTTP" : "通过"));
                });
            }
        });
        Class<?> http = cls("framework.cloudinter.common.HttpRequest");
        XposedHelpers.findAndHookMethod(http, "request", cls("framework.cloudinter.common.HttpResponse"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                final Session s = active;
                if (s == null) return;
                try {
                    String url = (String) XposedHelpers.getObjectField(p.thisObject, "url");
                    String body = (String) XposedHelpers.getObjectField(p.thisObject, "body");
                    String id = (String) XposedHelpers.callMethod(s.requestInfo, "getApplyId");
                    if (url == null || !url.endsWith("/dedicatedbearer/v1/apply") || !WireAudit.matches(body, id)) return;
                    Object auth = XposedHelpers.getObjectField(p.thisObject, "auth");
                    JSONObject audit = WireAudit.request(body, (Map<?, ?>) XposedHelpers.getObjectField(p.thisObject, "heads"),
                        auth instanceof String && !((String) auth).isEmpty(), url);
                    audit.put("enteredAt", System.currentTimeMillis()).put("enteredElapsed", SystemClock.elapsedRealtime());
                    java.util.regex.Matcher stamp = java.util.regex.Pattern.compile("timestamp=(\\d+)").matcher(auth instanceof String ? (String) auth : "");
                    if (stamp.find()) audit.put("signatureTimestamp", Long.parseLong(stamp.group(1)));
                    audit.put("network", networkSnapshot()).put("bypassScope", "仅 QoS 冷却和场景次数；保留公共接口每日次数/协议/并发/签名检查");
                    worker.post(() -> { if (active == s) { s.requestAudit = audit; emit(s, "已到原生 HTTP 发送层；实际 body/headers 已脱敏记录"); } });
                } catch (Throwable t) { event("HTTP 请求审计失败：" + describe(t)); }
            }
        });
        XposedHelpers.findAndHookMethod(http, "handleResponse", javax.net.ssl.HttpsURLConnection.class,
            cls("framework.cloudinter.common.HttpResponse"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    final Session s = active;
                    if (s == null) return;
                    try {
                        String body = (String) XposedHelpers.getObjectField(p.thisObject, "body");
                        if (!WireAudit.matches(body, (String) XposedHelpers.callMethod(s.requestInfo, "getApplyId"))) return;
                        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) p.args[0];
                        final int status = c.getResponseCode();
                        JSONObject details = json("status", status, "contentType", clean(c.getContentType()),
                            "serverDate", clean(c.getHeaderField("Date")), "serverDateEpoch", c.getDate(),
                            "receivedAt", System.currentTimeMillis(), "bodySentBeforeResponseHandling", true);
                        if (status != 200) {
                            // Native code discards non-200 bodies; its error stream is otherwise unused.
                            try (java.io.InputStream input = c.getErrorStream()) {
                                if (input != null) {
                                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                                    byte[] buffer = new byte[1024];
                                    while (bytes.size() < 32768) {
                                        int n = input.read(buffer, 0, Math.min(buffer.length, 32768 - bytes.size()));
                                        if (n < 0) break;
                                        bytes.write(buffer, 0, n);
                                    }
                                    details.put("errorBody", WireAudit.response(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)));
                                    details.put("errorBodyLimitBytes", 32768);
                                }
                            } catch (Throwable t) { details.put("errorBodyReadError", t.getClass().getSimpleName()); }
                        }
                        worker.post(() -> {
                            if (active != s) return;
                            s.e.httpStatus = status;
                            try { java.util.Iterator<String> it = details.keys(); while (it.hasNext()) { String k = it.next(); s.httpResponse.put(k, details.get(k)); } } catch (Exception ignored) {}
                            emit(s, "荣耀申请接口实际 HTTP 状态：" + status);
                        });
                    } catch (Throwable t) { event("HTTP 状态审计失败：" + describe(t)); }
                }
            });
        int rawHooks = 0;
        for (Method m : cls("framework.cloudinter.CloudReqTask").getDeclaredMethods()) {
            Class<?>[] a = m.getParameterTypes();
            if (a.length != 3 || a[0] != int.class || a[1] != String.class || a[2] != byte[].class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    final Session s = ownCloudTask(p.thisObject);
                    if (s == null) return;
                    try {
                        final int status = (Integer) p.args[0];
                        byte[] bytes = (byte[]) p.args[2];
                        Object body = bytes != null && bytes.length <= 65536
                            ? WireAudit.response(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                            : json("omitted", "响应为空或超过 64 KiB");
                        JSONObject details = json("status", status, "contentType", clean(p.args[1]),
                            "parsedBeforeNativeMapping", body, "responseBytes", bytes == null ? 0 : bytes.length,
                            "callbackAt", System.currentTimeMillis());
                        worker.post(() -> { if (active == s) { s.httpResponse = details; s.e.httpStatus = status; emit(s, "已记录原生反序列化前的云端 JSON 字段"); } });
                    } catch (Throwable t) { event("云端原始 JSON 审计失败：" + describe(t)); }
                }
            });
            rawHooks++;
        }
        if (rawHooks == 0) throw new IllegalStateException("未找到原始 HTTP 响应观察接口");
        auditHook = true;
    }
    private void installBearerHook() {
        Class<?> client = cls("ollie.transportlayer.framework.channelmanage.communication.client.BoosterClient");
        final Class<?> proto = XposedHelpers.findClass("com.hihonor.booster.protobuf.DedicatedBearerInfoIndOuterClass$DedicatedBearerInfoInd", loader);
        XposedHelpers.findAndHookMethod(client, "handleDedicatedBearerInfoInd", Bundle.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Bundle b = (Bundle) p.args[0];
                    if (b == null) return;
                    final int slot = b.getInt("slotId", -1);
                    byte[] raw = b.getByteArray("byte_array");
                    if (raw == null || raw.length == 0) return;
                    Object msg = XposedHelpers.callStaticMethod(proto, "parseFrom", (Object) raw);
                    final JSONArray list = listJson((List<?>) XposedHelpers.callMethod(msg, "getDedicatedBearerInfoList"));
                    final long received = System.currentTimeMillis();
                    final long receivedElapsed = SystemClock.elapsedRealtime();
                    worker.post(() -> bearerReport(slot, list, received, receivedElapsed));
                } catch (Throwable t) { event("真实承载回报解析失败：" + describe(t)); }
            }
        });
        bearerHook = true;
    }
    private JSONArray listJson(List<?> list) throws Exception {
        JSONArray out = new JSONArray();
        if (list != null) for (Object b : list) {
            if (out.length() >= 32) break;
            out.put(json("type", XposedHelpers.callMethod(b, "getBearerType"),
                "qci", XposedHelpers.callMethod(b, "getQci"), "state", XposedHelpers.callMethod(b, "getBearerState")));
        }
        return out;
    }
    private boolean hasActive(JSONArray list, int qci) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b != null && OutcomePolicy.matches(b.optInt("type", -1), b.optInt("qci", -1), b.optInt("state", -1), qci)) return true;
        }
        return false;
    }
    private void bearerReport(int slot, JSONArray list, long received, long receivedElapsed) {
        lastReports.put(slot, new BearerSnapshot(list, receivedElapsed));
        publish(json("observedSlot", slot, "observedBearers", list, "observedAt", received,
            "vendorSlots", vendorReports(), "bearerSource", "电话框架消息 105 → Booster 回调 2001"),
            "收到 SIM " + (slot + 1) + " 的真实承载列表：" + list);
        Session s = active;
        if (s == null || slot != s.slot || terminal(s)) return;
        s.bearers = list; s.bearerAt = received;
        boolean wasActive = s.e.bearerActive;
        s.e.bearerActive = hasActive(list, s.qci);
        s.e.bearerFresh = OutcomePolicy.freshForSession(slot, s.slot, receivedElapsed, s.sentElapsed);
        // An un-timestamped empty native cache is not evidence of prior absence.
        // A fresh inactive report followed by an active one also establishes a transition.
        if (!s.e.baselineKnown && !s.e.baselineActive && !s.activeNow
            && s.e.bearerFresh && !s.e.bearerActive) s.e.baselineKnown = true;
        if (wasActive && !s.e.bearerActive && s.activeNow) s.e.ended = true;
        if (s.e.bearerActive && s.e.bearerFresh) s.activeNow = true;
        emit(s, s.e.bearerActive ? "已观察到匹配的活跃专用承载" : "本次回报无匹配的活跃专用承载");
        if (terminal(s)) releaseMonitor(s);
    }
    private void consumeCommand() {
        try {
            Bundle data = context.getContentResolver().call(Protocol.BRIDGE, "take", null, null);
            if (data == null) return;
            String raw = data.getString("command", "");
            if (raw.isEmpty()) return;
            JSONObject command = new JSONObject(raw);
            if (System.currentTimeMillis() - command.optLong("createdAt") > 30000L) { event("忽略已过期的面板命令"); return; }
            String type = command.getString("type");
            publish(json("lastCommandId", command.optString("id"), "lastCommandType", type,
                "lastCommandAt", System.currentTimeMillis(), "lastCommandError", ""),
                "refresh".equals(type) ? null : "收到命令 " + type + " / " + command.optString("id"));
            if ("refresh".equals(type)) { if (!ready) install(); else refresh(); }
            else if ("stop".equals(type)) stopObservation();
            else if ("request".equals(type)) request(command.getString("id"), command.optBoolean("bypass", true));
        } catch (Throwable t) { publish(json("lastCommandError", describe(t)), "面板命令处理失败：" + describe(t)); }
    }
    private Object state() { return singleton(stateClass); }
    private int slot(Object st) { return (Integer) XposedHelpers.callMethod(st, "getDefaultDataSlotId"); }
    private JSONArray cachedBearers(int slot) {
        try { return listJson((List<?>) XposedHelpers.callMethod(singleton(linkClass), "getDedicatedBearInfoList", slot)); }
        catch (Throwable ignored) { return new JSONArray(); }
    }
    private JSONArray vendorReports() {
        JSONArray reports = new JSONArray();
        for (Map.Entry<Integer, BearerSnapshot> entry : lastReports.entrySet()) {
            BearerSnapshot report = entry.getValue();
            reports.put(json("slot", entry.getKey(), "bearers", report.list, "receivedElapsed", report.elapsed));
        }
        return reports;
    }
    private void refresh() {
        try {
            Object st = state();
            int slot = slot(st);
            int sub = (Integer) XposedHelpers.callStaticMethod(utilsClass, "convertSlotIdToSubId", slot);
            Object phone = XposedHelpers.callStaticMethod(utilsClass, "getTelephonyManager");
            String plmn = (String) XposedHelpers.callMethod(phone, "getSimOperator", sub);
            Object timer = singleton(timerClass);
            Object cache = XposedHelpers.getObjectField(timer, "punishTimerCache");
            Map<?, ?> quotas = (Map<?, ?>) XposedHelpers.getObjectField(timer, "sceneTime");
            JSONArray cached = cachedBearers(slot);
            boolean ipv4Ready = XposedHelpers.callMethod(st, "getCellularIpv4Addr", slot) != null;
            boolean ipv6Ready = XposedHelpers.callMethod(st, "getCellularIpv6Addr", slot) != null;
            JSONObject snapshot = json("backendReady", ready, "backendVersion", version,
                "compatibilityError", compatibilityError, "backendSeenAt", System.currentTimeMillis(),
                "refreshError", "",
                "defaultSlot", slot, "cachedBearers", cached, "cacheReadAt", System.currentTimeMillis(),
                "defaultOperator", OperatorRoute.label(plmn), "defaultPlmn", plmn,
                "managerStarted", XposedHelpers.callMethod(manager, "isStart"),
                "nativeBusy", XposedHelpers.getBooleanField(manager, "isTaskExecuting"),
                "qosPeriod", XposedHelpers.callMethod(st, "getQosPeriodStatus"),
                "dataOn", XposedHelpers.callMethod(st, "isDataOn"),
                "wifi", XposedHelpers.callMethod(st, "isWifiConnected"),
                "vpn", XposedHelpers.callMethod(st, "isVpnModeSwitchOn"),
                "roaming", XposedHelpers.callMethod(st, "isDataRoaming", slot),
                "ipv4Ready", ipv4Ready, "ipv6Ready", ipv6Ready,
                "qosFeatureEnabled", XposedHelpers.callStaticMethod(managerClass, "isQosEnabled"),
                "eligibleBuild", XposedHelpers.callStaticMethod(utilsClass, "isDomesticVersion"),
                "nativeTotalSwitch", XposedHelpers.callStaticMethod(cls("ollie.transportlayer.feature.transportlayerselfcure.dataacceleration.QosAppInfo"), "getSwitchState"),
                "cooldownActive", XposedHelpers.getBooleanField(timer, "isPunishDuration"),
                "cooldownCode", XposedHelpers.getIntField(cache, "error"),
                "cooldownStart", clean(XposedHelpers.getObjectField(cache, "startTime")),
                "cooldownMinutes", XposedHelpers.getIntField(cache, "duration"),
                "baseRemaining", quotas.get("Base"), "manualRemaining", quotas.get("Manual"), "failRemaining", quotas.get("Fail"));
            snapshot.put("commonCloudBudget", commonBudget(singleton(cls("framework.intf.CloudReqTaskExecutor"))));
            snapshot.put("vendorSlots", vendorReports());
            publish(snapshot, null);
        } catch (Throwable t) { publish(json("backendSeenAt", System.currentTimeMillis(), "refreshError", describe(t)), "读取原生状态失败：" + describe(t)); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void request(String id, boolean bypass) {
        if (!ready || !compatibilityError.isEmpty()) {
            publish(json("lastCommandError", "当前后端接口未就绪或版本不匹配"), "未发送：后端接口未就绪或版本不匹配"); return;
        }
        Session old = active;
        if (old != null && !terminal(old)) { publish(json("lastCommandError", "当前请求仍在观察中，未重复提交"), "当前请求仍在观察中，未重复提交"); return; }
        try {
            if (XposedHelpers.getBooleanField(manager, "isTaskExecuting")) { publish(json("lastCommandError", "原生服务已有请求正在执行"), "原生服务已有请求正在执行，未提交新请求"); return; }
            Object st = state();
            int slot = slot(st);
            if (slot < 0 || slot > 1) throw new IllegalStateException("未取得有效的上网卡槽");
            if (!(Boolean) XposedHelpers.callMethod(manager, "isStart")) throw new IllegalStateException("原生 QosManager 未启动");
            if (!(Boolean) XposedHelpers.callMethod(st, "isDataOn")) throw new IllegalStateException("请开启蜂窝数据");
            if ((Boolean) XposedHelpers.callMethod(st, "isWifiConnected")) throw new IllegalStateException("请断开 WLAN 后再申请");
            if ((Boolean) XposedHelpers.callMethod(st, "isVpnModeSwitchOn")) throw new IllegalStateException("请停用 VPN 后再申请");
            if ((Boolean) XposedHelpers.callMethod(st, "isDataRoaming", slot)) throw new IllegalStateException("请在非漫游状态下测试");
            int sub = (Integer) XposedHelpers.callStaticMethod(utilsClass, "convertSlotIdToSubId", slot);
            Object phone = XposedHelpers.callStaticMethod(utilsClass, "getTelephonyManager");
            String plmn = (String) XposedHelpers.callMethod(phone, "getSimOperator", sub);
            InetAddress v4 = (InetAddress) XposedHelpers.callMethod(st, "getCellularIpv4Addr", slot);
            InetAddress v6 = (InetAddress) XposedHelpers.callMethod(st, "getCellularIpv6Addr", slot);
            if (plmn == null || plmn.isEmpty() || v4 == null) throw new IllegalStateException("原生申请所需的 PLMN 或蜂窝 IPv4 尚未就绪");
            int rat;
            if ((Boolean) XposedHelpers.callMethod(st, "isNetworkType4G", slot)) rat = 1;
            else if ((Boolean) XposedHelpers.callMethod(st, "isNetworkTypeSaNsaEnDcActive", slot)) rat = 2;
            else throw new IllegalStateException("当前制式未满足原生 4G/5G 条件");
            boolean telecom = OperatorRoute.needsTelecomToken(plmn);
            if (telecom) {
                Object config = singleton(cls("ollie.intelligent.qos.QosConfig"));
                Map<?, ?> ids = (Map<?, ?>) XposedHelpers.callMethod(config, "getTokenAppid");
                Object appId = ids == null ? null : ids.get("6");
                if (appId == null || String.valueOf(appId).isEmpty()) throw new IllegalStateException("原生电信 QCI 6 AppID 未加载");
            }
            Class<? extends Enum> scene = (Class<? extends Enum>) cls("framework.intf.intelligent.QosConst$Scene");
            Class<? extends Enum> cause = (Class<? extends Enum>) cls("framework.intf.intelligent.QosConst$Cause");
            Constructor<?> ctor = requestClass.getConstructor(String.class, int.class, int.class, scene, cause, String.class, List.class, int.class);
            Object info = ctor.newInstance(plmn, rat, 6, Enum.valueOf(scene, "Manual"), Enum.valueOf(cause, "Scene"),
                v4.getHostAddress(), new ArrayList<String>(), 30);
            XposedHelpers.callMethod(info, "setIpv6", v6 == null ? "" : v6.getHostAddress());
            Session s = new Session(id, bypass);
            s.slot = slot; s.requestInfo = info;
            s.quotaBefore = quotaSnapshot();
            s.preflight = json("slot", slot, "subId", sub, "plmn", plmn, "rat", rat,
                "qciRequested", 6, "scene", "Manual", "cause", "Scene", "durationRequested", 30,
                "ipv4Present", v4 != null, "ipv6Present", v6 != null,
                "managerStarted", true, "dataOn", true, "wifi", false, "vpn", false, "roaming", false,
                "qosPeriod", XposedHelpers.callMethod(st, "getQosPeriodStatus"),
                "capturedAt", System.currentTimeMillis(), "timeZone", java.util.TimeZone.getDefault().getID());
            s.carrier = OperatorRoute.label(plmn); s.plmn = plmn; s.telecomRequired = telecom;
            try {
                s.bearers = listJson((List<?>) XposedHelpers.callMethod(singleton(linkClass), "getDedicatedBearInfoList", slot));
                s.e.baselineActive = hasActive(s.bearers, s.qci);
            } catch (Throwable ignored) { s.e.baselineKnown = false; s.bearers = new JSONArray(); }
            BearerSnapshot previous = lastReports.get(slot);
            s.e.baselineKnown = previous != null && OutcomePolicy.reliableBaseline(previous.elapsed, SystemClock.elapsedRealtime());
            if (s.e.baselineKnown) s.e.baselineActive |= hasActive(previous.list, s.qci);
            s.e.bearerActive = s.e.baselineActive;
            s.telecom = telecom ? "等待电信鉴权" : s.carrier + "：原生直接申请分支，无需电信令牌";
            if (old != null) releaseMonitor(old);
            active = s;
            publish(json("lastCommandError", "", "observedBearers", new JSONArray(), "observedAt", 0,
                "observedSlot", -1), "开始一次原生请求；目标 SIM " + (slot + 1) + " / QCI 6");
            emit(s, s.e.baselineActive ? "请求前缓存含活跃 QCI 6，后续不直接归因于本次请求"
                : s.e.baselineKnown ? "请求前新鲜回报未见匹配的活跃承载"
                : "请求前承载基线未知；原生空缓存不能证明没有已有承载");
            emit(s, "请求前本地计数/冷却：" + s.quotaBefore);
            if ((Boolean) XposedHelpers.callMethod(singleton(timerClass), "hasExistIp", info)) {
                s.e.localCode = 203;
                emit(s, "已存在同类 IP/QCI 的原生请求，保留重复请求保护，未重复发送");
                return;
            }
            try {
                XposedHelpers.callMethod(singleton(linkClass), "reportDedicatedBearRequest", slot, 1);
                s.monitorStarted = true;
            } catch (Throwable t) { event("请求承载监测未启动：" + describe(t)); }
            Handler callback = new Handler(worker.getLooper(), msg -> {
                if (active != s) return true;
                s.e.engineFinished = true;
                if (s.quotaAfter.length() == 0) s.quotaAfter = quotaSnapshot();
                try {
                    Object result = msg.obj;
                    Object nativeCode = result == null ? null : XposedHelpers.getObjectField(result, "userObj");
                    s.nativeResult = nativeCode instanceof Integer ? (Integer) nativeCode : null;
                    emit(s, "原生完成映射结果：" + s.nativeResult + "（不作为云端成功证据）");
                } catch (Throwable t) { event("读取原生完成回调失败：" + describe(t)); }
                return true;
            });
            initiating.set(s);
            s.sentAt = System.currentTimeMillis();
            s.sentElapsed = SystemClock.elapsedRealtime();
            try { XposedHelpers.callMethod(manager, "requestDedicatedBearer", info, callback.obtainMessage(9413)); }
            finally { initiating.remove(); }
            if (s.e.localCode == null) {
                s.e.error = "未观察到原生校验 Hook 命中，无法确认本次绕过；请导出诊断核对 LSPosed 运行版本";
            }
            s.applyId = clean(XposedHelpers.callMethod(info, "getApplyId"));
            emit(s, "已调用原生 requestDedicatedBearer；校验返回 " + s.e.localCode);
            worker.postDelayed(() -> monitor(s), 2000L);
        } catch (Throwable t) {
            Session s = active;
            if (s != null && s.id.equals(id)) { s.e.error = describe(t); emit(s, "请求异常：" + s.e.error); releaseMonitor(s); }
            else publish(json("lastCommandError", describe(t)), "未发送：" + describe(t));
        }
    }
    private boolean terminal(Session s) {
        OutcomePolicy.Result r = OutcomePolicy.decide(s.e);
        return r == OutcomePolicy.Result.ERROR || r == OutcomePolicy.Result.LOCAL_REJECTED
            || r == OutcomePolicy.Result.COMMON_LOCAL_REJECTED
            || r == OutcomePolicy.Result.HTTP_REJECTED
            || r == OutcomePolicy.Result.TELECOM_FAILED || r == OutcomePolicy.Result.CLOUD_REJECTED
            || r == OutcomePolicy.Result.BEARER_TIMEOUT || r == OutcomePolicy.Result.ENDED;
    }
    private void monitor(Session s) {
        if (active != s) return;
        long elapsed = SystemClock.elapsedRealtime() - s.startedElapsed;
        if (elapsed >= 120000L && !(s.e.bearerFresh && s.e.bearerActive && s.e.cloudReceived && Integer.valueOf(200).equals(s.e.cloudCode))) s.e.timedOut = true;
        refresh();
        emit(s, null);
        if (terminal(s) || (s.e.timedOut && !s.e.cloudReceived)) { releaseMonitor(s); return; }
        if (elapsed > s.duration * 60000L + 120000L) {
            s.e.ended = true;
            emit(s, "观察时段结束；最终网络状态以系统回报为准");
            releaseMonitor(s); return;
        }
        worker.postDelayed(() -> monitor(s), elapsed < 120000L ? 2000L : 10000L);
    }
    private void stopObservation() {
        Session s = active;
        if (s != null) { s.e.ended = true; emit(s, "已停止本次观察；已提交的请求由原生服务继续处理"); releaseMonitor(s); }
    }
    private void releaseMonitor(Session s) {
        if (!s.monitorStarted) return;
        try {
            JSONArray cached = listJson((List<?>) XposedHelpers.callMethod(singleton(linkClass), "getDedicatedBearInfoList", s.slot));
            boolean anyActive = false;
            for (int i = 0; i < cached.length(); i++) {
                JSONObject b = cached.optJSONObject(i);
                if (b != null && b.optInt("type") == 2 && b.optInt("state") == 1) anyActive = true;
            }
            if (anyActive || (Boolean) XposedHelpers.callMethod(manager, "isDBWorking", s.slot)) return;
            s.monitorStarted = false;
            XposedHelpers.callMethod(singleton(linkClass), "reportDedicatedBearRequest", s.slot, 2);
        }
        catch (Throwable t) { event("结束承载监测失败：" + describe(t)); }
    }
    private void emit(Session s, String event) {
        if (active != s) return;
        publish(json("sessionId", s.id, "startedAt", s.startedAt, "requestSlot", s.slot, "requestedQci", s.qci,
            "requestSentAt", s.sentAt, "operator", s.carrier, "plmn", s.plmn, "telecomRequired", s.telecomRequired,
            "requestedMinutes", 30, "returnedMinutes", s.returnedDuration, "bypass", s.bypass, "localCode", s.e.localCode,
            "qosBypassApplied", s.localBypassApplied,
            "engineFinished", s.e.engineFinished, "nativeResult", s.nativeResult, "cloudReceived", s.e.cloudReceived,
            "cloudCode", s.e.cloudCode, "cloudTransport", s.transport, "cloudMessage", s.msg, "remoteMessage", s.remoteMsg,
            "cloudRaw", s.cloudRaw, "telecom", s.telecom, "telecomHttp", s.telecomHttp,
            "telecomErrorCode", s.telecomErrorCode, "telecomErrorMessage", s.telecomErrorMessage,
            "sessionBearers", s.bearers, "bearerAt", s.bearerAt, "bearerFresh", s.e.bearerFresh,
            "bearerActive", s.e.bearerActive, "baselineActive", s.e.baselineActive, "timedOut", s.e.timedOut,
            "baselineKnown", s.e.baselineKnown,
            "ended", s.e.ended, "error", s.e.error, "applyId", s.applyId,
            "verdict", OutcomePolicy.decide(s.e).name(), "sessionOpen", !terminal(s),
            "requestAudit", s.requestAudit, "httpResponse", s.httpResponse, "httpStatus", s.e.httpStatus,
            "commonLocalCode", s.e.commonLocalCode,
            "quotaBefore", s.quotaBefore, "quotaAfter", s.quotaAfter, "commonCloudChecks", s.commonChecks,
            "preflight", s.preflight,
            "moduleVersion", Protocol.VERSION, "backendSeenAt", System.currentTimeMillis()),
            event == null ? null : "[" + s.id.substring(0, 8) + "] " + event);
    }
    private void event(String value) { publish(new JSONObject(), value); }
    private void publish(JSONObject patch, String event) {
        try {
            Bundle b = new Bundle(); b.putString("patch", patch.toString());
            if (event != null) b.putString("event", clean(event));
            Bundle result = context.getContentResolver().call(Protocol.BRIDGE, "publish", null, b);
            if (result != null && result.containsKey("error")) XposedBridge.log("FastPass bridge: " + result.getString("error"));
        } catch (Throwable t) { XposedBridge.log("FastPass bridge unavailable: " + t.getClass().getSimpleName()); }
    }
    private static JSONObject json(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) try { o.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]); } catch (Exception ignored) {}
        return o;
    }
    private static String describe(Throwable t) {
        Throwable value = t;
        for (int i = 0; i < 4 && value.getCause() != null; i++) value = value.getCause();
        return clean(value.getClass().getSimpleName() + ": " + value.getMessage());
    }
}
