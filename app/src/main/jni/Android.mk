LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := relayaudio
LOCAL_SRC_FILES := aaudio_out.c
LOCAL_LDLIBS := -laaudio
include $(BUILD_SHARED_LIBRARY)
