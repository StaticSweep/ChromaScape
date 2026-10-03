#ifndef SIMBAPLUGIN_HXX_INCLUDED
#define SIMBAPLUGIN_HXX_INCLUDED

#include <cstdint>
#include <cstdio>

#include "EIOS.hxx"
#include "TMemoryManager.hxx"


static const char* PascalExports[] =
{
    //"Pascal_Reflect_ClassLoader", "Function RIGetClassLoader(eios: Pointer; instance: Pointer): jobject; native;",
    "Pascal_Reflect_Equal", "Function RIObjectsEqual(eios: Pointer; A, B: Pointer): Boolean; native;",
    "Pascal_Reflect_InstanceOf", "Function RIObjectIsInstanceOf(eios: Pointer; instance: Pointer; cls: String): Boolean; native;",
    "Pascal_Reflect_Object", "Function RIGetObject(eios: Pointer; instance: Pointer; constref field: ^const RIField): Pointer; native;",
    "Pascal_Reflect_Release_Object", "Procedure RIReleaseObject(eios: Pointer; instance: Pointer); native;",
    "Pascal_Reflect_Release_Objects", "Procedure RIReleaseObjects(eios: Pointer; objects: Array of Pointer); native;",
    "Pascal_Reflect_Boolean", "Function RIGetBool(eios: Pointer; instance: Pointer; constref field: ^const RIField): Boolean; native;",
    "Pascal_Reflect_Char", "Function RIGetChar(eios: Pointer; instance: Pointer; constref field: ^const RIField): Char; native;",
    "Pascal_Reflect_Byte", "Function RIGetByte(eios: Pointer; instance: Pointer; constref field: ^const RIField): Byte; native;",
    "Pascal_Reflect_Short", "Function RIGetShort(eios: Pointer; instance: Pointer; constref field: ^const RIField): Int16; native;",
    "Pascal_Reflect_Int", "Function RIGetInt(eios: Pointer; instance: Pointer; constref field: ^const RIField): Int32; native;",
    "Pascal_Reflect_Long", "Function RIGetLong(eios: Pointer; instance: Pointer; constref field: ^const RIField): Int64; native;",
    "Pascal_Reflect_Float", "Function RIGetFloat(eios: Pointer; instance: Pointer; constref field: ^const RIField): Single; native;",
    "Pascal_Reflect_Double", "Function RIGetDouble(eios: Pointer; instance: Pointer; constref field: ^const RIField): Double; native;",
    "Pascal_Reflect_String", "Function RIGetString(eios: Pointer; instance: Pointer; constref field: ^const RIField): String; native;",
    "Pascal_Reflect_Array", "Function RIGetArray(eios: Pointer; instance: Pointer; constref field: ^const RIField): Pointer; native;",

    //Array size
    "Pascal_Reflect_Array_With_Size", "Function RIGetArray(eios: Pointer; instance: Pointer; output_size: ^SizeUInt; constref field: ^const RIField): Pointer; overload; native;",
    "Pascal_Reflect_Array_Size", "Function RIGetArraySize(eios: Pointer; arr: Pointer): SizeUInt; native;",
    "Pascal_Reflect_Array_Indices", "Function RIGetArrayElements(eios: Pointer; arr: Pointer; elementType: ReflectionType; indices: Array of Int32): Pointer; overload; native;",

    //Array 1-D
    "Pascal_Reflect_Array_SingleIndex", "Function RIGetArraySingleElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; index: SizeUInt): Pointer; native;",
    "Pascal_Reflect_Array_Index", "Function RIGetArrayElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; index, length: SizeUInt): Pointer; native;",

    //Array 2-D
    "Pascal_Reflect_Array_SingleIndex2D", "Function RIGetArraySingleElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; x, y: Int32): Pointer; overload; native;",
    "Pascal_Reflect_Array_Index2D", "Function RIGetArrayElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; length: SizeUInt; x, y: Int32): Pointer; overload; native;",

    //Array 3-D
    "Pascal_Reflect_Array_SingleIndex3D", "Function RIGetArraySingleElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; x, y, z: Int32): Pointer; overload; native;",
    "Pascal_Reflect_Array_Index3D", "Function RIGetArrayElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; length: SizeUInt; x, y, z: Int32): Pointer; overload; native;",

    //Array 4-D
    "Pascal_Reflect_Array_SingleIndex4D", "Function RIGetArraySingleElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; x, y, z, w: Int32): Pointer; overload; native;",
    "Pascal_Reflect_Array_Index4D", "Function RIGetArrayElement(eios: Pointer; arr: Pointer; elementType: ReflectionType; length: SizeUInt; x, y, z, w: Int32): Pointer; overload; native;",

    //Type Info
    "Pascal_Reflect_Class_Name", "Function RIGetClassName(eios: Pointer; instance: Pointer): String native;",
    "Pascal_Reflect_Class_Type", "Function RIGetClassType(eios: Pointer; instance: Pointer): String native;",

    //Graphics
    "Pascal_GetImageFormat", "Function EIOS_GetImageFormat(eios: Pointer): RIImageFormat; native;",
    "Pascal_SetImageFormat", "Procedure EIOS_SetImageFormat(eios: Pointer; format: RIImageFormat); native;",
    "Pascal_GetDebugImageBuffer", "Function EIOS_GetDebugImageBuffer(eios: Pointer): ^UInt8; native;",
    "Pascal_SetGraphicsDebugging", "Procedure EIOS_SetGraphicsDebugging(eios: Pointer; enabled: Boolean); native;",

    //Pairing
    "Pascal_PairClient", "Function EIOS_PairClient(pid: Int32): Pointer; native;",
    "Pascal_KillClientPID", "Procedure EIOS_KillClient(pid: Int32); native;",
    "Pascal_KillClient", "Procedure EIOS_KillClient(eios: Pointer); overload; native;",
    "Pascal_ReleaseClient", "Procedure EIOS_ReleaseClient(eios: Pointer); overload; native;",
    "Pascal_GetClients", "Function EIOS_GetClients(unpaired_only: Boolean): SizeUInt; native;",
    "Pascal_GetClientPID", "Function EIOS_GetClientPID(index: SizeUInt): Int32; native;",

    //Injecting
    "Pascal_Inject", "Procedure RIInject(process_name: String); native;",
    "Pascal_Inject_PID", "Procedure RIInject(pid: Int32); overload; native;",

    //Other
    "Pascal_HasFocus", "Function EIOS_HasFocus(eios: Pointer): Boolean; native;",
    "Pascal_GainFocus", "Procedure EIOS_GainFocus(eios: Pointer); native;",
    "Pascal_LoseFocus", "Procedure EIOS_LoseFocus(eios: Pointer); native;",
    "Pascal_IsKeyboardInputEnabled", "Function EIOS_IsKeyboardInputEnabled(eios: Pointer): Boolean; native;",
    "Pascal_SetKeyboardInputEnabled", "Function EIOS_SetKeyboardInputEnabled(eios: Pointer; enabled: Boolean): Boolean; native;",
    "Pascal_IsMouseInputEnabled", "Function EIOS_IsMouseInputEnabled(eios: Pointer): Boolean; native;",
    "Pascal_SetMouseInputEnabled", "Function EIOS_SetMouseInputEnabled(eios: Pointer; enabled: Boolean): Boolean; native;",
    "Pascal_GetRealMousePosition", "Procedure EIOS_GetRealMousePosition(eios: Pointer; var x, y: Int32); native;",
    "Pascal_GetKeyboardSpeed", "Function EIOS_GetKeyboardSpeed(eios: Pointer): Int32; native;",
    "Pascal_SetKeyboardSpeed", "Procedure EIOS_SetKeyboardSpeed(eios: Pointer; speed: Int32); native;",
    "Pascal_GetKeyboardRepeatDelay", "Function EIOS_GetKeyboardRepeatDelay(eios: Pointer): Int32; native;",
    "Pascal_SetKeyboardRepeatDelay", "Procedure EIOS_SetKeyboardRepeatDelay(eios: Pointer; delay: Int32); native;",
    //"Pascal_GetGraphicsScaling", "Function EIOS_GetGraphicsScaling(eios: Pointer): Boolean; native;",
    //"Pascal_SetGraphicsScaling", "Procedure EIOS_SetGraphicsScaling(eios: Pointer; enabled: Boolean); native;",

    //RemoteVM
    "Pascal_RemoteVM_Init", "Procedure RemoteVM.Init(eios: Pointer); native;",
    "Pascal_RemoteVM_InitEx", "Procedure RemoteVM.Init(args: array of String); overload; native;",
    "Pascal_RemoteVM_Free", "Procedure RemoteVM.Free(); native;",

    "Pascal_RemoteVM_MaxMemoryChunkSize", "Function RemoteVM.MaxMemoryChunkSize(): SizeUInt; native;",
    "Pascal_RemoteVM_AllocateMemory", "Function RemoteVM.AllocateMemory(size: SizeUInt): Pointer; native;",
    "Pascal_RemoteVM_ReadMemory", "Function RemoteVM.ReadMemory(destintation, source: Pointer; size: SizeUInt): Boolean; native;",
    "Pascal_RemoteVM_WriteMemory", "Function RemoteVM.WriteMemory(destintation, source: Pointer; size: SizeUInt): Boolean; native;",
    "Pascal_RemoteVM_FreeMemory", "Procedure RemoteVM.FreeMemory(memory: Pointer); native;",
    "Pascal_RemoteVM_GetVersion", "Function RemoteVM.GetVersion: jint; native",
    "Pascal_RemoteVM_DefineClass", "Function RemoteVM.DefineClass(name: String; loader: jobject; buf: Pointer; len: jsize): jclass; native;",
    "Pascal_RemoteVM_FindClass", "Function RemoteVM.FindClass(name: String): jclass; native;",
    "Pascal_RemoteVM_FromReflectedMethod", "Function RemoteVM.FromReflectedMethod(method: jobject): jmethodID; native;",
    "Pascal_RemoteVM_FromReflectedField", "Function RemoteVM.FromReflectedField(field: jobject): jfieldID; native;",
    "Pascal_RemoteVM_ToReflectedMethod", "Function RemoteVM.ToReflectedMethod(cls: jclass; methodID: jmethodID; isStatic: jboolean): jobject; native;",
    "Pascal_RemoteVM_GetSuperclass", "Function RemoteVM.GetSuperclass(sub: jclass): jclass; native;",
    "Pascal_RemoteVM_IsAssignableFrom", "Function RemoteVM.IsAssignableFrom(sub, sup: jclass): jboolean; native;",
    "Pascal_RemoteVM_ToReflectedField", "Function RemoteVM.ToReflectedField(cls: jclass; fieldID: jfieldID; isStatic: jboolean): jobject; native;",
    "Pascal_RemoteVM_Throw", "Function RemoteVM.Throw(obj: jthrowable): jint; native;",
    "Pascal_RemoteVM_ThrowNew", "Function RemoteVM.ThrowNew(clazz: jclass; const msg: string): jint; native;",
    "Pascal_RemoteVM_GetExceptionMessage", "Function RemoteVM.GetExceptionMessage(): String; native;",
    "Pascal_RemoteVM_FatalError", "Procedure RemoteVM.FatalError(msg: String); native;",
    "Pascal_RemoteVM_DeleteGlobalRef", "Procedure RemoteVM.DeleteGlobalRef(gref: jobject); native;",
    "Pascal_RemoteVM_IsSameObject", "Function RemoteVM.IsSameObject(obj1, obj2: jobject): jboolean; native;",
    "Pascal_RemoteVM_AllocObject", "Function RemoteVM.AllocObject(clazz: jclass): jobject; native;",
    "Pascal_RemoteVM_NewObject", "Function RemoteVM.NewObject(clazz: jclass; methodID: jmethodID; args: array of jvalue): jobject; native;",
    "Pascal_RemoteVM_GetObjectClass", "Function RemoteVM.GetObjectClass(obj: jobject): jclass; native;",
    "Pascal_RemoteVM_IsInstanceOf", "Function RemoteVM.IsInstanceOf(obj: jobject; clazz: jclass): jboolean; native;",
    "Pascal_RemoteVM_GetMethodID", "Function RemoteVM.GetMethodID(clazz: jclass; name, sig: String): jmethodID; native;",
    "Pascal_RemoteVM_CallObjectMethod", "Function RemoteVM.CallObjectMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jobject; native;",
    "Pascal_RemoteVM_CallBooleanMethod", "Function RemoteVM.CallBooleanMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jboolean; native;",
    "Pascal_RemoteVM_CallByteMethod", "Function RemoteVM.CallByteMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jbyte; native;",
    "Pascal_RemoteVM_CallCharMethod", "Function RemoteVM.CallCharMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jchar; native;",
    "Pascal_RemoteVM_CallShortMethod", "Function RemoteVM.CallShortMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jshort; native;",
    "Pascal_RemoteVM_CallIntMethod", "Function RemoteVM.CallIntMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jint; native;",
    "Pascal_RemoteVM_CallLongMethod", "Function RemoteVM.CallLongMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jlong; native;",
    "Pascal_RemoteVM_CallFloatMethod", "Function RemoteVM.CallFloatMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jfloat; native;",
    "Pascal_RemoteVM_CallDoubleMethod", "Function RemoteVM.CallDoubleMethod(obj: jobject; methodID: jmethodID; args: array of jvalue): jdouble; native;",
    "Pascal_RemoteVM_CallVoidMethod", "Procedure RemoteVM.CallVoidMethod(obj: jobject; methodID: jmethodID; args: array of jvalue); native;",
    "Pascal_RemoteVM_CallNonVirtualObjectMethod", "Function RemoteVM.CallNonVirtualObjectMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jobject; native;",
    "Pascal_RemoteVM_CallNonVirtualBooleanMethod", "Function RemoteVM.CallNonVirtualBooleanMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jboolean; native;",
    "Pascal_RemoteVM_CallNonVirtualByteMethod", "Function RemoteVM.CallNonVirtualByteMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jbyte; native;",
    "Pascal_RemoteVM_CallNonVirtualCharMethod", "Function RemoteVM.CallNonVirtualCharMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jchar; native;",
    "Pascal_RemoteVM_CallNonVirtualShortMethod", "Function RemoteVM.CallNonVirtualShortMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jshort; native;",
    "Pascal_RemoteVM_CallNonVirtualIntMethod", "Function RemoteVM.CallNonVirtualIntMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jint; native;",
    "Pascal_RemoteVM_CallNonVirtualLongMethod", "Function RemoteVM.CallNonVirtualLongMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jlong; native;",
    "Pascal_RemoteVM_CallNonVirtualFloatMethod", "Function RemoteVM.CallNonVirtualFloatMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jfloat; native;",
    "Pascal_RemoteVM_CallNonVirtualDoubleMethod", "Function RemoteVM.CallNonVirtualDoubleMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue): jdouble; native;",
    "Pascal_RemoteVM_CallNonVirtualVoidMethod", "Procedure RemoteVM.CallNonVirtualVoidMethod(obj: jobject; clazz: jclass; methodID: jmethodID; args: array of jvalue); native;",
    "Pascal_RemoteVM_GetFieldID", "Function RemoteVM.GetFieldID(clazz: jclass; name, sig: string): jfieldID; native;",
    "Pascal_RemoteVM_GetObjectField", "Function RemoteVM.GetObjectField(obj: jobject; fieldID: jfieldID): jobject; native;",
    "Pascal_RemoteVM_GetBooleanField", "Function RemoteVM.GetBooleanField(obj: jobject; fieldID: jfieldID): jboolean; native;",
    "Pascal_RemoteVM_GetByteField", "Function RemoteVM.GetByteField(obj: jobject; fieldID: jfieldID): jbyte; native;",
    "Pascal_RemoteVM_GetCharField", "Function RemoteVM.GetCharField(obj: jobject; fieldID: jfieldID): jchar; native;",
    "Pascal_RemoteVM_GetShortField", "Function RemoteVM.GetShortField(obj: jobject; fieldID: jfieldID): jshort; native;",
    "Pascal_RemoteVM_GetIntField", "Function RemoteVM.GetIntField(obj: jobject; fieldID: jfieldID): jint; native;",
    "Pascal_RemoteVM_GetFloatField", "Function RemoteVM.GetFloatField(obj: jobject; fieldID: jfieldID): jfloat; native;",
    "Pascal_RemoteVM_GetDoubleField", "Function RemoteVM.GetDoubleField(obj: jobject; fieldID: jfieldID): jdouble; native;",
    "Pascal_RemoteVM_SetObjectField", "Procedure RemoteVM.SetObjectField(obj: jobject; fieldID: jfieldID; value: jobject); native;",
    "Pascal_RemoteVM_SetBooleanField", "Procedure RemoteVM.SetBooleanField(obj: jobject; fieldID: jfieldID; value: jboolean); native;",
    "Pascal_RemoteVM_SetByteField", "Procedure RemoteVM.SetByteField(obj: jobject; fieldID: jfieldID; value: jbyte); native;",
    "Pascal_RemoteVM_SetCharField", "Procedure RemoteVM.SetCharField(obj: jobject; fieldID: jfieldID; value: jchar); native;",
    "Pascal_RemoteVM_SetShortField", "Procedure RemoteVM.SetShortField(obj: jobject; fieldID: jfieldID; value: jshort); native;",
    "Pascal_RemoteVM_SetIntField", "Procedure RemoteVM.SetIntField(obj: jobject; fieldID: jfieldID; value: jint); native;",
    "Pascal_RemoteVM_SetFloatField", "Procedure RemoteVM.SetFloatField(obj: jobject; fieldID: jfieldID; value: jfloat); native;",
    "Pascal_RemoteVM_SetDoubleField", "Procedure RemoteVM.SetDoubleField(obj: jobject; fieldID: jfieldID; value: jdouble); native;",
    "Pascal_RemoteVM_GetStaticMethodID", "Function RemoteVM.GetStaticMethodID(clazz: jclass; name, sig: string): jmethodID; native;",
    "Pascal_RemoteVM_CallStaticObjectMethod", "Function RemoteVM.CallStaticObjectMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jobject; native;",
    "Pascal_RemoteVM_CallStaticBooleanMethod", "Function RemoteVM.CallStaticBooleanMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jboolean; native;",
    "Pascal_RemoteVM_CallStaticByteMethod", "Function RemoteVM.CallStaticByteMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jbyte; native;",
    "Pascal_RemoteVM_CallStaticCharMethod", "Function RemoteVM.CallStaticCharMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jchar; native;",
    "Pascal_RemoteVM_CallStaticShortMethod", "Function RemoteVM.CallStaticShortMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jshort; native;",
    "Pascal_RemoteVM_CallStaticIntMethod", "Function RemoteVM.CallStaticIntMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jint; native;",
    "Pascal_RemoteVM_CallStaticLongMethod", "Function RemoteVM.CallStaticLongMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jlong; native;",
    "Pascal_RemoteVM_CallStaticFloatMethod", "Function RemoteVM.CallStaticFloatMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jfloat; native;",
    "Pascal_RemoteVM_CallStaticDoubleMethod", "Function RemoteVM.CallStaticDoubleMethod(clazz: jclass; methodID: jmethodID; args: array of jvalue): jdouble; native;",
    "Pascal_RemoteVM_CallStaticVoidMethod", "Procedure RemoteVM.CallStaticVoidMethod(cls: jclass; methodID: jmethodID; args: array of jvalue); native;",
    "Pascal_RemoteVM_GetStaticFieldID", "Function RemoteVM.GetStaticFieldID(clazz: jclass; name: string; sign: string): jfieldID; native;",
    "Pascal_RemoteVM_GetStaticObjectField", "Function RemoteVM.GetStaticObjectField(clazz: jclass; fieldID: jfieldID): jobject; native;",
    "Pascal_RemoteVM_GetStaticBooleanField", "Function RemoteVM.GetStaticBooleanField(clazz: jclass; fieldID: jfieldID): jboolean; native;",
    "Pascal_RemoteVM_GetStaticByteField", "Function RemoteVM.GetStaticByteField(clazz: jclass; fieldID: jfieldID): jbyte; native;",
    "Pascal_RemoteVM_GetStaticCharField", "Function RemoteVM.GetStaticCharField(clazz: jclass; fieldID: jfieldID): jchar; native;",
    "Pascal_RemoteVM_GetStaticShortField", "Function RemoteVM.GetStaticShortField(clazz: jclass; fieldID: jfieldID): jshort; native;",
    "Pascal_RemoteVM_GetStaticIntField", "Function RemoteVM.GetStaticIntField(clazz: jclass; fieldID: jfieldID): jint; native;",
    "Pascal_RemoteVM_GetStaticFloatField", "Function RemoteVM.GetStaticFloatField(clazz: jclass; fieldID: jfieldID): jfloat; native;",
    "Pascal_RemoteVM_GetStaticDoubleField", "Function RemoteVM.GetStaticDoubleField(clazz: jclass; fieldID: jfieldID): jdouble; native;",
    "Pascal_RemoteVM_SetStaticObjectField", "Procedure RemoteVM.SetStaticObjectField(clazz: jclass; fieldID: jfieldID; value: jobject); native;",
    "Pascal_RemoteVM_SetStaticBooleanField", "Procedure RemoteVM.SetStaticBooleanField(clazz: jclass; fieldID: jfieldID; value: jboolean); native;",
    "Pascal_RemoteVM_SetStaticByteField", "Procedure RemoteVM.SetStaticByteField(clazz: jclass; fieldID: jfieldID; value: jbyte); native;",
    "Pascal_RemoteVM_SetStaticCharField", "Procedure RemoteVM.SetStaticCharField(clazz: jclass; fieldID: jfieldID; value: jchar); native;",
    "Pascal_RemoteVM_SetStaticShortField", "Procedure RemoteVM.SetStaticShortField(clazz: jclass; fieldID: jfieldID; value: jshort); native;",
    "Pascal_RemoteVM_SetStaticIntField", "Procedure RemoteVM.SetStaticIntField(clazz: jclass; fieldID: jfieldID; value: jint); native;",
    "Pascal_RemoteVM_SetStaticFloatField", "Procedure RemoteVM.SetStaticFloatField(clazz: jclass; fieldID: jfieldID; value: jfloat); native;",
    "Pascal_RemoteVM_SetStaticDoubleField", "Procedure RemoteVM.SetStaticDoubleField(clazz: jclass; fieldID: jfieldID; value: jdouble); native;",
    "Pascal_RemoteVM_NewString", "Function RemoteVM.NewString(unicode_string: widestring): jstring; native;",
    "Pascal_RemoteVM_GetStringLength", "Function RemoteVM.GetStringLength(str: jstring): jsize; native;",
    "Pascal_RemoteVM_GetStringChars", "Function RemoteVM.GetStringChars(str: jstring): widestring; native;",
    "Pascal_RemoteVM_NewStringUTF", "Function RemoteVM.NewStringUTF(utf_string: string): jstring; native;",
    "Pascal_RemoteVM_GetStringUTFLength", "Function RemoteVM.GetStringUTFLength(str: jstring): jsize; native;",
    "Pascal_RemoteVM_GetStringUTFChars", "Function RemoteVM.GetStringUTFChars(str: jstring): string; native;",
    "Pascal_RemoteVM_GetArrayLength", "Function RemoteVM.GetArrayLength(arr: jarray): jsize; native;",
    "Pascal_RemoteVM_NewObjectArray", "Function RemoteVM.NewObjectArray(len: jsize; clazz: jclass; init: jobject): jobjectArray; native;",
    "Pascal_RemoteVM_NewBooleanArray", "Function RemoteVM.NewBooleanArray(len: jsize): jbooleanArray; native;",
    "Pascal_RemoteVM_NewByteArray", "Function RemoteVM.NewByteArray(len: jsize): jbyteArray; native;",
    "Pascal_RemoteVM_NewCharArray", "Function RemoteVM.NewCharArray(len: jsize): jcharArray; native;",
    "Pascal_RemoteVM_NewShortArray", "Function RemoteVM.NewShortArray(len: jsize): jshortArray; native;",
    "Pascal_RemoteVM_NewIntArray", "Function RemoteVM.NewIntArray(len: jsize): jintArray; native;",
    "Pascal_RemoteVM_NewLongArray", "Function RemoteVM.NewLongArray(len: jsize): jlongArray; native;",
    "Pascal_RemoteVM_NewFloatArray", "Function RemoteVM.NewFloatArray(len: jsize): jfloatArray; native;",
    "Pascal_RemoteVM_NewDoubleArray", "Function RemoteVM.NewDoubleArray(len: jsize): jdoubleArray; native;",
    "Pascal_RemoteVM_GetObjectArrayElements", "Function RemoteVM.GetObjectArrayElements(arr: jobjectArray): array of jobject; native;",
    "Pascal_RemoteVM_SetObjectArrayElements", "Procedure RemoteVM.SetObjectArrayElements(arr: jobjectArray; index: jsize; elements: array of jobject); native;",
    "Pascal_RemoteVM_GetBooleanArrayElements", "Function RemoteVM.GetBooleanArrayElements(arr: jbooleanArray): array of jboolean; native;",
    "Pascal_RemoteVM_GetByteArrayElements", "Function RemoteVM.GetByteArrayElements(arr: jbyteArray): array of jbyte; native;",
    "Pascal_RemoteVM_GetCharArrayElements", "Function RemoteVM.GetCharArrayElements(arr: jcharArray): array of jchar; native;",
    "Pascal_RemoteVM_GetShortArrayElements", "Function RemoteVM.GetShortArrayElements(arr: jshortArray): array of jshort; native;",
    "Pascal_RemoteVM_GetIntArrayElements", "Function RemoteVM.GetIntArrayElements(arr: jintArray): array of jint; native;",
    "Pascal_RemoteVM_GetLongArrayElements", "Function RemoteVM.GetLongArrayElements(arr: jlongArray): array of jlong; native;",
    "Pascal_RemoteVM_GetFloatArrayElements", "Function RemoteVM.GetFloatArrayElements(arr: jfloatArray): array of jfloat; native;",
    "Pascal_RemoteVM_GetDoubleArrayElements", "Function RemoteVM.GetDoubleArrayElements(arr: jdoubleArray): array of jdouble; native;",
    "Pascal_RemoteVM_GetBooleanArrayRegion", "Function RemoteVM.GetBooleanArrayRegion(arr: jbooleanArray; start, len: jsize): array of jboolean; native;",
    "Pascal_RemoteVM_GetByteArrayRegion", "Function RemoteVM.GetByteArrayRegion(arr: jbyteArray; start, len: jsize): array of jbyte; native;",
    "Pascal_RemoteVM_GetCharArrayRegion", "Function RemoteVM.GetCharArrayRegion(arr: jcharArray; start, len: jsize): array of jchar; native;",
    "Pascal_RemoteVM_GetShortArrayRegion", "Function RemoteVM.GetShortArrayRegion(arr: jshortArray; start, len: jsize): array of jshort; native;",
    "Pascal_RemoteVM_GetIntArrayRegion", "Function RemoteVM.GetIntArrayRegion(arr: jintArray; start, len: jsize): array of jint; native;",
    "Pascal_RemoteVM_GetLongArrayRegion", "Function RemoteVM.GetLongArrayRegion(arr: jlongArray; start, len: jsize): array of jlong; native;",
    "Pascal_RemoteVM_GetFloatArrayRegion", "Function RemoteVM.GetFloatArrayRegion(arr: jfloatArray; start, len: jsize): array of jfloat; native;",
    "Pascal_RemoteVM_GetDoubleArrayRegion", "Function RemoteVM.GetDoubleArrayRegion(arr: jdoubleArray; start, len: jsize): array of jboolean; native;",
    "Pascal_RemoteVM_SetBooleanArrayRegion", "Procedure RemoteVM.SetBooleanArrayRegion(arr: jbooleanArray; start, len: jsize; buf: array of jboolean); native;",
    "Pascal_RemoteVM_SetByteArrayRegion", "Procedure RemoteVM.SetByteArrayRegion(arr: jbyteArray; start, len: jsize; buf: array of jbyte); native;",
    "Pascal_RemoteVM_SetCharArrayRegion", "Procedure RemoteVM.SetCharArrayRegion(arr: jcharArray; start, len: jsize; buf: array of jchar); native;",
    "Pascal_RemoteVM_SetShortArrayRegion", "Procedure RemoteVM.SetShortArrayRegion(arr: jshortArray; start, len: jsize; buf: array of jshort); native;",
    "Pascal_RemoteVM_SetIntArrayRegion", "Procedure RemoteVM.SetIntArrayRegion(arr: jintArray; start, len: jsize; buf: array of jint); native;",
    "Pascal_RemoteVM_SetLongArrayRegion", "Procedure RemoteVM.SetLongArrayRegion(arr: jlongArray; start, len: jsize; buf: array of jlong); native;",
    "Pascal_RemoteVM_SetFloatArrayRegion", "Procedure RemoteVM.SetFloatArrayRegion(arr: jfloatArray; start, len: jsize; buf: array of jfloat); native;",
    "Pascal_RemoteVM_SetDoubleArrayRegion", "Procedure RemoteVM.SetDoubleArrayRegion(arr: jdoubleArray; start, len: jsize; buf: array of jboolean); native;",
    "Pascal_RemoteVM_MonitorEnter", "Function RemoteVM.MonitorEnter(obj: jobject): jint; native;",
    "Pascal_RemoteVM_MonitorExit", "Function RemoteVM.MonitorExit(obj: jobject): jint; native;",
    //"Pascal_RemoteVM_GetJavaVM", "Function RemoteVM.GetJavaVM(): JavaVM; native;",
    "Pascal_RemoteVM_NewDirectByteBuffer", "Function RemoteVM.NewDirectByteBuffer(address: Pointer; capacity: jlong): jobject; native;",
    "Pascal_RemoteVM_GetDirectBufferAddress", "Function RemoteVM.GetDirectBufferAddress(buf: jobject): Pointer; native;",
    "Pascal_RemoteVM_GetDirectBufferCapacity", "Function RemoteVM.GetDirectBufferCapacity(buf: jobject): jlong; native;",
    "Pascal_RemoteVM_GetObjectRefType", "Function RemoteVM.GetObjectRefType(obj: jobject): jobjectRefType; native;"
};

