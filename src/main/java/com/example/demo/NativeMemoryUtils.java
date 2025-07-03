package com.example.demo;

public class NativeMemoryUtils {
    static {
        System.loadLibrary("native_memory_utils");
    }

    public static native long allocateAndFillMemory(long size, String fill);
    public static native void freeMemory(long address);
}
