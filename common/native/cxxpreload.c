#include <dlfcn.h>
#include <jni.h>

JNIEXPORT jboolean JNICALL
Java_link_e4all_AndroidNatives_preloadCxx0(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    if (p == 0) return JNI_FALSE;
    void *h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return h != 0 ? JNI_TRUE : JNI_FALSE;
}