static const char* PascalTypes[] =
{
    "ReflectionType", "(RI_CHAR = 0, RI_BYTE = 1, RI_BOOLEAN = 2, RI_SHORT = 3, RI_INT = 4, RI_LONG = 5, RI_FLOAT = 6, RI_DOUBLE = 7, RI_STRING = 8, RI_OBJECT = 9)",
    "RIImageFormat", "(RI_BGR_BGRA = 0, RI_BGRA = 1, RI_RGBA = 2, RI_ARGB = 3, RI_ABGR = 4)",
    "RIField", "packed record cls, field, desc: String; end;",

    //JVM Types
    "jint", "Int32",
    "jlong", "Int64",
    "jbyte", "UInt8",
    "jboolean", "ByteBool",
    "jchar", "UInt16",
    "jshort", "Int16",
    "jfloat", "Single",
    "jdouble", "Double",
    "jsize", "jint",

    "jobject", "type Pointer",
    "jclass", "type jobject",
    "jthrowable", "type jobject",
    "jstring", "type jobject",
    "jarray", "type jobject",

    "jbooleanArray", "type jarray",
    "jbyteArray", "type jarray",
    "jcharArray", "type jarray",
    "jshortArray", "type jarray",
    "jintArray", "type jarray",
    "jlongArray", "type jarray",
    "jfloatArray", "type jarray",
    "jdoubleArray", "type jarray",
    "jobjectArray", "type jarray",

    "jweak", "type jobject",
    "jfieldID", "type Pointer",
    "jmethodID", "type Pointer",

    "jvalue", "union z: jboolean; b: jbyte; c: jchar; s: jshort; i: jint; j: jlong; f: jfloat; d: jdouble; l: jobject; end;",
    "jobjectRefType", "(JNIInvalidRefType = 0, JNILocalRefType = 1, JNIGlobalRefType = 2, JNIWeakGlobalRefType = 3)",

    "RemoteVM", "type Pointer"
};

