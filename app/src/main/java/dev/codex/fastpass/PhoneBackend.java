package dev.codex.fastpass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only RIL data-call queries in the phone process. No acceleration or modem writes. */
public final class PhoneBackend {
    private static PhoneBackend instance;
    private final Context context;
    private final ClassLoader loader;
    private final Handler worker;
    private final List<Endpoint> endpoints = new ArrayList<>();
    private int attempts;
    private static final class Endpoint {
        int slot, serial;
        long lastQuery;
        Object ci;
        boolean registered, pending;
        JSONObject row;
    }
    private static final class Ticket {
        final Endpoint endpoint;
        final int serial;
        Ticket(Endpoint endpoint, int serial) { this.endpoint = endpoint; this.serial = serial; }
    }
    public static synchronized void attach(Context c, ClassLoader loader) {
        if (instance != null) return;
        try { instance = new PhoneBackend(c, loader); }
        catch (Throwable t) { XposedBridge.log("FastPass phone attach: " + t.getClass().getSimpleName()); }
    }
    private PhoneBackend(Context c, ClassLoader cl) {
        context = c; loader = cl;
        HandlerThread t = new HandlerThread("FastPassRadioObserver"); t.start();
        worker = new Handler(t.getLooper(), this::onReply);
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                worker.post(() -> { if (endpoints.isEmpty()) discover(); else refresh(); });
            }
        }, new IntentFilter(Protocol.RADIO_ACTION), Protocol.PERMISSION, worker, Context.RECEIVER_EXPORTED);
        publish("", "电话进程观察器已连接，等待 RIL 数据连接接口");
        worker.post(this::discover);
    }
    private void discover() {
        attempts++;
        try {
            Class<?> factory = XposedHelpers.findClass("com.android.internal.telephony.PhoneFactory", loader);
            Object[] phones = (Object[]) XposedHelpers.callStaticMethod(factory, "getPhones");
            if (phones == null || phones.length == 0) throw new IllegalStateException("电话框架尚未创建 Phone 对象");
            for (Object phone : phones) {
                if (phone == null || endpoints.size() >= 4) continue;
                Endpoint e = new Endpoint();
                e.slot = ((Number) XposedHelpers.callMethod(phone, "getPhoneId")).intValue();
                boolean exists = false;
                for (Endpoint old : endpoints) if (old.slot == e.slot) exists = true;
                if (exists) continue;
                e.ci = XposedHelpers.getObjectField(phone, "mCi");
                if (e.ci == null) continue;
                e.row = WireAudit.json("slot", e.slot, "calls", new JSONArray(), "receivedAt", 0, "source", "尚无回报");
                try { XposedHelpers.callMethod(e.ci, "registerForDataCallListChanged", worker, 2, e); e.registered = true; }
                catch (Throwable t) { e.row.put("passiveError", describe(t)); }
                endpoints.add(e);
            }
            if (endpoints.isEmpty()) throw new IllegalStateException("未取得 RIL CommandsInterface");
            publish("", "已连接本机 RIL，读取各卡槽数据连接的默认 QoS 与 QoS 会话");
            refresh();
        } catch (Throwable t) {
            publish(describe(t), null);
            if (attempts < 15) worker.postDelayed(this::discover, 2000L);
        }
    }
    private void refresh() {
        for (Endpoint e : endpoints) {
            if (e.pending || SystemClock.elapsedRealtime() - e.lastQuery < 3000L) continue;
            e.lastQuery = SystemClock.elapsedRealtime(); e.pending = true;
            Ticket ticket = new Ticket(e, ++e.serial);
            try {
                e.row.put("pending", true).put("queryAt", System.currentTimeMillis());
                XposedHelpers.callMethod(e.ci, "getDataCallList", worker.obtainMessage(1, ticket));
                worker.postDelayed(() -> {
                    if (e.pending && e.serial == ticket.serial) {
                        e.pending = false;
                        try { e.row.put("pending", false).put("error", "RIL 查询 12 秒未回调；现有条目是上次回报"); } catch (Exception ignored) {}
                        publish("", "SIM " + (e.slot + 1) + " 数据连接查询超时");
                    }
                }, 12000L);
            } catch (Throwable t) {
                e.pending = false;
                try { e.row.put("pending", false).put("error", describe(t)); } catch (Exception ignored) {}
            }
        }
        publish("", null);
    }
    private boolean onReply(Message msg) {
        if (msg.what != 1 && msg.what != 2) return false;
        try {
            Object ar = msg.obj;
            Object cookie = XposedHelpers.getObjectField(ar, "userObj");
            Endpoint e;
            if (cookie instanceof Ticket) {
                Ticket ticket = (Ticket) cookie; e = ticket.endpoint;
                if (ticket.serial != e.serial || !e.pending) return true;
                e.pending = false;
            } else if (cookie instanceof Endpoint) e = (Endpoint) cookie;
            else return true;
            Object error = XposedHelpers.getObjectField(ar, "exception");
            Object result = XposedHelpers.getObjectField(ar, "result");
            if (error != null || !(result instanceof List)) {
                e.row.put("pending", false).put("error", error instanceof Throwable ? describe((Throwable) error) : "RIL 未返回数据连接列表");
                publish("", "SIM " + (e.slot + 1) + " 查询失败，未推测 QCI");
                return true;
            }
            JSONArray calls = new JSONArray();
            for (Object call : (List<?>) result) {
                if (calls.length() >= 16) break;
                if (call == null) continue;
                calls.put(RadioEvidence.dataCall(call, (target, method) -> XposedHelpers.callMethod(target, method)));
            }
            e.row = WireAudit.json("slot", e.slot, "receivedAt", System.currentTimeMillis(),
                "receivedElapsed", SystemClock.elapsedRealtime(), "calls", calls, "pending", e.pending,
                "callsTotal", ((List<?>) result).size(),
                "error", "", "passiveRegistered", e.registered,
                "source", msg.what == 1 ? "RIL.getDataCallList 主动只读查询" : "RIL 数据连接变化回报");
            publish("", "SIM " + (e.slot + 1) + " 数据连接回报：" + calls.length() + " 条");
        } catch (Throwable t) { publish(describe(t), "电话回报解析失败"); }
        return true;
    }
    private void publish(String error, String event) {
        try {
            JSONArray slots = new JSONArray();
            for (Endpoint e : endpoints) slots.put(e.row);
            String iface = "";
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
                    if (lp != null) iface = lp.getInterfaceName();
                }
            } catch (Throwable ignored) { /* QoS evidence remains useful without interface identification. */ }
            JSONObject patch = WireAudit.json("radioReady", !endpoints.isEmpty(), "radioSeenAt", System.currentTimeMillis(),
                "radioSlots", slots, "radioError", error, "radioModuleVersion", Protocol.VERSION,
                "radioActiveCellularInterface", iface, "radioUid", android.os.Process.myUid());
            Bundle b = new Bundle(); b.putString("patch", patch.toString());
            if (event != null) b.putString("event", "[本机 QoS] " + event);
            context.getContentResolver().call(Protocol.BRIDGE, "publishRadio", null, b);
        } catch (Throwable t) { XposedBridge.log("FastPass radio bridge: " + t.getClass().getSimpleName()); }
    }
    private static String describe(Throwable t) {
        for (int i = 0; i < 4 && t.getCause() != null; i++) t = t.getCause();
        return WireAudit.clean(t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
