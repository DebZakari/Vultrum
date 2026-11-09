package dev.vultrum.compat;

/** Whether Vitrail's entity shadow draw is running. Render thread only: Vitrail sets up and draws its shadow entities there. */
public final class VitrailShadowDraw {
    private static boolean active;

    private VitrailShadowDraw() {}

    public static boolean active() { return active; }

    public static void set(boolean value) { active = value; }
}
