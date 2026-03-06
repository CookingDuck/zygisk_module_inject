LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := inject
LOCAL_SRC_FILES := inject.cpp
LOCAL_LDLIBS := -llog -lstdc++
LOCAL_CPPFLAGS := -std=c++17  -fexceptions
LOCAL_STL := c++_shared
include $(BUILD_SHARED_LIBRARY)

 
