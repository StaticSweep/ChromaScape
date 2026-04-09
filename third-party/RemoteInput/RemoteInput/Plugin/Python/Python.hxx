//
// Created by Brandon on 2023-02-16.
//

#ifndef REMOTEINPUT_PYTHON_HXX
#define REMOTEINPUT_PYTHON_HXX

#if !defined(USE_PYBIND11)
#include "PythonMacros.hxx"

class Python
{
private:
    #if defined(_WIN32) || defined(_WIN64)
    HMODULE module;
    #else
    void* module;
    #endif // defined

    void steal(Python& a, Python& b);

    void* Py_NoneStruct_Ptr;
    void* Py_TrueStruct_Ptr;
    void* Py_FalseStruct_Ptr;
    void* Py_ListType_Ptr;

    int (*PyAIter_Check_Ptr)(PyObject*);
    int (*PyArg_Parse_Ptr)(PyObject*, const char*, ...);
    int (*PyArg_ParseTuple_Ptr)(PyObject*, const char*, ...);
    int (*PyArg_ParseTupleAndKeywords_Ptr)(PyObject*, PyObject*, const char*, char**, ...);
    int (*PyArg_UnpackTuple_Ptr)(PyObject*, const char*, Py_ssize_t, Py_ssize_t, ...);
    int (*PyArg_VaParse_Ptr)(PyObject*, const char*, va_list);
    int (*PyArg_VaParseTupleAndKeywords_Ptr)(PyObject*, PyObject*, const char*, char**, va_list);
    int (*PyArg_ValidateKeywordArguments_Ptr)(PyObject*);
    PyObject* (*PyBool_FromLong_Ptr)(long);

    #if HAS_PYTHON_VERSION(0x030B1000)
    void (*PyBuffer_FillContiguousStrides_Ptr)(int, Py_ssize_t*, Py_ssize_t*, int, char);
    int (*PyBuffer_FillInfo_Ptr)(Py_buffer*, PyObject*, void*, Py_ssize_t, int, int);
    int (*PyBuffer_FromContiguous_Ptr)(const Py_buffer*, const void*, Py_ssize_t, char);
    void* (*PyBuffer_GetPointer_Ptr)(const Py_buffer*, const Py_ssize_t*);
    int (*PyBuffer_IsContiguous_Ptr)(const Py_buffer*, char);
    void (*PyBuffer_Release_Ptr)(Py_buffer*);
    Py_ssize_t (*PyBuffer_SizeFromFormat_Ptr)(const char*);
    int (*PyBuffer_ToContiguous_Ptr)(void*, const Py_buffer*, Py_ssize_t, char);
    #endif

    char* (*PyByteArray_AsString_Ptr)(PyObject*);
    PyObject* (*PyByteArray_Concat_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyByteArray_FromObject_Ptr)(PyObject*);
    PyObject* (*PyByteArray_FromStringAndSize_Ptr)(const char*, Py_ssize_t);
    int (*PyByteArray_Resize_Ptr)(PyObject*, Py_ssize_t);
    Py_ssize_t (*PyByteArray_Size_Ptr)(PyObject*);
    char* (*PyBytes_AsString_Ptr)(PyObject*);
    int (*PyBytes_AsStringAndSize_Ptr)(PyObject*, char**, Py_ssize_t*);
    void (*PyBytes_Concat_Ptr)(PyObject**, PyObject*);
    void (*PyBytes_ConcatAndDel_Ptr)(PyObject**, PyObject*);
    PyObject* (*PyBytes_DecodeEscape_Ptr)(const char*, Py_ssize_t, const char*, Py_ssize_t, const char*);
    PyObject* (*PyBytes_FromFormat_Ptr)(const char*, ...);
    PyObject* (*PyBytes_FromFormatV_Ptr)(const char*, va_list);
    PyObject* (*PyBytes_FromObject_Ptr)(PyObject*);
    PyObject* (*PyBytes_FromString_Ptr)(const char*);
    PyObject* (*PyBytes_FromStringAndSize_Ptr)(const char*, Py_ssize_t);
    PyObject* (*PyBytes_Repr_Ptr)(PyObject*, int);
    Py_ssize_t (*PyBytes_Size_Ptr)(PyObject*);
    PyObject* (*PyCFunction_Call_Ptr)(PyObject*, PyObject*, PyObject*);
    int (*PyCFunction_GetFlags_Ptr)(PyObject*);
    PyCFunction (*PyCFunction_GetFunction_Ptr)(PyObject*);
    PyObject* (*PyCFunction_GetSelf_Ptr)(PyObject*);

    PyObject* (*PyCFunction_New_Ptr)(PyMethodDef*, PyObject*);

    #if HAS_PYTHON_VERSION(0x03090000)
    PyObject* (*PyCFunction_NewEx_Ptr)(PyMethodDef*, PyObject*, PyObject*);
    #endif

    PyObject* (*PyCMethod_New_Ptr)(PyMethodDef*, PyObject*, PyObject*, PyTypeObject*);
    PyObject* (*PyCallIter_New_Ptr)(PyObject*, PyObject*);
    int (*PyCallable_Check_Ptr)(PyObject*);
    void* (*PyCapsule_GetContext_Ptr)(PyObject*);
    PyCapsule_Destructor (*PyCapsule_GetDestructor_Ptr)(PyObject*);
    const char* (*PyCapsule_GetName_Ptr)(PyObject*);
    void* (*PyCapsule_GetPointer_Ptr)(PyObject*, const char*);
    void* (*PyCapsule_Import_Ptr)(const char*, int);
    int (*PyCapsule_IsValid_Ptr)(PyObject*, const char*);
    PyObject* (*PyCapsule_New_Ptr)(void*, const char*, PyCapsule_Destructor);
    int (*PyCapsule_SetContext_Ptr)(PyObject*, void*);
    int (*PyCapsule_SetDestructor_Ptr)(PyObject*, PyCapsule_Destructor);
    int (*PyCapsule_SetName_Ptr)(PyObject*, const char*);
    int (*PyCapsule_SetPointer_Ptr)(PyObject*, void*);
    PyObject* (*PyComplex_FromDoubles_Ptr)(double, double);
    double (*PyComplex_ImagAsDouble_Ptr)(PyObject*);
    double (*PyComplex_RealAsDouble_Ptr)(PyObject*);
    PyObject* (*PyDescr_NewClassMethod_Ptr)(PyTypeObject*, PyMethodDef*);
    PyObject* (*PyDescr_NewGetSet_Ptr)(PyTypeObject*, PyGetSetDef*);
    PyObject* (*PyDescr_NewMember_Ptr)(PyTypeObject*, PyMemberDef*);
    PyObject* (*PyDescr_NewMethod_Ptr)(PyTypeObject*, PyMethodDef*);
    PyObject* (*PyDictProxy_New_Ptr)(PyObject*);
    void (*PyDict_Clear_Ptr)(PyObject*);
    int (*PyDict_Contains_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyDict_Copy_Ptr)(PyObject*);
    int (*PyDict_DelItem_Ptr)(PyObject*, PyObject*);
    int (*PyDict_DelItemString_Ptr)(PyObject*, const char*);
    PyObject* (*PyDict_GetItem_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyDict_GetItemString_Ptr)(PyObject*, const char*);
    PyObject* (*PyDict_GetItemWithError_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyDict_Items_Ptr)(PyObject*);
    PyObject* (*PyDict_Keys_Ptr)(PyObject*);
    int (*PyDict_Merge_Ptr)(PyObject*, PyObject*, int);
    int (*PyDict_MergeFromSeq2_Ptr)(PyObject*, PyObject*, int);
    PyObject* (*PyDict_New_Ptr)();
    int (*PyDict_Next_Ptr)(PyObject*, Py_ssize_t*, PyObject**, PyObject**);
    int (*PyDict_SetItem_Ptr)(PyObject*, PyObject*, PyObject*);
    int (*PyDict_SetItemString_Ptr)(PyObject*, const char*, PyObject*);
    Py_ssize_t (*PyDict_Size_Ptr)(PyObject*);
    int (*PyDict_Update_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyDict_Values_Ptr)(PyObject*);
    int (*PyErr_BadArgument_Ptr)();

    #ifdef PyErr_BadInternalCall
    void (*_PyErr_BadInternalCall_Ptr)(const char*, int);
    #endif

