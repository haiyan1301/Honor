package dev.codex.fastpass;

/** Mirrors the native QosManager's Telecom token list; Mobile uses its direct cloud branch. */
public final class OperatorRoute {
    public static boolean needsTelecomToken(String plmn) {
        return "46003".equals(plmn) || "46005".equals(plmn) || "46011".equals(plmn) || "46012".equals(plmn);
    }
    public static String label(String plmn) {
        if (needsTelecomToken(plmn)) return "中国电信";
        if ("46000".equals(plmn) || "46002".equals(plmn) || "46004".equals(plmn)
            || "46007".equals(plmn) || "46008".equals(plmn) || "46020".equals(plmn) || "460020".equals(plmn)) return "中国移动";
        return "其他运营商";
    }
    private OperatorRoute() {}
}
