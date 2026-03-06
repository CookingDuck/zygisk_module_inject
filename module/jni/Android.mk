LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := inject
LOCAL_SRC_FILES := inject.cpp
LOCAL_LDLIBS := -llog
LOCAL_CPPFLAGS := -std=c++17  -fexceptions
LOCAL_STL := c++_static
include $(BUILD_SHARED_LIBRARY)

 
