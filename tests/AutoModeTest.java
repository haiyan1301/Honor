package dev.codex.fastpass;

public final class AutoModeTest {
    private static int checks;
    private static void check(String name, boolean yes) { if (!yes) throw new AssertionError(name); checks++; }
    private static final class EqualRequest {
        @Override public boolean equals(Object o) { return o instanceof EqualRequest; }
        @Override public int hashCode() { return 1; }
    }
    public static void main(String[] args) {
        check("native Gaming QCI3 supported", AutoPolicy.supported(7,8,3,1,30));
        check("native Railway QCI6 supported", AutoPolicy.supported(8,8,6,1,30));
        check("manual scene never relaxed", !AutoPolicy.supported(21,8,6,1,30));
        check("unmapped scene18 never invented as Campus", !AutoPolicy.supported(18,8,6,1,30));
        check("non QoS action excluded", !AutoPolicy.supported(7,19,3,1,30));
        check("mixed actions excluded", !AutoPolicy.supported(7,9,3,1,30));
        check("missing async config excluded", !AutoPolicy.supported(8,8,6,0,30));
        check("missing duration excluded", !AutoPolicy.supported(8,8,6,1,-1));
        check("unknown QCI config excluded", !AutoPolicy.supported(8,8,4,1,30));
        check("native scene must match origin", AutoPolicy.matchesNativeScene(7,"Gaming"));
        check("Manual cannot be relabelled Gaming", !AutoPolicy.matchesNativeScene(7,"Manual"));
        check("different auto scene cannot borrow origin", !AutoPolicy.matchesNativeScene(7,"SpeedTest"));
        check("all mapped scenes", AutoPolicy.matchesNativeScene(9,"Metro") && AutoPolicy.matchesNativeScene(10,"Live")
            && AutoPolicy.matchesNativeScene(14,"Commute") && AutoPolicy.matchesNativeScene(15,"SpeedTest")
            && AutoPolicy.matchesNativeScene(16,"FlashSale") && AutoPolicy.matchesNativeScene(19,"Grab"));
        int hard = 1|2|4|8|16|1024|4096|8192|16384|32768|65536|131072;
        int required = hard|AutoPolicy.RELAXABLE_ENTER_BITS;
        check("only local switch/congestion/penalty bits relaxed", AutoPolicy.enterAllowed(hard,required));
        for (int bit : new int[]{1,2,4,8,16,1024,4096,8192,16384,32768,65536,131072})
            check("real native requirement remains blocked: "+bit, !AutoPolicy.enterAllowed(hard&~bit,required));
        check("invalid bitmap remains blocked", !AutoPolicy.enterAllowed(-1,-1));
        check("common limit bypass needs all constraints", AutoPolicy.bypassCommonLimit(true,true,true,true));
        check("disabled mode preserves common limit", !AutoPolicy.bypassCommonLimit(false,true,true,true));
        check("unmanaged origin preserves common limit", !AutoPolicy.bypassCommonLimit(true,false,true,true));
        check("missing config preserves common limit", !AutoPolicy.bypassCommonLimit(true,true,false,true));
        check("normal counter path not overridden", !AutoPolicy.bypassCommonLimit(true,true,true,false));
        AutoRequestIndex<String> index = new AutoRequestIndex<>(2);
        Object a=new EqualRequest(), b=new EqualRequest(), c=new EqualRequest();
        index.put(a,"request-A"); index.put(b,"request-B");
        check("native request identity not equals", "request-A".equals(index.get(a)) && "request-B".equals(index.get(b)));
        check("old callback still resolves after newer attempt", "request-A".equals(index.get(a)));
        index.put(b,"request-B-updated");
        check("updating same native identity does not evict", index.snapshot().size()==2 && index.get(a)!=null);
        index.put(c,"request-C");
        check("bounded index evicts oldest", index.get(a)==null && "request-B-updated".equals(index.get(b)) && "request-C".equals(index.get(c)));
        check("observer snapshot preserves chronological requests", index.snapshot().get(0).equals("request-B-updated") && index.snapshot().get(1).equals("request-C"));
        System.out.println("PASS: "+checks+" native-auto policy/index checks");
    }
}
