#include <jni.h>
#include "NitroPlayerOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::nitroplayer::initialize(vm);
}