static const long int PascalExportCount = sizeof(PascalExports) / (sizeof(PascalExports[0]) * 2);
static const long int PascalTypeCount = sizeof(PascalTypes) / (sizeof(PascalTypes[0]) * 2);


#ifdef __cplusplus
extern "C"
{
#endif

EXPORT int GetPluginABIVersion() noexcept;
EXPORT int GetFunctionCount() noexcept;
EXPORT int GetTypeCount() noexcept;
EXPORT int GetFunctionInfo(int Index, void** Address, char** Definition) noexcept;
EXPORT int GetTypeInfo(int Index, char** Type, char** Definition) noexcept;

#if defined(PASCAL_CALLING_CONVENTION)
EXPORT void SetPluginMemManager(TMemoryManager MemMgr) noexcept;
#endif

EXPORT void SetPluginSimbaMethods(TSimbaMethods Methods) noexcept;
EXPORT void SetPluginSimbaMemoryAllocators(TSimbaMemoryAllocators Allocators) noexcept;
EXPORT void RegisterSimbaPlugin(TSimbaInfomation* Information, TSimbaMethodsExtended* Methods) noexcept;
EXPORT void OnAttach(void* info) noexcept;
EXPORT void OnDetach() noexcept;

#ifdef __cplusplus
}
#endif

