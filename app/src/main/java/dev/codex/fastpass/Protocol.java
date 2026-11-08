package dev.codex.fastpass;

import android.net.Uri;

public final class Protocol {
    public static final String PACKAGE = "dev.codex.fastpass";
    public static final String TARGET = "com.hihonor.systemserver";
    public static final String SETTINGS = "com.android.settings";
    public static final String ACTION = "dev.codex.fastpass.COMMAND";
    public static final String PERMISSION = "dev.codex.fastpass.permission.CONTROL";
    public static final String ROOT = "com.hihonor.systemserver.booster.";
    public static final Uri BRIDGE = Uri.parse("content://dev.codex.fastpass.bridge");
    private Protocol() {}
}
