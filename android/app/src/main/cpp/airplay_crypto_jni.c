#include <jni.h>
#include <string.h>
#include <stdint.h>
#include "playfair.h"

JNIEXPORT jbyteArray JNICALL
Java_com_aydtv_aydtv_cast_CastCryptoHelper_decryptPlayFair(
    JNIEnv *env,
    jobject thiz,
    jbyteArray keymsgArray,
    jbyteArray ekeyArray
) {
    if (keymsgArray == NULL || ekeyArray == NULL) {
        return NULL;
    }

    jsize keymsgLen = (*env)->GetArrayLength(env, keymsgArray);
    jsize ekeyLen = (*env)->GetArrayLength(env, ekeyArray);

    if (keymsgLen < 164 || ekeyLen < 72) {
        return NULL;
    }

    jbyte *keymsg = (*env)->GetByteArrayElements(env, keymsgArray, NULL);
    jbyte *ekey = (*env)->GetByteArrayElements(env, ekeyArray, NULL);

    if (keymsg == NULL || ekey == NULL) {
        if (keymsg) (*env)->ReleaseByteArrayElements(env, keymsgArray, keymsg, JNI_ABORT);
        if (ekey) (*env)->ReleaseByteArrayElements(env, ekeyArray, ekey, JNI_ABORT);
        return NULL;
    }

    unsigned char aesKeyOut[16];
    memset(aesKeyOut, 0, sizeof(aesKeyOut));

    // FairPlay SAP v3 deşifreleme motorunu çalıştır
    playfair_decrypt((unsigned char *)keymsg, (unsigned char *)ekey, aesKeyOut);

    (*env)->ReleaseByteArrayElements(env, keymsgArray, keymsg, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, ekeyArray, ekey, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, 16, (jbyte *)aesKeyOut);
    }
    return result;
}
