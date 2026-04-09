//
// Created by Brandon on 2020-12-08.
//

#ifndef REMOTEINPUT_REMOTEVM_HXX
#define REMOTEINPUT_REMOTEVM_HXX

#include <vector>
#include <string>
#include <functional>
#include <cstdint>

#include "JNI_Common.hxx"
#include "TypeTraits.hxx"
#include "ImageData.hxx"

class ControlCenter;
struct EIOSData;
enum class RemoteVMCommand: std::uint32_t;

class RemoteVM final
{
private:
    JNIEnv* env;
    ControlCenter* control_center;
    std::function<bool(std::function<void(Stream&, ImageData*)>)> send_command;
    ImageData* (ControlCenter::*get_image_data)() const;

    template<typename T>
    T local_to_global(T object) const noexcept;

    template<typename R, typename... Args>
    R SendCommand(RemoteVMCommand command, Args&&... args) const noexcept;

    template<typename R, typename... Args>
    R ExecuteCommand(ImageData* image_data, R (RemoteVM::*func)(Args...) const noexcept) const noexcept;

public:
    // MSVC will crash when initializing `send_command` and `get_image_data`. So this convenience function is here.
    // MSVC seems to not like the pointer to method.
    RemoteVM(JNIEnv* env, ControlCenter* control_center) noexcept;

    RemoteVM(JNIEnv* env,
             ControlCenter* control_center,
             std::function<bool(std::function<void(Stream&, ImageData*)>)>&& send_command,
             ImageData* (ControlCenter::*get_image_data)() const) noexcept;
    ~RemoteVM();

    RemoteVM(const RemoteVM& other) = delete;
    RemoteVM(RemoteVM&& other) noexcept ;

    RemoteVM& operator = (const RemoteVM& other) = delete;
    RemoteVM& operator = (RemoteVM&& other) noexcept;

    std::size_t MaxMemoryChunkSize() const noexcept;
    void* AllocateMemory(std::size_t size) const noexcept;
    bool ReadMemory(void* destination, void* source, std::size_t size) const noexcept;
    bool WriteMemory(void* destination, void* source, std::size_t size) const noexcept;
    void FreeMemory(void* memory) const noexcept;