    int (*PyErr_CheckSignals_Ptr)();
    void (*PyErr_Clear_Ptr)();
    void (*PyErr_Display_Ptr)(PyObject*, PyObject*, PyObject*);
    int (*PyErr_ExceptionMatches_Ptr)(PyObject*);
    void (*PyErr_Fetch_Ptr)(PyObject**, PyObject**, PyObject**);
    PyObject* (*PyErr_Format_Ptr)(PyObject*, const char*, ...);
    PyObject* (*PyErr_FormatV_Ptr)(PyObject*, const char*, va_list);
    void (*PyErr_GetExcInfo_Ptr)(PyObject**, PyObject**, PyObject**);
    PyObject* (*PyErr_GetHandledException_Ptr)();
    int (*PyErr_GivenExceptionMatches_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyErr_NewException_Ptr)(const char*, PyObject*, PyObject*);
    PyObject* (*PyErr_NewExceptionWithDoc_Ptr)(const char*, const char*, PyObject*, PyObject*);
    PyObject* (*PyErr_NoMemory_Ptr)();
    void (*PyErr_NormalizeException_Ptr)(PyObject**, PyObject**, PyObject**);
    PyObject* (*PyErr_Occurred_Ptr)();
    void (*PyErr_Print_Ptr)();
    void (*PyErr_PrintEx_Ptr)(int);
    PyObject* (*PyErr_ProgramText_Ptr)(const char*, int);
    int (*PyErr_ResourceWarning_Ptr)(PyObject*, Py_ssize_t, const char*, ...);
    void (*PyErr_Restore_Ptr)(PyObject*, PyObject*, PyObject*);
    void (*PyErr_SetExcInfo_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyErr_SetFromErrno_Ptr)(PyObject*);
    PyObject* (*PyErr_SetFromErrnoWithFilename_Ptr)(PyObject*, const char*);
    PyObject* (*PyErr_SetFromErrnoWithFilenameObject_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyErr_SetFromErrnoWithFilenameObjects_Ptr)(PyObject*, PyObject*, PyObject*);
    void (*PyErr_SetHandledException_Ptr)(PyObject*);
    PyObject* (*PyErr_SetImportError_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyErr_SetImportErrorSubclass_Ptr)(PyObject*, PyObject*, PyObject*, PyObject*);
    void (*PyErr_SetInterrupt_Ptr)();
    int (*PyErr_SetInterruptEx_Ptr)(int);
    void (*PyErr_SetNone_Ptr)(PyObject*);
    void (*PyErr_SetObject_Ptr)(PyObject*, PyObject*);
    void (*PyErr_SetString_Ptr)(PyObject*, const char*);
    void (*PyErr_SyntaxLocation_Ptr)(const char*, int);
    void (*PyErr_SyntaxLocationEx_Ptr)(const char*, int, int);
    int (*PyErr_WarnEx_Ptr)(PyObject*, const char*, Py_ssize_t);
    int (*PyErr_WarnExplicit_Ptr)(PyObject*, const char*, const char*, int, const char*, PyObject*);
    int (*PyErr_WarnFormat_Ptr)(PyObject*, Py_ssize_t, const char*, ...);
    void (*PyErr_WriteUnraisable_Ptr)(PyObject*);
    void (*PyEval_AcquireLock_Ptr)();
    void (*PyEval_AcquireThread_Ptr)(PyThreadState*);
    PyObject* (*PyEval_CallFunction_Ptr)(PyObject*, const char*, ...);
    PyObject* (*PyEval_CallMethod_Ptr)(PyObject*, const char*, const char*, ...);
    PyObject* (*PyEval_CallObjectWithKeywords_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyEval_EvalCode_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyEval_EvalCodeEx_Ptr)(PyObject*, PyObject*, PyObject*, PyObject*const*, int, PyObject*const*, int, PyObject*const*, int, PyObject*, PyObject*);
    #if HAS_PYTHON_VERSION(0x03090000)
    PyObject* (*PyEval_EvalFrame_Ptr)(PyFrameObject*);
    PyObject* (*PyEval_EvalFrameEx_Ptr)(PyFrameObject*, int);
    #endif
    PyObject* (*PyEval_GetBuiltins_Ptr)();
    #if HAS_PYTHON_VERSION(0x03090000)
    PyFrameObject* (*PyEval_GetFrame_Ptr)();
    #endif
    const char* (*PyEval_GetFuncDesc_Ptr)(PyObject*);
    const char* (*PyEval_GetFuncName_Ptr)(PyObject*);
    PyObject* (*PyEval_GetGlobals_Ptr)();
    PyObject* (*PyEval_GetLocals_Ptr)();
    void (*PyEval_InitThreads_Ptr)();
    void (*PyEval_ReleaseLock_Ptr)();
    void (*PyEval_ReleaseThread_Ptr)(PyThreadState*);
    void (*PyEval_RestoreThread_Ptr)(PyThreadState*);
    PyThreadState* (*PyEval_SaveThread_Ptr)();
    int (*PyEval_ThreadsInitialized_Ptr)();
    const char* (*PyExceptionClass_Name_Ptr)(PyObject*);
    PyObject* (*PyException_GetCause_Ptr)(PyObject*);
    PyObject* (*PyException_GetContext_Ptr)(PyObject*);
    PyObject* (*PyException_GetTraceback_Ptr)(PyObject*);
    void (*PyException_SetCause_Ptr)(PyObject*, PyObject*);
    void (*PyException_SetContext_Ptr)(PyObject*, PyObject*);
    int (*PyException_SetTraceback_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyFile_FromFd_Ptr)(int, const char*, const char*, int, const char*, const char*, const char*, int);
    PyObject* (*PyFile_GetLine_Ptr)(PyObject*, int);
    int (*PyFile_WriteObject_Ptr)(PyObject*, PyObject*, int);
    int (*PyFile_WriteString_Ptr)(const char*, PyObject*);
    double (*PyFloat_AsDouble_Ptr)(PyObject*);
    PyObject* (*PyFloat_FromDouble_Ptr)(double);
    PyObject* (*PyFloat_FromString_Ptr)(PyObject*);
    PyObject* (*PyFloat_GetInfo_Ptr)();
    double (*PyFloat_GetMax_Ptr)();
    double (*PyFloat_GetMin_Ptr)();
    PyObject* (*PyFrozenSet_New_Ptr)(PyObject*);
    Py_ssize_t (*PyGC_Collect_Ptr)();
    int (*PyGC_Disable_Ptr)();
    int (*PyGC_Enable_Ptr)();
    int (*PyGC_IsEnabled_Ptr)();
    PyGILState_STATE (*PyGILState_Ensure_Ptr)();
    PyThreadState* (*PyGILState_GetThisThreadState_Ptr)();
    void (*PyGILState_Release_Ptr)(PyGILState_STATE);
    PyObject* (*PyImport_AddModule_Ptr)(const char*);
    PyObject* (*PyImport_AddModuleObject_Ptr)(PyObject*);
    int (*PyImport_AppendInittab_Ptr)(const char*, PyObject*(*)());
    PyObject* (*PyImport_ExecCodeModule_Ptr)(const char*, PyObject*);
    PyObject* (*PyImport_ExecCodeModuleEx_Ptr)(const char*, PyObject*, const char*);
    PyObject* (*PyImport_ExecCodeModuleObject_Ptr)(PyObject*, PyObject*, PyObject*, PyObject*);
    PyObject* (*PyImport_ExecCodeModuleWithPathnames_Ptr)(const char*, PyObject*, const char*, const char*);
    PyObject* (*PyImport_GetImporter_Ptr)(PyObject*);
    long (*PyImport_GetMagicNumber_Ptr)();
    const char* (*PyImport_GetMagicTag_Ptr)();
    PyObject* (*PyImport_GetModule_Ptr)(PyObject*);
    PyObject* (*PyImport_GetModuleDict_Ptr)();
    PyObject* (*PyImport_Import_Ptr)(PyObject*);
    int (*PyImport_ImportFrozenModule_Ptr)(const char*);
    int (*PyImport_ImportFrozenModuleObject_Ptr)(PyObject*);
    PyObject* (*PyImport_ImportModule_Ptr)(const char*);
    PyObject* (*PyImport_ImportModuleLevel_Ptr)(const char*, PyObject*, PyObject*, PyObject*, int);
    PyObject* (*PyImport_ImportModuleLevelObject_Ptr)(PyObject*, PyObject*, PyObject*, PyObject*, int);
    PyObject* (*PyImport_ImportModuleNoBlock_Ptr)(const char*);
    PyObject* (*PyImport_ReloadModule_Ptr)(PyObject*);
    int (*PyIndex_Check_Ptr)(PyObject*);
    void (*PyInterpreterState_Clear_Ptr)(PyInterpreterState*);
    void (*PyInterpreterState_Delete_Ptr)(PyInterpreterState*);
    PyInterpreterState* (*PyInterpreterState_Get_Ptr)();
    PyObject* (*PyInterpreterState_GetDict_Ptr)(PyInterpreterState*);
    int64_t (*PyInterpreterState_GetID_Ptr)(PyInterpreterState*);
    PyInterpreterState* (*PyInterpreterState_New_Ptr)();
    int (*PyIter_Check_Ptr)(PyObject*);
    PyObject* (*PyIter_Next_Ptr)(PyObject*);
    #if HAS_PYTHON_VERSION(0x030A0000)
    PySendResult (*PyIter_Send_Ptr)(PyObject*, PyObject*, PyObject**);
    #endif
    int (*PyList_Append_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyList_AsTuple_Ptr)(PyObject*);
    PyObject* (*PyList_GetItem_Ptr)(PyObject*, Py_ssize_t);
    PyObject* (*PyList_GetSlice_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t);
    int (*PyList_Insert_Ptr)(PyObject*, Py_ssize_t, PyObject*);
    PyObject* (*PyList_New_Ptr)(Py_ssize_t);
    int (*PyList_Reverse_Ptr)(PyObject*);
    int (*PyList_SetItem_Ptr)(PyObject*, Py_ssize_t, PyObject*);
    int (*PyList_SetSlice_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t, PyObject*);
    Py_ssize_t (*PyList_Size_Ptr)(PyObject*);
    int (*PyList_Sort_Ptr)(PyObject*);
    double (*PyLong_AsDouble_Ptr)(PyObject*);
    long (*PyLong_AsLong_Ptr)(PyObject*);
    long (*PyLong_AsLongAndOverflow_Ptr)(PyObject*, int*);
    long long (*PyLong_AsLongLong_Ptr)(PyObject*);
    long long (*PyLong_AsLongLongAndOverflow_Ptr)(PyObject*, int*);
    size_t (*PyLong_AsSize_t_Ptr)(PyObject*);
    Py_ssize_t (*PyLong_AsSsize_t_Ptr)(PyObject*);
    unsigned long (*PyLong_AsUnsignedLong_Ptr)(PyObject*);
    unsigned long long (*PyLong_AsUnsignedLongLong_Ptr)(PyObject*);
    unsigned long long (*PyLong_AsUnsignedLongLongMask_Ptr)(PyObject*);
    unsigned long (*PyLong_AsUnsignedLongMask_Ptr)(PyObject*);
    void* (*PyLong_AsVoidPtr_Ptr)(PyObject*);
    PyObject* (*PyLong_FromDouble_Ptr)(double);
    PyObject* (*PyLong_FromLong_Ptr)(long);
    PyObject* (*PyLong_FromLongLong_Ptr)(long long);
    PyObject* (*PyLong_FromSize_t_Ptr)(size_t);
    PyObject* (*PyLong_FromSsize_t_Ptr)(Py_ssize_t);
    PyObject* (*PyLong_FromString_Ptr)(const char*, char**, int);
    PyObject* (*PyLong_FromUnsignedLong_Ptr)(unsigned long);
    PyObject* (*PyLong_FromUnsignedLongLong_Ptr)(unsigned long long);
    PyObject* (*PyLong_FromVoidPtr_Ptr)(void*);
    PyObject* (*PyLong_GetInfo_Ptr)();
    int (*PyMapping_Check_Ptr)(PyObject*);
    PyObject* (*PyMapping_GetItemString_Ptr)(PyObject*, const char*);
    int (*PyMapping_HasKey_Ptr)(PyObject*, PyObject*);
    int (*PyMapping_HasKeyString_Ptr)(PyObject*, const char*);
    PyObject* (*PyMapping_Items_Ptr)(PyObject*);
    PyObject* (*PyMapping_Keys_Ptr)(PyObject*);
    Py_ssize_t (*PyMapping_Length_Ptr)(PyObject*);
    int (*PyMapping_SetItemString_Ptr)(PyObject*, const char*, PyObject*);
    Py_ssize_t (*PyMapping_Size_Ptr)(PyObject*);
    PyObject* (*PyMapping_Values_Ptr)(PyObject*);
    void* (*PyMem_Calloc_Ptr)(size_t, size_t);
    void (*PyMem_Free_Ptr)(void*);
    void* (*PyMem_Malloc_Ptr)(size_t);
    void* (*PyMem_Realloc_Ptr)(void*, size_t);
    #if HAS_PYTHON_VERSION(0x030B1000)
    PyObject* (*PyMemoryView_FromBuffer_Ptr)(const Py_buffer*);
    #endif
    PyObject* (*PyMemoryView_FromMemory_Ptr)(char*, Py_ssize_t, int);
    PyObject* (*PyMemoryView_FromObject_Ptr)(PyObject*);
    PyObject* (*PyMemoryView_GetContiguous_Ptr)(PyObject*, int, char);
    PyObject* (*PyModuleDef_Init_Ptr)(PyModuleDef*);
    int (*PyModule_AddFunctions_Ptr)(PyObject*, PyMethodDef*);
    int (*PyModule_AddIntConstant_Ptr)(PyObject*, const char*, long);
    int (*PyModule_AddObject_Ptr)(PyObject*, const char*, PyObject*);
    int (*PyModule_AddObjectRef_Ptr)(PyObject*, const char*, PyObject*);
    int (*PyModule_AddStringConstant_Ptr)(PyObject*, const char*, const char*);
    int (*PyModule_AddType_Ptr)(PyObject*, PyTypeObject*);
    PyObject* (*PyModule_Create2_Ptr)(PyModuleDef*, int);
    int (*PyModule_ExecDef_Ptr)(PyObject*, PyModuleDef*);
    PyObject* (*PyModule_FromDefAndSpec2_Ptr)(PyModuleDef*, PyObject*, int);
    PyModuleDef* (*PyModule_GetDef_Ptr)(PyObject*);
    PyObject* (*PyModule_GetDict_Ptr)(PyObject*);
    const char* (*PyModule_GetFilename_Ptr)(PyObject*);
    PyObject* (*PyModule_GetFilenameObject_Ptr)(PyObject*);
    const char* (*PyModule_GetName_Ptr)(PyObject*);
    PyObject* (*PyModule_GetNameObject_Ptr)(PyObject*);
    void* (*PyModule_GetState_Ptr)(PyObject*);
    PyObject* (*PyModule_New_Ptr)(const char*);
    PyObject* (*PyModule_NewObject_Ptr)(PyObject*);
    int (*PyModule_SetDocString_Ptr)(PyObject*, const char*);
    PyObject* (*PyNumber_Absolute_Ptr)(PyObject*);
    PyObject* (*PyNumber_Add_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_And_Ptr)(PyObject*, PyObject*);
    Py_ssize_t (*PyNumber_AsSsize_t_Ptr)(PyObject*, PyObject*);
    int (*PyNumber_Check_Ptr)(PyObject*);
    PyObject* (*PyNumber_Divmod_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Float_Ptr)(PyObject*);
    PyObject* (*PyNumber_FloorDivide_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceAdd_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceAnd_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceFloorDivide_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceLshift_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceMatrixMultiply_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceMultiply_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceOr_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlacePower_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceRemainder_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceRshift_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceSubtract_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceTrueDivide_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_InPlaceXor_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Index_Ptr)(PyObject*);
    PyObject* (*PyNumber_Invert_Ptr)(PyObject*);
    PyObject* (*PyNumber_Long_Ptr)(PyObject*);
    PyObject* (*PyNumber_Lshift_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_MatrixMultiply_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Multiply_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Negative_Ptr)(PyObject*);
    PyObject* (*PyNumber_Or_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Positive_Ptr)(PyObject*);
    PyObject* (*PyNumber_Power_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyNumber_Remainder_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Rshift_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Subtract_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_ToBase_Ptr)(PyObject*, int);
    PyObject* (*PyNumber_TrueDivide_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyNumber_Xor_Ptr)(PyObject*, PyObject*);
    void (*PyOS_AfterFork_Ptr)();
    void (*PyOS_AfterFork_Child_Ptr)();
    void (*PyOS_AfterFork_Parent_Ptr)();
    void (*PyOS_BeforeFork_Ptr)();
    PyObject* (*PyOS_FSPath_Ptr)(PyObject*);
    int (*PyOS_InterruptOccurred_Ptr)();
    char* (*PyOS_double_to_string_Ptr)(double, char, int, int, int*);
    PyOS_sighandler_t (*PyOS_getsig_Ptr)(int);
    int (*PyOS_mystricmp_Ptr)(const char*, const char*);
    int (*PyOS_mystrnicmp_Ptr)(const char*, const char*, Py_ssize_t);
    PyOS_sighandler_t (*PyOS_setsig_Ptr)(int, PyOS_sighandler_t);
    int (*PyOS_snprintf_Ptr)(char*, size_t, const char*, ...);
    double (*PyOS_string_to_double_Ptr)(const char*, char**, PyObject*);
    long (*PyOS_strtol_Ptr)(const char*, char**, int);
    unsigned long (*PyOS_strtoul_Ptr)(const char*, char**, int);
    int (*PyOS_vsnprintf_Ptr)(char*, size_t, const char*, va_list);
    PyObject* (*PyObject_ASCII_Ptr)(PyObject*);
    int (*PyObject_AsCharBuffer_Ptr)(PyObject*, const char**, Py_ssize_t*);
    int (*PyObject_AsFileDescriptor_Ptr)(PyObject*);
    int (*PyObject_AsReadBuffer_Ptr)(PyObject*, const void**, Py_ssize_t*);
    int (*PyObject_AsWriteBuffer_Ptr)(PyObject*, void**, Py_ssize_t*);
    PyObject* (*PyObject_Bytes_Ptr)(PyObject*);
    PyObject* (*PyObject_Call_Ptr)(PyObject*, PyObject*, PyObject*);
    PyObject* (*PyObject_CallFunction_Ptr)(PyObject*, const char*, ...);
    PyObject* (*PyObject_CallFunctionObjArgs_Ptr)(PyObject*, ...);
    PyObject* (*PyObject_CallMethod_Ptr)(PyObject*, const char*, const char*, ...);
    PyObject* (*PyObject_CallMethodObjArgs_Ptr)(PyObject*, PyObject*, ...);
    PyObject* (*PyObject_CallNoArgs_Ptr)(PyObject*);
    PyObject* (*PyObject_CallObject_Ptr)(PyObject*, PyObject*);
    void* (*PyObject_Calloc_Ptr)(size_t, size_t);
    int (*PyObject_CheckBuffer_Ptr)(PyObject*);
    int (*PyObject_CheckReadBuffer_Ptr)(PyObject*);
    void (*PyObject_ClearWeakRefs_Ptr)(PyObject*);
    int (*PyObject_CopyData_Ptr)(PyObject*, PyObject*);
    int (*PyObject_DelItem_Ptr)(PyObject*, PyObject*);
    int (*PyObject_DelItemString_Ptr)(PyObject*, const char*);
    PyObject* (*PyObject_Dir_Ptr)(PyObject*);
    PyObject* (*PyObject_Format_Ptr)(PyObject*, PyObject*);
    void (*PyObject_Free_Ptr)(void*);
    void (*PyObject_GC_Del_Ptr)(void*);
    int (*PyObject_GC_IsFinalized_Ptr)(PyObject*);
    int (*PyObject_GC_IsTracked_Ptr)(PyObject*);
    void (*PyObject_GC_Track_Ptr)(void*);
    void (*PyObject_GC_UnTrack_Ptr)(void*);
    PyObject* (*PyObject_GenericGetAttr_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyObject_GenericGetDict_Ptr)(PyObject*, void*);
    int (*PyObject_GenericSetAttr_Ptr)(PyObject*, PyObject*, PyObject*);
    int (*PyObject_GenericSetDict_Ptr)(PyObject*, PyObject*, void*);
    PyObject* (*PyObject_GetAIter_Ptr)(PyObject*);
    PyObject* (*PyObject_GetAttr_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyObject_GetAttrString_Ptr)(PyObject*, const char*);
    #if HAS_PYTHON_VERSION(0x030B1000)
    int (*PyObject_GetBuffer_Ptr)(PyObject*, Py_buffer*, int);
    #endif
    PyObject* (*PyObject_GetItem_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyObject_GetIter_Ptr)(PyObject*);
    int (*PyObject_HasAttr_Ptr)(PyObject*, PyObject*);
    int (*PyObject_HasAttrString_Ptr)(PyObject*, const char*);
    Py_hash_t (*PyObject_Hash_Ptr)(PyObject*);
    Py_hash_t (*PyObject_HashNotImplemented_Ptr)(PyObject*);
    PyObject* (*PyObject_Init_Ptr)(PyObject*, PyTypeObject*);
    PyVarObject* (*PyObject_InitVar_Ptr)(PyVarObject*, PyTypeObject*, Py_ssize_t);
    int (*PyObject_IsInstance_Ptr)(PyObject*, PyObject*);
    int (*PyObject_IsSubclass_Ptr)(PyObject*, PyObject*);
    int (*PyObject_IsTrue_Ptr)(PyObject*);
    Py_ssize_t (*PyObject_Length_Ptr)(PyObject*);
    void* (*PyObject_Malloc_Ptr)(size_t);
    int (*PyObject_Not_Ptr)(PyObject*);
    void* (*PyObject_Realloc_Ptr)(void*, size_t);
    PyObject* (*PyObject_Repr_Ptr)(PyObject*);
    PyObject* (*PyObject_RichCompare_Ptr)(PyObject*, PyObject*, int);
    int (*PyObject_RichCompareBool_Ptr)(PyObject*, PyObject*, int);
    PyObject* (*PyObject_SelfIter_Ptr)(PyObject*);
    int (*PyObject_SetAttr_Ptr)(PyObject*, PyObject*, PyObject*);
    int (*PyObject_SetAttrString_Ptr)(PyObject*, const char*, PyObject*);
    int (*PyObject_SetItem_Ptr)(PyObject*, PyObject*, PyObject*);
    Py_ssize_t (*PyObject_Size_Ptr)(PyObject*);
    PyObject* (*PyObject_Str_Ptr)(PyObject*);
    PyObject* (*PyObject_Type_Ptr)(PyObject*);
    #if HAS_PYTHON_VERSION(0x030B0000)
    int (*PyObject_TypeCheck_Ptr)(PyObject*, PyTypeObject*);
    #endif
    PyObject* (*PySeqIter_New_Ptr)(PyObject*);
    int (*PySequence_Check_Ptr)(PyObject*);
    PyObject* (*PySequence_Concat_Ptr)(PyObject*, PyObject*);
    int (*PySequence_Contains_Ptr)(PyObject*, PyObject*);
    Py_ssize_t (*PySequence_Count_Ptr)(PyObject*, PyObject*);
    int (*PySequence_DelItem_Ptr)(PyObject*, Py_ssize_t);
    int (*PySequence_DelSlice_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t);
    PyObject* (*PySequence_Fast_Ptr)(PyObject*, const char*);
    PyObject* (*PySequence_GetItem_Ptr)(PyObject*, Py_ssize_t);
    PyObject* (*PySequence_GetSlice_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t);
    int (*PySequence_In_Ptr)(PyObject*, PyObject*);
    PyObject* (*PySequence_InPlaceConcat_Ptr)(PyObject*, PyObject*);
    PyObject* (*PySequence_InPlaceRepeat_Ptr)(PyObject*, Py_ssize_t);
    Py_ssize_t (*PySequence_Index_Ptr)(PyObject*, PyObject*);
    Py_ssize_t (*PySequence_Length_Ptr)(PyObject*);
    PyObject* (*PySequence_List_Ptr)(PyObject*);
    PyObject* (*PySequence_Repeat_Ptr)(PyObject*, Py_ssize_t);
    int (*PySequence_SetItem_Ptr)(PyObject*, Py_ssize_t, PyObject*);
    int (*PySequence_SetSlice_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t, PyObject*);
    Py_ssize_t (*PySequence_Size_Ptr)(PyObject*);
    PyObject* (*PySequence_Tuple_Ptr)(PyObject*);
    int (*PySet_Add_Ptr)(PyObject*, PyObject*);
    int (*PySet_Clear_Ptr)(PyObject*);
    int (*PySet_Contains_Ptr)(PyObject*, PyObject*);
    int (*PySet_Discard_Ptr)(PyObject*, PyObject*);
    PyObject* (*PySet_New_Ptr)(PyObject*);
    PyObject* (*PySet_Pop_Ptr)(PyObject*);
    Py_ssize_t (*PySet_Size_Ptr)(PyObject*);
    Py_ssize_t (*PySlice_AdjustIndices_Ptr)(Py_ssize_t, Py_ssize_t*, Py_ssize_t*, Py_ssize_t);
    int (*PySlice_GetIndices_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t*, Py_ssize_t*, Py_ssize_t*);
    int (*PySlice_GetIndicesEx_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t*, Py_ssize_t*, Py_ssize_t*, Py_ssize_t*);
    PyObject* (*PySlice_New_Ptr)(PyObject*, PyObject*, PyObject*);
    int (*PySlice_Unpack_Ptr)(PyObject*, Py_ssize_t*, Py_ssize_t*, Py_ssize_t*);
    int (*PyState_AddModule_Ptr)(PyObject*, PyModuleDef*);
    PyObject* (*PyState_FindModule_Ptr)(PyModuleDef*);
    int (*PyState_RemoveModule_Ptr)(PyModuleDef*);
    PyObject* (*PyStructSequence_GetItem_Ptr)(PyObject*, Py_ssize_t);
    PyObject* (*PyStructSequence_New_Ptr)(PyTypeObject*);
    PyTypeObject* (*PyStructSequence_NewType_Ptr)(PyStructSequence_Desc*);
    void (*PyStructSequence_SetItem_Ptr)(PyObject*, Py_ssize_t, PyObject*);
    void (*PySys_AddWarnOption_Ptr)(const wchar_t*);
    void (*PySys_AddWarnOptionUnicode_Ptr)(PyObject*);
    void (*PySys_AddXOption_Ptr)(const wchar_t*);
    void (*PySys_FormatStderr_Ptr)(const char*, ...);
    void (*PySys_FormatStdout_Ptr)(const char*, ...);
    PyObject* (*PySys_GetObject_Ptr)(const char*);
    PyObject* (*PySys_GetXOptions_Ptr)();
    int (*PySys_HasWarnOptions_Ptr)();
    void (*PySys_ResetWarnOptions_Ptr)();
    void (*PySys_SetArgv_Ptr)(int, wchar_t**);
    void (*PySys_SetArgvEx_Ptr)(int, wchar_t**, int);
    int (*PySys_SetObject_Ptr)(const char*, PyObject*);
    void (*PySys_SetPath_Ptr)(const wchar_t*);
    void (*PySys_WriteStderr_Ptr)(const char*, ...);
    void (*PySys_WriteStdout_Ptr)(const char*, ...);
    void (*PyThreadState_Clear_Ptr)(PyThreadState*);
    void (*PyThreadState_Delete_Ptr)(PyThreadState*);
    PyThreadState* (*PyThreadState_Get_Ptr)();
    PyObject* (*PyThreadState_GetDict_Ptr)();
    #if HAS_PYTHON_VERSION(0x03090000)
    PyFrameObject* (*PyThreadState_GetFrame_Ptr)(PyThreadState*);
    #endif
    uint64_t (*PyThreadState_GetID_Ptr)(PyThreadState*);
    PyInterpreterState* (*PyThreadState_GetInterpreter_Ptr)(PyThreadState*);
    PyThreadState* (*PyThreadState_New_Ptr)(PyInterpreterState*);
    int (*PyThreadState_SetAsyncExc_Ptr)(unsigned long, PyObject*);
    PyThreadState* (*PyThreadState_Swap_Ptr)(PyThreadState*);
    PyObject* (*PyThread_GetInfo_Ptr)();
    void (*PyThread_ReInitTLS_Ptr)();
    int (*PyThread_acquire_lock_Ptr)(PyThread_type_lock, int);
    PyLockStatus (*PyThread_acquire_lock_timed_Ptr)(PyThread_type_lock, long long, int);
    PyThread_type_lock (*PyThread_allocate_lock_Ptr)();
    int (*PyThread_create_key_Ptr)();
    void (*PyThread_delete_key_Ptr)(int);
    void (*PyThread_delete_key_value_Ptr)(int);
    void (*PyThread_exit_thread_Ptr)();
    void (*PyThread_free_lock_Ptr)(PyThread_type_lock);
    void* (*PyThread_get_key_value_Ptr)(int);
    size_t (*PyThread_get_stacksize_Ptr)();
    unsigned long (*PyThread_get_thread_ident_Ptr)();
    unsigned long (*PyThread_get_thread_native_id_Ptr)();
    void (*PyThread_init_thread_Ptr)();
    void (*PyThread_release_lock_Ptr)(PyThread_type_lock);
    int (*PyThread_set_key_value_Ptr)(int, void*);
    int (*PyThread_set_stacksize_Ptr)(size_t);
    unsigned long (*PyThread_start_new_thread_Ptr)(void (*)(void*), void*);
    #if HAS_PYTHON_VERSION(0x03070000)
    Py_tss_t* (*PyThread_tss_alloc_Ptr)();
    int (*PyThread_tss_create_Ptr)(Py_tss_t*);
    void (*PyThread_tss_delete_Ptr)(Py_tss_t*);
    void (*PyThread_tss_free_Ptr)(Py_tss_t*);
    void* (*PyThread_tss_get_Ptr)(Py_tss_t*);
    int (*PyThread_tss_is_created_Ptr)(Py_tss_t*);
    int (*PyThread_tss_set_Ptr)(Py_tss_t*, void*);
    #endif
    #if HAS_PYTHON_VERSION(0x03090000)
    int (*PyTraceBack_Here_Ptr)(PyFrameObject*);
    #endif
    int (*PyTraceBack_Print_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyTuple_GetItem_Ptr)(PyObject*, Py_ssize_t);
    PyObject* (*PyTuple_GetSlice_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t);
    PyObject* (*PyTuple_New_Ptr)(Py_ssize_t);
    PyObject* (*PyTuple_Pack_Ptr)(Py_ssize_t, ...);
    int (*PyTuple_SetItem_Ptr)(PyObject*, Py_ssize_t, PyObject*);
    Py_ssize_t (*PyTuple_Size_Ptr)(PyObject*);
    unsigned int (*PyType_ClearCache_Ptr)();
    #if !defined(Py_LIMITED_API)
    const char* (*PyType_Name_Ptr)(PyTypeObject*);
    #endif
    PyObject* (*PyType_FromModuleAndSpec_Ptr)(PyObject*, PyType_Spec*, PyObject*);
    PyObject* (*PyType_FromSpec_Ptr)(PyType_Spec*);
    PyObject* (*PyType_FromSpecWithBases_Ptr)(PyType_Spec*, PyObject*);
    PyObject* (*PyType_GenericAlloc_Ptr)(PyTypeObject*, Py_ssize_t);
    PyObject* (*PyType_GenericNew_Ptr)(PyTypeObject*, PyObject*, PyObject*);
    unsigned long (*PyType_GetFlags_Ptr)(PyTypeObject*);
    PyObject* (*PyType_GetModule_Ptr)(PyTypeObject*);
    void* (*PyType_GetModuleState_Ptr)(PyTypeObject*);
    PyObject* (*PyType_GetName_Ptr)(PyTypeObject*);
    PyObject* (*PyType_GetQualName_Ptr)(PyTypeObject*);
    void* (*PyType_GetSlot_Ptr)(PyTypeObject*, int);
    int (*PyType_IsSubtype_Ptr)(PyTypeObject*, PyTypeObject*);
    void (*PyType_Modified_Ptr)(PyTypeObject*);
    int (*PyType_Ready_Ptr)(PyTypeObject*);
    PyObject* (*PyUnicodeDecodeError_Create_Ptr)(const char*, const char*, Py_ssize_t, Py_ssize_t, Py_ssize_t, const char*);
    PyObject* (*PyUnicodeDecodeError_GetEncoding_Ptr)(PyObject*);
    int (*PyUnicodeDecodeError_GetEnd_Ptr)(PyObject*, Py_ssize_t*);
    PyObject* (*PyUnicodeDecodeError_GetObject_Ptr)(PyObject*);
    PyObject* (*PyUnicodeDecodeError_GetReason_Ptr)(PyObject*);
    int (*PyUnicodeDecodeError_GetStart_Ptr)(PyObject*, Py_ssize_t*);
    int (*PyUnicodeDecodeError_SetEnd_Ptr)(PyObject*, Py_ssize_t);
    int (*PyUnicodeDecodeError_SetReason_Ptr)(PyObject*, const char*);
    int (*PyUnicodeDecodeError_SetStart_Ptr)(PyObject*, Py_ssize_t);
    PyObject* (*PyUnicodeEncodeError_GetEncoding_Ptr)(PyObject*);
    int (*PyUnicodeEncodeError_GetEnd_Ptr)(PyObject*, Py_ssize_t*);
    PyObject* (*PyUnicodeEncodeError_GetObject_Ptr)(PyObject*);
    PyObject* (*PyUnicodeEncodeError_GetReason_Ptr)(PyObject*);
    int (*PyUnicodeEncodeError_GetStart_Ptr)(PyObject*, Py_ssize_t*);
    int (*PyUnicodeEncodeError_SetEnd_Ptr)(PyObject*, Py_ssize_t);
    int (*PyUnicodeEncodeError_SetReason_Ptr)(PyObject*, const char*);
    int (*PyUnicodeEncodeError_SetStart_Ptr)(PyObject*, Py_ssize_t);
    int (*PyUnicodeTranslateError_GetEnd_Ptr)(PyObject*, Py_ssize_t*);
    PyObject* (*PyUnicodeTranslateError_GetObject_Ptr)(PyObject*);
    PyObject* (*PyUnicodeTranslateError_GetReason_Ptr)(PyObject*);
    int (*PyUnicodeTranslateError_GetStart_Ptr)(PyObject*, Py_ssize_t*);
    int (*PyUnicodeTranslateError_SetEnd_Ptr)(PyObject*, Py_ssize_t);
    int (*PyUnicodeTranslateError_SetReason_Ptr)(PyObject*, const char*);
    int (*PyUnicodeTranslateError_SetStart_Ptr)(PyObject*, Py_ssize_t);
    void (*PyUnicode_Append_Ptr)(PyObject**, PyObject*);
    void (*PyUnicode_AppendAndDel_Ptr)(PyObject**, PyObject*);
    PyObject* (*PyUnicode_AsASCIIString_Ptr)(PyObject*);
    PyObject* (*PyUnicode_AsCharmapString_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyUnicode_AsDecodedObject_Ptr)(PyObject*, const char*, const char*);
    PyObject* (*PyUnicode_AsDecodedUnicode_Ptr)(PyObject*, const char*, const char*);
    PyObject* (*PyUnicode_AsEncodedObject_Ptr)(PyObject*, const char*, const char*);
    PyObject* (*PyUnicode_AsEncodedString_Ptr)(PyObject*, const char*, const char*);
    PyObject* (*PyUnicode_AsEncodedUnicode_Ptr)(PyObject*, const char*, const char*);
    PyObject* (*PyUnicode_AsLatin1String_Ptr)(PyObject*);
    PyObject* (*PyUnicode_AsRawUnicodeEscapeString_Ptr)(PyObject*);
    Py_UCS4* (*PyUnicode_AsUCS4_Ptr)(PyObject*, Py_UCS4*, Py_ssize_t, int);
    Py_UCS4* (*PyUnicode_AsUCS4Copy_Ptr)(PyObject*);
    PyObject* (*PyUnicode_AsUTF16String_Ptr)(PyObject*);
    PyObject* (*PyUnicode_AsUTF32String_Ptr)(PyObject*);
    const char* (*PyUnicode_AsUTF8AndSize_Ptr)(PyObject*, Py_ssize_t*);
    PyObject* (*PyUnicode_AsUTF8String_Ptr)(PyObject*);
    PyObject* (*PyUnicode_AsUnicodeEscapeString_Ptr)(PyObject*);
    Py_ssize_t (*PyUnicode_AsWideChar_Ptr)(PyObject*, wchar_t*, Py_ssize_t);
    wchar_t* (*PyUnicode_AsWideCharString_Ptr)(PyObject*, Py_ssize_t*);
    PyObject* (*PyUnicode_BuildEncodingMap_Ptr)(PyObject*);
    int (*PyUnicode_Compare_Ptr)(PyObject*, PyObject*);
    int (*PyUnicode_CompareWithASCIIString_Ptr)(PyObject*, const char*);
    PyObject* (*PyUnicode_Concat_Ptr)(PyObject*, PyObject*);
    int (*PyUnicode_Contains_Ptr)(PyObject*, PyObject*);
    Py_ssize_t (*PyUnicode_Count_Ptr)(PyObject*, PyObject*, Py_ssize_t, Py_ssize_t);
    PyObject* (*PyUnicode_Decode_Ptr)(const char*, Py_ssize_t, const char*, const char*);
    PyObject* (*PyUnicode_DecodeASCII_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_DecodeCharmap_Ptr)(const char*, Py_ssize_t, PyObject*, const char*);
    PyObject* (*PyUnicode_DecodeFSDefault_Ptr)(const char*);
    PyObject* (*PyUnicode_DecodeFSDefaultAndSize_Ptr)(const char*, Py_ssize_t);
    PyObject* (*PyUnicode_DecodeLatin1_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_DecodeLocale_Ptr)(const char*, const char*);
    PyObject* (*PyUnicode_DecodeLocaleAndSize_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_DecodeRawUnicodeEscape_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_DecodeUTF16_Ptr)(const char*, Py_ssize_t, const char*, int*);
    PyObject* (*PyUnicode_DecodeUTF16Stateful_Ptr)(const char*, Py_ssize_t, const char*, int*, Py_ssize_t*);
    PyObject* (*PyUnicode_DecodeUTF32_Ptr)(const char*, Py_ssize_t, const char*, int*);
    PyObject* (*PyUnicode_DecodeUTF32Stateful_Ptr)(const char*, Py_ssize_t, const char*, int*, Py_ssize_t*);
    PyObject* (*PyUnicode_DecodeUTF7_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_DecodeUTF7Stateful_Ptr)(const char*, Py_ssize_t, const char*, Py_ssize_t*);
    PyObject* (*PyUnicode_DecodeUTF8_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_DecodeUTF8Stateful_Ptr)(const char*, Py_ssize_t, const char*, Py_ssize_t*);
    PyObject* (*PyUnicode_DecodeUnicodeEscape_Ptr)(const char*, Py_ssize_t, const char*);
    PyObject* (*PyUnicode_EncodeFSDefault_Ptr)(PyObject*);
    PyObject* (*PyUnicode_EncodeLocale_Ptr)(PyObject*, const char*);
    int (*PyUnicode_FSConverter_Ptr)(PyObject*, void*);
    int (*PyUnicode_FSDecoder_Ptr)(PyObject*, void*);
    Py_ssize_t (*PyUnicode_Find_Ptr)(PyObject*, PyObject*, Py_ssize_t, Py_ssize_t, int);
    Py_ssize_t (*PyUnicode_FindChar_Ptr)(PyObject*, Py_UCS4, Py_ssize_t, Py_ssize_t, int);
    PyObject* (*PyUnicode_Format_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyUnicode_FromEncodedObject_Ptr)(PyObject*, const char*, const char*);
    PyObject* (*PyUnicode_FromFormat_Ptr)(const char*, ...);
    PyObject* (*PyUnicode_FromFormatV_Ptr)(const char*, va_list);
    PyObject* (*PyUnicode_FromObject_Ptr)(PyObject*);
    PyObject* (*PyUnicode_FromOrdinal_Ptr)(int);
    PyObject* (*PyUnicode_FromString_Ptr)(const char*);
    PyObject* (*PyUnicode_FromStringAndSize_Ptr)(const char*, Py_ssize_t);
    PyObject* (*PyUnicode_FromWideChar_Ptr)(const wchar_t*, Py_ssize_t);
    const char* (*PyUnicode_GetDefaultEncoding_Ptr)();
    Py_ssize_t (*PyUnicode_GetLength_Ptr)(PyObject*);
    Py_ssize_t (*PyUnicode_GetSize_Ptr)(PyObject*);
    PyObject* (*PyUnicode_InternFromString_Ptr)(const char*);
    void (*PyUnicode_InternImmortal_Ptr)(PyObject**);
    void (*PyUnicode_InternInPlace_Ptr)(PyObject**);
    int (*PyUnicode_IsIdentifier_Ptr)(PyObject*);
    PyObject* (*PyUnicode_Join_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyUnicode_Partition_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyUnicode_RPartition_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyUnicode_RSplit_Ptr)(PyObject*, PyObject*, Py_ssize_t);
    Py_UCS4 (*PyUnicode_ReadChar_Ptr)(PyObject*, Py_ssize_t);
    PyObject* (*PyUnicode_Replace_Ptr)(PyObject*, PyObject*, PyObject*, Py_ssize_t);
    int (*PyUnicode_Resize_Ptr)(PyObject**, Py_ssize_t);
    PyObject* (*PyUnicode_RichCompare_Ptr)(PyObject*, PyObject*, int);
    PyObject* (*PyUnicode_Split_Ptr)(PyObject*, PyObject*, Py_ssize_t);
    PyObject* (*PyUnicode_Splitlines_Ptr)(PyObject*, int);
    PyObject* (*PyUnicode_Substring_Ptr)(PyObject*, Py_ssize_t, Py_ssize_t);
    Py_ssize_t (*PyUnicode_Tailmatch_Ptr)(PyObject*, PyObject*, Py_ssize_t, Py_ssize_t, int);
    PyObject* (*PyUnicode_Translate_Ptr)(PyObject*, PyObject*, const char*);
    int (*PyUnicode_WriteChar_Ptr)(PyObject*, Py_ssize_t, Py_UCS4);
    PyObject* (*PyWeakref_GetObject_Ptr)(PyObject*);
    PyObject* (*PyWeakref_NewProxy_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyWeakref_NewRef_Ptr)(PyObject*, PyObject*);
    PyObject* (*PyWrapper_New_Ptr)(PyObject*, PyObject*);
    int (*Py_AddPendingCall_Ptr)(int (*)(void*), void*);
    int (*Py_AtExit_Ptr)(void (*)());
    PyObject* (*Py_BuildValue_Ptr)(const char*, ...);
    int (*Py_BytesMain_Ptr)(int, char**);
    PyObject* (*Py_CompileString_Ptr)(const char*, const char*, int);
    void (*Py_DecRef_Ptr)(PyObject*);
    wchar_t* (*Py_DecodeLocale_Ptr)(const char*, size_t*);
    char* (*Py_EncodeLocale_Ptr)(const wchar_t*, size_t*);
    void (*Py_EndInterpreter_Ptr)(PyThreadState*);
    int (*Py_EnterRecursiveCall_Ptr)(const char*);
    void (*Py_Exit_Ptr)(int);
    void (*Py_FatalError_Ptr)(const char*);
    void (*Py_Finalize_Ptr)();
    int (*Py_FinalizeEx_Ptr)();
    PyObject* (*Py_GenericAlias_Ptr)(PyObject*, PyObject*);
    const char* (*Py_GetBuildInfo_Ptr)();
    const char* (*Py_GetCompiler_Ptr)();
    const char* (*Py_GetCopyright_Ptr)();
    wchar_t* (*Py_GetExecPrefix_Ptr)();
    wchar_t* (*Py_GetPath_Ptr)();
    const char* (*Py_GetPlatform_Ptr)();
    wchar_t* (*Py_GetPrefix_Ptr)();
    wchar_t* (*Py_GetProgramFullPath_Ptr)();
    wchar_t* (*Py_GetProgramName_Ptr)();
    wchar_t* (*Py_GetPythonHome_Ptr)();
    int (*Py_GetRecursionLimit_Ptr)();
    const char* (*Py_GetVersion_Ptr)();
    void (*Py_IncRef_Ptr)(PyObject*);
    void (*Py_Initialize_Ptr)();
    void (*Py_InitializeEx_Ptr)(int);
    int (*Py_Is_Ptr)(PyObject*, PyObject*);
    int (*Py_IsInitialized_Ptr)();
    void (*Py_LeaveRecursiveCall_Ptr)();
    int (*Py_Main_Ptr)(int, wchar_t**);
    int (*Py_MakePendingCalls_Ptr)();
    PyThreadState* (*Py_NewInterpreter_Ptr)();
    PyObject* (*Py_NewRef_Ptr)(PyObject*);
    int (*Py_ReprEnter_Ptr)(PyObject*);
    void (*Py_ReprLeave_Ptr)(PyObject*);
    void (*Py_SetPath_Ptr)(const wchar_t*);
    void (*Py_SetProgramName_Ptr)(const wchar_t*);
    void (*Py_SetPythonHome_Ptr)(const wchar_t*);
    void (*Py_SetRecursionLimit_Ptr)(int);
    PyObject* (*Py_VaBuildValue_Ptr)(const char*, va_list);

