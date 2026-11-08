package dev.codex.fastpass;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (Protocol.PHONE.equals(p.packageName) && Protocol.PHONE.equals(p.processName)) {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    PhoneBackend.attach((Context) param.args[0], p.classLoader);
                }
            });
        }
        if (Protocol.TARGET.equals(p.packageName) && Protocol.TARGET.equals(p.processName)) {
            XposedHelpers.findAndHookMethod("com.hihonor.systemserver.HwServerApplication", p.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Context app = (Context) param.thisObject;
                        NativeBackend.attach(app, app.getClassLoader());
                    }
                });
            XposedBridge.log("FastPass: awaiting system service application startup");
        }
        if (Protocol.SETTINGS.equals(p.packageName) && Protocol.SETTINGS.equals(p.processName)) {
            XposedHelpers.findAndHookMethod("com.android.settings.WirelessSettings", p.classLoader,
                "onCreate", Bundle.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        addEntry(param.thisObject, p.classLoader);
                    }
                });
        }
    }
    private static void addEntry(Object fragment, ClassLoader loader) {
        try {
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (screen == null || XposedHelpers.callMethod(screen, "findPreference", "fastpass_observer_panel") != null) return;
            Context context = (Context) XposedHelpers.callMethod(fragment, "getContext");
            Object preference = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.hihonor.settingslib.preference.ArrowPreference", loader), context);
            XposedHelpers.callMethod(preference, "setKey", "fastpass_observer_panel");
            XposedHelpers.callMethod(preference, "setTitle", "荣耀优速通 · 观察台");
            XposedHelpers.callMethod(preference, "setSummary", "原生申请、云端返回与真实承载状态");
            XposedHelpers.callMethod(preference, "setOrder", 99);
            Intent intent = new Intent().setClassName(Protocol.PACKAGE, Protocol.PACKAGE + ".MainActivity");
            XposedHelpers.callMethod(preference, "setIntent", intent);
            Object category = XposedHelpers.callMethod(screen, "findPreference", "mobile_network_other_settings_category");
            XposedHelpers.callMethod(category == null ? screen : category, "addPreference", preference);
        } catch (Throwable t) {
            XposedBridge.log("FastPass Settings entry: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
