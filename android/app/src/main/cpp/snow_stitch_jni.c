#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "snow_stitch_images.h"

typedef struct {
    SnowStitchSession *session;
    SnowStitchFramePool *pool;
    uint32_t width, height;
    uint32_t extent;
} Stitch;

JNIEXPORT void JNICALL
Java_local_voxfix_debug_ScreenshotStitch_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    Stitch *stitch = (Stitch *)(intptr_t)handle;
    if (stitch == NULL) return;
    if (stitch->session != NULL) snow_stitch_session_destroy(stitch->session);
    if (stitch->pool != NULL) snow_stitch_frame_pool_destroy(stitch->pool);
    free(stitch);
}

JNIEXPORT jlong JNICALL
Java_local_voxfix_debug_ScreenshotStitch_create(JNIEnv *env, jobject self, jint width, jint height, jboolean horizontal) {
    if (width <= 0 || height <= 0 || (uint64_t)width * height > 16000000) return 0;
    SnowStitchConfig config = {0};
    if (!snow_stitch_config_default(&config)) return 0;
    config.axis = horizontal ? SNOW_STITCH_AXIS_HORIZONTAL : SNOW_STITCH_AXIS_VERTICAL;
    config.max_output_pixels = 16000000;
    config.max_output_height = 20000;
    Stitch *stitch = calloc(1, sizeof(*stitch));
    if (stitch == NULL) return 0;
    stitch->width = (uint32_t)width;
    stitch->height = (uint32_t)height;
    stitch->pool = snow_stitch_frame_pool_create(stitch->width, stitch->height, 3);
    stitch->session = snow_stitch_session_create(&config);
    if (stitch->pool != NULL && stitch->session != NULL) return (jlong)(intptr_t)stitch;
    Java_local_voxfix_debug_ScreenshotStitch_destroy(env, self, (jlong)(intptr_t)stitch);
    return 0;
}

JNIEXPORT jintArray JNICALL
Java_local_voxfix_debug_ScreenshotStitch_pushFrame(JNIEnv *env, jobject self, jlong handle, jobject buffer,
                                                   jint stride, jint offset, jboolean horizontal) {
    (void)self;
    Stitch *stitch = (Stitch *)(intptr_t)handle;
    if (stitch == NULL || buffer == NULL || stride <= 0 || offset < 0) return NULL;
    const uint8_t *source = (*env)->GetDirectBufferAddress(env, buffer);
    const jlong size = (*env)->GetDirectBufferCapacity(env, buffer);
    const size_t row = (size_t)stitch->width * 4;
    const uint64_t end = (uint64_t)offset + (uint64_t)(stitch->height - 1) * (uint32_t)stride + row;
    if (source == NULL || size < 0 || row + (size_t)(offset % stride) > (size_t)stride ||
        end > (uint64_t)size) return NULL;
    SnowStitchFrameBuffer *frame = snow_stitch_frame_pool_acquire(stitch->pool);
    if (frame == NULL) return NULL;
    SnowStitchMutableImageInfo info = {0};
    if (!snow_stitch_frame_buffer_info(frame, &info) || info.rgba_bytes == NULL ||
        info.stride_bytes < row || info.rgba_len < (size_t)(stitch->height - 1) * info.stride_bytes + row) {
        snow_stitch_frame_buffer_destroy(frame);
        return NULL;
    }
    for (size_t y = 0; y < stitch->height; y++) {
        memcpy(info.rgba_bytes + y * info.stride_bytes, source + offset + y * (size_t)stride, row);
    }
    SnowStitchFrameOutcome outcome = {0};
    if (!snow_stitch_session_push_owned(stitch->session, &frame, &outcome)) return NULL;
    stitch->extent = horizontal ? outcome.output_width : outcome.output_height;
    jint result[] = {(jint)outcome.event, (jint)outcome.output_width, (jint)outcome.output_height};
    jintArray output = (*env)->NewIntArray(env, 3);
    if (output != NULL) (*env)->SetIntArrayRegion(env, output, 0, 3, result);
    return output;
}

JNIEXPORT jboolean JNICALL
Java_local_voxfix_debug_ScreenshotStitch_renderFrame(JNIEnv *env, jobject self, jlong handle,
                                                     jobject bitmap, jboolean preview, jint start, jint end) {
    (void)self;
    Stitch *stitch = (Stitch *)(intptr_t)handle;
    AndroidBitmapInfo target = {0};
    if (stitch == NULL || start < 0 || end <= start || (uint32_t)end > stitch->extent || bitmap == NULL ||
        AndroidBitmap_getInfo(env, bitmap, &target) != ANDROID_BITMAP_RESULT_SUCCESS ||
        target.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    SnowStitchOwnedImage *image = preview
        ? snow_stitch_session_render_scaled_axis(stitch->session, (uint32_t)start, (uint32_t)(end-start),
                                                 target.width, target.height)
        : snow_stitch_session_materialize_axis(stitch->session, (uint32_t)start, (uint32_t)end);
    if (image == NULL) return JNI_FALSE;
    SnowStitchImageInfo info = {0};
    void *pixels = NULL;
    const size_t row = (size_t)target.width * 4;
    jboolean ok = JNI_FALSE;
    if (target.stride >= row && snow_stitch_owned_image_info(image, &info) &&
        info.width == target.width && info.height == target.height &&
        info.stride_bytes >= row && info.rgba_len >= (size_t)(info.height - 1) * info.stride_bytes + row &&
        AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
        for (size_t y = 0; y < target.height; y++) {
            memcpy((uint8_t *)pixels + y * target.stride, info.rgba_bytes + y * info.stride_bytes, row);
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        ok = JNI_TRUE;
    }
    snow_stitch_owned_image_destroy(image);
    return ok;
}
