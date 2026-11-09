package dev.codex.fastpass;

public final class OutcomePolicyTest {
    private static int checks;
    private static OutcomePolicy.Evidence request() {
        OutcomePolicy.Evidence e = new OutcomePolicy.Evidence(); e.requested = true; e.localCode = -1; e.baselineKnown = true; return e;
    }
    private static void result(String name, OutcomePolicy.Result expected, OutcomePolicy.Evidence e) {
        if (OutcomePolicy.decide(e) != expected) throw new AssertionError(name + ": " + OutcomePolicy.decide(e) + " != " + expected);
        checks++;
    }
    private static void check(String name, boolean condition) { if (!condition) throw new AssertionError(name); checks++; }
    public static void main(String[] args) {
        result("no request", OutcomePolicy.Result.IDLE, new OutcomePolicy.Evidence());
        OutcomePolicy.Evidence e = request();
        result("waiting for real evidence", OutcomePolicy.Result.WAITING, e);
        e.engineFinished = true;
        result("native result 1 is not server confirmation", OutcomePolicy.Result.TRANSPORT_UNCONFIRMED, e);
        e.bearerActive = true; e.bearerFresh = true;
        result("bearer alone is not cloud confirmation", OutcomePolicy.Result.TRANSPORT_UNCONFIRMED, e);
        e.cloudReceived = true; e.cloudCode = 200;
        result("server200 plus new active bearer", OutcomePolicy.Result.CONFIRMED, e);
        e.baselineActive = true;
        result("existing active bearer is not attributed to new request", OutcomePolicy.Result.ACTIVE_PREEXISTING, e);
        e.baselineActive = false; e.bearerFresh = false;
        result("stale cached bearer cannot confirm", OutcomePolicy.Result.WAITING, e);
        e.bearerFresh = true; e.baselineKnown = false;
        result("unknown baseline cannot establish new attribution", OutcomePolicy.Result.ACTIVE_PREEXISTING, e);
        e.baselineKnown = true;
        e.bearerFresh = true; e.cloudCode = 202;
        result("raw cloud202 wins over positive local/bearer state", OutcomePolicy.Result.CLOUD_REJECTED, e);
        e = request(); e.engineFinished = true; e.cloudReceived = false; e.bearerFresh = true; e.bearerActive = true;
        result("timeout mapped to local success stays unconfirmed", OutcomePolicy.Result.TRANSPORT_UNCONFIRMED, e);
        e.timedOut = true;
        result("unconfirmed request becomes terminal after deadline", OutcomePolicy.Result.BEARER_TIMEOUT, e);
        e = request(); e.localCode = 202;
        result("local202 is not a raw cloud response", OutcomePolicy.Result.LOCAL_REJECTED, e);
        e = request(); e.telecomFailed = true;
        result("telecom token failure", OutcomePolicy.Result.TELECOM_FAILED, e);
        e = request(); e.cloudReceived = true; e.cloudCode = 200; e.timedOut = true;
        result("cloud200 without an active bearer expires", OutcomePolicy.Result.BEARER_TIMEOUT, e);
        e.bearerActive = true; e.bearerFresh = true;
        result("late bearer cannot reopen timed out observation", OutcomePolicy.Result.BEARER_TIMEOUT, e);
        e = new OutcomePolicy.Evidence(); e.requested = true; e.localCode = -1;
        e.cloudReceived = true; e.cloudCode = 200; e.bearerActive = true; e.bearerFresh = true;
        result("empty cache never implies a known baseline", OutcomePolicy.Result.ACTIVE_PREEXISTING, e);
        check("recent report establishes a baseline", OutcomePolicy.reliableBaseline(1000, 6000));
        check("old report cannot establish a baseline", !OutcomePolicy.reliableBaseline(1000, 6001));
        check("missing report cannot establish a baseline", !OutcomePolicy.reliableBaseline(0, 1000));
        check("future report cannot establish a baseline", !OutcomePolicy.reliableBaseline(1001, 1000));
        e = request(); e.httpStatus = 403;
        result("real HTTP denial is not native timeout success", OutcomePolicy.Result.HTTP_REJECTED, e);
        e.httpStatus = 200; e.cloudReceived = true; e.cloudCode = 202;
        result("HTTP200 does not override business202", OutcomePolicy.Result.CLOUD_REJECTED, e);
        e = request(); e.commonLocalCode = 101;
        result("shared local quota failure is not a cloud response", OutcomePolicy.Result.COMMON_LOCAL_REJECTED, e);
        e.commonLocalCode = 1004;
        result("shared local check passed keeps waiting", OutcomePolicy.Result.WAITING, e);
        e.cloudReceived = true; e.cloudCode = 200; e.bearerActive = true; e.bearerFresh = true; e.attributionUncertain = true;
        result("SIM change keeps attribution uncertain", OutcomePolicy.Result.ACTIVE_PREEXISTING, e);
        check("active dedicated QCI6", OutcomePolicy.matches(2, 6, 1, 6));
        check("native Gaming QCI3 can match", OutcomePolicy.matches(2, 3, 1, 3));
        check("QCI6 cannot substitute native Gaming QCI3", !OutcomePolicy.matches(2, 6, 1, 3));
        check("VIP QCI6 is not requested dedicated bearer", !OutcomePolicy.matches(1, 6, 1, 6));
        check("wrong QCI", !OutcomePolicy.matches(2, 4, 1, 6));
        check("inactive QCI6", !OutcomePolicy.matches(2, 6, 0, 6));
        check("same slot fresh report", OutcomePolicy.freshForSession(0, 0, 1001, 1000));
        check("other SIM cannot satisfy request", !OutcomePolicy.freshForSession(1, 0, 1001, 1000));
        check("earlier report cannot satisfy request", !OutcomePolicy.freshForSession(0, 0, 999, 1000));
        check("pre-submit report cannot satisfy request", !OutcomePolicy.freshForSession(0, 0, 1001, 0));
        check("CTCC route 46003", OperatorRoute.needsTelecomToken("46003"));
        check("CTCC route 46011", OperatorRoute.needsTelecomToken("46011"));
        check("CMCC 46000 does not request Telecom token", !OperatorRoute.needsTelecomToken("46000"));
        check("CMCC 46002 does not request Telecom token", !OperatorRoute.needsTelecomToken("46002"));
        check("CMCC label 46007", "中国移动".equals(OperatorRoute.label("46007")));
        check("unknown operator is not forced to Telecom", !OperatorRoute.needsTelecomToken("00101"));
        System.out.println("PASS: " + checks + " domain checks");
    }
}