    #if HAS_PYTHON_VERSION(0x030A0000)
    PyObject* (*Py_XNewRef_Ptr)(PyObject*);
    #endif

public:
    Python();

    Python(const Python& other) = delete;
    Python(Python &&other);

    Python& operator = (const Python& other) = delete;
    Python& operator = (Python&& other);

    bool IsPythonLoaded() const;

    int PyAIter_Check(PyObject* o);

    template<typename... Args>
    int PyArg_Parse(PyObject* args, const char* format, Args... arguments)
    {
        return (*PyArg_Parse_Ptr)(args, format, arguments...);
    }

    int PyArg_ParseTuple(PyObject* args, const char* format, ...);
    int PyArg_ParseTupleAndKeywords(PyObject* args, PyObject* kw, const char* format, char* keywords[], ...);

    template<typename... Args>
    int PyArg_UnpackTuple(PyObject* args, const char* name, Py_ssize_t min, Py_ssize_t max, Args... arguments)
    {
        return (*PyArg_UnpackTuple_Ptr)(args, name, min, max, arguments...);
    }

    int PyArg_VaParse(PyObject* args, const char* format, va_list vargs);
    int PyArg_VaParseTupleAndKeywords(PyObject* args, PyObject* kw, const char* format, char* keywords[], va_list vargs);
    int PyArg_ValidateKeywordArguments(PyObject* keywords);
    PyObject* PyBool_FromLong(long v);

