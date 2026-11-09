package org.jetbrains.skiko;

/**
 * Replaces skiko's class of the same name (Display.kt) to stop the UI freezing for good when the
 * last monitor goes away. It is found first because desktop.jar leads the classpath, and the native
 * method binds by class name, so it has to keep this name.
 *
 * With no monitor, as during suspend or when a screen drops off HDMI while asleep, X has no active
 * CRTC and skiko reads a refresh rate of 0. Only its non-Linux branch has a lower bound, so its frame
 * limiter works out 1000 / 0.0 = Infinity and waits that long for the next frame. It never looks at
 * the rate again, so nothing brings it back: every animation frame queues behind it for the rest of
 * the process, silently, while audio, MPRIS and the UI thread carry on. Only AWT paint events, such
 * as restoring a minimized window, still draw.
 *
 * Unfixed as of skiko 0.156.0. Delete this file once skiko bounds the rate on Linux too. After
 * changing the Compose version, check that getDisplayRefreshRate still has this signature.
 */
public final class DisplayKt {
    public static final double MinMainstreamMonitorRefreshRate = 60.0;

    private static boolean rateMissing = false;

    private DisplayKt() {
    }

    public static final double getDisplayRefreshRate(HardwareLayer layer) {
        if (OsArch_jvmKt.getHostOs() != OS.Linux) {
            int rate = layer.getGraphicsConfiguration().getDevice().getDisplayMode().getRefreshRate();
            return Math.max(rate, MinMainstreamMonitorRefreshRate);
        }
        LinuxDrawingSurface surface = AWTLinuxDrawingSurfaceKt.lockLinuxDrawingSurface(layer);
        try {
            double rate = getLinuxDisplayRefreshRate(surface.getDisplay(), surface.getWindow());
            logChange(rate);
            return usableRate(rate);
        } finally {
            AWTLinuxDrawingSurfaceKt.unlockLinuxDrawingSurface(surface);
        }
    }

    /** Written as a negated comparison so that NaN counts as missing too. */
    static double usableRate(double rate) {
        return rate > 0 ? rate : MinMainstreamMonitorRefreshRate;
    }

    /** Runs on the UI thread. Only logs changes: this is sampled once a second while drawing. */
    private static void logChange(double rate) {
        boolean missing = usableRate(rate) != rate;
        if (missing == rateMissing) return;
        rateMissing = missing;
        System.err.println(missing
                ? "[musikbox ui] Display reports no refresh rate (monitor gone), assuming 60 Hz"
                : "[musikbox ui] Display is back at " + Math.round(rate) + " Hz");
    }

    private static final native double getLinuxDisplayRefreshRate(long display, long window);
}
