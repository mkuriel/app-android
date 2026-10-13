package com.kottov.btt_engine;

/** JNI bridge matching the Live BPM native beat-and-tempo tracking engine. */
public final class BttNative {
    static { System.loadLibrary("btt_engine"); }

    public native long initBtt();
    public native void destroyBtt(long handle);
    public native boolean processBtt(long handle,short[] samples,int count);
    public native double getBpm(long handle);
    public native double getCertainty(long handle);
    public native double bttGetSignalQuality(long handle);
    public native double bttGetSpectralFlatness(long handle);
    public native double bttGetStability(long handle);
    public native int bttGetStatus(long handle);
    public native double bttGetTempoHistogramPeakProminence(long handle);
    public native double[] getOctaveScores(long handle);
    public native double[] getSnapshot(long handle);
    public native void clearBtt(long handle);
    public native void restartBtt(long handle,boolean preserveTempo);
}