    #if HAS_PYTHON_VERSION(0x030B1000)
    void PyBuffer_FillContiguousStrides(int ndims, Py_ssize_t* shape, Py_ssize_t* strides, int itemsize, char fort);
    int PyBuffer_FillInfo(Py_buffer* view, PyObject* o, void* buf, Py_ssize_t len, int readonly, int flags);
    int PyBuffer_FromContiguous(const Py_buffer* view, const void* buf, Py_ssize_t len, char order);
    void* PyBuffer_GetPointer(const Py_buffer* view, const Py_ssize_t* indices);
    int PyBuffer_IsContiguous(const Py_buffer* view, char fort);
    void PyBuffer_Release(Py_buffer* view);
    Py_ssize_t PyBuffer_SizeFromFormat(const char* format);
    int PyBuffer_ToContiguous(void* buf, const Py_buffer* view, Py_ssize_t len, char order);
    #endif

    char* PyByteArray_AsString(PyObject* bytearray);
    PyObject* PyByteArray_Concat(PyObject* a, PyObject* b);
    PyObject* PyByteArray_FromObject(PyObject* o);
    PyObject* PyByteArray_FromStringAndSize(const char* string, Py_ssize_t len);
    int PyByteArray_Resize(PyObject* bytearray, Py_ssize_t len);
    Py_ssize_t PyByteArray_Size(PyObject* bytearray);
    char* PyBytes_AsString(PyObject* bytearray);
    int PyBytes_AsStringAndSize(PyObject* obj, char** s, Py_ssize_t* len);
    void PyBytes_Concat(PyObject** bytes, PyObject* newpart);
    void PyBytes_ConcatAndDel(PyObject** bytes, PyObject* newpart);
    PyObject* PyBytes_DecodeEscape(const char* arg1, Py_ssize_t arg2, const char* arg3, Py_ssize_t arg4, const char* arg5);
    PyObject* PyBytes_FromFormat(const char *format, ...);
    PyObject* PyBytes_FromFormatV(const char* format, va_list vargs);
    PyObject* PyBytes_FromObject(PyObject* o);
    PyObject* PyBytes_FromString(const char* string);
    PyObject* PyBytes_FromStringAndSize(const char* string, Py_ssize_t len);
    PyObject* PyBytes_Repr(PyObject* o, int smartquotes);
    Py_ssize_t PyBytes_Size(PyObject* o);
    PyObject* PyCFunction_Call(PyObject* func, PyObject* arg, PyObject* kw);
    int PyCFunction_GetFlags(PyObject* op);
    PyCFunction PyCFunction_GetFunction(PyObject* op);
    PyObject* PyCFunction_GetSelf(PyObject* op);
    PyObject* (PyCFunction_New)(PyMethodDef* ml, PyObject* self);

