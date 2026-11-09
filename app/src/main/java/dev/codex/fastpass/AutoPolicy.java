package dev.codex.fastpass;

/** Only existing, mapped native automatic QoS scenes may receive local gate relaxation. */
public final class AutoPolicy {
    public static final int RELAXABLE_ENTER_BITS = 512 | 2048 | 2097152 | 4194304;
    public static String sceneName(int id) {
        switch (id) {
            case 7: return "Gaming";
            case 8: return "Railway";
            case 9: return "Metro";
            case 10: return "Live";
            case 14: return "Commute";
            case 15: return "SpeedTest";
            case 16: return "FlashSale";
            case 19: return "Grab";
            default: return "";
        }
    }
    public static boolean supported(int id, int action, int qci, int async, int duration) {
        return !sceneName(id).isEmpty() && action == 8 && (qci == 3 || qci == 6) && async == 1 && duration > 0;
    }
    public static boolean matchesNativeScene(int id, String actualScene) {
        return !sceneName(id).isEmpty() && sceneName(id).equals(actualScene);
    }
    public static boolean enterAllowed(int actual, int required) {
        return required >= 0 && ((actual | RELAXABLE_ENTER_BITS) & required) == required;
    }
    public static boolean bypassCommonLimit(boolean enabled, boolean nativeAutomaticOrigin, boolean configLoaded,
                                            boolean rawExceeded) {
        return enabled && nativeAutomaticOrigin && configLoaded && rawExceeded;
    }
    private AutoPolicy() {}
}
