#ifndef MOCK_JNI_H
#define MOCK_JNI_H

#ifdef _WIN32
#define JNIEXPORT __declspec(dllexport)
#define JNICALL __stdcall
#else
#define JNIEXPORT
#define JNICALL
#endif

typedef unsigned char jboolean;
typedef signed char jbyte;
typedef unsigned short jchar;
typedef short jshort;
typedef int jint;
typedef long long jlong;
typedef float jfloat;
typedef double jdouble;
typedef void* jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray jfloatArray;

#define JNI_FALSE 0
#define JNI_TRUE 1

struct JNINativeInterface;
typedef const struct JNINativeInterface* JNIEnv;

#endif // MOCK_JNI_H