    #if HAS_PYTHON_VERSION(0x03050000)
    PyObject* (PyCFunction_NewEx)(PyMethodDef* ml, PyObject* self, PyObject* module);
    #endif

    PyObject* PyCMethod_New(PyMethodDef* ml, PyObject* self, PyObject* module, PyTypeObject* type);
    PyObject* PyCallIter_New(PyObject* callable, PyObject* sentinel);
    int PyCallable_Check(PyObject* o);
    void* PyCapsule_GetContext(PyObject* capsule);
    PyCapsule_Destructor PyCapsule_GetDestructor(PyObject* capsule);
    const char* PyCapsule_GetName(PyObject* capsule);
    void* PyCapsule_GetPointer(PyObject* capsule, const char* name);
    void* PyCapsule_Import(const char* name, int no_block);
    int PyCapsule_IsValid(PyObject* capsule, const char* name);
    PyObject* PyCapsule_New(void* pointer, const char* name, PyCapsule_Destructor destructor);
    int PyCapsule_SetContext(PyObject* capsule, void* context);
    int PyCapsule_SetDestructor(PyObject* capsule, PyCapsule_Destructor destructor);
    int PyCapsule_SetName(PyObject* capsule, const char* name);
    int PyCapsule_SetPointer(PyObject* capsule, void* pointer);
    PyObject* PyComplex_FromDoubles(double real, double imag);
    double PyComplex_ImagAsDouble(PyObject* op);
    double PyComplex_RealAsDouble(PyObject* op);
    PyObject* PyDescr_NewClassMethod(PyTypeObject* type, PyMethodDef* method);
    PyObject* PyDescr_NewGetSet(PyTypeObject* type, PyGetSetDef* getset);
    PyObject* PyDescr_NewMember(PyTypeObject* type, PyMemberDef* meth);
    PyObject* PyDescr_NewMethod(PyTypeObject* type, PyMethodDef* meth);
    PyObject* PyDictProxy_New(PyObject* mapping);
    void PyDict_Clear(PyObject* mp);
    int PyDict_Contains(PyObject* mp, PyObject* key);
    PyObject* PyDict_Copy(PyObject* mp);
    int PyDict_DelItem(PyObject* mp, PyObject* key);
    int PyDict_DelItemString(PyObject* dp, const char* key);
    PyObject* PyDict_GetItem(PyObject* mp, PyObject* key);
    PyObject* PyDict_GetItemString(PyObject* dp, const char* key);
    PyObject* PyDict_GetItemWithError(PyObject* mp, PyObject* key);
    PyObject* PyDict_Items(PyObject* mp);
    PyObject* PyDict_Keys(PyObject* mp);
    int PyDict_Merge(PyObject* mp, PyObject* other, int override);
    int PyDict_MergeFromSeq2(PyObject* d, PyObject* seq2, int override);
    PyObject* PyDict_New();
    int PyDict_Next(PyObject* mp, Py_ssize_t* pos, PyObject** key, PyObject** value);
    int PyDict_SetItem(PyObject* mp, PyObject* key, PyObject* item);
    int PyDict_SetItemString(PyObject* dp, const char* key, PyObject* item);
    Py_ssize_t PyDict_Size(PyObject* mp);
    int PyDict_Update(PyObject* mp, PyObject* other);
    PyObject* PyDict_Values(PyObject* mp);
    int PyErr_BadArgument();

    #ifdef PyErr_BadInternalCall
    void (PyErr_BadInternalCall)();
    #endif

    int PyErr_CheckSignals();
    void PyErr_Clear();
    void PyErr_Display(PyObject* exc, PyObject* val, PyObject* tb);
    int PyErr_ExceptionMatches(PyObject* exc);
    void PyErr_Fetch(PyObject** ptype, PyObject** pvalue, PyObject** ptraceback);
    PyObject* PyErr_Format(PyObject* exception, const char* format, ...);
    PyObject* PyErr_FormatV(PyObject* exception, const char* format, va_list vargs);
    void PyErr_GetExcInfo(PyObject** ptype, PyObject** pvalue, PyObject** ptraceback);
    PyObject* PyErr_GetHandledException();
    int PyErr_GivenExceptionMatches(PyObject* given, PyObject* exc);
    PyObject* PyErr_NewException(const char* name, PyObject* base, PyObject* dict);
    PyObject* PyErr_NewExceptionWithDoc(const char* name, const char* doc, PyObject* base, PyObject* dict);
    PyObject* PyErr_NoMemory();
    void PyErr_NormalizeException(PyObject** exc, PyObject** val, PyObject** tb);
    PyObject* PyErr_Occurred();
    void PyErr_Print();
    void PyErr_PrintEx(int set_sys_last_vars);
    PyObject* PyErr_ProgramText(const char* filename, int lineno);

    template<typename... Args>
    int PyErr_ResourceWarning(PyObject* source, Py_ssize_t stack_level, const char* format, Args... args)
    {
        return (*PyErr_ResourceWarning_Ptr)(source, stack_level, format, args...);
    }

    void PyErr_Restore(PyObject* type, PyObject* value, PyObject* traceback);
    void PyErr_SetExcInfo(PyObject* type, PyObject* value, PyObject* traceback);
    PyObject* PyErr_SetFromErrno(PyObject* type);
    PyObject* PyErr_SetFromErrnoWithFilename(PyObject* type, const char* filename);
    PyObject* PyErr_SetFromErrnoWithFilenameObject(PyObject* type, PyObject* filenameObject);
    PyObject* PyErr_SetFromErrnoWithFilenameObjects(PyObject* type, PyObject* filenameObject, PyObject* filenameObject2);
    PyObject* PyErr_SetImportError(PyObject *msg, PyObject *name, PyObject *path);
    void PyErr_SetHandledException(PyObject* exc);
    PyObject* PyErr_SetImportErrorSubclass(PyObject* exception, PyObject* msg, PyObject* name, PyObject* path);
    void PyErr_SetInterrupt();
    int PyErr_SetInterruptEx(int signum);
    void PyErr_SetNone(PyObject* type);
    void PyErr_SetObject(PyObject* type, PyObject* value);
    void PyErr_SetString(PyObject* exception, const char* string);
    void PyErr_SyntaxLocation(const char* filename, int lineno);
    void PyErr_SyntaxLocationEx(const char* filename, int lineno, int col_offset);
    int PyErr_WarnEx(PyObject* category, const char* message, Py_ssize_t stack_level);
    int PyErr_WarnExplicit(PyObject* category, const char* message, const char* filename, int lineno, const char* module, PyObject* registry);

    template<typename... Args>
    int PyErr_WarnFormat(PyObject* category, Py_ssize_t stack_level, const char* format, Args... args)
    {
        return (*PyErr_WarnFormat_Ptr)(category, stack_level, format, args...);
    }

    void PyErr_WriteUnraisable(PyObject* obj);
    void PyEval_AcquireLock();
    void PyEval_AcquireThread(PyThreadState* tstate);

    template<typename... Args>
    PyObject* PyEval_CallFunction(PyObject* callable, const char* format, Args... args)
    {
        return (*PyEval_CallFunction_Ptr)(callable, format, args...);
    }

    template<typename... Args>
    PyObject* PyEval_CallMethod(PyObject* obj, const char* name, const char* format, Args... args)
    {
        return (*PyEval_CallMethod_Ptr)(obj, name, format, args...);
    }

