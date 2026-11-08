package dev.codex.fastpass;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG = 0xff0d1723, CARD = 0xff172636, TEXT = 0xffe8f0f7;
    private static final int MUTED = 0xff9fb0c0, MINT = 0xff55e0bc, AMBER = 0xffffca7b, RED = 0xffff969b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            readSnapshot();
            if (System.currentTimeMillis() - lastPing > 15000L) { lastPing = System.currentTimeMillis(); command("refresh", false); }
            handler.postDelayed(this, 1000L);
        }
    };
    private TextView connection, verdict, explanation, local, cloud, bearer, raw, events, latestError, radio, audit;
    private Button request;
    private CheckBox bypass;
    private JSONObject lastState = new JSONObject();
    private JSONArray lastEvents = new JSONArray();
    private JSONArray requestHistory = new JSONArray();
    private String pendingId = "", displayFingerprint = "";
    private long pendingAt;
    private long lastPing;
    private boolean exportPending;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { pendingId = saved.getString("pendingId", ""); pendingAt = saved.getLong("pendingAt", 0); }
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(0, bars.top, 0, bars.bottom); return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(column); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); setContentView(root);
        TextView eyebrow = text("HONOR FAST PASS  /  OBSERVER", 11, MINT); eyebrow.setLetterSpacing(.12f); column.addView(eyebrow);
        TextView title = text("优速通观察台", 29, TEXT); title.setTypeface(null, Typeface.BOLD); margin(column, title, 8, 4);
        TextView subtitle = text("一次真实申请，逐层查看结果", 14, MUTED); margin(column, subtitle, 0, 18);

        LinearLayout status = card(column, "系统连接");
        connection = body(status, 14);
        latestError = body(status, 13); latestError.setTextColor(RED);
        Button refresh = button("刷新系统状态", false); status.addView(refresh); refresh.setOnClickListener(v -> command("refresh", true));

        LinearLayout radioCard = card(column, "本机各卡槽真实 QoS（独立读取）");
        radio = body(radioCard, 14); radio.setTextIsSelectable(true);
        Button radioRefresh = button("读取本机 QCI / 5QI", false); radioCard.addView(radioRefresh);
        radioRefresh.setOnClickListener(v -> refreshRadio());

        LinearLayout result = card(column, "本次加速承载判定");
        verdict = text("等待开始", 24, MUTED); verdict.setTypeface(null, Typeface.BOLD); result.addView(verdict);
        explanation = body(result, 14);

        LinearLayout actions = card(column, "原生申请");
        TextView description = body(actions, 14);
        description.setText("使用当前上网卡申请 QCI 6 专用承载，建议时长 30 分钟。请使用 4G/5G 蜂窝网络，断开 WLAN、停用 VPN，并处于非漫游状态。");
        bypass = new CheckBox(this); bypass.setText("仅本次绕过 QoS 冷却与场景次数"); bypass.setTextColor(TEXT); bypass.setTextSize(14);
        bypass.setButtonTintList(android.content.res.ColorStateList.valueOf(MINT)); bypass.setChecked(getPreferences(0).getBoolean("bypass", true));
        bypass.setOnCheckedChangeListener((b, checked) -> getPreferences(0).edit().putBoolean("bypass", checked).apply()); actions.addView(bypass);
        request = button("发送一次加速请求", true); actions.addView(request); request.setOnClickListener(v -> command("request", true));
        Button stop = button("停止本次观察", false); actions.addView(stop); stop.setOnClickListener(v -> command("stop", true));
        Button nativePage = button("打开原生荣耀优速通页面", false); actions.addView(nativePage);
        nativePage.setOnClickListener(v -> {
            try { startActivity(new Intent("android.settings.EXPRESS_APP_SETTINGS").setPackage(Protocol.SETTINGS)); }
            catch (Throwable t) { toast("未找到原生页面：" + t.getClass().getSimpleName()); }
        });

        local = body(card(column, "本机条件与原生计数"), 14);
        cloud = body(card(column, "运营商处理与云端返回"), 14);
        bearer = body(card(column, "本次申请的专用承载证据"), 14);
        audit = body(card(column, "实际请求与本地计数审计"), 12); audit.setTypeface(Typeface.MONOSPACE); audit.setTextIsSelectable(true);
        raw = body(card(column, "云端响应字段"), 12); raw.setTypeface(Typeface.MONOSPACE);
        raw.setTextIsSelectable(true);
        events = body(card(column, "事件记录"), 12); events.setTextIsSelectable(true);

        LinearLayout diagnostics = card(column, "诊断与启用说明");
        Button export = button("导出诊断文件", false); diagnostics.addView(export); export.setOnClickListener(v -> export());
        Button copy = button("复制诊断信息", false); diagnostics.addView(copy);
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("优速通诊断", diagnostic()));
            toast("已复制");
        });
        TextView help = body(diagnostics, 13);
        help.setText("在 LSPosed 启用本模块，作用域勾选「系统服务」com.hihonor.systemserver、「设置」com.android.settings，以及「电话服务」com.android.phone，然后重启手机。\n\n本机 QoS 通过电话进程只读查询 RIL 数据连接列表。框架没有返回的 QCI/5QI 保留未知；每个数据连接可能有自己的默认 QoS 和多个会话。\n\n申请仅绕过 QoS 冷却和场景次数；公共云接口每日次数、协议同意、并发和签名检查仍沿用原生逻辑。诊断记录真实发送层参数及两类本地计数。\n\n版本 " + Protocol.VERSION + " · 系统服务 70.1.0.1 / Android 16");
        connection.setText("等待系统服务响应，请确认 LSPosed 作用域并重启。");
        explanation.setText("云端业务码 200 + 本次请求后对应卡槽的活跃专用承载回报，才判定为已确认成功。");
    }
    @Override protected void onResume() {
        super.onResume(); handler.post(poll); handler.postDelayed(() -> command("refresh", false), 350L);
    }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("pendingId", pendingId); out.putLong("pendingAt", pendingAt); super.onSaveInstanceState(out);
    }
    private void command(String type, boolean showError) {
        try {
            Bundle args = new Bundle(); args.putBoolean("bypass", bypass.isChecked());
            Bundle result = getContentResolver().call(Protocol.BRIDGE, "enqueue", type, args);
            if (result == null || result.containsKey("error")) throw new IllegalStateException(result == null ? "本机控制桥未返回" : result.getString("error"));
            if ("request".equals(type)) { pendingId = result.getString("id", ""); pendingAt = System.currentTimeMillis(); }
            Intent i = new Intent(Protocol.ACTION).setPackage(Protocol.TARGET);
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            sendBroadcast(i);
            if ("refresh".equals(type)) refreshRadio();
            readSnapshot();
        } catch (Throwable t) { if (showError) toast(String.valueOf(t.getMessage())); }
    }
    private void refreshRadio() {
        Intent i = new Intent(Protocol.RADIO_ACTION).setPackage(Protocol.PHONE);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        sendBroadcast(i);
    }
    private void readSnapshot() {
        try {
            Bundle b = getContentResolver().call(Protocol.BRIDGE, "snapshot", null, null);
            if (b == null) return;
            lastState = new JSONObject(b.getString("state", "{}"));
            lastEvents = new JSONArray(b.getString("events", "[]"));
            requestHistory = new JSONArray(b.getString("history", "[]"));
            String fingerprint = lastState.toString() + pendingId + (System.currentTimeMillis() / 5000L);
            if (!fingerprint.equals(displayFingerprint)) { displayFingerprint = fingerprint; render(); }
        } catch (Throwable t) { connection.setText("读取面板状态失败：" + t.getClass().getSimpleName()); }
    }
    private void render() throws Exception {
        JSONObject s = lastState;
        long seen = s.optLong("backendSeenAt", 0);
        boolean ready = s.optBoolean("backendReady", false) && seen > 0 && System.currentTimeMillis() - seen < 120000L;
        String mismatch = value(s, "compatibilityError");
        if (ready && !Protocol.VERSION.equals(s.optString("moduleVersion"))) mismatch = "模块后端版本尚未更新，请重启手机";
        connection.setText((ready ? "已连接原生系统服务" : "等待 LSPosed 后端")
            + "\n后端版本：" + value(s, "backendVersion") + "\n模块后端：" + value(s, "moduleVersion")
            + "\n最近响应：" + time(seen));
        connection.setTextColor(ready && mismatch.isEmpty() ? MINT : AMBER);
        String commandError = value(s, "lastCommandError");
        if (!pendingId.isEmpty() && pendingId.equals(s.optString("sessionId"))) pendingId = "";
        if (!pendingId.isEmpty() && System.currentTimeMillis() - pendingAt > 30000L) {
            commandError = "请求未在 30 秒内由后端接收，请检查作用域和系统服务连接"; pendingId = "";
        }
        if (!commandError.isEmpty() && s.optLong("lastCommandAt", 0) >= pendingAt) pendingId = "";
        String errors = mismatch;
        if (!commandError.isEmpty()) errors += (errors.isEmpty() ? "" : "\n") + commandError;
        if (!value(s, "refreshError").isEmpty()) errors += (errors.isEmpty() ? "" : "\n") + value(s, "refreshError");
        latestError.setText(errors); latestError.setVisibility(errors.isEmpty() ? View.GONE : View.VISIBLE);
        request.setEnabled(ready && mismatch.isEmpty() && pendingId.isEmpty() && !s.optBoolean("sessionOpen", false));

        String code = s.optString("verdict", "IDLE");
        String headline, detail;
        int color = AMBER;
        switch (code) {
            case "CONFIRMED": headline = "已确认承载激活"; color = MINT;
                detail = "本次云端原始业务码为 200，且对应卡槽在请求之后回报了类型 2、QCI 6、状态 1 的专用承载。"; break;
            case "ACTIVE_PREEXISTING": headline = "承载活跃，归因未确认";
                detail = "云端返回 200，已收到活跃承载回报；但请求前已有匹配承载，或无法可靠读取请求前状态，不能把效果归因于本次申请。"; break;
            case "LOCAL_REJECTED": headline = "本地校验拒绝：" + number(s, "localCode"); color = RED;
                detail = localMeaning(s.optInt("localCode", 0)); break;
            case "COMMON_LOCAL_REJECTED": headline = "公共接口本地拦截：" + number(s, "commonLocalCode"); color = RED;
                detail = s.optInt("commonLocalCode") == 101 ? "业务 1004 的本地每日调用上限已触发，本次没有进入 HTTP 发送。"
                    : s.optInt("commonLocalCode") == 102 ? "原生公共接口的协议同意检查未通过，本次没有发给云端。"
                    : "原生公共接口正在处理同业务请求，本次未重复提交。"; break;
            case "TELECOM_FAILED": headline = "电信鉴权失败"; color = RED;
                detail = "原生服务未获得电信令牌，尚未完成后续荣耀云端申请。"; break;
            case "HTTP_REJECTED": headline = "云接口 HTTP 非成功：" + number(s, "httpStatus"); color = RED;
                detail = "这是实际 HTTP 层结果；没有把本地完成映射当作云端成功。"; break;
            case "CLOUD_REJECTED": headline = "云端非成功结果：" + number(s, "cloudCode"); color = RED;
                detail = s.optInt("cloudCode") == 202 ? "原生定义为超出服务有效期。具体是哪项权益或策略，当前云端消息没有说明；本地绕过不能改变该返回。"
                    : "展示原始业务返回码及消息。"; break;
            case "TRANSPORT_UNCONFIRMED": headline = "云端响应未确认";
                detail = "原生申请流程已回调，但没有确认的原始云端响应。即使本地完成结果为 1，也不会显示成功。"; break;
            case "BEARER_TIMEOUT": headline = "承载确认超时"; color = RED;
                detail = "120 秒内未同时取得本次云端成功及新的匹配承载回报，未判定成功。"; break;
            case "ENDED": headline = "本次观察已结束"; color = MUTED;
                detail = "保留已获取的原始证据；当前承载状态可通过刷新或新的底层回报查看。"; break;
            case "ERROR": headline = "请求处理异常"; color = RED; detail = value(s, "error"); break;
            case "PREPARING": headline = "正在准备原生请求"; detail = "读取当前上网卡与蜂窝地址，构造原生 QCI 6 请求。"; break;
            case "WAITING": headline = "等待云端 / 承载回报"; detail = "申请已经过本地检查，等待原始云端结果与底层承载证据。"; break;
            default: headline = "等待开始"; color = MUTED; detail = "开启模块并刷新连接后，可发送一次真实申请。";
        }
        if (!pendingId.isEmpty()) { headline = "等待系统接收本次命令"; detail = "当前尚未把旧请求结果作为这次请求的结果。"; color = AMBER; }
        else if (!commandError.isEmpty() && s.optLong("lastCommandAt", 0) >= s.optLong("startedAt", 0)) {
            headline = "本次操作未发送"; detail = commandError; color = RED;
        }
        verdict.setText(headline); verdict.setTextColor(color);
        explanation.setText(detail + (s.optLong("startedAt", 0) > 0 ? "\n请求时间：" + time(s.optLong("startedAt")) : ""));

        local.setText("上网卡槽：" + sim(s.optInt("defaultSlot", -1))
            + "\n当前运营商：" + value(s, "defaultOperator") + " / PLMN " + value(s, "defaultPlmn")
            + "\n原生 QosManager：" + yes(s, "managerStarted")
            + "\n产品 QoS 开关：" + yes(s, "qosFeatureEnabled") + "   本地版本检查：" + yes(s, "eligibleBuild")
            + "\n原生页面总开关：" + number(s, "nativeTotalSwitch") + "   原生任务忙碌：" + yes(s, "nativeBusy")
            + "\n蜂窝数据：" + yes(s, "dataOn") + "   WLAN：" + yes(s, "wifi") + "   VPN：" + yes(s, "vpn")
            + "\n漫游：" + yes(s, "roaming") + "   IPv4 已就绪：" + yes(s, "ipv4Ready") + "   IPv6：" + yes(s, "ipv6Ready")
            + "\n本地服务期状态：" + number(s, "qosPeriod")
            + "\n冷却状态：" + yes(s, "cooldownActive") + " / 错误码 " + number(s, "cooldownCode")
            + "\n冷却记录起点：" + value(s, "cooldownStart") + " / " + number(s, "cooldownMinutes") + " 分钟"
            + "\n当前基础剩余：" + number(s, "baseRemaining") + "   手动剩余：" + number(s, "manualRemaining")
            + "\n失败池剩余：" + number(s, "failRemaining")
            + "\n本次校验码：" + number(s, "localCode") + "   申请绕过：" + yes(s, "bypass")
            + "\n实际应用 QoS 单次绕过：" + yes(s, "qosBypassApplied"));
        cloud.setText("运营商：" + value(s, "operator") + "   PLMN：" + value(s, "plmn")
            + "\n申请卡槽：" + sim(s.optInt("requestSlot", -1))
            + "\n运营商分支：" + value(s, "telecom")
            + "\n电信 HTTP：" + number(s, "telecomHttp")
            + "\n电信错误码：" + value(s, "telecomErrorCode") + "\n电信错误消息：" + value(s, "telecomErrorMessage")
            + "\n实际云端响应：" + yes(s, "cloudReceived") + "\n云端原始业务码：" + number(s, "cloudCode")
            + "\n荣耀接口 HTTP：" + number(s, "httpStatus")
            + "\nmsg：" + value(s, "cloudMessage") + "\nremoteMessage：" + value(s, "remoteMessage")
            + "\n网络传输结果：" + value(s, "cloudTransport")
            + "\n原生完成映射：" + number(s, "nativeResult") + "（仅供参考）"
            + "\n请求时长：30 分钟 / 返回时长：" + number(s, "returnedMinutes") + " 分钟");
        JSONArray list = s.optJSONArray("observedBearers");
        String source = "真实电话回报";
        long reportAt = s.optLong("observedAt", 0);
        int observedSlot = s.optInt("observedSlot", -1);
        if (!value(s, "sessionId").isEmpty()) {
            list = s.optJSONArray("sessionBearers"); reportAt = s.optLong("bearerAt", 0); observedSlot = s.optInt("requestSlot", -1);
            source = s.optBoolean("bearerFresh") ? "本次申请卡槽的新电话回报" : "请求前缓存，本次尚无对应卡槽新回报";
        } else if (reportAt == 0 || observedSlot != s.optInt("defaultSlot", -1)) {
            list = s.optJSONArray("cachedBearers"); source = "当前上网卡原生缓存，不能单独归因于本次请求"; observedSlot = s.optInt("defaultSlot", -1); reportAt = 0;
        }
        StringBuilder bs = new StringBuilder("来源：").append(source).append("\n卡槽：").append(sim(observedSlot));
        bs.append("\n回报时间：").append(time(reportAt));
        if (list == null || list.length() == 0) bs.append("\n尚无可显示的承载条目，真实 QCI 未知");
        else for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            bs.append("\n\n承载 ").append(i + 1).append("   QCI = ").append(item.optInt("qci", -1))
                .append("\n类型 = ").append(item.optInt("type", -1)).append("   状态 = ").append(item.optInt("state", -1));
        }
        bs.append("\n\n判定目标：类型 2 / QCI 6 / 状态 1")
            .append("\n本次收到新回报：").append(yes(s, "bearerFresh"))
            .append("\n本次匹配承载活跃：").append(yes(s, "bearerActive"));
        bearer.setText(bs.toString());
        renderRadio(s);
        JSONObject actual = s.optJSONObject("requestAudit");
        JSONObject auditView = new JSONObject().put("actualHttpRequest", actual == null ? new JSONObject() : actual)
            .put("preflight", s.optJSONObject("preflight"))
            .put("qosQuotaBefore", s.optJSONObject("quotaBefore")).put("qosQuotaAfter", s.optJSONObject("quotaAfter"))
            .put("commonCloudChecks", s.optJSONObject("commonCloudChecks"))
            .put("commonCloudBudgetNow", s.optJSONObject("commonCloudBudget"));
        audit.setText((actual != null && actual.optBoolean("captured") ? "已捕获本次实际 HTTP 对象（令牌、设备标识和 IP 已脱敏）"
            : "尚未到实际 HTTP 发送层；不能用构造参数冒充已发送参数")
            + "\n\n" + auditView.toString(2)
            + "\n\n通用调用次数另有 CHR 统计落库，云端是否使用统计无法由客户端证明。云端也可独立按设备、令牌、IP 等校验权益和频次。");
        String rawValue = value(s, "cloudRaw");
        raw.setText(new JSONObject().put("httpEvidence", s.optJSONObject("httpResponse"))
            .put("nativeParsedResponse", rawValue.isEmpty() ? JSONObject.NULL : new JSONObject(rawValue)).toString(2));
        StringBuilder log = new StringBuilder();
        for (int i = lastEvents.length() - 1; i >= Math.max(0, lastEvents.length() - 70); i--) {
            JSONObject e = lastEvents.getJSONObject(i);
            log.append(time(e.optLong("time"))).append("  ").append(e.optString("text")).append("\n\n");
        }
        events.setText(log.length() == 0 ? "等待事件" : log.toString());
    }
    private void renderRadio(JSONObject s) {
        StringBuilder out = new StringBuilder();
        long seen = s.optLong("radioSeenAt");
        if (seen == 0 || !s.optBoolean("radioReady")) {
            out.append("电话进程尚未就绪。请在 LSPosed 额外勾选 com.android.phone，重启后读取。\n");
        } else out.append("电话进程最近响应：").append(time(seen)).append("\n");
        if (!value(s, "radioError").isEmpty()) out.append("读取状态：").append(value(s, "radioError")).append("\n");
        JSONArray slots = s.optJSONArray("radioSlots");
        String activeIface = s.optString("radioActiveCellularInterface", "");
        if (slots != null) for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i); if (slot == null) continue;
            out.append("\n").append(sim(slot.optInt("slot", -1))).append(" · ").append(slot.optString("source", ""))
                .append("\n回报时间：").append(time(slot.optLong("receivedAt")));
            if (slot.optBoolean("pending")) out.append("\n正在执行只读查询；下方为此前回报");
            if (!slot.optString("error", "").isEmpty()) out.append("\n此次读取：").append(slot.optString("error")).append("；下方保留上次回报");
            JSONArray calls = slot.optJSONArray("calls");
            if (calls == null || calls.length() == 0) { out.append("\n未收到数据连接条目，QCI/5QI 未知\n"); continue; }
            for (int n = 0; n < calls.length(); n++) {
                JSONObject call = calls.optJSONObject(n); if (call == null) continue;
                String iface = call.optString("interface", "");
                out.append("\n\n连接 CID ").append(number(call, "cid")).append(" · ").append(iface)
                    .append(!activeIface.isEmpty() && activeIface.equals(iface) ? "（系统默认蜂窝接口）" : "")
                    .append("\n连接状态：").append(number(call, "linkStatus")).append(" / cause ").append(number(call, "cause"))
                    .append("\n该连接默认 QoS：").append(qosText(call.optJSONObject("defaultQos")));
                JSONArray sessions = call.optJSONArray("qosSessions");
                if (sessions != null) for (int q = 0; q < sessions.length(); q++) {
                    JSONObject session = sessions.optJSONObject(q); if (session != null) out.append("\n会话 ")
                        .append(number(session, "sessionId")).append("：").append(qosText(session.optJSONObject("qos")));
                }
                if (!call.optBoolean("sessionsApiAvailable")) out.append("\nQoS 会话接口不可用");
                if (sessions != null && call.optInt("qosSessionsTotal") > sessions.length()) out.append("\n会话数量超过展示上限，仅显示前 8 条");
            }
            out.append("\n");
            if (slot.optInt("callsTotal") > calls.length()) out.append("数据连接超过展示上限，仅显示前 16 条\n");
        }
        JSONArray vendor = s.optJSONArray("vendorSlots");
        out.append("\n荣耀专用承载消息 105 / 2001：");
        if (vendor == null || vendor.length() == 0) out.append("尚无回报");
        else for (int i = 0; i < vendor.length(); i++) {
            JSONObject v = vendor.optJSONObject(i); if (v == null) continue;
            long age = SystemClock.elapsedRealtime() - v.optLong("receivedElapsed");
            out.append("\n").append(sim(v.optInt("slot", -1))).append(" · ").append(age >= 0 ? age / 1000 + " 秒前" : "旧启动记录")
                .append("\n").append(v.optJSONArray("bearers"));
        }
        out.append("\n\nQCI/5QI 由网络分配，手机读取。同一卡可有多个数据连接；LTE QCI 与 NR 5QI 分别展示。未返回字段时不推测数值。");
        radio.setText(out.toString());
    }
    private static String qosText(JSONObject q) {
        if (q == null) return "未知";
        if (!q.optBoolean("available")) return "未知（" + q.optString("reason", "未提供") + (q.has("value") ? "，原值 " + q.opt("value") : "") + "）";
        return ("NR_5QI".equals(q.optString("kind")) ? "NR 5QI = " : "LTE QCI = ") + q.optInt("value");
    }
    private static String value(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }
    private static String number(JSONObject o, String key) { String s = value(o, key); return s.isEmpty() ? "未知" : s; }
    private static String yes(JSONObject o, String key) { return !o.has(key) || o.isNull(key) ? "未知" : o.optBoolean(key) ? "是" : "否"; }
    private static String sim(int slot) { return slot < 0 ? "未知" : "SIM " + (slot + 1); }
    private static String time(long t) { return t <= 0 ? "尚无记录" : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(t)); }
    private static String localMeaning(int code) {
        switch (code) {
            case 101: return "原生 QoS 服务未启动。";
            case 105: return "本地当日次数检查未通过。";
            case 106: return "原生请求参数无效，未提交云端。";
            case 107: return "原生服务已有其它请求，保留并发保护。";
            case 202: return "本地仍在保存的 202 冷却状态；可核对本次绕过是否开启。";
            case 203: return "原生服务已记录同类 IP/QCI 请求，保留重复请求保护。";
            default: return "原生本地校验返回 " + code + "，未当作云端返回码。";
        }
    }
    private String diagnostic() {
        try { return new JSONObject().put("app", "FastPass Observer " + Protocol.VERSION)
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("exportedAt", System.currentTimeMillis()).put("timeZone", java.util.TimeZone.getDefault().getID())
            .put("elapsedRealtime", SystemClock.elapsedRealtime()).put("state", lastState)
            .put("requestHistory", requestHistory).put("events", lastEvents).toString(2); }
        catch (Exception e) { return "Unable to build diagnostics: " + e; }
    }
    private void export() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_TITLE, "fastpass-diagnostic-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt");
            exportPending = true; startActivityForResult(i, 42);
        } catch (Throwable t) { toast("无法打开保存位置：" + t.getClass().getSimpleName()); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IllegalStateException("无法写入所选文件");
                out.write(diagnostic().getBytes(StandardCharsets.UTF_8)); toast("诊断文件已保存");
            } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
        }
        exportPending = false;
    }
    private LinearLayout card(LinearLayout parent, String name) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(16), dp(17), dp(16)); card.setBackground(shape(CARD, 17));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); parent.addView(card, p);
        TextView label = text(name, 12, MUTED); label.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10); card.addView(label, lp); return card;
    }
    private TextView body(LinearLayout parent, int size) {
        TextView t = text("", size, TEXT); t.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(9); parent.addView(t, p); return t;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(primary ? BG : TEXT); b.setBackgroundTintList(null);
        b.setBackground(shape(primary ? MINT : 0xff22384c, 11)); b.setMinHeight(dp(46));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(8); b.setLayoutParams(p); return b;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void margin(LinearLayout p, View v, int top, int bottom) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(top); l.bottomMargin = dp(bottom); p.addView(v, l); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
