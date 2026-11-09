package dev.codex.fastpass;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Map;
import org.json.JSONObject;

/** Observes native origin and relaxes local predicates; never creates or edits a request. */
public final class NativeAutoHooks {
    public interface Sink {
        boolean enabled();
        void note(String name, JSONObject details);
    }
    public static final String ORIGIN_KEY = "fastpass.native.auto.origin";
    public static final class Origin {
        final WeakReference<Object> scene;
        final int sceneId, uid, userSceneId, qci, duration, slot;
        final String nativeScene;
        Origin(Object object, int id, int uid, int userScene, int qci, int duration, String name, int slot) {
            this.scene = new WeakReference<>(object); sceneId = id; this.uid = uid;
            userSceneId = userScene; this.qci = qci; this.duration = duration; nativeScene = name; this.slot = slot;
        }
        public JSONObject json() { return WireAudit.json("sceneId", sceneId, "uid", uid, "userSceneId", userSceneId,
            "scene", nativeScene, "configuredQci", qci, "configuredMinutes", duration, "slotAtConstruction", slot,
            "source", "DataAccelerateScene → constructDBRequestInfo → toDBRequestInfo → QosManager"); }
    }
    private final ClassLoader loader;
    private final Sink sink;
    private final Class<?> sceneClass, managerClass;
    private final ThreadLocal<ArrayDeque<Origin>> flow = new ThreadLocal<ArrayDeque<Origin>>() {
        @Override protected ArrayDeque<Origin> initialValue() { return new ArrayDeque<>(); }
    };
    private final ThreadLocal<Integer> dispatch = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };
    public NativeAutoHooks(ClassLoader loader, Sink sink) {
        this.loader = loader; this.sink = sink;
        sceneClass = cls("ollie.transportlayer.feature.transportlayerselfcure.dataacceleration.DataAccelerateScene");
        managerClass = cls("ollie.transportlayer.feature.transportlayerselfcure.dataacceleration.DataAccelerateManager");
    }
    private Class<?> cls(String name) { return XposedHelpers.findClass(Protocol.ROOT + name, loader); }
    private Origin readScene(Object scene) {
        try {
            Object c = XposedHelpers.getObjectField(scene, "dataAccSceneConfig");
            int id = XposedHelpers.getIntField(c, "sceneId"), qci = XposedHelpers.getIntField(c, "qciType");
            int minutes = XposedHelpers.getIntField(c, "dedicatedBearerReqDuration");
            if (!AutoPolicy.supported(id, XposedHelpers.getIntField(c, "accAction"), qci,
                XposedHelpers.getIntField(c, "isSupportAsyncExec"), minutes)) return null;
            Object nativeName = XposedHelpers.callMethod(scene, "getQosReqScene");
            String name = nativeName == null ? AutoPolicy.sceneName(id) : String.valueOf(nativeName);
            if (!AutoPolicy.matchesNativeScene(id, name)) return null;
            return new Origin(scene, id, XposedHelpers.getIntField(scene, "uid"),
                XposedHelpers.getIntField(scene, "userSceneId"), qci, minutes, name,
                ((Number) XposedHelpers.callMethod(XposedHelpers.getObjectField(scene, "ollieStateManager"), "getDefaultDataSlotId")).intValue());
        } catch (Throwable ignored) { return null; }
    }
    private boolean supportedConfig(Object c, int id) {
        try { return c != null && AutoPolicy.supported(id, XposedHelpers.getIntField(c, "accAction"),
            XposedHelpers.getIntField(c, "qciType"), XposedHelpers.getIntField(c, "isSupportAsyncExec"),
            XposedHelpers.getIntField(c, "dedicatedBearerReqDuration")); }
        catch (Throwable ignored) { return false; }
    }
    public Origin originFor(Object info) {
        Object attached = XposedHelpers.getAdditionalInstanceField(info, ORIGIN_KEY);
        Origin o = attached instanceof Origin ? (Origin) attached : flow.get().peek();
        if (o == null) return null;
        try { return AutoPolicy.matchesNativeScene(o.sceneId, String.valueOf(XposedHelpers.callMethod(info, "getScene"))) ? o : null; }
        catch (Throwable ignored) { return null; }
    }
    public void install() {
        String[] dispatchNames = {"handleUserSceneEnter", "handleBearerSceneChange", "triggerQosSceneInstance",
            "onOldProtocolHighSpeedRailChanged", "onOldProtocolMetroChanged", "onOldProtocolLiveChanged", "onOldProtocolCommuteChanged"};
        for (String name : dispatchNames) {
            java.util.Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(managerClass, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { dispatch.set(dispatch.get() + 1); }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int n = dispatch.get() - 1; if (n <= 0) dispatch.remove(); else dispatch.set(n);
            }
            });
            if (installed.isEmpty()) throw new IllegalStateException("缺少原生自动事件接口：" + name);
        }
        XposedHelpers.findAndHookMethod(managerClass, "isQosRequestNeeded", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!sink.enabled() || dispatch.get() <= 0 || p.hasThrowable() || Boolean.TRUE.equals(p.getResult())) return;
                try {
                    // Keep actual backend/product support; only local period/region/switch policy is relaxed.
                    if ((Boolean) XposedHelpers.callMethod(p.thisObject, "isQosFeatureEnabled")
                        && (Boolean) XposedHelpers.callMethod(p.thisObject, "isStart")) {
                        p.setResult(true);
                        sink.note("manager.localPolicy", WireAudit.json("raw", false, "effective", true,
                            "scope", "真实自动事件；本地服务期/国内策略/总开关"));
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedHelpers.findAndHookMethod(managerClass, "isSceneEnabled", int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!sink.enabled() || p.hasThrowable() || Boolean.TRUE.equals(p.getResult())) return;
                try {
                    int id = (Integer) p.args[0];
                    Object c = ((Map<?,?>) XposedHelpers.getObjectField(p.thisObject, "dataAccSceneConfigHashMap")).get(id);
                    if (supportedConfig(c, id)) {
                        p.setResult(true);
                        sink.note("manager.sceneEnable", WireAudit.json("sceneId", id, "raw", false, "effective", true));
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedHelpers.findAndHookMethod(managerClass, "triggerDataAccelerateSceneInstance", int.class, int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    int id = (Integer) p.args[1];
                    Object c = ((Map<?,?>) XposedHelpers.getObjectField(p.thisObject, "dataAccSceneConfigHashMap")).get(id);
                    if (supportedConfig(c, id)) sink.note("native.sceneEvent", WireAudit.json("uid", p.args[0],
                        "sceneId", id, "userSceneId", p.args[2], "scene", AutoPolicy.sceneName(id),
                        "source", "系统原生场景事件；尚不等于申请已发送"));
                } catch (Throwable ignored) {}
            }
        });
        XposedHelpers.findAndHookMethod(sceneClass, "isSatisfyEnterCondition", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!sink.enabled() || p.hasThrowable() || Boolean.TRUE.equals(p.getResult())) return;
                Origin o = readScene(p.thisObject); if (o == null) return;
                try {
                    Object c = XposedHelpers.getObjectField(p.thisObject, "dataAccSceneConfig");
                    int raw = XposedHelpers.getIntField(p.thisObject, "enterCondition"), required = XposedHelpers.getIntField(c, "accEnter");
                    boolean allowed = AutoPolicy.enterAllowed(raw, required);
                    if (allowed) p.setResult(true);
                    sink.note("scene.enterConditions", WireAudit.json("origin", o.json(), "rawMask", raw,
                        "requiredMask", required, "relaxedBits", AutoPolicy.RELAXABLE_ENTER_BITS,
                        "remainingBlockedBits", required & ~(raw | AutoPolicy.RELAXABLE_ENTER_BITS), "effective", allowed));
                } catch (Throwable ignored) {}
            }
        });
        XposedHelpers.findAndHookMethod(sceneClass, "isNonLagPenaltyDuration", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Origin o = readScene(p.thisObject);
                if (sink.enabled() && o != null) {
                    p.setResult(true);
                    sink.note("scene.localPenalty", WireAudit.json("origin", o.json(), "effective", true,
                        "raw", "未执行原生本地惩罚谓词，未改写计时器"));
                }
            }
        });
        for (String name : new String[]{"startQosReq", "connectQosCloudService"}) {
            XposedHelpers.findAndHookMethod(sceneClass, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Origin o = readScene(p.thisObject); if (o == null) return;
                    flow.get().push(o); p.setObjectExtra("fastpassPushed", true);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.getObjectExtra("fastpassPushed"))) {
                        ArrayDeque<Origin> stack = flow.get(); if (!stack.isEmpty()) stack.pop();
                        if (stack.isEmpty()) flow.remove();
                    }
                }
            });
        }
        XposedHelpers.findAndHookMethod(sceneClass, "constructDBRequestInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.getResult() == null) return;
                Origin o = readScene(p.thisObject);
                if (o != null) XposedHelpers.setAdditionalInstanceField(p.getResult(), ORIGIN_KEY, o);
            }
        });
        XposedHelpers.findAndHookMethod(cls("ollie.transportlayer.framework.intf.CommsCloud.DBRequestInfoForTrans"), "toDBRequestInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || p.getResult() == null) return;
                Object attached = XposedHelpers.getAdditionalInstanceField(p.thisObject, ORIGIN_KEY);
                Origin o = attached instanceof Origin ? (Origin) attached : flow.get().peek();
                if (o != null) XposedHelpers.setAdditionalInstanceField(p.getResult(), ORIGIN_KEY, o);
            }
        });
        // Ask modern LSPosed to undo potential private-helper inlining in these callers.
        deoptimize(managerClass, "handleUserSceneEnter", "handleBearerSceneChange", "triggerQosSceneInstance", "triggerDataAccelerateSceneInstance",
            "onOldProtocolHighSpeedRailChanged", "onOldProtocolMetroChanged", "onOldProtocolLiveChanged", "onOldProtocolCommuteChanged");
        deoptimize(sceneClass, "init", "initAsyncExtraConditionBitMap", "connectQosCloudService", "startQosReq");
        deoptimize(cls("ollie.transportlayer.framework.CommsCloud.DedicatedBearerCould"), "requestDedicatedBearer");
        deoptimize(cls("framework.intf.IntelligentProxy"), "requestDedicatedBearer");
        deoptimize(cls("ollie.intelligent.IntelligentManager"), "requestDedicatedBearer");
        for (String inner : new String[]{"PrepareAccState", "AsyncExecState"})
            deoptimize(XposedHelpers.findClass(sceneClass.getName() + "$" + inner, loader), "processMessage", "enter");
    }
    public void reevaluateExisting() {
        try {
            Object manager = XposedHelpers.callStaticMethod(managerClass, "getInstance");
            android.os.Handler handler = (android.os.Handler) XposedHelpers.getObjectField(manager, "handler");
            handler.post(() -> {
                try {
                    Map<?,?> map = (Map<?,?>) XposedHelpers.getObjectField(manager, "dataAccSceneMap");
                    Object[] scenes = map.values().toArray();
                    int n = 0;
                    for (Object scene : scenes) if (scene != null && readScene(scene) != null) {
                        XposedHelpers.callMethod(scene, "sendMessage", 2); n++;
                    }
                    sink.note("control.reevaluate", WireAudit.json("existingNativeScenes", n,
                        "note", "仅在原生线程通知已有自动场景重评估；没有创建场景或直接提交申请"));
                } catch (Throwable t) { sink.note("control.reevaluate", WireAudit.json("error", t.getClass().getSimpleName())); }
            });
        } catch (Throwable t) { sink.note("control.reevaluate", WireAudit.json("error", t.getClass().getSimpleName())); }
    }
    public JSONObject snapshot() {
        try {
            Object manager = XposedHelpers.callStaticMethod(managerClass, "getInstance");
            org.json.JSONArray configs = new org.json.JSONArray();
            Map<?,?> map = (Map<?,?>) XposedHelpers.getObjectField(manager, "dataAccSceneConfigHashMap");
            for (int id : new int[]{7,8,9,10,14,15,16,19}) {
                Object c = map.get(id); if (!supportedConfig(c, id)) continue;
                configs.put(WireAudit.json("sceneId", id, "scene", AutoPolicy.sceneName(id),
                    "nativeEnabled", XposedHelpers.getIntField(c, "sceneEnable"), "qci", XposedHelpers.getIntField(c, "qciType"),
                    "minutes", XposedHelpers.getIntField(c, "dedicatedBearerReqDuration")));
            }
            return WireAudit.json("started", XposedHelpers.callMethod(manager, "isStart"), "nativeConfigs", configs);
        } catch (Throwable t) { return WireAudit.json("error", t.getClass().getSimpleName()); }
    }
    public static void deoptimize(Class<?> c, String... names) {
        try {
            for (Method api : XposedBridge.class.getDeclaredMethods()) {
                if (!api.getName().equals("deoptimizeMethod") || api.getParameterTypes().length != 1) continue;
                api.setAccessible(true);
                for (Method m : c.getDeclaredMethods()) for (String name : names)
                    if (m.getName().equals(name) && api.getParameterTypes()[0].isInstance(m)) api.invoke(null, m);
                break;
            }
        } catch (Throwable ignored) { /* Actual hits remain visible in diagnostic evidence. */ }
    }
}