    PyObject* PyEval_CallObjectWithKeywords(PyObject* callable, PyObject* args, PyObject* kwargs);
    PyObject* PyEval_EvalCode(PyObject* co, PyObject* globals, PyObject* locals);
    PyObject* PyEval_EvalCodeEx(PyObject* co, PyObject* globals, PyObject* locals, PyObject*const* args, int argc, PyObject* const* kwds, int kwdc, PyObject* const* defs, int defc, PyObject* kwdefs, PyObject* closure);
    #if HAS_PYTHON_VERSION(0x03090000)
    PyObject* PyEval_EvalFrame(PyFrameObject* f);
    PyObject* PyEval_EvalFrameEx(PyFrameObject* f, int exc);
    #endif
    PyObject* PyEval_GetBuiltins();
    #if HAS_PYTHON_VERSION(0x03090000)
    PyFrameObject* PyEval_GetFrame();
    #endif
    const char* PyEval_GetFuncDesc(PyObject* func);
    const char* PyEval_GetFuncName(PyObject* func);
    PyObject* PyEval_GetGlobals();
    PyObject* PyEval_GetLocals();
    void PyEval_InitThreads();
    void PyEval_ReleaseLock();
    void PyEval_ReleaseThread(PyThreadState* tstate);
    void PyEval_RestoreThread(PyThreadState* tstate);
    PyThreadState* PyEval_SaveThread();
    int PyEval_ThreadsInitialized();
    const char* (PyExceptionClass_Name)(PyObject* exc);
    PyObject* PyException_GetCause(PyObject* exc);
    PyObject* PyException_GetContext(PyObject* exc);
    PyObject* PyException_GetTraceback(PyObject* exc);
    void PyException_SetCause(PyObject* exc, PyObject* cause);
    void PyException_SetContext(PyObject* exc, PyObject* context);
    int PyException_SetTraceback(PyObject* exc, PyObject* traceback);
    PyObject* PyFile_FromFd(int fd, const char* name, const char* mode, int buffering, const char* encoding, const char* errors, const char* newline, int closefd);
    PyObject* PyFile_GetLine(PyObject* p, int n);
    int PyFile_WriteObject(PyObject* obj, PyObject* p, int flags);
    int PyFile_WriteString(const char* s, PyObject* p);
    double PyFloat_AsDouble(PyObject* pyfloat);
    PyObject* PyFloat_FromDouble(double dbl);
    PyObject* PyFloat_FromString(PyObject* str);
    PyObject* PyFloat_GetInfo();
    double PyFloat_GetMax();
    double PyFloat_GetMin();
    PyObject* PyFrozenSet_New(PyObject* iterable);
    Py_ssize_t PyGC_Collect();
    int PyGC_Disable();
    int PyGC_Enable();
    int PyGC_IsEnabled();
    PyGILState_STATE PyGILState_Ensure();
    PyThreadState* PyGILState_GetThisThreadState();
    void PyGILState_Release(PyGILState_STATE state);
    PyObject* PyImport_AddModule(const char* name);
    PyObject* PyImport_AddModuleObject(PyObject* name);
    int PyImport_AppendInittab(const char* name, PyObject*(*initfunc)());
    PyObject* PyImport_ExecCodeModule(const char* name, PyObject* co);
    PyObject* PyImport_ExecCodeModuleEx(const char* name, PyObject* co, const char* pathname);
    PyObject* PyImport_ExecCodeModuleObject(PyObject* name, PyObject* co, PyObject* pathname, PyObject* cpathname);
    PyObject* PyImport_ExecCodeModuleWithPathnames(const char* name, PyObject* co, const char* pathname, const char* cpathname);
    PyObject* PyImport_GetImporter(PyObject* path);
    long PyImport_GetMagicNumber();
    const char* PyImport_GetMagicTag();
    PyObject* PyImport_GetModule(PyObject *name);
    PyObject* PyImport_GetModuleDict();
    PyObject* PyImport_Import(PyObject* name);
    int PyImport_ImportFrozenModule(const char* name);
    int PyImport_ImportFrozenModuleObject(PyObject* name);
    PyObject* PyImport_ImportModule(const char* name);
    PyObject* PyImport_ImportModuleLevel(const char* name, PyObject* globals, PyObject* locals, PyObject* fromlist, int level);
    PyObject* PyImport_ImportModuleLevelObject(PyObject* name, PyObject* globals, PyObject* locals, PyObject* fromlist, int level);
    PyObject* PyImport_ImportModuleNoBlock(const char* name);
    PyObject* PyImport_ReloadModule(PyObject* m);
    int (PyIndex_Check)(PyObject* o);
    void PyInterpreterState_Clear(PyInterpreterState* state);
    void PyInterpreterState_Delete(PyInterpreterState* state);
    PyInterpreterState* PyInterpreterState_Get();
    PyObject* PyInterpreterState_GetDict(PyInterpreterState* state);
    int64_t PyInterpreterState_GetID(PyInterpreterState* state);
    PyInterpreterState* PyInterpreterState_New();
    int (PyIter_Check)(PyObject* o);
    PyObject* PyIter_Next(PyObject* o);
    #if HAS_PYTHON_VERSION(0x030A0000)
    PySendResult PyIter_Send(PyObject* iter, PyObject* arg, PyObject** presult);
    #endif
    int PyList_Append(PyObject* list, PyObject* item);
    PyObject* PyList_AsTuple(PyObject* list);
    PyObject* PyList_GetItem(PyObject* list, Py_ssize_t index);
    PyObject* PyList_GetSlice(PyObject* list, Py_ssize_t low, Py_ssize_t high);
    int PyList_Insert(PyObject* list, Py_ssize_t index, PyObject* item);
    PyObject* PyList_New(Py_ssize_t size);
    int PyList_Reverse(PyObject* list);
    int PyList_SetItem(PyObject* list, Py_ssize_t index, PyObject* item);
    int PyList_SetSlice(PyObject* list, Py_ssize_t low, Py_ssize_t high, PyObject* item);
    Py_ssize_t PyList_Size(PyObject* list);
    int PyList_Sort(PyObject* list);
    double PyLong_AsDouble(PyObject* pylong);
    long PyLong_AsLong(PyObject* pylong);
    long PyLong_AsLongAndOverflow(PyObject* pylong, int* overflow);
    long long PyLong_AsLongLong(PyObject* pylong);
    long long PyLong_AsLongLongAndOverflow(PyObject* pylong, int* overflow);
    size_t PyLong_AsSize_t(PyObject* pylong);
    Py_ssize_t PyLong_AsSsize_t(PyObject* pylong);
    unsigned long PyLong_AsUnsignedLong(PyObject* pylong);
    unsigned long long PyLong_AsUnsignedLongLong(PyObject* pylong);
    unsigned long long PyLong_AsUnsignedLongLongMask(PyObject* pylong);
    unsigned long PyLong_AsUnsignedLongMask(PyObject* pylong);
    void* PyLong_AsVoidPtr(PyObject* pylong);
    PyObject* PyLong_FromDouble(double dbl);
    PyObject* PyLong_FromLong(long lng);
    PyObject* PyLong_FromLongLong(long long longlong);
    PyObject* PyLong_FromSize_t(size_t size);
    PyObject* PyLong_FromSsize_t(Py_ssize_t size);
    PyObject* PyLong_FromString(const char* str, char** pend, int base);
    PyObject* PyLong_FromUnsignedLong(unsigned long ulong);
    PyObject* PyLong_FromUnsignedLongLong(unsigned long long ulonglong);
    PyObject* PyLong_FromVoidPtr(void* ptr);
    PyObject* PyLong_GetInfo();
    int PyMapping_Check(PyObject* o);
    PyObject* PyMapping_GetItemString(PyObject* o, const char* key);
    int PyMapping_HasKey(PyObject* o, PyObject* key);
    int PyMapping_HasKeyString(PyObject* o, const char* key);
    PyObject* PyMapping_Items(PyObject* o);
    PyObject* PyMapping_Keys(PyObject* o);

    #ifdef PyMapping_Length
    #undef PyMapping_Length
    Py_ssize_t PyMapping_Length(PyObject* o);
    #endif

    int PyMapping_SetItemString(PyObject* o, const char* key, PyObject* value);
    Py_ssize_t PyMapping_Size(PyObject* o);
    PyObject* PyMapping_Values(PyObject* o);
    void* PyMem_Calloc(size_t nelem, size_t elsize);
    void PyMem_Free(void* ptr);
    void* PyMem_Malloc(size_t size);
    void* PyMem_Realloc(void* ptr, size_t new_size);

    #if HAS_PYTHON_VERSION(0x030B1000)
    PyObject* PyMemoryView_FromBuffer(const Py_buffer* info);
    #endif

