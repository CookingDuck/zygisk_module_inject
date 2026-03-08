LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := inject

# mylinker 源码
MYLINKER_SRC_FILES := \
    mylinker/mylinker.cpp \
    mylinker/elf_loader.cpp \
    mylinker/elf_reader.cpp \
    mylinker/memory_manager.cpp \
    mylinker/relocator.cpp \
    mylinker/soinfo_manager.cpp \
    mylinker/utils.cpp

LOCAL_SRC_FILES := inject.cpp $(MYLINKER_SRC_FILES)

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/mylinker/include \
    $(LOCAL_PATH)/xdl/include

LOCAL_LDLIBS := -llog -ldl
LOCAL_CPPFLAGS := -std=c++17 -fexceptions
LOCAL_STL := c++_static
include $(BUILD_SHARED_LIBRARY)
