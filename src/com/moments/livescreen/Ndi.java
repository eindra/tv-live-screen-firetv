package com.moments.livescreen;

import android.view.Surface;

/** The native side (jni/ndiview.c). */
final class Ndi {
    static {
        System.loadLibrary("ndi");
        System.loadLibrary("ndiview");
    }

    static native boolean init();
    /** Names of the NDI sources on the network, after waiting up to waitMs for changes. */
    static native String[] sources(int waitMs);
    /** Shows this source in the surface; low asks the sender for its smaller, lighter stream. */
    static native boolean start(String source, Surface surface, boolean low);
    static native void stop();
    /** Frames drawn, width, height, ms since the last frame (-1 if none), fps x 100. */
    static native long[] stats();

    private Ndi() {}
}
