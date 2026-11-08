package dev.codex.fastpass;

/** Independent of Android and of the native UI's optimistic success mapping. */
public final class OutcomePolicy {
    public enum Result {
        IDLE, PREPARING, LOCAL_REJECTED, COMMON_LOCAL_REJECTED, TELECOM_FAILED, HTTP_REJECTED, CLOUD_REJECTED,
        WAITING, TRANSPORT_UNCONFIRMED, ACTIVE_PREEXISTING, CONFIRMED,
        BEARER_TIMEOUT, ENDED, ERROR
    }
    public static final class Evidence {
        public boolean requested, engineFinished, cloudReceived, telecomFailed;
        public boolean bearerActive, bearerFresh, baselineActive, timedOut, ended;
        public boolean baselineKnown;
        public Integer cloudCode, localCode, httpStatus, commonLocalCode;
        public String error;
    }
    public static Result decide(Evidence e) {
        if (e.error != null && !e.error.isEmpty()) return Result.ERROR;
        if (!e.requested) return Result.IDLE;
        if (e.localCode != null && e.localCode != -1) return Result.LOCAL_REJECTED;
        if (e.commonLocalCode != null && e.commonLocalCode != 1004) return Result.COMMON_LOCAL_REJECTED;
        if (e.telecomFailed) return Result.TELECOM_FAILED;
        if (e.httpStatus != null && e.httpStatus != 200) return Result.HTTP_REJECTED;
        if (e.cloudReceived && e.cloudCode != null && e.cloudCode != 200) return Result.CLOUD_REJECTED;
        if (e.ended) return Result.ENDED;
        if (e.timedOut) return Result.BEARER_TIMEOUT;
        if (e.cloudReceived && e.cloudCode != null && e.cloudCode == 200 && e.bearerActive && e.bearerFresh) {
            return e.baselineActive || !e.baselineKnown ? Result.ACTIVE_PREEXISTING : Result.CONFIRMED;
        }
        if (e.engineFinished && !e.cloudReceived) return Result.TRANSPORT_UNCONFIRMED;
        if (e.localCode == null) return Result.PREPARING;
        return Result.WAITING;
    }
    public static boolean matches(int type, int qci, int state, int wantedQci) {
        return type == 2 && qci == wantedQci && state == 1;
    }
    public static boolean freshForSession(int reportSlot, int requestedSlot, long reportedAt, long requestedAt) {
        return reportSlot == requestedSlot && reportedAt >= requestedAt && requestedAt > 0;
    }
    public static boolean reliableBaseline(long reportedAt, long requestedAt) {
        return reportedAt > 0 && requestedAt >= reportedAt && requestedAt - reportedAt <= 5000L;
    }
    private OutcomePolicy() {}
}