    jint GetVersion() const noexcept;
    jclass DefineClass(const std::string &name, jobject loader, const jbyte* buf, jsize len) const noexcept;
    jclass FindClass(const std::string &name) const noexcept;
    jmethodID FromReflectedMethod(jobject method) const noexcept;
    jfieldID FromReflectedField(jobject field) const noexcept;
    jobject ToReflectedMethod(jclass cls, jmethodID methodID, jboolean isStatic) const noexcept;
    jclass GetSuperclass(jclass sub) const noexcept;
    jboolean IsAssignableFrom(jclass sub, jclass sup) const noexcept;
    jobject ToReflectedField(jclass cls, jfieldID fieldID, jboolean isStatic) const noexcept;
    jint Throw(jthrowable obj) const noexcept;
    jint ThrowNew(jclass clazz, const std::string &msg) const noexcept;
    std::string GetExceptionMessage() const noexcept;
    void FatalError(const std::string &msg) const noexcept;
    void DeleteGlobalRef(jobject gref) const noexcept;
    jboolean IsSameObject(jobject obj1, jobject obj2) const noexcept;
    jobject AllocObject(jclass clazz) const noexcept;
    jobject NewObject(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jclass GetObjectClass(jobject obj) const noexcept;
    jboolean IsInstanceOf(jobject obj, jclass clazz) const noexcept;
    jmethodID GetMethodID(jclass clazz, const std::string &name, const std::string &sig) const noexcept;
    jobject CallObjectMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jboolean CallBooleanMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jbyte CallByteMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jchar CallCharMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jshort CallShortMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jint CallIntMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jlong CallLongMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jfloat CallFloatMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jdouble CallDoubleMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    void CallVoidMethod(jobject obj, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jobject CallNonvirtualObjectMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jboolean CallNonvirtualBooleanMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jbyte CallNonvirtualByteMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jchar CallNonvirtualCharMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jshort CallNonvirtualShortMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jint CallNonvirtualIntMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jlong CallNonvirtualLongMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jfloat CallNonvirtualFloatMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jdouble CallNonvirtualDoubleMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    void CallNonvirtualVoidMethod(jobject obj, jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jfieldID GetFieldID(jclass clazz, const std::string &name, const std::string &sig) const noexcept;
    jobject GetObjectField(jobject obj, jfieldID fieldID) const noexcept;
    jboolean GetBooleanField(jobject obj, jfieldID fieldID) const noexcept;
    jbyte GetByteField(jobject obj, jfieldID fieldID) const noexcept;
    jchar GetCharField(jobject obj, jfieldID fieldID) const noexcept;
    jshort GetShortField(jobject obj, jfieldID fieldID) const noexcept;
    jint GetIntField(jobject obj, jfieldID fieldID) const noexcept;
    jlong GetLongField(jobject obj, jfieldID fieldID) const noexcept;
    jfloat GetFloatField(jobject obj, jfieldID fieldID) const noexcept;
    jdouble GetDoubleField(jobject obj, jfieldID fieldID) const noexcept;
    void SetObjectField(jobject obj, jfieldID fieldID, jobject val) const noexcept;
    void SetBooleanField(jobject obj, jfieldID fieldID, jboolean val) const noexcept;
    void SetByteField(jobject obj, jfieldID fieldID, jbyte val) const noexcept;
    void SetCharField(jobject obj, jfieldID fieldID, jchar val) const noexcept;
    void SetShortField(jobject obj, jfieldID fieldID, jshort val) const noexcept;
    void SetIntField(jobject obj, jfieldID fieldID, jint val) const noexcept;
    void SetLongField(jobject obj, jfieldID fieldID, jlong val) const noexcept;
    void SetFloatField(jobject obj, jfieldID fieldID, jfloat val) const noexcept;
    void SetDoubleField(jobject obj, jfieldID fieldID, jdouble val) const noexcept;
    jmethodID GetStaticMethodID(jclass clazz, const std::string &name, const std::string &sig) const noexcept;
    jobject CallStaticObjectMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jboolean CallStaticBooleanMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jbyte CallStaticByteMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jchar CallStaticCharMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jshort CallStaticShortMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jint CallStaticIntMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jlong CallStaticLongMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jfloat CallStaticFloatMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jdouble CallStaticDoubleMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    void CallStaticVoidMethod(jclass clazz, jmethodID methodID, const std::vector<jvalue> &args) const noexcept;
    jfieldID GetStaticFieldID(jclass clazz, const std::string &name, const std::string &sig) const noexcept;
    jobject GetStaticObjectField(jclass clazz, jfieldID fieldID) const noexcept;
    jboolean GetStaticBooleanField(jclass clazz, jfieldID fieldID) const noexcept;
    jbyte GetStaticByteField(jclass clazz, jfieldID fieldID) const noexcept;
    jchar GetStaticCharField(jclass clazz, jfieldID fieldID) const noexcept;
    jshort GetStaticShortField(jclass clazz, jfieldID fieldID) const noexcept;
    jint GetStaticIntField(jclass clazz, jfieldID fieldID) const noexcept;
    jlong GetStaticLongField(jclass clazz, jfieldID fieldID) const noexcept;
    jfloat GetStaticFloatField(jclass clazz, jfieldID fieldID) const noexcept;
    jdouble GetStaticDoubleField(jclass clazz, jfieldID fieldID) const noexcept;
    void SetStaticObjectField(jclass clazz, jfieldID fieldID, jobject value) const noexcept;
    void SetStaticBooleanField(jclass clazz, jfieldID fieldID, jboolean value) const noexcept;
    void SetStaticByteField(jclass clazz, jfieldID fieldID, jbyte value) const noexcept;
    void SetStaticCharField(jclass clazz, jfieldID fieldID, jchar value) const noexcept;
    void SetStaticShortField(jclass clazz, jfieldID fieldID, jshort value) const noexcept;
    void SetStaticIntField(jclass clazz, jfieldID fieldID, jint value) const noexcept;
    void SetStaticLongField(jclass clazz, jfieldID fieldID, jlong value) const noexcept;
    void SetStaticFloatField(jclass clazz, jfieldID fieldID, jfloat value) const noexcept;
    void SetStaticDoubleField(jclass clazz, jfieldID fieldID, jdouble value) const noexcept;
    jstring NewString(const std::wstring &unicode) const noexcept;
    jsize GetStringLength(jstring str) const noexcept;
    std::wstring GetStringChars(jstring str) const noexcept;
    jstring NewStringUTF(const std::string &utf) const noexcept;
    jsize GetStringUTFLength(jstring str) const noexcept;
    std::string GetStringUTFChars(jstring str) const noexcept;
    jsize GetArrayLength(jarray array) const noexcept;
    jobjectArray NewObjectArray(jsize len, jclass clazz, jobject init) const noexcept;
    jbooleanArray NewBooleanArray(jsize len) const noexcept;
    jbyteArray NewByteArray(jsize len) const noexcept;
    jcharArray NewCharArray(jsize len) const noexcept;
    jshortArray NewShortArray(jsize len) const noexcept;
    jintArray NewIntArray(jsize len) const noexcept;
    jlongArray NewLongArray(jsize len) const noexcept;
    jfloatArray NewFloatArray(jsize len) const noexcept;
    jdoubleArray NewDoubleArray(jsize len) const noexcept;
    std::vector<jobject> GetObjectArrayElements(jobjectArray array) const noexcept;
    void SetObjectArrayElements(jobjectArray array, jsize index, const std::vector<jobject> &elements) const noexcept;
    std::vector<jboolean> GetBooleanArrayElements(jbooleanArray array) const noexcept;
    std::vector<jbyte> GetByteArrayElements(jbyteArray array) const noexcept;
    std::vector<jchar> GetCharArrayElements(jcharArray array) const noexcept;
    std::vector<jshort> GetShortArrayElements(jshortArray array) const noexcept;
    std::vector<jint> GetIntArrayElements(jintArray array) const noexcept;
    std::vector<jlong> GetLongArrayElements(jlongArray array) const noexcept;
    std::vector<jfloat> GetFloatArrayElements(jfloatArray array) const noexcept;
    std::vector<jdouble> GetDoubleArrayElements(jdoubleArray array) const noexcept;
    std::vector<jboolean> GetBooleanArrayRegion(jbooleanArray array, jsize start, jsize len) const noexcept;
    std::vector<jbyte> GetByteArrayRegion(jbyteArray array, jsize start, jsize len) const noexcept;
    std::vector<jchar> GetCharArrayRegion(jcharArray array, jsize start, jsize len) const noexcept;
    std::vector<jshort> GetShortArrayRegion(jshortArray array, jsize start, jsize len) const noexcept;
    std::vector<jint> GetIntArrayRegion(jintArray array, jsize start, jsize len) const noexcept;
    std::vector<jlong> GetLongArrayRegion(jlongArray array, jsize start, jsize len) const noexcept;
    std::vector<jfloat> GetFloatArrayRegion(jfloatArray array, jsize start, jsize len) const noexcept;
    std::vector<jdouble> GetDoubleArrayRegion(jdoubleArray array, jsize start, jsize len) const noexcept;
    void SetBooleanArrayRegion(jbooleanArray array, jsize start, const std::vector<jboolean>& buf) const noexcept;
    void SetByteArrayRegion(jbyteArray array, jsize start, const std::vector<jbyte>& buf) const noexcept;
    void SetCharArrayRegion(jcharArray array, jsize start, const std::vector<jchar>& buf) const noexcept;
    void SetShortArrayRegion(jshortArray array, jsize start, const std::vector<jshort>& buf) const noexcept;
    void SetIntArrayRegion(jintArray array, jsize start, const std::vector<jint>& buf) const noexcept;
    void SetLongArrayRegion(jlongArray array, jsize start, const std::vector<jlong>& buf) const noexcept;
    void SetFloatArrayRegion(jfloatArray array, jsize start, const std::vector<jfloat>& buf) const noexcept;
    void SetDoubleArrayRegion(jdoubleArray array, jsize start, const std::vector<jdouble>& buf) const noexcept;
    jint MonitorEnter(jobject obj) const noexcept;
    jint MonitorExit(jobject obj) const noexcept;
    JavaVM* GetJavaVM() const noexcept;
    jobject NewDirectByteBuffer(void* address, jlong capacity) const noexcept;
    void* GetDirectBufferAddress(jobject buf) const noexcept;
    jlong GetDirectBufferCapacity(jobject buf) const noexcept;
    jobjectRefType GetObjectRefType(jobject obj) const noexcept;

    bool is_remote() const noexcept;
    void process_command(ImageData* image_data) const noexcept;
};

#endif //REMOTEINPUT_REMOTEVM_HXX
