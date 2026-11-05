#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "snow_visual_region_detector.h"

JNIEXPORT jintArray JNICALL
Java_local_voxfix_debug_ScreenshotAutoFilter_detectNative(JNIEnv *env, jobject self,
                                                         jintArray input, jint width, jint height) {
    (void)self;
    if (input == NULL || width <= 0 || height <= 0) return NULL;
    const size_t count = (size_t)width * (size_t)height;
    if (count > 16000000 || (size_t)(*env)->GetArrayLength(env, input) != count) return NULL;

    uint8_t *bgr = malloc(count * 3);
    if (bgr == NULL) return NULL;
    jint *pixels = (*env)->GetIntArrayElements(env, input, NULL);
    if (pixels == NULL) {
        free(bgr);
        return NULL;
    }
    for (size_t i = 0; i < count; i++) {
        const uint32_t pixel = (uint32_t)pixels[i];
        bgr[3 * i] = (uint8_t)pixel;
        bgr[3 * i + 1] = (uint8_t)(pixel >> 8);
        bgr[3 * i + 2] = (uint8_t)(pixel >> 16);
    }
    (*env)->ReleaseIntArrayElements(env, input, pixels, JNI_ABORT);

    SnowDetectedRegions *regions = NULL;
    const int status = snow_detect_visual_regions(bgr, count * 3, (uint32_t)width, (uint32_t)height,
                                                   (size_t)width * 3, &regions);
    free(bgr);
    if (status != 0 || regions == NULL) return NULL;

    size_t size = 0;
    const SnowDetectedRegion *data = snow_detected_regions_data(regions, &size);
    jintArray output = NULL;
    if (size <= INT32_MAX / 5) {
        output = (*env)->NewIntArray(env, (jsize)(size * 5));
        if (output != NULL) for (size_t i = 0; i < size; i++) {
            const jint row[] = {data[i].x, data[i].y, data[i].width, data[i].height, (jint)data[i].category};
            (*env)->SetIntArrayRegion(env, output, (jsize)(i * 5), 5, row);
            if ((*env)->ExceptionCheck(env)) break;
        }
    }
    snow_detected_regions_release(regions);
    return output;
}
