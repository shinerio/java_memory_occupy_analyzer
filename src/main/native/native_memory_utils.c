#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "com_example_demo_NativeMemoryUtils.h"

JNIEXPORT jlong JNICALL Java_com_example_demo_NativeMemoryUtils_allocateAndFillMemory
  (JNIEnv *env, jclass clazz, jlong size, jstring fill) {
    const char *fillStr = (*env)->GetStringUTFChars(env, fill, 0);
    size_t fillLen = strlen(fillStr);
    if (fillLen == 0) fillLen = 1;

    char *mem = (char *)malloc((size_t)size);
    if (!mem) {
        (*env)->ReleaseStringUTFChars(env, fill, fillStr);
        return 0;
    }
    for (jlong i = 0; i < size; ++i) {
        mem[i] = fillStr[i % fillLen];
    }
    (*env)->ReleaseStringUTFChars(env, fill, fillStr);
    return (jlong)(intptr_t)mem;
}

JNIEXPORT void JNICALL Java_com_example_demo_NativeMemoryUtils_freeMemory
  (JNIEnv *env, jclass clazz, jlong address) {
    if (address != 0) {
        free((void *)(intptr_t)address);
    }
} 