#ifdef __cplusplus
extern "C"
{
#endif

EXPORT void Pascal_Reflect_ClassLoader(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Equal(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_InstanceOf(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Object(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Release_Object(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Release_Objects(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Boolean(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Char(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Byte(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Short(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Int(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Long(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Float(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Double(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_String(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_With_Size(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_Size(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_Indices(void** Params, void** Result) noexcept;

EXPORT void Pascal_Reflect_Array_SingleIndex(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_SingleIndex2D(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_SingleIndex3D(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_SingleIndex4D(void** Params, void** Result) noexcept;

EXPORT void Pascal_Reflect_Array_Index(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_Index2D(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_Index3D(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Array_Index4D(void** Params, void** Result) noexcept;

EXPORT void Pascal_Reflect_Class_Name(void** Params, void** Result) noexcept;
EXPORT void Pascal_Reflect_Class_Type(void** Params, void** Result) noexcept;

EXPORT void Pascal_GetImageFormat(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetImageFormat(void** Params, void** Result) noexcept;
EXPORT void Pascal_GetDebugImageBuffer(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetGraphicsDebugging(void** Params, void** Result) noexcept;
EXPORT void Pascal_PairClient(void** Params, void** Result) noexcept;
EXPORT void Pascal_KillClientPID(void** Params, void** Result) noexcept;
EXPORT void Pascal_KillClient(void** Params, void** Result) noexcept;
EXPORT void Pascal_ReleaseClient(void** Params, void** Result) noexcept;
EXPORT void Pascal_GetClients(void** Params, void** Result) noexcept;
EXPORT void Pascal_GetClientPID(void** Params, void** Result) noexcept;

EXPORT void Pascal_Inject(void** Params, void** Result) noexcept;
EXPORT void Pascal_Inject_PID(void** Params, void** Result) noexcept;

EXPORT void Pascal_HasFocus(void** Params, void** Result) noexcept;
EXPORT void Pascal_GainFocus(void** Params, void** Result) noexcept;
EXPORT void Pascal_LoseFocus(void** Params, void** Result) noexcept;
EXPORT void Pascal_IsKeyboardInputEnabled(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetKeyboardInputEnabled(void** Params, void** Result) noexcept;
EXPORT void Pascal_IsMouseInputEnabled(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetMouseInputEnabled(void** Params, void** Result) noexcept;
EXPORT void Pascal_GetRealMousePosition(void** Params, void** Result) noexcept;

EXPORT void Pascal_GetKeyboardSpeed(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetKeyboardSpeed(void** Params, void** Result) noexcept;
EXPORT void Pascal_GetKeyboardRepeatDelay(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetKeyboardRepeatDelay(void** Params, void** Result) noexcept;
EXPORT void Pascal_GetGraphicsScaling(void** Params, void** Result) noexcept;
EXPORT void Pascal_SetGraphicsScaling(void** Params, void** Result) noexcept;

// MARK: - RemoteVM
EXPORT void Pascal_RemoteVM_Init(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_InitEx(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_Free(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_MaxMemoryChunkSize(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_AllocateMemory(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_ReadMemory(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_WriteMemory(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_FreeMemory(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetVersion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_DefineClass(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_FindClass(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_FromReflectedMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_FromReflectedField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_ToReflectedMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetSuperclass(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_IsAssignableFrom(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_ToReflectedField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_Throw(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_ThrowNew(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetExceptionMessage(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_FatalError(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_DeleteGlobalRef(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_IsSameObject(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_AllocObject(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewObject(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetObjectClass(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_IsInstanceOf(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetMethodID(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallObjectMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallBooleanMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallByteMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallCharMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallShortMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallIntMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallLongMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallFloatMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallDoubleMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallVoidMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualObjectMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualBooleanMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualByteMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualCharMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualShortMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualIntMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualLongMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualFloatMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualDoubleMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallNonvirtualVoidMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetFieldID(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetObjectField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetBooleanField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetByteField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetCharField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetShortField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetIntField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetLongField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetFloatField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetDoubleField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetObjectField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetBooleanField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetByteField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetCharField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetShortField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetIntField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetLongField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetFloatField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetDoubleField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticMethodID(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticObjectMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticBooleanMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticByteMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticCharMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticShortMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticIntMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticLongMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticFloatMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticDoubleMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_CallStaticVoidMethod(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticFieldID(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticObjectField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticBooleanField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticByteField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticCharField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticShortField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticIntField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticLongField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticFloatField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStaticDoubleField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticObjectField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticBooleanField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticByteField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticCharField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticShortField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticIntField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticLongField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticFloatField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetStaticDoubleField(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewString(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStringLength(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStringChars(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewStringUTF(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStringUTFLength(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetStringUTFChars(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetArrayLength(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewObjectArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewBooleanArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewByteArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewCharArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewShortArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewIntArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewLongArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewFloatArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewDoubleArray(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetObjectArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetObjectArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetBooleanArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetByteArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetCharArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetShortArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetIntArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetLongArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetFloatArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetDoubleArrayElements(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetBooleanArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetByteArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetCharArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetShortArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetIntArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetLongArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetFloatArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetDoubleArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetBooleanArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetByteArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetCharArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetShortArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetIntArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetLongArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetFloatArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_SetDoubleArrayRegion(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_MonitorEnter(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_MonitorExit(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetJavaVM(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_NewDirectByteBuffer(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetDirectBufferAddress(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetDirectBufferCapacity(void** Params, void** Result);
EXPORT void Pascal_RemoteVM_GetObjectRefType(void** Params, void** Result);

#ifdef __cplusplus
}
#endif

#endif // SIMBAPLUGIN_HXX_INCLUDED
