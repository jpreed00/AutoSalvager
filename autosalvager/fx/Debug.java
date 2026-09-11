package autosalvager.fx;

import illuminatus.core.graphics.Color;
import menu.ChatWindow;

/**
 * Debug logging switch for the Auto-Salvager mod (mirrors the Station Refinery mod).
 *
 * <p>{@link #ENABLED} is a compile-time constant from the Makefile {@code DEBUG}
 * flag ({@code make DEBUG=1}, or true/yes/on). A plain {@code make} compiles it
 * as {@code false}, and javac constant-folds it so every {@code if
 * (Debug.ENABLED)} guard (and the debug chat itself) is stripped from the bytecode
 * entirely — zero runtime cost. When on, each salvage pass prints what the modules
 * are doing (targets picked, formations launched) to the chat window via
 * {@link ChatWindow#add}.</p>
 *
 * <p>Public on purpose: it's called from the mixin's {@code @Unique} methods, which
 * are merged into {@code crafting.SalvageSystem} (a different package) at load time.</p>
 */
public final class Debug {

    /** Master switch (compile-time): {@code make DEBUG=1} to enable, plain {@code make} to strip. */
    public static final boolean ENABLED = BuildConfig.DEBUG;

    private Debug() {
    }

    /** Prints a debug line to chat (falls back to stdout if chat is unavailable). */
    public static void log(String msg) {
        if (!ENABLED) {
            return;
        }
        try {
            ChatWindow.add(Color.YELLOW, "[AutoSalvager] " + msg);
        } catch (Throwable t) {
            System.out.println("[AutoSalvager] " + msg);
        }
    }
}