    PyObject* PyMemoryView_FromMemory(char* mem, Py_ssize_t size, int flags);
    PyObject* PyMemoryView_FromObject(PyObject* base);
    PyObject* PyMemoryView_GetContiguous(PyObject* base, int buffertype, char order);
    PyObject* PyModuleDef_Init(PyModuleDef* def);
    int PyModule_AddFunctions(PyObject* module, PyMethodDef* functions);
    int PyModule_AddIntConstant(PyObject* module, const char* name, long value);
    int PyModule_AddObject(PyObject* module, const char* name, PyObject* value);
    int PyModule_AddObjectRef(PyObject* mod, const char* name, PyObject* value);
    int PyModule_AddStringConstant(PyObject* module, const char* name, const char* value);
    int PyModule_AddType(PyObject* module, PyTypeObject* type);
    PyObject* PyModule_Create2(PyModuleDef* def, int apiver);
    int PyModule_ExecDef(PyObject* module, PyModuleDef* def);
    PyObject* PyModule_FromDefAndSpec2(PyModuleDef* def, PyObject* spec, int module_api_version);
    PyModuleDef* PyModule_GetDef(PyObject* module);
    PyObject* PyModule_GetDict(PyObject* module);
    const char* PyModule_GetFilename(PyObject* module);
    PyObject* PyModule_GetFilenameObject(PyObject* module);
    const char* PyModule_GetName(PyObject* module);
    PyObject* PyModule_GetNameObject(PyObject* module);
    void* PyModule_GetState(PyObject* module);
    PyObject* PyModule_New(const char* name);
    PyObject* PyModule_NewObject(PyObject* name);
    int PyModule_SetDocString(PyObject* module, const char* docs);
    PyObject* PyNumber_Absolute(PyObject* o);
    PyObject* PyNumber_Add(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_And(PyObject* o1, PyObject* o2);
    Py_ssize_t PyNumber_AsSsize_t(PyObject* o, PyObject* exc);
    int PyNumber_Check(PyObject* o);
    PyObject* PyNumber_Divmod(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Float(PyObject* o);
    PyObject* PyNumber_FloorDivide(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceAdd(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceAnd(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceFloorDivide(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceLshift(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceMatrixMultiply(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceMultiply(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceOr(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlacePower(PyObject* o1, PyObject* o2, PyObject* o3);
    PyObject* PyNumber_InPlaceRemainder(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceRshift(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceSubtract(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceTrueDivide(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_InPlaceXor(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Index(PyObject* o);
    PyObject* PyNumber_Invert(PyObject* o);
    PyObject* PyNumber_Long(PyObject* o);
    PyObject* PyNumber_Lshift(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_MatrixMultiply(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Multiply(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Negative(PyObject* o);
    PyObject* PyNumber_Or(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Positive(PyObject* o);
    PyObject* PyNumber_Power(PyObject* o1, PyObject* o2, PyObject* o3);
    PyObject* PyNumber_Remainder(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Rshift(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Subtract(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_ToBase(PyObject* n, int base);
    PyObject* PyNumber_TrueDivide(PyObject* o1, PyObject* o2);
    PyObject* PyNumber_Xor(PyObject* o1, PyObject* o2);
    void PyOS_AfterFork();
    void PyOS_AfterFork_Child();
    void PyOS_AfterFork_Parent();
    void PyOS_BeforeFork();
    PyObject* PyOS_FSPath(PyObject* path);
    int PyOS_InterruptOccurred();
    char* PyOS_double_to_string(double val, char format_code, int precision, int flags, int* type);
    PyOS_sighandler_t PyOS_getsig(int i);
    int PyOS_mystricmp(const char* a, const char* b);
    int PyOS_mystrnicmp(const char* a, const char* b, Py_ssize_t max_len);
    PyOS_sighandler_t PyOS_setsig(int i, PyOS_sighandler_t handler);

    template<typename... Args>
    int PyOS_snprintf(char* str, size_t size, const char* format, Args... args)
    {
        return (*PyOS_snprintf_Ptr)(str, size, format, args...);
    }

    double PyOS_string_to_double(const char* str, char** endptr, PyObject* overflow_exception);
    long PyOS_strtol(const char* str, char** endptr, int base);
    unsigned long PyOS_strtoul(const char* str, char** endptr, int base);
    int PyOS_vsnprintf(char* str, size_t size, const char* format, va_list va);
    PyObject* PyObject_ASCII(PyObject* o);
    int PyObject_AsCharBuffer(PyObject* obj, const char** buffer, Py_ssize_t* buffer_len);
    int PyObject_AsFileDescriptor(PyObject* o);
    int PyObject_AsReadBuffer(PyObject* obj, const void** buffer, Py_ssize_t* buffer_len);
    int PyObject_AsWriteBuffer(PyObject* obj, void** buffer, Py_ssize_t* buffer_len);
    PyObject* PyObject_Bytes(PyObject* o);

    PyObject* PyObject_Call(PyObject* callable, PyObject* args, PyObject* kwargs);

    template<typename... Args>
    PyObject* PyObject_CallFunction(PyObject* callable, const char* format, Args... args)
    {
        return (*PyObject_CallFunction_Ptr)(callable, format, args...);
    }

    template<typename... Args>
    PyObject* PyObject_CallFunctionObjArgs(PyObject* callable, Args... args)
    {
        return (*PyObject_CallFunctionObjArgs_Ptr)(callable, args...);
    }

    template<typename... Args>
    PyObject* PyObject_CallMethod(PyObject* obj, const char* name, const char* format, Args... args)
    {
        return (*PyObject_CallMethod_Ptr)(obj, name, format, args...);
    }

    template<typename... Args>
    PyObject* PyObject_CallMethodObjArgs(PyObject* obj, PyObject* name, Args... args)
    {
        return (*PyObject_CallMethodObjArgs_Ptr)(obj, name, args...);
    }

    PyObject* PyObject_CallNoArgs(PyObject* func);
    PyObject* PyObject_CallObject(PyObject* callable, PyObject* args);
    void* PyObject_Calloc(size_t nelem, size_t elsize);
    int PyObject_CheckBuffer(PyObject* obj);
    int PyObject_CheckReadBuffer(PyObject* obj);
    void PyObject_ClearWeakRefs(PyObject* obj);
    int PyObject_CopyData(PyObject* dest, PyObject* src);
    int PyObject_DelItem(PyObject* o, PyObject* key);
    int PyObject_DelItemString(PyObject* o, const char* key);
    PyObject* PyObject_Dir(PyObject* obj);
    PyObject* PyObject_Format(PyObject* obj, PyObject* format_spec);
    void PyObject_Free(void* ptr);
    void PyObject_GC_Del(void* op);
    int PyObject_GC_IsFinalized(PyObject* op);
    int PyObject_GC_IsTracked(PyObject* op);
    void PyObject_GC_Track(void* op);
    void PyObject_GC_UnTrack(void* op);
    PyObject* PyObject_GenericGetAttr(PyObject* o, PyObject* name);
    PyObject* PyObject_GenericGetDict(PyObject* o, void* context);
    int PyObject_GenericSetAttr(PyObject* o, PyObject* name, PyObject* value);
    int PyObject_GenericSetDict(PyObject* o, PyObject* value, void* context);
    PyObject* PyObject_GetAIter(PyObject* o);
    PyObject* PyObject_GetAttr(PyObject* o, PyObject* attr_name);
    PyObject* PyObject_GetAttrString(PyObject* o, const char* attr);

    #if HAS_PYTHON_VERSION(0x030B1000)
    int PyObject_GetBuffer(PyObject* obj, Py_buffer* view, int flags);
    #endif

    PyObject* PyObject_GetItem(PyObject* o, PyObject* key);
    PyObject* PyObject_GetIter(PyObject* o);
    int PyObject_HasAttr(PyObject* o, PyObject* attr_name);
    int PyObject_HasAttrString(PyObject* o, const char* attr);
    Py_hash_t PyObject_Hash(PyObject* o);
    Py_hash_t PyObject_HashNotImplemented(PyObject* o);
    PyObject* PyObject_Init(PyObject* o, PyTypeObject* type);
    PyVarObject* PyObject_InitVar(PyVarObject* op, PyTypeObject* type, Py_ssize_t size);
    int PyObject_IsInstance(PyObject* object, PyObject* typeorclass);
    int PyObject_IsSubclass(PyObject* object, PyObject* typeorclass);
    int PyObject_IsTrue(PyObject* o);

    #ifdef PyObject_Length
    #undef PyObject_Length
    Py_ssize_t PyObject_Length(PyObject* o);
    #endif

    void* PyObject_Malloc(size_t size);
    int PyObject_Not(PyObject* o);
    void* PyObject_Realloc(void* ptr, size_t new_size);
    PyObject* PyObject_Repr(PyObject* o);
    PyObject* PyObject_RichCompare(PyObject* a, PyObject* b, int opid);
    int PyObject_RichCompareBool(PyObject* a, PyObject* b, int opid);
    PyObject* PyObject_SelfIter(PyObject* o);
    int PyObject_SetAttr(PyObject* o, PyObject* attr_name, PyObject* v);
    int PyObject_SetAttrString(PyObject* o, const char* attr_name, PyObject* value);
    int PyObject_SetItem(PyObject* o, PyObject* key, PyObject* v);
    Py_ssize_t PyObject_Size(PyObject* o);
    PyObject* PyObject_Str(PyObject* o);
    PyObject* PyObject_Type(PyObject* o);
    #if HAS_PYTHON_VERSION(0x030B0000)
    int (PyObject_TypeCheck)(PyObject* o, PyTypeObject* type);
    #endif
    PyObject* PySeqIter_New(PyObject* o);
    int PySequence_Check(PyObject* o);
    PyObject* PySequence_Concat(PyObject* o1, PyObject* o2);
    int PySequence_Contains(PyObject* seq, PyObject* ob);
    Py_ssize_t PySequence_Count(PyObject* o, PyObject* value);
    int PySequence_DelItem(PyObject* o, Py_ssize_t i);
    int PySequence_DelSlice(PyObject* o, Py_ssize_t i1, Py_ssize_t i2);
    PyObject* PySequence_Fast(PyObject* o, const char* m);
    PyObject* PySequence_GetItem(PyObject* o, Py_ssize_t i);
    PyObject* PySequence_GetSlice(PyObject* o, Py_ssize_t i1, Py_ssize_t i2);

    #ifdef PySequence_In
    #undef PySequence_In
    int PySequence_In(PyObject* o, PyObject* value);
    #endif

    PyObject* PySequence_InPlaceConcat(PyObject* o1, PyObject* o2);
    PyObject* PySequence_InPlaceRepeat(PyObject* o, Py_ssize_t count);
    Py_ssize_t PySequence_Index(PyObject* o, PyObject* value);

    #ifdef PySequence_Length
    #undef PySequence_Length
    Py_ssize_t PySequence_Length(PyObject* o);
    #endif

    PyObject* PySequence_List(PyObject* o);
    PyObject* PySequence_Repeat(PyObject* o, Py_ssize_t count);
    int PySequence_SetItem(PyObject* o, Py_ssize_t i, PyObject* v);
    int PySequence_SetSlice(PyObject* o, Py_ssize_t i1, Py_ssize_t i2, PyObject* v);
    Py_ssize_t PySequence_Size(PyObject* o);
    PyObject* PySequence_Tuple(PyObject* o);
    int PySet_Add(PyObject* set, PyObject* key);
    int PySet_Clear(PyObject* set);
    int PySet_Contains(PyObject* anyset, PyObject* key);
    int PySet_Discard(PyObject* set, PyObject* key);
    PyObject* PySet_New(PyObject* iterable);
    PyObject* PySet_Pop(PyObject* set);
    Py_ssize_t PySet_Size(PyObject* anyset);
    Py_ssize_t PySlice_AdjustIndices(Py_ssize_t length, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t step);
    int PySlice_GetIndices(PyObject* r, Py_ssize_t length, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t* step);

    #ifdef PySlice_GetIndicesEx
    int (PySlice_GetIndicesEx)(PyObject* r, Py_ssize_t length, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t* step, Py_ssize_t* slicelength);
    #endif

    PyObject* PySlice_New(PyObject* start, PyObject* stop, PyObject* step);
    int PySlice_Unpack(PyObject* slice, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t* step);
    int PyState_AddModule(PyObject* module, PyModuleDef* def);
    PyObject* PyState_FindModule(PyModuleDef* def);
    int PyState_RemoveModule(PyModuleDef* def);
    PyObject* PyStructSequence_GetItem(PyObject* p, Py_ssize_t pos);
    PyObject* PyStructSequence_New(PyTypeObject* type);
    PyTypeObject* PyStructSequence_NewType(PyStructSequence_Desc* desc);
    void PyStructSequence_SetItem(PyObject* p, Py_ssize_t pos, PyObject* o);
    void PySys_AddWarnOption(const wchar_t* s);
    void PySys_AddWarnOptionUnicode(PyObject* unicode);
    void PySys_AddXOption(const wchar_t* option);

    template<typename... Args>
    void PySys_FormatStderr(const char* format, Args... args)
    {
        return (*PySys_FormatStderr_Ptr)(format, args...);
    }

    template<typename... Args>
    void PySys_FormatStdout(const char* format, Args... args)
    {
        return (*PySys_FormatStdout_Ptr)(format, args...);
    }

    PyObject* PySys_GetObject(const char* name);
    PyObject* PySys_GetXOptions();
    int PySys_HasWarnOptions();
    void PySys_ResetWarnOptions();
    void PySys_SetArgv(int argc, wchar_t** argv);
    void PySys_SetArgvEx(int argc, wchar_t** argv, int updatepath);
    int PySys_SetObject(const char* name, PyObject* v);
    void PySys_SetPath(const wchar_t* path);

    template<typename... Args>
    void PySys_WriteStderr(const char* format, Args... args)
    {
        return (*PySys_WriteStderr_Ptr)(format, args...);
    }

    template<typename... Args>
    void PySys_WriteStdout(const char* format, Args... args)
    {
        return (*PySys_WriteStdout_Ptr)(format, args...);
    }

    void PyThreadState_Clear(PyThreadState* tstate);
    void PyThreadState_Delete(PyThreadState* tstate);
    PyThreadState* PyThreadState_Get();
    PyObject* PyThreadState_GetDict();
    #if HAS_PYTHON_VERSION(0x03090000)
    PyFrameObject* PyThreadState_GetFrame(PyThreadState* tstate);
    #endif
    uint64_t PyThreadState_GetID(PyThreadState* tstate);
    PyInterpreterState* PyThreadState_GetInterpreter(PyThreadState* tstate);
    PyThreadState* PyThreadState_New(PyInterpreterState* interp);
    int PyThreadState_SetAsyncExc(unsigned long id, PyObject* exc);
    PyThreadState* PyThreadState_Swap(PyThreadState* tstate);
    PyObject* PyThread_GetInfo();
    void PyThread_ReInitTLS();
    int PyThread_acquire_lock(PyThread_type_lock lock, int intr_flag);
    PyLockStatus PyThread_acquire_lock_timed(PyThread_type_lock lock, long long microseconds, int intr_flag);
    PyThread_type_lock PyThread_allocate_lock();
    int PyThread_create_key();
    void PyThread_delete_key(int key);
    void PyThread_delete_key_value(int key);
    void PyThread_exit_thread();
    void PyThread_free_lock(PyThread_type_lock lock);
    void* PyThread_get_key_value(int key);
    size_t PyThread_get_stacksize();
    unsigned long PyThread_get_thread_ident();
    unsigned long PyThread_get_thread_native_id();
    void PyThread_init_thread();
    void PyThread_release_lock(PyThread_type_lock lock);
    int PyThread_set_key_value(int key, void* value);
    int PyThread_set_stacksize(size_t size);
    unsigned long PyThread_start_new_thread(void (*initfunc)(void*) , void* pdata);
    #if HAS_PYTHON_VERSION(0x03070000)
    Py_tss_t* PyThread_tss_alloc();
    int PyThread_tss_create(Py_tss_t* key);
    void PyThread_tss_delete(Py_tss_t* key);
    void PyThread_tss_free(Py_tss_t* key);
    void* PyThread_tss_get(Py_tss_t* key);
    int PyThread_tss_is_created(Py_tss_t* key);
    int PyThread_tss_set(Py_tss_t* key, void* value);
    #endif
    #if HAS_PYTHON_VERSION(0x03090000)
    int PyTraceBack_Here(PyFrameObject* f);
    #endif
    int PyTraceBack_Print(PyObject* v, PyObject* f);
    PyObject* PyTuple_GetItem(PyObject* tuple, Py_ssize_t index);
    PyObject* PyTuple_GetSlice(PyObject* tuple, Py_ssize_t low, Py_ssize_t high);
    PyObject* PyTuple_New(Py_ssize_t size);

    template<typename... Args>
    PyObject* PyTuple_Pack(Py_ssize_t n, Args... args)
    {
        return (*PyTuple_Pack_Ptr)(n, args...);
    }

    int PyTuple_SetItem(PyObject* tuple, Py_ssize_t index, PyObject* item);
    Py_ssize_t PyTuple_Size(PyObject* tuple);
    unsigned int PyType_ClearCache();
    const char* _PyType_Name(PyTypeObject* type);
    PyObject* PyType_FromModuleAndSpec(PyObject* module, PyType_Spec* spec, PyObject* bases);
    PyObject* PyType_FromSpec(PyType_Spec* spec);
    PyObject* PyType_FromSpecWithBases(PyType_Spec* spec, PyObject* bases);
    PyObject* PyType_GenericAlloc(PyTypeObject* type, Py_ssize_t nitems);
    PyObject* PyType_GenericNew(PyTypeObject* type, PyObject* args, PyObject* kwds);
    unsigned long PyType_GetFlags(PyTypeObject* type);
    PyObject* PyType_GetModule(PyTypeObject* type);
    void* PyType_GetModuleState(PyTypeObject* type);
    PyObject* PyType_GetName(PyTypeObject* type);
    PyObject* PyType_GetQualName(PyTypeObject* type);
    void* PyType_GetSlot(PyTypeObject* type, int slot);
    int PyType_IsSubtype(PyTypeObject* child, PyTypeObject* parent);
    void PyType_Modified(PyTypeObject* type);
    int PyType_Ready(PyTypeObject* type);
    PyObject* PyUnicodeDecodeError_Create(const char* encoding, const char* object, Py_ssize_t length, Py_ssize_t start, Py_ssize_t end, const char* reason);
    PyObject* PyUnicodeDecodeError_GetEncoding(PyObject* exc);
    int PyUnicodeDecodeError_GetEnd(PyObject* exc, Py_ssize_t* end);
    PyObject* PyUnicodeDecodeError_GetObject(PyObject* exc);
    PyObject* PyUnicodeDecodeError_GetReason(PyObject* exc);
    int PyUnicodeDecodeError_GetStart(PyObject* exc, Py_ssize_t* start);
    int PyUnicodeDecodeError_SetEnd(PyObject* exc, Py_ssize_t end);
    int PyUnicodeDecodeError_SetReason(PyObject* exc, const char* reason);
    int PyUnicodeDecodeError_SetStart(PyObject* exc, Py_ssize_t start);
    PyObject* PyUnicodeEncodeError_GetEncoding(PyObject* exc);
    int PyUnicodeEncodeError_GetEnd(PyObject* exc, Py_ssize_t* end);
    PyObject* PyUnicodeEncodeError_GetObject(PyObject* exc);
    PyObject* PyUnicodeEncodeError_GetReason(PyObject* exc);
    int PyUnicodeEncodeError_GetStart(PyObject* exc, Py_ssize_t* start);
    int PyUnicodeEncodeError_SetEnd(PyObject* exc, Py_ssize_t end);
    int PyUnicodeEncodeError_SetReason(PyObject* exc, const char* reason);
    int PyUnicodeEncodeError_SetStart(PyObject* exc, Py_ssize_t start);
    int PyUnicodeTranslateError_GetEnd(PyObject* exc, Py_ssize_t* end);
    PyObject* PyUnicodeTranslateError_GetObject(PyObject* exc);
    PyObject* PyUnicodeTranslateError_GetReason(PyObject* exc);
    int PyUnicodeTranslateError_GetStart(PyObject* exc, Py_ssize_t* start);
    int PyUnicodeTranslateError_SetEnd(PyObject* exc, Py_ssize_t end);
    int PyUnicodeTranslateError_SetReason(PyObject* exc, const char* reason);
    int PyUnicodeTranslateError_SetStart(PyObject* exc, Py_ssize_t start);
    void PyUnicode_Append(PyObject** pleft, PyObject* right);
    void PyUnicode_AppendAndDel(PyObject** pleft, PyObject* right);
    PyObject* PyUnicode_AsASCIIString(PyObject* unicode);
    PyObject* PyUnicode_AsCharmapString(PyObject* unicode, PyObject* mapping);
    PyObject* PyUnicode_AsDecodedObject(PyObject* unicode, const char* encoding, const char* errors);
    PyObject* PyUnicode_AsDecodedUnicode(PyObject* unicode, const char* encoding, const char* errors);
    PyObject* PyUnicode_AsEncodedObject(PyObject* unicode, const char* encoding, const char* errors);
    PyObject* PyUnicode_AsEncodedString(PyObject* unicode, const char* encoding, const char* errors);
    PyObject* PyUnicode_AsEncodedUnicode(PyObject* unicode, const char* encoding, const char* errors);
    PyObject* PyUnicode_AsLatin1String(PyObject* unicode);
    PyObject* PyUnicode_AsRawUnicodeEscapeString(PyObject* unicode);
    Py_UCS4* PyUnicode_AsUCS4(PyObject* unicode, Py_UCS4* buffer, Py_ssize_t buflen, int copy_null);
    Py_UCS4* PyUnicode_AsUCS4Copy(PyObject* unicode);
    PyObject* PyUnicode_AsUTF16String(PyObject* unicode);
    PyObject* PyUnicode_AsUTF32String(PyObject* unicode);
    const char* PyUnicode_AsUTF8AndSize(PyObject* unicode, Py_ssize_t* size);
    PyObject* PyUnicode_AsUTF8String(PyObject* unicode);
    PyObject* PyUnicode_AsUnicodeEscapeString(PyObject* unicode);
    Py_ssize_t PyUnicode_AsWideChar(PyObject* unicode, wchar_t* w, Py_ssize_t size);
    wchar_t* PyUnicode_AsWideCharString(PyObject* unicode, Py_ssize_t* size);
    PyObject* PyUnicode_BuildEncodingMap(PyObject* string);
    int PyUnicode_Compare(PyObject* left, PyObject* right);
    int PyUnicode_CompareWithASCIIString(PyObject* left, const char* right);
    PyObject* PyUnicode_Concat(PyObject* left, PyObject* right);
    int PyUnicode_Contains(PyObject* container, PyObject* element);
    Py_ssize_t PyUnicode_Count(PyObject* str, PyObject* substr, Py_ssize_t start, Py_ssize_t end);
    PyObject* PyUnicode_Decode(const char* s, Py_ssize_t size, const char* encoding, const char* errors);
    PyObject* PyUnicode_DecodeASCII(const char* string, Py_ssize_t length, const char* errors);
    PyObject* PyUnicode_DecodeCharmap(const char* string, Py_ssize_t length, PyObject* mapping, const char* errors);
    PyObject* PyUnicode_DecodeFSDefault(const char* s);
    PyObject* PyUnicode_DecodeFSDefaultAndSize(const char* s, Py_ssize_t size);
    PyObject* PyUnicode_DecodeLatin1(const char* string, Py_ssize_t length, const char* errors);
    PyObject* PyUnicode_DecodeLocale(const char* str, const char* errors);
    PyObject* PyUnicode_DecodeLocaleAndSize(const char* str, Py_ssize_t len, const char* errors);
    PyObject* PyUnicode_DecodeRawUnicodeEscape(const char* string, Py_ssize_t length, const char* errors);
    PyObject* PyUnicode_DecodeUTF16(const char* string, Py_ssize_t length, const char* errors, int* byteorder);
    PyObject* PyUnicode_DecodeUTF16Stateful(const char* string, Py_ssize_t length, const char* errors, int* byteorder, Py_ssize_t* consumed);
    PyObject* PyUnicode_DecodeUTF32(const char* string, Py_ssize_t length, const char* errors, int* byteorder);
    PyObject* PyUnicode_DecodeUTF32Stateful(const char* string, Py_ssize_t length, const char* errors, int* byteorder, Py_ssize_t* consumed);
    PyObject* PyUnicode_DecodeUTF7(const char* string, Py_ssize_t length, const char* errors);
    PyObject* PyUnicode_DecodeUTF7Stateful(const char* string, Py_ssize_t length, const char* errors, Py_ssize_t* consumed);
    PyObject* PyUnicode_DecodeUTF8(const char* string, Py_ssize_t length, const char* errors);
    PyObject* PyUnicode_DecodeUTF8Stateful(const char* string, Py_ssize_t length, const char* errors, Py_ssize_t* consumed);
    PyObject* PyUnicode_DecodeUnicodeEscape(const char* string, Py_ssize_t length, const char* errors);
    PyObject* PyUnicode_EncodeFSDefault(PyObject* unicode);
    PyObject* PyUnicode_EncodeLocale(PyObject* unicode, const char* errors);
    int PyUnicode_FSConverter(PyObject* obj, void* result);
    int PyUnicode_FSDecoder(PyObject* obj, void* result);
    Py_ssize_t PyUnicode_Find(PyObject* str, PyObject* substr, Py_ssize_t start, Py_ssize_t end, int direction);
    Py_ssize_t PyUnicode_FindChar(PyObject* str, Py_UCS4 ch, Py_ssize_t start, Py_ssize_t end, int direction);
    PyObject* PyUnicode_Format(PyObject* format, PyObject* args);
    PyObject* PyUnicode_FromEncodedObject(PyObject* obj, const char* encoding, const char* errors);
    PyObject* PyUnicode_FromFormat(const char* format, ...);
    PyObject* PyUnicode_FromFormatV(const char* format, va_list vargs);
    PyObject* PyUnicode_FromObject(PyObject* obj);
    PyObject* PyUnicode_FromOrdinal(int ordinal);
    PyObject *PyUnicode_FromString(const char* u);
    PyObject* PyUnicode_FromStringAndSize(const char* u, Py_ssize_t size);
    PyObject* PyUnicode_FromWideChar(const wchar_t* w, Py_ssize_t size);
    const char* PyUnicode_GetDefaultEncoding();
    Py_ssize_t PyUnicode_GetLength(PyObject* unicode);
    Py_ssize_t PyUnicode_GetSize(PyObject* unicode);
    PyObject* PyUnicode_InternFromString(const char* u);
    void PyUnicode_InternImmortal(PyObject** unicode);
    void PyUnicode_InternInPlace(PyObject** unicode);
    int PyUnicode_IsIdentifier(PyObject* s);
    PyObject* PyUnicode_Join(PyObject* separator, PyObject* seq);
    PyObject* PyUnicode_Partition(PyObject* s, PyObject* sep);
    PyObject* PyUnicode_RPartition(PyObject* s, PyObject* sep);
    PyObject* PyUnicode_RSplit(PyObject* s, PyObject* sep, Py_ssize_t maxsplit);
    Py_UCS4 PyUnicode_ReadChar(PyObject* unicode, Py_ssize_t index);
    PyObject* PyUnicode_Replace(PyObject* str, PyObject* substr, PyObject* replstr, Py_ssize_t maxcount);
    int PyUnicode_Resize(PyObject** unicode, Py_ssize_t length);
    PyObject* PyUnicode_RichCompare(PyObject* left, PyObject* right, int op);
    PyObject* PyUnicode_Split(PyObject* s, PyObject* sep, Py_ssize_t maxsplit);
    PyObject* PyUnicode_Splitlines(PyObject* s, int keepends);
    PyObject* PyUnicode_Substring(PyObject* str, Py_ssize_t start, Py_ssize_t end);
    Py_ssize_t PyUnicode_Tailmatch(PyObject* str, PyObject* substr, Py_ssize_t start, Py_ssize_t end, int direction);
    PyObject* PyUnicode_Translate(PyObject* str, PyObject* table, const char* errors);
    int PyUnicode_WriteChar(PyObject* unicode, Py_ssize_t index, Py_UCS4 character);
    PyObject* PyWeakref_GetObject(PyObject* ref);
    PyObject* PyWeakref_NewProxy(PyObject* ob, PyObject* callback);
    PyObject* PyWeakref_NewRef(PyObject* ob, PyObject* callback);
    PyObject* PyWrapper_New(PyObject* descr, PyObject* self);
    int Py_AddPendingCall(int (*func)(void*), void* arg);
    int Py_AtExit(void (*func)());

    template<typename... Args>
    PyObject* Py_BuildValue(const char* format, Args... args)
    {
        return (*Py_BuildValue_Ptr)(format, args...);
    }

    int Py_BytesMain(int argc, char** argv);
    PyObject* (Py_CompileString)(const char* str, const char* filename, int start);
    void Py_DecRef(PyObject* obj);
    wchar_t* Py_DecodeLocale(const char* arg, size_t* size);
    char* Py_EncodeLocale(const wchar_t* text, size_t* error_pos);
    void Py_EndInterpreter(PyThreadState* tstate);
    int (Py_EnterRecursiveCall)(const char* where);
    void Py_Exit(int exit_code);
    void (Py_FatalError)(const char* message);
    void Py_Finalize();
    int Py_FinalizeEx();
    PyObject* Py_GenericAlias(PyObject* origin, PyObject* args);
    const char* Py_GetBuildInfo();
    const char* Py_GetCompiler();
    const char* Py_GetCopyright();
    wchar_t* Py_GetExecPrefix();
    wchar_t* Py_GetPath();
    const char* Py_GetPlatform();
    wchar_t* Py_GetPrefix();
    wchar_t* Py_GetProgramFullPath();
    wchar_t* Py_GetProgramName();
    wchar_t* Py_GetPythonHome();
    int Py_GetRecursionLimit();
    const char* Py_GetVersion();
    void Py_IncRef(PyObject* obj);
    void Py_Initialize();
    void Py_InitializeEx(int initsigs);
    int (Py_Is)(PyObject* x, PyObject* y);
    int (Py_IsFalse)(PyObject* x);
    int Py_IsInitialized();
    int (Py_IsNone)(PyObject* x);
    int (Py_IsTrue)(PyObject* x);
    int (Py_IS_TYPE)(PyObject* o, PyTypeObject* type);
    PyTypeObject* (Py_TYPE)(PyObject *o);
    void (Py_LeaveRecursiveCall)();
    int Py_Main(int argc, wchar_t** argv);
    int Py_MakePendingCalls();
    PyThreadState* Py_NewInterpreter();

    #ifdef Py_NewRef
    PyObject* (Py_NewRef)(PyObject* obj);
    #endif

    int Py_ReprEnter(PyObject* object);
    void Py_ReprLeave(PyObject* object);
    void Py_SetPath(const wchar_t* path);
    void Py_SetProgramName(const wchar_t* name);
    void Py_SetPythonHome(const wchar_t* home);
    void Py_SetRecursionLimit(int limit);
    PyObject* Py_VaBuildValue(const char* format, va_list vargs);

    #ifdef Py_XNewRef
    PyObject* (Py_XNewRef)(PyObject* obj);
    #endif


    void (Py_INCREF)(PyObject* op)
    {
        //++op->ob_refcnt;
        Py_IncRef(op);
    }

    void (Py_DECREF)(PyObject* op)
    {
        Py_DecRef(op);
        /*PyObject* _py_decref_tmp = op;
        if (--(_py_decref_tmp)->ob_refcnt == 0)
        {
            _Py_Dealloc(_py_decref_tmp);
        }*/
    }

    void (Py_CLEAR)(PyObject* op)
    {
        PyObject* _py_tmp = op;
        if (_py_tmp != NULL)
        {
            op = NULL;
            (Py_DECREF)(_py_tmp);
        }
    }

    void (Py_XINCREF)(PyObject* op)
    {
        PyObject* _py_xincref_tmp = op;
        if (_py_xincref_tmp != NULL)
        {
            (Py_INCREF)(_py_xincref_tmp);
        }
    }

    void (Py_XDECREF)(PyObject* op)
    {
        PyObject* _py_xdecref_tmp = op;
        if (_py_xdecref_tmp != NULL)
        {
            (Py_DECREF)(_py_xdecref_tmp);
        }
    }

    bool (PyList_Check)(PyObject* op)
    {
        return ((PyType_GetFlags((Py_TYPE)(op)) & Py_TPFLAGS_LIST_SUBCLASS) != 0);
    }

    template<typename Type>
    Type* (PyObject_New)(PyTypeObject* type_object)
    {
        auto _PyObject_Size = [](PyTypeObject* tp) -> Py_ssize_t {
            std::size_t offset = sizeof(PyVarObject) + sizeof(const char*);
            return *reinterpret_cast<Py_ssize_t*>(reinterpret_cast<char*>(tp) + offset);
        };

        auto _PyObject_New = [this, &_PyObject_Size](PyTypeObject* tp) -> PyObject* {
            PyObject* op = reinterpret_cast<PyObject*>(PyObject_Malloc(_PyObject_Size(tp)));
            if (op == NULL) {
                return PyErr_NoMemory();
            }
            PyObject_Init(op, tp);
            return op;
        };

        return reinterpret_cast<Type*>(_PyObject_New(type_object));
    }

    PyObject* Py_GetNone_Object() const
    {
        return static_cast<PyObject*>(Py_NoneStruct_Ptr);
    }

    PyTypeObject* PyList_Type() const
    {
        return static_cast<PyTypeObject*>(Py_ListType_Ptr);
    }
};
#endif

#endif //REMOTEINPUT_PYTHON_HXX
