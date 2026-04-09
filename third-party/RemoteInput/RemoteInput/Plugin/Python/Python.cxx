//
// Created by Brandon on 2023-02-16.
//

#include "Python.hxx"

#if !defined(USE_PYBIND11)
#include "Platform.hxx"

void Python::steal(Python& a, Python& b)
{
    a.module = b.module;
    b.module = nullptr;

    a.Py_NoneStruct_Ptr = b.Py_NoneStruct_Ptr;
    b.Py_NoneStruct_Ptr = nullptr;

    a.Py_TrueStruct_Ptr = b.Py_TrueStruct_Ptr;
    b.Py_TrueStruct_Ptr = nullptr;

    a.Py_FalseStruct_Ptr = b.Py_FalseStruct_Ptr;
    b.Py_FalseStruct_Ptr = nullptr;

    a.Py_ListType_Ptr = b.Py_ListType_Ptr;
    b.Py_ListType_Ptr = nullptr;

    a.PyAIter_Check_Ptr = b.PyAIter_Check_Ptr;
    b.PyAIter_Check_Ptr = nullptr;
    a.PyArg_Parse_Ptr = b.PyArg_Parse_Ptr;
    b.PyArg_Parse_Ptr = nullptr;
    a.PyArg_ParseTuple_Ptr = b.PyArg_ParseTuple_Ptr;
    b.PyArg_ParseTuple_Ptr = nullptr;
    a.PyArg_ParseTupleAndKeywords_Ptr = b.PyArg_ParseTupleAndKeywords_Ptr;
    b.PyArg_ParseTupleAndKeywords_Ptr = nullptr;
    a.PyArg_UnpackTuple_Ptr = b.PyArg_UnpackTuple_Ptr;
    b.PyArg_UnpackTuple_Ptr = nullptr;
    a.PyArg_VaParse_Ptr = b.PyArg_VaParse_Ptr;
    b.PyArg_VaParse_Ptr = nullptr;
    a.PyArg_VaParseTupleAndKeywords_Ptr = b.PyArg_VaParseTupleAndKeywords_Ptr;
    b.PyArg_VaParseTupleAndKeywords_Ptr = nullptr;
    a.PyArg_ValidateKeywordArguments_Ptr = b.PyArg_ValidateKeywordArguments_Ptr;
    b.PyArg_ValidateKeywordArguments_Ptr = nullptr;
    a.PyBool_FromLong_Ptr = b.PyBool_FromLong_Ptr;
    b.PyBool_FromLong_Ptr = nullptr;

    #if HAS_PYTHON_VERSION(0x030B1000)
    a.PyBuffer_FillContiguousStrides_Ptr = b.PyBuffer_FillContiguousStrides_Ptr;
    b.PyBuffer_FillContiguousStrides_Ptr = nullptr;
    a.PyBuffer_FillInfo_Ptr = b.PyBuffer_FillInfo_Ptr;
    b.PyBuffer_FillInfo_Ptr = nullptr;
    a.PyBuffer_FromContiguous_Ptr = b.PyBuffer_FromContiguous_Ptr;
    b.PyBuffer_FromContiguous_Ptr = nullptr;
    a.PyBuffer_GetPointer_Ptr = b.PyBuffer_GetPointer_Ptr;
    b.PyBuffer_GetPointer_Ptr = nullptr;
    a.PyBuffer_IsContiguous_Ptr = b.PyBuffer_IsContiguous_Ptr;
    b.PyBuffer_IsContiguous_Ptr = nullptr;
    a.PyBuffer_Release_Ptr = b.PyBuffer_Release_Ptr;
    b.PyBuffer_Release_Ptr = nullptr;
    a.PyBuffer_SizeFromFormat_Ptr = b.PyBuffer_SizeFromFormat_Ptr;
    b.PyBuffer_SizeFromFormat_Ptr = nullptr;
    a.PyBuffer_ToContiguous_Ptr = b.PyBuffer_ToContiguous_Ptr;
    b.PyBuffer_ToContiguous_Ptr = nullptr;
    #endif

    a.PyByteArray_AsString_Ptr = b.PyByteArray_AsString_Ptr;
    b.PyByteArray_AsString_Ptr = nullptr;
    a.PyByteArray_Concat_Ptr = b.PyByteArray_Concat_Ptr;
    b.PyByteArray_Concat_Ptr = nullptr;
    a.PyByteArray_FromObject_Ptr = b.PyByteArray_FromObject_Ptr;
    b.PyByteArray_FromObject_Ptr = nullptr;
    a.PyByteArray_FromStringAndSize_Ptr = b.PyByteArray_FromStringAndSize_Ptr;
    b.PyByteArray_FromStringAndSize_Ptr = nullptr;
    a.PyByteArray_Resize_Ptr = b.PyByteArray_Resize_Ptr;
    b.PyByteArray_Resize_Ptr = nullptr;
    a.PyByteArray_Size_Ptr = b.PyByteArray_Size_Ptr;
    b.PyByteArray_Size_Ptr = nullptr;
    a.PyBytes_AsString_Ptr = b.PyBytes_AsString_Ptr;
    b.PyBytes_AsString_Ptr = nullptr;
    a.PyBytes_AsStringAndSize_Ptr = b.PyBytes_AsStringAndSize_Ptr;
    b.PyBytes_AsStringAndSize_Ptr = nullptr;
    a.PyBytes_Concat_Ptr = b.PyBytes_Concat_Ptr;
    b.PyBytes_Concat_Ptr = nullptr;
    a.PyBytes_ConcatAndDel_Ptr = b.PyBytes_ConcatAndDel_Ptr;
    b.PyBytes_ConcatAndDel_Ptr = nullptr;
    a.PyBytes_DecodeEscape_Ptr = b.PyBytes_DecodeEscape_Ptr;
    b.PyBytes_DecodeEscape_Ptr = nullptr;
    a.PyBytes_FromFormat_Ptr = b.PyBytes_FromFormat_Ptr;
    b.PyBytes_FromFormat_Ptr = nullptr;
    a.PyBytes_FromFormatV_Ptr = b.PyBytes_FromFormatV_Ptr;
    b.PyBytes_FromFormatV_Ptr = nullptr;
    a.PyBytes_FromObject_Ptr = b.PyBytes_FromObject_Ptr;
    b.PyBytes_FromObject_Ptr = nullptr;
    a.PyBytes_FromString_Ptr = b.PyBytes_FromString_Ptr;
    b.PyBytes_FromString_Ptr = nullptr;
    a.PyBytes_FromStringAndSize_Ptr = b.PyBytes_FromStringAndSize_Ptr;
    b.PyBytes_FromStringAndSize_Ptr = nullptr;
    a.PyBytes_Repr_Ptr = b.PyBytes_Repr_Ptr;
    b.PyBytes_Repr_Ptr = nullptr;
    a.PyBytes_Size_Ptr = b.PyBytes_Size_Ptr;
    b.PyBytes_Size_Ptr = nullptr;
    a.PyCFunction_Call_Ptr = b.PyCFunction_Call_Ptr;
    b.PyCFunction_Call_Ptr = nullptr;
    a.PyCFunction_GetFlags_Ptr = b.PyCFunction_GetFlags_Ptr;
    b.PyCFunction_GetFlags_Ptr = nullptr;
    a.PyCFunction_GetFunction_Ptr = b.PyCFunction_GetFunction_Ptr;
    b.PyCFunction_GetFunction_Ptr = nullptr;
    a.PyCFunction_GetSelf_Ptr = b.PyCFunction_GetSelf_Ptr;
    b.PyCFunction_GetSelf_Ptr = nullptr;
    #ifdef PyCFunction_New
    a.PyCFunction_New_Ptr = b.PyCFunction_New_Ptr;
    b.PyCFunction_New_Ptr = nullptr;
    #endif
    #if HAS_PYTHON_VERSION(0x03090000)
    a.PyCFunction_NewEx_Ptr = b.PyCFunction_NewEx_Ptr;
    b.PyCFunction_NewEx_Ptr = nullptr;
    #endif
    a.PyCMethod_New_Ptr = b.PyCMethod_New_Ptr;
    b.PyCMethod_New_Ptr = nullptr;
    a.PyCallIter_New_Ptr = b.PyCallIter_New_Ptr;
    b.PyCallIter_New_Ptr = nullptr;
    a.PyCallable_Check_Ptr = b.PyCallable_Check_Ptr;
    b.PyCallable_Check_Ptr = nullptr;
    a.PyCapsule_GetContext_Ptr = b.PyCapsule_GetContext_Ptr;
    b.PyCapsule_GetContext_Ptr = nullptr;
    a.PyCapsule_GetDestructor_Ptr = b.PyCapsule_GetDestructor_Ptr;
    b.PyCapsule_GetDestructor_Ptr = nullptr;
    a.PyCapsule_GetName_Ptr = b.PyCapsule_GetName_Ptr;
    b.PyCapsule_GetName_Ptr = nullptr;
    a.PyCapsule_GetPointer_Ptr = b.PyCapsule_GetPointer_Ptr;
    b.PyCapsule_GetPointer_Ptr = nullptr;
    a.PyCapsule_Import_Ptr = b.PyCapsule_Import_Ptr;
    b.PyCapsule_Import_Ptr = nullptr;
    a.PyCapsule_IsValid_Ptr = b.PyCapsule_IsValid_Ptr;
    b.PyCapsule_IsValid_Ptr = nullptr;
    a.PyCapsule_New_Ptr = b.PyCapsule_New_Ptr;
    b.PyCapsule_New_Ptr = nullptr;
    a.PyCapsule_SetContext_Ptr = b.PyCapsule_SetContext_Ptr;
    b.PyCapsule_SetContext_Ptr = nullptr;
    a.PyCapsule_SetDestructor_Ptr = b.PyCapsule_SetDestructor_Ptr;
    b.PyCapsule_SetDestructor_Ptr = nullptr;
    a.PyCapsule_SetName_Ptr = b.PyCapsule_SetName_Ptr;
    b.PyCapsule_SetName_Ptr = nullptr;
    a.PyCapsule_SetPointer_Ptr = b.PyCapsule_SetPointer_Ptr;
    b.PyCapsule_SetPointer_Ptr = nullptr;
    a.PyComplex_FromDoubles_Ptr = b.PyComplex_FromDoubles_Ptr;
    b.PyComplex_FromDoubles_Ptr = nullptr;
    a.PyComplex_ImagAsDouble_Ptr = b.PyComplex_ImagAsDouble_Ptr;
    b.PyComplex_ImagAsDouble_Ptr = nullptr;
    a.PyComplex_RealAsDouble_Ptr = b.PyComplex_RealAsDouble_Ptr;
    b.PyComplex_RealAsDouble_Ptr = nullptr;
    a.PyDescr_NewClassMethod_Ptr = b.PyDescr_NewClassMethod_Ptr;
    b.PyDescr_NewClassMethod_Ptr = nullptr;
    a.PyDescr_NewGetSet_Ptr = b.PyDescr_NewGetSet_Ptr;
    b.PyDescr_NewGetSet_Ptr = nullptr;
    a.PyDescr_NewMember_Ptr = b.PyDescr_NewMember_Ptr;
    b.PyDescr_NewMember_Ptr = nullptr;
    a.PyDescr_NewMethod_Ptr = b.PyDescr_NewMethod_Ptr;
    b.PyDescr_NewMethod_Ptr = nullptr;
    a.PyDictProxy_New_Ptr = b.PyDictProxy_New_Ptr;
    b.PyDictProxy_New_Ptr = nullptr;
    a.PyDict_Clear_Ptr = b.PyDict_Clear_Ptr;
    b.PyDict_Clear_Ptr = nullptr;
    a.PyDict_Contains_Ptr = b.PyDict_Contains_Ptr;
    b.PyDict_Contains_Ptr = nullptr;
    a.PyDict_Copy_Ptr = b.PyDict_Copy_Ptr;
    b.PyDict_Copy_Ptr = nullptr;
    a.PyDict_DelItem_Ptr = b.PyDict_DelItem_Ptr;
    b.PyDict_DelItem_Ptr = nullptr;
    a.PyDict_DelItemString_Ptr = b.PyDict_DelItemString_Ptr;
    b.PyDict_DelItemString_Ptr = nullptr;
    a.PyDict_GetItem_Ptr = b.PyDict_GetItem_Ptr;
    b.PyDict_GetItem_Ptr = nullptr;
    a.PyDict_GetItemString_Ptr = b.PyDict_GetItemString_Ptr;
    b.PyDict_GetItemString_Ptr = nullptr;
    a.PyDict_GetItemWithError_Ptr = b.PyDict_GetItemWithError_Ptr;
    b.PyDict_GetItemWithError_Ptr = nullptr;
    a.PyDict_Items_Ptr = b.PyDict_Items_Ptr;
    b.PyDict_Items_Ptr = nullptr;
    a.PyDict_Keys_Ptr = b.PyDict_Keys_Ptr;
    b.PyDict_Keys_Ptr = nullptr;
    a.PyDict_Merge_Ptr = b.PyDict_Merge_Ptr;
    b.PyDict_Merge_Ptr = nullptr;
    a.PyDict_MergeFromSeq2_Ptr = b.PyDict_MergeFromSeq2_Ptr;
    b.PyDict_MergeFromSeq2_Ptr = nullptr;
    a.PyDict_New_Ptr = b.PyDict_New_Ptr;
    b.PyDict_New_Ptr = nullptr;
    a.PyDict_Next_Ptr = b.PyDict_Next_Ptr;
    b.PyDict_Next_Ptr = nullptr;
    a.PyDict_SetItem_Ptr = b.PyDict_SetItem_Ptr;
    b.PyDict_SetItem_Ptr = nullptr;
    a.PyDict_SetItemString_Ptr = b.PyDict_SetItemString_Ptr;
    b.PyDict_SetItemString_Ptr = nullptr;
    a.PyDict_Size_Ptr = b.PyDict_Size_Ptr;
    b.PyDict_Size_Ptr = nullptr;
    a.PyDict_Update_Ptr = b.PyDict_Update_Ptr;
    b.PyDict_Update_Ptr = nullptr;
    a.PyDict_Values_Ptr = b.PyDict_Values_Ptr;
    b.PyDict_Values_Ptr = nullptr;
    a.PyErr_BadArgument_Ptr = b.PyErr_BadArgument_Ptr;
    b.PyErr_BadArgument_Ptr = nullptr;
    a._PyErr_BadInternalCall_Ptr = b._PyErr_BadInternalCall_Ptr;
    b._PyErr_BadInternalCall_Ptr = nullptr;
    a.PyErr_CheckSignals_Ptr = b.PyErr_CheckSignals_Ptr;
    b.PyErr_CheckSignals_Ptr = nullptr;
    a.PyErr_Clear_Ptr = b.PyErr_Clear_Ptr;
    b.PyErr_Clear_Ptr = nullptr;
    a.PyErr_Display_Ptr = b.PyErr_Display_Ptr;
    b.PyErr_Display_Ptr = nullptr;
    a.PyErr_ExceptionMatches_Ptr = b.PyErr_ExceptionMatches_Ptr;
    b.PyErr_ExceptionMatches_Ptr = nullptr;
    a.PyErr_Fetch_Ptr = b.PyErr_Fetch_Ptr;
    b.PyErr_Fetch_Ptr = nullptr;
    a.PyErr_Format_Ptr = b.PyErr_Format_Ptr;
    b.PyErr_Format_Ptr = nullptr;
    a.PyErr_FormatV_Ptr = b.PyErr_FormatV_Ptr;
    b.PyErr_FormatV_Ptr = nullptr;
    a.PyErr_GetExcInfo_Ptr = b.PyErr_GetExcInfo_Ptr;
    b.PyErr_GetExcInfo_Ptr = nullptr;
    a.PyErr_GetHandledException_Ptr = b.PyErr_GetHandledException_Ptr;
    b.PyErr_GetHandledException_Ptr = nullptr;
    a.PyErr_GivenExceptionMatches_Ptr = b.PyErr_GivenExceptionMatches_Ptr;
    b.PyErr_GivenExceptionMatches_Ptr = nullptr;
    a.PyErr_NewException_Ptr = b.PyErr_NewException_Ptr;
    b.PyErr_NewException_Ptr = nullptr;
    a.PyErr_NewExceptionWithDoc_Ptr = b.PyErr_NewExceptionWithDoc_Ptr;
    b.PyErr_NewExceptionWithDoc_Ptr = nullptr;
    a.PyErr_NoMemory_Ptr = b.PyErr_NoMemory_Ptr;
    b.PyErr_NoMemory_Ptr = nullptr;
    a.PyErr_NormalizeException_Ptr = b.PyErr_NormalizeException_Ptr;
    b.PyErr_NormalizeException_Ptr = nullptr;
    a.PyErr_Occurred_Ptr = b.PyErr_Occurred_Ptr;
    b.PyErr_Occurred_Ptr = nullptr;
    a.PyErr_Print_Ptr = b.PyErr_Print_Ptr;
    b.PyErr_Print_Ptr = nullptr;
    a.PyErr_PrintEx_Ptr = b.PyErr_PrintEx_Ptr;
    b.PyErr_PrintEx_Ptr = nullptr;
    a.PyErr_ProgramText_Ptr = b.PyErr_ProgramText_Ptr;
    b.PyErr_ProgramText_Ptr = nullptr;
    a.PyErr_ResourceWarning_Ptr = b.PyErr_ResourceWarning_Ptr;
    b.PyErr_ResourceWarning_Ptr = nullptr;
    a.PyErr_Restore_Ptr = b.PyErr_Restore_Ptr;
    b.PyErr_Restore_Ptr = nullptr;
    a.PyErr_SetExcInfo_Ptr = b.PyErr_SetExcInfo_Ptr;
    b.PyErr_SetExcInfo_Ptr = nullptr;
    a.PyErr_SetFromErrno_Ptr = b.PyErr_SetFromErrno_Ptr;
    b.PyErr_SetFromErrno_Ptr = nullptr;
    a.PyErr_SetFromErrnoWithFilename_Ptr = b.PyErr_SetFromErrnoWithFilename_Ptr;
    b.PyErr_SetFromErrnoWithFilename_Ptr = nullptr;
    a.PyErr_SetFromErrnoWithFilenameObject_Ptr = b.PyErr_SetFromErrnoWithFilenameObject_Ptr;
    b.PyErr_SetFromErrnoWithFilenameObject_Ptr = nullptr;
    a.PyErr_SetFromErrnoWithFilenameObjects_Ptr = b.PyErr_SetFromErrnoWithFilenameObjects_Ptr;
    b.PyErr_SetFromErrnoWithFilenameObjects_Ptr = nullptr;
    a.PyErr_SetHandledException_Ptr = b.PyErr_SetHandledException_Ptr;
    b.PyErr_SetHandledException_Ptr = nullptr;
    a.PyErr_SetImportError_Ptr = b.PyErr_SetImportError_Ptr;
    b.PyErr_SetImportError_Ptr = nullptr;
    a.PyErr_SetImportErrorSubclass_Ptr = b.PyErr_SetImportErrorSubclass_Ptr;
    b.PyErr_SetImportErrorSubclass_Ptr = nullptr;
    a.PyErr_SetInterrupt_Ptr = b.PyErr_SetInterrupt_Ptr;
    b.PyErr_SetInterrupt_Ptr = nullptr;
    a.PyErr_SetInterruptEx_Ptr = b.PyErr_SetInterruptEx_Ptr;
    b.PyErr_SetInterruptEx_Ptr = nullptr;
    a.PyErr_SetNone_Ptr = b.PyErr_SetNone_Ptr;
    b.PyErr_SetNone_Ptr = nullptr;
    a.PyErr_SetObject_Ptr = b.PyErr_SetObject_Ptr;
    b.PyErr_SetObject_Ptr = nullptr;
    a.PyErr_SetString_Ptr = b.PyErr_SetString_Ptr;
    b.PyErr_SetString_Ptr = nullptr;
    a.PyErr_SyntaxLocation_Ptr = b.PyErr_SyntaxLocation_Ptr;
    b.PyErr_SyntaxLocation_Ptr = nullptr;
    a.PyErr_SyntaxLocationEx_Ptr = b.PyErr_SyntaxLocationEx_Ptr;
    b.PyErr_SyntaxLocationEx_Ptr = nullptr;
    a.PyErr_WarnEx_Ptr = b.PyErr_WarnEx_Ptr;
    b.PyErr_WarnEx_Ptr = nullptr;
    a.PyErr_WarnExplicit_Ptr = b.PyErr_WarnExplicit_Ptr;
    b.PyErr_WarnExplicit_Ptr = nullptr;
    a.PyErr_WarnFormat_Ptr = b.PyErr_WarnFormat_Ptr;
    b.PyErr_WarnFormat_Ptr = nullptr;
    a.PyErr_WriteUnraisable_Ptr = b.PyErr_WriteUnraisable_Ptr;
    b.PyErr_WriteUnraisable_Ptr = nullptr;
    a.PyEval_AcquireLock_Ptr = b.PyEval_AcquireLock_Ptr;
    b.PyEval_AcquireLock_Ptr = nullptr;
    a.PyEval_AcquireThread_Ptr = b.PyEval_AcquireThread_Ptr;
    b.PyEval_AcquireThread_Ptr = nullptr;
    a.PyEval_CallFunction_Ptr = b.PyEval_CallFunction_Ptr;
    b.PyEval_CallFunction_Ptr = nullptr;
    a.PyEval_CallMethod_Ptr = b.PyEval_CallMethod_Ptr;
    b.PyEval_CallMethod_Ptr = nullptr;
    a.PyEval_CallObjectWithKeywords_Ptr = b.PyEval_CallObjectWithKeywords_Ptr;
    b.PyEval_CallObjectWithKeywords_Ptr = nullptr;
    a.PyEval_EvalCode_Ptr = b.PyEval_EvalCode_Ptr;
    b.PyEval_EvalCode_Ptr = nullptr;
    a.PyEval_EvalCodeEx_Ptr = b.PyEval_EvalCodeEx_Ptr;
    b.PyEval_EvalCodeEx_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x03090000)
    a.PyEval_EvalFrame_Ptr = b.PyEval_EvalFrame_Ptr;
    b.PyEval_EvalFrame_Ptr = nullptr;
    a.PyEval_EvalFrameEx_Ptr = b.PyEval_EvalFrameEx_Ptr;
    b.PyEval_EvalFrameEx_Ptr = nullptr;
    #endif
    a.PyEval_GetBuiltins_Ptr = b.PyEval_GetBuiltins_Ptr;
    b.PyEval_GetBuiltins_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x03090000)
    a.PyEval_GetFrame_Ptr = b.PyEval_GetFrame_Ptr;
    b.PyEval_GetFrame_Ptr = nullptr;
    #endif
    a.PyEval_GetFuncDesc_Ptr = b.PyEval_GetFuncDesc_Ptr;
    b.PyEval_GetFuncDesc_Ptr = nullptr;
    a.PyEval_GetFuncName_Ptr = b.PyEval_GetFuncName_Ptr;
    b.PyEval_GetFuncName_Ptr = nullptr;
    a.PyEval_GetGlobals_Ptr = b.PyEval_GetGlobals_Ptr;
    b.PyEval_GetGlobals_Ptr = nullptr;
    a.PyEval_GetLocals_Ptr = b.PyEval_GetLocals_Ptr;
    b.PyEval_GetLocals_Ptr = nullptr;
    a.PyEval_InitThreads_Ptr = b.PyEval_InitThreads_Ptr;
    b.PyEval_InitThreads_Ptr = nullptr;
    a.PyEval_ReleaseLock_Ptr = b.PyEval_ReleaseLock_Ptr;
    b.PyEval_ReleaseLock_Ptr = nullptr;
    a.PyEval_ReleaseThread_Ptr = b.PyEval_ReleaseThread_Ptr;
    b.PyEval_ReleaseThread_Ptr = nullptr;
    a.PyEval_RestoreThread_Ptr = b.PyEval_RestoreThread_Ptr;
    b.PyEval_RestoreThread_Ptr = nullptr;
    a.PyEval_SaveThread_Ptr = b.PyEval_SaveThread_Ptr;
    b.PyEval_SaveThread_Ptr = nullptr;
    a.PyEval_ThreadsInitialized_Ptr = b.PyEval_ThreadsInitialized_Ptr;
    b.PyEval_ThreadsInitialized_Ptr = nullptr;
    a.PyExceptionClass_Name_Ptr = b.PyExceptionClass_Name_Ptr;
    b.PyExceptionClass_Name_Ptr = nullptr;
    a.PyException_GetCause_Ptr = b.PyException_GetCause_Ptr;
    b.PyException_GetCause_Ptr = nullptr;
    a.PyException_GetContext_Ptr = b.PyException_GetContext_Ptr;
    b.PyException_GetContext_Ptr = nullptr;
    a.PyException_GetTraceback_Ptr = b.PyException_GetTraceback_Ptr;
    b.PyException_GetTraceback_Ptr = nullptr;
    a.PyException_SetCause_Ptr = b.PyException_SetCause_Ptr;
    b.PyException_SetCause_Ptr = nullptr;
    a.PyException_SetContext_Ptr = b.PyException_SetContext_Ptr;
    b.PyException_SetContext_Ptr = nullptr;
    a.PyException_SetTraceback_Ptr = b.PyException_SetTraceback_Ptr;
    b.PyException_SetTraceback_Ptr = nullptr;
    a.PyFile_FromFd_Ptr = b.PyFile_FromFd_Ptr;
    b.PyFile_FromFd_Ptr = nullptr;
    a.PyFile_GetLine_Ptr = b.PyFile_GetLine_Ptr;
    b.PyFile_GetLine_Ptr = nullptr;
    a.PyFile_WriteObject_Ptr = b.PyFile_WriteObject_Ptr;
    b.PyFile_WriteObject_Ptr = nullptr;
    a.PyFile_WriteString_Ptr = b.PyFile_WriteString_Ptr;
    b.PyFile_WriteString_Ptr = nullptr;
    a.PyFloat_AsDouble_Ptr = b.PyFloat_AsDouble_Ptr;
    b.PyFloat_AsDouble_Ptr = nullptr;
    a.PyFloat_FromDouble_Ptr = b.PyFloat_FromDouble_Ptr;
    b.PyFloat_FromDouble_Ptr = nullptr;
    a.PyFloat_FromString_Ptr = b.PyFloat_FromString_Ptr;
    b.PyFloat_FromString_Ptr = nullptr;
    a.PyFloat_GetInfo_Ptr = b.PyFloat_GetInfo_Ptr;
    b.PyFloat_GetInfo_Ptr = nullptr;
    a.PyFloat_GetMax_Ptr = b.PyFloat_GetMax_Ptr;
    b.PyFloat_GetMax_Ptr = nullptr;
    a.PyFloat_GetMin_Ptr = b.PyFloat_GetMin_Ptr;
    b.PyFloat_GetMin_Ptr = nullptr;
    a.PyFrozenSet_New_Ptr = b.PyFrozenSet_New_Ptr;
    b.PyFrozenSet_New_Ptr = nullptr;
    a.PyGC_Collect_Ptr = b.PyGC_Collect_Ptr;
    b.PyGC_Collect_Ptr = nullptr;
    a.PyGC_Disable_Ptr = b.PyGC_Disable_Ptr;
    b.PyGC_Disable_Ptr = nullptr;
    a.PyGC_Enable_Ptr = b.PyGC_Enable_Ptr;
    b.PyGC_Enable_Ptr = nullptr;
    a.PyGC_IsEnabled_Ptr = b.PyGC_IsEnabled_Ptr;
    b.PyGC_IsEnabled_Ptr = nullptr;
    a.PyGILState_Ensure_Ptr = b.PyGILState_Ensure_Ptr;
    b.PyGILState_Ensure_Ptr = nullptr;
    a.PyGILState_GetThisThreadState_Ptr = b.PyGILState_GetThisThreadState_Ptr;
    b.PyGILState_GetThisThreadState_Ptr = nullptr;
    a.PyGILState_Release_Ptr = b.PyGILState_Release_Ptr;
    b.PyGILState_Release_Ptr = nullptr;
    a.PyImport_AddModule_Ptr = b.PyImport_AddModule_Ptr;
    b.PyImport_AddModule_Ptr = nullptr;
    a.PyImport_AddModuleObject_Ptr = b.PyImport_AddModuleObject_Ptr;
    b.PyImport_AddModuleObject_Ptr = nullptr;
    a.PyImport_AppendInittab_Ptr = b.PyImport_AppendInittab_Ptr;
    b.PyImport_AppendInittab_Ptr = nullptr;
    a.PyImport_ExecCodeModule_Ptr = b.PyImport_ExecCodeModule_Ptr;
    b.PyImport_ExecCodeModule_Ptr = nullptr;
    a.PyImport_ExecCodeModuleEx_Ptr = b.PyImport_ExecCodeModuleEx_Ptr;
    b.PyImport_ExecCodeModuleEx_Ptr = nullptr;
    a.PyImport_ExecCodeModuleObject_Ptr = b.PyImport_ExecCodeModuleObject_Ptr;
    b.PyImport_ExecCodeModuleObject_Ptr = nullptr;
    a.PyImport_ExecCodeModuleWithPathnames_Ptr = b.PyImport_ExecCodeModuleWithPathnames_Ptr;
    b.PyImport_ExecCodeModuleWithPathnames_Ptr = nullptr;
    a.PyImport_GetImporter_Ptr = b.PyImport_GetImporter_Ptr;
    b.PyImport_GetImporter_Ptr = nullptr;
    a.PyImport_GetMagicNumber_Ptr = b.PyImport_GetMagicNumber_Ptr;
    b.PyImport_GetMagicNumber_Ptr = nullptr;
    a.PyImport_GetMagicTag_Ptr = b.PyImport_GetMagicTag_Ptr;
    b.PyImport_GetMagicTag_Ptr = nullptr;
    a.PyImport_GetModule_Ptr = b.PyImport_GetModule_Ptr;
    b.PyImport_GetModule_Ptr = nullptr;
    a.PyImport_GetModuleDict_Ptr = b.PyImport_GetModuleDict_Ptr;
    b.PyImport_GetModuleDict_Ptr = nullptr;
    a.PyImport_Import_Ptr = b.PyImport_Import_Ptr;
    b.PyImport_Import_Ptr = nullptr;
    a.PyImport_ImportFrozenModule_Ptr = b.PyImport_ImportFrozenModule_Ptr;
    b.PyImport_ImportFrozenModule_Ptr = nullptr;
    a.PyImport_ImportFrozenModuleObject_Ptr = b.PyImport_ImportFrozenModuleObject_Ptr;
    b.PyImport_ImportFrozenModuleObject_Ptr = nullptr;
    a.PyImport_ImportModule_Ptr = b.PyImport_ImportModule_Ptr;
    b.PyImport_ImportModule_Ptr = nullptr;
    a.PyImport_ImportModuleLevel_Ptr = b.PyImport_ImportModuleLevel_Ptr;
    b.PyImport_ImportModuleLevel_Ptr = nullptr;
    a.PyImport_ImportModuleLevelObject_Ptr = b.PyImport_ImportModuleLevelObject_Ptr;
    b.PyImport_ImportModuleLevelObject_Ptr = nullptr;
    a.PyImport_ImportModuleNoBlock_Ptr = b.PyImport_ImportModuleNoBlock_Ptr;
    b.PyImport_ImportModuleNoBlock_Ptr = nullptr;
    a.PyImport_ReloadModule_Ptr = b.PyImport_ReloadModule_Ptr;
    b.PyImport_ReloadModule_Ptr = nullptr;
    a.PyIndex_Check_Ptr = b.PyIndex_Check_Ptr;
    b.PyIndex_Check_Ptr = nullptr;
    a.PyInterpreterState_Clear_Ptr = b.PyInterpreterState_Clear_Ptr;
    b.PyInterpreterState_Clear_Ptr = nullptr;
    a.PyInterpreterState_Delete_Ptr = b.PyInterpreterState_Delete_Ptr;
    b.PyInterpreterState_Delete_Ptr = nullptr;
    a.PyInterpreterState_Get_Ptr = b.PyInterpreterState_Get_Ptr;
    b.PyInterpreterState_Get_Ptr = nullptr;
    a.PyInterpreterState_GetDict_Ptr = b.PyInterpreterState_GetDict_Ptr;
    b.PyInterpreterState_GetDict_Ptr = nullptr;
    a.PyInterpreterState_GetID_Ptr = b.PyInterpreterState_GetID_Ptr;
    b.PyInterpreterState_GetID_Ptr = nullptr;
    a.PyInterpreterState_New_Ptr = b.PyInterpreterState_New_Ptr;
    b.PyInterpreterState_New_Ptr = nullptr;
    a.PyIter_Check_Ptr = b.PyIter_Check_Ptr;
    b.PyIter_Check_Ptr = nullptr;
    a.PyIter_Next_Ptr = b.PyIter_Next_Ptr;
    b.PyIter_Next_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x030A0000)
    a.PyIter_Send_Ptr = b.PyIter_Send_Ptr;
    b.PyIter_Send_Ptr = nullptr;
    #endif
    a.PyList_Append_Ptr = b.PyList_Append_Ptr;
    b.PyList_Append_Ptr = nullptr;
    a.PyList_AsTuple_Ptr = b.PyList_AsTuple_Ptr;
    b.PyList_AsTuple_Ptr = nullptr;
    a.PyList_GetItem_Ptr = b.PyList_GetItem_Ptr;
    b.PyList_GetItem_Ptr = nullptr;
    a.PyList_GetSlice_Ptr = b.PyList_GetSlice_Ptr;
    b.PyList_GetSlice_Ptr = nullptr;
    a.PyList_Insert_Ptr = b.PyList_Insert_Ptr;
    b.PyList_Insert_Ptr = nullptr;
    a.PyList_New_Ptr = b.PyList_New_Ptr;
    b.PyList_New_Ptr = nullptr;
    a.PyList_Reverse_Ptr = b.PyList_Reverse_Ptr;
    b.PyList_Reverse_Ptr = nullptr;
    a.PyList_SetItem_Ptr = b.PyList_SetItem_Ptr;
    b.PyList_SetItem_Ptr = nullptr;
    a.PyList_SetSlice_Ptr = b.PyList_SetSlice_Ptr;
    b.PyList_SetSlice_Ptr = nullptr;
    a.PyList_Size_Ptr = b.PyList_Size_Ptr;
    b.PyList_Size_Ptr = nullptr;
    a.PyList_Sort_Ptr = b.PyList_Sort_Ptr;
    b.PyList_Sort_Ptr = nullptr;
    a.PyLong_AsDouble_Ptr = b.PyLong_AsDouble_Ptr;
    b.PyLong_AsDouble_Ptr = nullptr;
    a.PyLong_AsLong_Ptr = b.PyLong_AsLong_Ptr;
    b.PyLong_AsLong_Ptr = nullptr;
    a.PyLong_AsLongAndOverflow_Ptr = b.PyLong_AsLongAndOverflow_Ptr;
    b.PyLong_AsLongAndOverflow_Ptr = nullptr;
    a.PyLong_AsLongLong_Ptr = b.PyLong_AsLongLong_Ptr;
    b.PyLong_AsLongLong_Ptr = nullptr;
    a.PyLong_AsLongLongAndOverflow_Ptr = b.PyLong_AsLongLongAndOverflow_Ptr;
    b.PyLong_AsLongLongAndOverflow_Ptr = nullptr;
    a.PyLong_AsSize_t_Ptr = b.PyLong_AsSize_t_Ptr;
    b.PyLong_AsSize_t_Ptr = nullptr;
    a.PyLong_AsSsize_t_Ptr = b.PyLong_AsSsize_t_Ptr;
    b.PyLong_AsSsize_t_Ptr = nullptr;
    a.PyLong_AsUnsignedLong_Ptr = b.PyLong_AsUnsignedLong_Ptr;
    b.PyLong_AsUnsignedLong_Ptr = nullptr;
    a.PyLong_AsUnsignedLongLong_Ptr = b.PyLong_AsUnsignedLongLong_Ptr;
    b.PyLong_AsUnsignedLongLong_Ptr = nullptr;
    a.PyLong_AsUnsignedLongLongMask_Ptr = b.PyLong_AsUnsignedLongLongMask_Ptr;
    b.PyLong_AsUnsignedLongLongMask_Ptr = nullptr;
    a.PyLong_AsUnsignedLongMask_Ptr = b.PyLong_AsUnsignedLongMask_Ptr;
    b.PyLong_AsUnsignedLongMask_Ptr = nullptr;
    a.PyLong_AsVoidPtr_Ptr = b.PyLong_AsVoidPtr_Ptr;
    b.PyLong_AsVoidPtr_Ptr = nullptr;
    a.PyLong_FromDouble_Ptr = b.PyLong_FromDouble_Ptr;
    b.PyLong_FromDouble_Ptr = nullptr;
    a.PyLong_FromLong_Ptr = b.PyLong_FromLong_Ptr;
    b.PyLong_FromLong_Ptr = nullptr;
    a.PyLong_FromLongLong_Ptr = b.PyLong_FromLongLong_Ptr;
    b.PyLong_FromLongLong_Ptr = nullptr;
    a.PyLong_FromSize_t_Ptr = b.PyLong_FromSize_t_Ptr;
    b.PyLong_FromSize_t_Ptr = nullptr;
    a.PyLong_FromSsize_t_Ptr = b.PyLong_FromSsize_t_Ptr;
    b.PyLong_FromSsize_t_Ptr = nullptr;
    a.PyLong_FromString_Ptr = b.PyLong_FromString_Ptr;
    b.PyLong_FromString_Ptr = nullptr;
    a.PyLong_FromUnsignedLong_Ptr = b.PyLong_FromUnsignedLong_Ptr;
    b.PyLong_FromUnsignedLong_Ptr = nullptr;
    a.PyLong_FromUnsignedLongLong_Ptr = b.PyLong_FromUnsignedLongLong_Ptr;
    b.PyLong_FromUnsignedLongLong_Ptr = nullptr;
    a.PyLong_FromVoidPtr_Ptr = b.PyLong_FromVoidPtr_Ptr;
    b.PyLong_FromVoidPtr_Ptr = nullptr;
    a.PyLong_GetInfo_Ptr = b.PyLong_GetInfo_Ptr;
    b.PyLong_GetInfo_Ptr = nullptr;
    a.PyMapping_Check_Ptr = b.PyMapping_Check_Ptr;
    b.PyMapping_Check_Ptr = nullptr;
    a.PyMapping_GetItemString_Ptr = b.PyMapping_GetItemString_Ptr;
    b.PyMapping_GetItemString_Ptr = nullptr;
    a.PyMapping_HasKey_Ptr = b.PyMapping_HasKey_Ptr;
    b.PyMapping_HasKey_Ptr = nullptr;
    a.PyMapping_HasKeyString_Ptr = b.PyMapping_HasKeyString_Ptr;
    b.PyMapping_HasKeyString_Ptr = nullptr;
    a.PyMapping_Items_Ptr = b.PyMapping_Items_Ptr;
    b.PyMapping_Items_Ptr = nullptr;
    a.PyMapping_Keys_Ptr = b.PyMapping_Keys_Ptr;
    b.PyMapping_Keys_Ptr = nullptr;
    a.PyMapping_Length_Ptr = b.PyMapping_Length_Ptr;
    b.PyMapping_Length_Ptr = nullptr;
    a.PyMapping_SetItemString_Ptr = b.PyMapping_SetItemString_Ptr;
    b.PyMapping_SetItemString_Ptr = nullptr;
    a.PyMapping_Size_Ptr = b.PyMapping_Size_Ptr;
    b.PyMapping_Size_Ptr = nullptr;
    a.PyMapping_Values_Ptr = b.PyMapping_Values_Ptr;
    b.PyMapping_Values_Ptr = nullptr;
    a.PyMem_Calloc_Ptr = b.PyMem_Calloc_Ptr;
    b.PyMem_Calloc_Ptr = nullptr;
    a.PyMem_Free_Ptr = b.PyMem_Free_Ptr;
    b.PyMem_Free_Ptr = nullptr;
    a.PyMem_Malloc_Ptr = b.PyMem_Malloc_Ptr;
    b.PyMem_Malloc_Ptr = nullptr;
    a.PyMem_Realloc_Ptr = b.PyMem_Realloc_Ptr;
    b.PyMem_Realloc_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x030B1000)
    a.PyMemoryView_FromBuffer_Ptr = b.PyMemoryView_FromBuffer_Ptr;
    b.PyMemoryView_FromBuffer_Ptr = nullptr;
    #endif
    a.PyMemoryView_FromMemory_Ptr = b.PyMemoryView_FromMemory_Ptr;
    b.PyMemoryView_FromMemory_Ptr = nullptr;
    a.PyMemoryView_FromObject_Ptr = b.PyMemoryView_FromObject_Ptr;
    b.PyMemoryView_FromObject_Ptr = nullptr;
    a.PyMemoryView_GetContiguous_Ptr = b.PyMemoryView_GetContiguous_Ptr;
    b.PyMemoryView_GetContiguous_Ptr = nullptr;
    a.PyModuleDef_Init_Ptr = b.PyModuleDef_Init_Ptr;
    b.PyModuleDef_Init_Ptr = nullptr;
    a.PyModule_AddFunctions_Ptr = b.PyModule_AddFunctions_Ptr;
    b.PyModule_AddFunctions_Ptr = nullptr;
    a.PyModule_AddIntConstant_Ptr = b.PyModule_AddIntConstant_Ptr;
    b.PyModule_AddIntConstant_Ptr = nullptr;
    a.PyModule_AddObject_Ptr = b.PyModule_AddObject_Ptr;
    b.PyModule_AddObject_Ptr = nullptr;
    a.PyModule_AddObjectRef_Ptr = b.PyModule_AddObjectRef_Ptr;
    b.PyModule_AddObjectRef_Ptr = nullptr;
    a.PyModule_AddStringConstant_Ptr = b.PyModule_AddStringConstant_Ptr;
    b.PyModule_AddStringConstant_Ptr = nullptr;
    a.PyModule_AddType_Ptr = b.PyModule_AddType_Ptr;
    b.PyModule_AddType_Ptr = nullptr;
    a.PyModule_Create2_Ptr = b.PyModule_Create2_Ptr;
    b.PyModule_Create2_Ptr = nullptr;
    a.PyModule_ExecDef_Ptr = b.PyModule_ExecDef_Ptr;
    b.PyModule_ExecDef_Ptr = nullptr;
    a.PyModule_FromDefAndSpec2_Ptr = b.PyModule_FromDefAndSpec2_Ptr;
    b.PyModule_FromDefAndSpec2_Ptr = nullptr;
    a.PyModule_GetDef_Ptr = b.PyModule_GetDef_Ptr;
    b.PyModule_GetDef_Ptr = nullptr;
    a.PyModule_GetDict_Ptr = b.PyModule_GetDict_Ptr;
    b.PyModule_GetDict_Ptr = nullptr;
    a.PyModule_GetFilename_Ptr = b.PyModule_GetFilename_Ptr;
    b.PyModule_GetFilename_Ptr = nullptr;
    a.PyModule_GetFilenameObject_Ptr = b.PyModule_GetFilenameObject_Ptr;
    b.PyModule_GetFilenameObject_Ptr = nullptr;
    a.PyModule_GetName_Ptr = b.PyModule_GetName_Ptr;
    b.PyModule_GetName_Ptr = nullptr;
    a.PyModule_GetNameObject_Ptr = b.PyModule_GetNameObject_Ptr;
    b.PyModule_GetNameObject_Ptr = nullptr;
    a.PyModule_GetState_Ptr = b.PyModule_GetState_Ptr;
    b.PyModule_GetState_Ptr = nullptr;
    a.PyModule_New_Ptr = b.PyModule_New_Ptr;
    b.PyModule_New_Ptr = nullptr;
    a.PyModule_NewObject_Ptr = b.PyModule_NewObject_Ptr;
    b.PyModule_NewObject_Ptr = nullptr;
    a.PyModule_SetDocString_Ptr = b.PyModule_SetDocString_Ptr;
    b.PyModule_SetDocString_Ptr = nullptr;
    a.PyNumber_Absolute_Ptr = b.PyNumber_Absolute_Ptr;
    b.PyNumber_Absolute_Ptr = nullptr;
    a.PyNumber_Add_Ptr = b.PyNumber_Add_Ptr;
    b.PyNumber_Add_Ptr = nullptr;
    a.PyNumber_And_Ptr = b.PyNumber_And_Ptr;
    b.PyNumber_And_Ptr = nullptr;
    a.PyNumber_AsSsize_t_Ptr = b.PyNumber_AsSsize_t_Ptr;
    b.PyNumber_AsSsize_t_Ptr = nullptr;
    a.PyNumber_Check_Ptr = b.PyNumber_Check_Ptr;
    b.PyNumber_Check_Ptr = nullptr;
    a.PyNumber_Divmod_Ptr = b.PyNumber_Divmod_Ptr;
    b.PyNumber_Divmod_Ptr = nullptr;
    a.PyNumber_Float_Ptr = b.PyNumber_Float_Ptr;
    b.PyNumber_Float_Ptr = nullptr;
    a.PyNumber_FloorDivide_Ptr = b.PyNumber_FloorDivide_Ptr;
    b.PyNumber_FloorDivide_Ptr = nullptr;
    a.PyNumber_InPlaceAdd_Ptr = b.PyNumber_InPlaceAdd_Ptr;
    b.PyNumber_InPlaceAdd_Ptr = nullptr;
    a.PyNumber_InPlaceAnd_Ptr = b.PyNumber_InPlaceAnd_Ptr;
    b.PyNumber_InPlaceAnd_Ptr = nullptr;
    a.PyNumber_InPlaceFloorDivide_Ptr = b.PyNumber_InPlaceFloorDivide_Ptr;
    b.PyNumber_InPlaceFloorDivide_Ptr = nullptr;
    a.PyNumber_InPlaceLshift_Ptr = b.PyNumber_InPlaceLshift_Ptr;
    b.PyNumber_InPlaceLshift_Ptr = nullptr;
    a.PyNumber_InPlaceMatrixMultiply_Ptr = b.PyNumber_InPlaceMatrixMultiply_Ptr;
    b.PyNumber_InPlaceMatrixMultiply_Ptr = nullptr;
    a.PyNumber_InPlaceMultiply_Ptr = b.PyNumber_InPlaceMultiply_Ptr;
    b.PyNumber_InPlaceMultiply_Ptr = nullptr;
    a.PyNumber_InPlaceOr_Ptr = b.PyNumber_InPlaceOr_Ptr;
    b.PyNumber_InPlaceOr_Ptr = nullptr;
    a.PyNumber_InPlacePower_Ptr = b.PyNumber_InPlacePower_Ptr;
    b.PyNumber_InPlacePower_Ptr = nullptr;
    a.PyNumber_InPlaceRemainder_Ptr = b.PyNumber_InPlaceRemainder_Ptr;
    b.PyNumber_InPlaceRemainder_Ptr = nullptr;
    a.PyNumber_InPlaceRshift_Ptr = b.PyNumber_InPlaceRshift_Ptr;
    b.PyNumber_InPlaceRshift_Ptr = nullptr;
    a.PyNumber_InPlaceSubtract_Ptr = b.PyNumber_InPlaceSubtract_Ptr;
    b.PyNumber_InPlaceSubtract_Ptr = nullptr;
    a.PyNumber_InPlaceTrueDivide_Ptr = b.PyNumber_InPlaceTrueDivide_Ptr;
    b.PyNumber_InPlaceTrueDivide_Ptr = nullptr;
    a.PyNumber_InPlaceXor_Ptr = b.PyNumber_InPlaceXor_Ptr;
    b.PyNumber_InPlaceXor_Ptr = nullptr;
    a.PyNumber_Index_Ptr = b.PyNumber_Index_Ptr;
    b.PyNumber_Index_Ptr = nullptr;
    a.PyNumber_Invert_Ptr = b.PyNumber_Invert_Ptr;
    b.PyNumber_Invert_Ptr = nullptr;
    a.PyNumber_Long_Ptr = b.PyNumber_Long_Ptr;
    b.PyNumber_Long_Ptr = nullptr;
    a.PyNumber_Lshift_Ptr = b.PyNumber_Lshift_Ptr;
    b.PyNumber_Lshift_Ptr = nullptr;
    a.PyNumber_MatrixMultiply_Ptr = b.PyNumber_MatrixMultiply_Ptr;
    b.PyNumber_MatrixMultiply_Ptr = nullptr;
    a.PyNumber_Multiply_Ptr = b.PyNumber_Multiply_Ptr;
    b.PyNumber_Multiply_Ptr = nullptr;
    a.PyNumber_Negative_Ptr = b.PyNumber_Negative_Ptr;
    b.PyNumber_Negative_Ptr = nullptr;
    a.PyNumber_Or_Ptr = b.PyNumber_Or_Ptr;
    b.PyNumber_Or_Ptr = nullptr;
    a.PyNumber_Positive_Ptr = b.PyNumber_Positive_Ptr;
    b.PyNumber_Positive_Ptr = nullptr;
    a.PyNumber_Power_Ptr = b.PyNumber_Power_Ptr;
    b.PyNumber_Power_Ptr = nullptr;
    a.PyNumber_Remainder_Ptr = b.PyNumber_Remainder_Ptr;
    b.PyNumber_Remainder_Ptr = nullptr;
    a.PyNumber_Rshift_Ptr = b.PyNumber_Rshift_Ptr;
    b.PyNumber_Rshift_Ptr = nullptr;
    a.PyNumber_Subtract_Ptr = b.PyNumber_Subtract_Ptr;
    b.PyNumber_Subtract_Ptr = nullptr;
    a.PyNumber_ToBase_Ptr = b.PyNumber_ToBase_Ptr;
    b.PyNumber_ToBase_Ptr = nullptr;
    a.PyNumber_TrueDivide_Ptr = b.PyNumber_TrueDivide_Ptr;
    b.PyNumber_TrueDivide_Ptr = nullptr;
    a.PyNumber_Xor_Ptr = b.PyNumber_Xor_Ptr;
    b.PyNumber_Xor_Ptr = nullptr;
    a.PyOS_AfterFork_Ptr = b.PyOS_AfterFork_Ptr;
    b.PyOS_AfterFork_Ptr = nullptr;
    a.PyOS_AfterFork_Child_Ptr = b.PyOS_AfterFork_Child_Ptr;
    b.PyOS_AfterFork_Child_Ptr = nullptr;
    a.PyOS_AfterFork_Parent_Ptr = b.PyOS_AfterFork_Parent_Ptr;
    b.PyOS_AfterFork_Parent_Ptr = nullptr;
    a.PyOS_BeforeFork_Ptr = b.PyOS_BeforeFork_Ptr;
    b.PyOS_BeforeFork_Ptr = nullptr;
    a.PyOS_FSPath_Ptr = b.PyOS_FSPath_Ptr;
    b.PyOS_FSPath_Ptr = nullptr;
    a.PyOS_InterruptOccurred_Ptr = b.PyOS_InterruptOccurred_Ptr;
    b.PyOS_InterruptOccurred_Ptr = nullptr;
    a.PyOS_double_to_string_Ptr = b.PyOS_double_to_string_Ptr;
    b.PyOS_double_to_string_Ptr = nullptr;
    a.PyOS_getsig_Ptr = b.PyOS_getsig_Ptr;
    b.PyOS_getsig_Ptr = nullptr;
    a.PyOS_mystricmp_Ptr = b.PyOS_mystricmp_Ptr;
    b.PyOS_mystricmp_Ptr = nullptr;
    a.PyOS_mystrnicmp_Ptr = b.PyOS_mystrnicmp_Ptr;
    b.PyOS_mystrnicmp_Ptr = nullptr;
    a.PyOS_setsig_Ptr = b.PyOS_setsig_Ptr;
    b.PyOS_setsig_Ptr = nullptr;
    a.PyOS_snprintf_Ptr = b.PyOS_snprintf_Ptr;
    b.PyOS_snprintf_Ptr = nullptr;
    a.PyOS_string_to_double_Ptr = b.PyOS_string_to_double_Ptr;
    b.PyOS_string_to_double_Ptr = nullptr;
    a.PyOS_strtol_Ptr = b.PyOS_strtol_Ptr;
    b.PyOS_strtol_Ptr = nullptr;
    a.PyOS_strtoul_Ptr = b.PyOS_strtoul_Ptr;
    b.PyOS_strtoul_Ptr = nullptr;
    a.PyOS_vsnprintf_Ptr = b.PyOS_vsnprintf_Ptr;
    b.PyOS_vsnprintf_Ptr = nullptr;
    a.PyObject_ASCII_Ptr = b.PyObject_ASCII_Ptr;
    b.PyObject_ASCII_Ptr = nullptr;
    a.PyObject_AsCharBuffer_Ptr = b.PyObject_AsCharBuffer_Ptr;
    b.PyObject_AsCharBuffer_Ptr = nullptr;
    a.PyObject_AsFileDescriptor_Ptr = b.PyObject_AsFileDescriptor_Ptr;
    b.PyObject_AsFileDescriptor_Ptr = nullptr;
    a.PyObject_AsReadBuffer_Ptr = b.PyObject_AsReadBuffer_Ptr;
    b.PyObject_AsReadBuffer_Ptr = nullptr;
    a.PyObject_AsWriteBuffer_Ptr = b.PyObject_AsWriteBuffer_Ptr;
    b.PyObject_AsWriteBuffer_Ptr = nullptr;
    a.PyObject_Bytes_Ptr = b.PyObject_Bytes_Ptr;
    b.PyObject_Bytes_Ptr = nullptr;
    a.PyObject_Call_Ptr = b.PyObject_Call_Ptr;
    b.PyObject_Call_Ptr = nullptr;
    a.PyObject_CallFunction_Ptr = b.PyObject_CallFunction_Ptr;
    b.PyObject_CallFunction_Ptr = nullptr;
    a.PyObject_CallFunctionObjArgs_Ptr = b.PyObject_CallFunctionObjArgs_Ptr;
    b.PyObject_CallFunctionObjArgs_Ptr = nullptr;
    a.PyObject_CallMethod_Ptr = b.PyObject_CallMethod_Ptr;
    b.PyObject_CallMethod_Ptr = nullptr;
    a.PyObject_CallMethodObjArgs_Ptr = b.PyObject_CallMethodObjArgs_Ptr;
    b.PyObject_CallMethodObjArgs_Ptr = nullptr;
    a.PyObject_CallNoArgs_Ptr = b.PyObject_CallNoArgs_Ptr;
    b.PyObject_CallNoArgs_Ptr = nullptr;
    a.PyObject_CallObject_Ptr = b.PyObject_CallObject_Ptr;
    b.PyObject_CallObject_Ptr = nullptr;
    a.PyObject_Calloc_Ptr = b.PyObject_Calloc_Ptr;
    b.PyObject_Calloc_Ptr = nullptr;
    a.PyObject_CheckBuffer_Ptr = b.PyObject_CheckBuffer_Ptr;
    b.PyObject_CheckBuffer_Ptr = nullptr;
    a.PyObject_CheckReadBuffer_Ptr = b.PyObject_CheckReadBuffer_Ptr;
    b.PyObject_CheckReadBuffer_Ptr = nullptr;
    a.PyObject_ClearWeakRefs_Ptr = b.PyObject_ClearWeakRefs_Ptr;
    b.PyObject_ClearWeakRefs_Ptr = nullptr;
    a.PyObject_CopyData_Ptr = b.PyObject_CopyData_Ptr;
    b.PyObject_CopyData_Ptr = nullptr;
    a.PyObject_DelItem_Ptr = b.PyObject_DelItem_Ptr;
    b.PyObject_DelItem_Ptr = nullptr;
    a.PyObject_DelItemString_Ptr = b.PyObject_DelItemString_Ptr;
    b.PyObject_DelItemString_Ptr = nullptr;
    a.PyObject_Dir_Ptr = b.PyObject_Dir_Ptr;
    b.PyObject_Dir_Ptr = nullptr;
    a.PyObject_Format_Ptr = b.PyObject_Format_Ptr;
    b.PyObject_Format_Ptr = nullptr;
    a.PyObject_Free_Ptr = b.PyObject_Free_Ptr;
    b.PyObject_Free_Ptr = nullptr;
    a.PyObject_GC_Del_Ptr = b.PyObject_GC_Del_Ptr;
    b.PyObject_GC_Del_Ptr = nullptr;
    a.PyObject_GC_IsFinalized_Ptr = b.PyObject_GC_IsFinalized_Ptr;
    b.PyObject_GC_IsFinalized_Ptr = nullptr;
    a.PyObject_GC_IsTracked_Ptr = b.PyObject_GC_IsTracked_Ptr;
    b.PyObject_GC_IsTracked_Ptr = nullptr;
    a.PyObject_GC_Track_Ptr = b.PyObject_GC_Track_Ptr;
    b.PyObject_GC_Track_Ptr = nullptr;
    a.PyObject_GC_UnTrack_Ptr = b.PyObject_GC_UnTrack_Ptr;
    b.PyObject_GC_UnTrack_Ptr = nullptr;
    a.PyObject_GenericGetAttr_Ptr = b.PyObject_GenericGetAttr_Ptr;
    b.PyObject_GenericGetAttr_Ptr = nullptr;
    a.PyObject_GenericGetDict_Ptr = b.PyObject_GenericGetDict_Ptr;
    b.PyObject_GenericGetDict_Ptr = nullptr;
    a.PyObject_GenericSetAttr_Ptr = b.PyObject_GenericSetAttr_Ptr;
    b.PyObject_GenericSetAttr_Ptr = nullptr;
    a.PyObject_GenericSetDict_Ptr = b.PyObject_GenericSetDict_Ptr;
    b.PyObject_GenericSetDict_Ptr = nullptr;
    a.PyObject_GetAIter_Ptr = b.PyObject_GetAIter_Ptr;
    b.PyObject_GetAIter_Ptr = nullptr;
    a.PyObject_GetAttr_Ptr = b.PyObject_GetAttr_Ptr;
    b.PyObject_GetAttr_Ptr = nullptr;
    a.PyObject_GetAttrString_Ptr = b.PyObject_GetAttrString_Ptr;
    b.PyObject_GetAttrString_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x030B1000)
    a.PyObject_GetBuffer_Ptr = b.PyObject_GetBuffer_Ptr;
    b.PyObject_GetBuffer_Ptr = nullptr;
    #endif
    a.PyObject_GetItem_Ptr = b.PyObject_GetItem_Ptr;
    b.PyObject_GetItem_Ptr = nullptr;
    a.PyObject_GetIter_Ptr = b.PyObject_GetIter_Ptr;
    b.PyObject_GetIter_Ptr = nullptr;
    a.PyObject_HasAttr_Ptr = b.PyObject_HasAttr_Ptr;
    b.PyObject_HasAttr_Ptr = nullptr;
    a.PyObject_HasAttrString_Ptr = b.PyObject_HasAttrString_Ptr;
    b.PyObject_HasAttrString_Ptr = nullptr;
    a.PyObject_Hash_Ptr = b.PyObject_Hash_Ptr;
    b.PyObject_Hash_Ptr = nullptr;
    a.PyObject_HashNotImplemented_Ptr = b.PyObject_HashNotImplemented_Ptr;
    b.PyObject_HashNotImplemented_Ptr = nullptr;
    a.PyObject_Init_Ptr = b.PyObject_Init_Ptr;
    b.PyObject_Init_Ptr = nullptr;
    a.PyObject_InitVar_Ptr = b.PyObject_InitVar_Ptr;
    b.PyObject_InitVar_Ptr = nullptr;
    a.PyObject_IsInstance_Ptr = b.PyObject_IsInstance_Ptr;
    b.PyObject_IsInstance_Ptr = nullptr;
    a.PyObject_IsSubclass_Ptr = b.PyObject_IsSubclass_Ptr;
    b.PyObject_IsSubclass_Ptr = nullptr;
    a.PyObject_IsTrue_Ptr = b.PyObject_IsTrue_Ptr;
    b.PyObject_IsTrue_Ptr = nullptr;
    a.PyObject_Length_Ptr = b.PyObject_Length_Ptr;
    b.PyObject_Length_Ptr = nullptr;
    a.PyObject_Malloc_Ptr = b.PyObject_Malloc_Ptr;
    b.PyObject_Malloc_Ptr = nullptr;
    a.PyObject_Not_Ptr = b.PyObject_Not_Ptr;
    b.PyObject_Not_Ptr = nullptr;
    a.PyObject_Realloc_Ptr = b.PyObject_Realloc_Ptr;
    b.PyObject_Realloc_Ptr = nullptr;
    a.PyObject_Repr_Ptr = b.PyObject_Repr_Ptr;
    b.PyObject_Repr_Ptr = nullptr;
    a.PyObject_RichCompare_Ptr = b.PyObject_RichCompare_Ptr;
    b.PyObject_RichCompare_Ptr = nullptr;
    a.PyObject_RichCompareBool_Ptr = b.PyObject_RichCompareBool_Ptr;
    b.PyObject_RichCompareBool_Ptr = nullptr;
    a.PyObject_SelfIter_Ptr = b.PyObject_SelfIter_Ptr;
    b.PyObject_SelfIter_Ptr = nullptr;
    a.PyObject_SetAttr_Ptr = b.PyObject_SetAttr_Ptr;
    b.PyObject_SetAttr_Ptr = nullptr;
    a.PyObject_SetAttrString_Ptr = b.PyObject_SetAttrString_Ptr;
    b.PyObject_SetAttrString_Ptr = nullptr;
    a.PyObject_SetItem_Ptr = b.PyObject_SetItem_Ptr;
    b.PyObject_SetItem_Ptr = nullptr;
    a.PyObject_Size_Ptr = b.PyObject_Size_Ptr;
    b.PyObject_Size_Ptr = nullptr;
    a.PyObject_Str_Ptr = b.PyObject_Str_Ptr;
    b.PyObject_Str_Ptr = nullptr;
    a.PyObject_Type_Ptr = b.PyObject_Type_Ptr;
    b.PyObject_Type_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x030B0000)
    a.PyObject_TypeCheck_Ptr = b.PyObject_TypeCheck_Ptr;
    b.PyObject_TypeCheck_Ptr = nullptr;
    #endif
    a.PySeqIter_New_Ptr = b.PySeqIter_New_Ptr;
    b.PySeqIter_New_Ptr = nullptr;
    a.PySequence_Check_Ptr = b.PySequence_Check_Ptr;
    b.PySequence_Check_Ptr = nullptr;
    a.PySequence_Concat_Ptr = b.PySequence_Concat_Ptr;
    b.PySequence_Concat_Ptr = nullptr;
    a.PySequence_Contains_Ptr = b.PySequence_Contains_Ptr;
    b.PySequence_Contains_Ptr = nullptr;
    a.PySequence_Count_Ptr = b.PySequence_Count_Ptr;
    b.PySequence_Count_Ptr = nullptr;
    a.PySequence_DelItem_Ptr = b.PySequence_DelItem_Ptr;
    b.PySequence_DelItem_Ptr = nullptr;
    a.PySequence_DelSlice_Ptr = b.PySequence_DelSlice_Ptr;
    b.PySequence_DelSlice_Ptr = nullptr;
    a.PySequence_Fast_Ptr = b.PySequence_Fast_Ptr;
    b.PySequence_Fast_Ptr = nullptr;
    a.PySequence_GetItem_Ptr = b.PySequence_GetItem_Ptr;
    b.PySequence_GetItem_Ptr = nullptr;
    a.PySequence_GetSlice_Ptr = b.PySequence_GetSlice_Ptr;
    b.PySequence_GetSlice_Ptr = nullptr;
    a.PySequence_In_Ptr = b.PySequence_In_Ptr;
    b.PySequence_In_Ptr = nullptr;
    a.PySequence_InPlaceConcat_Ptr = b.PySequence_InPlaceConcat_Ptr;
    b.PySequence_InPlaceConcat_Ptr = nullptr;
    a.PySequence_InPlaceRepeat_Ptr = b.PySequence_InPlaceRepeat_Ptr;
    b.PySequence_InPlaceRepeat_Ptr = nullptr;
    a.PySequence_Index_Ptr = b.PySequence_Index_Ptr;
    b.PySequence_Index_Ptr = nullptr;
    a.PySequence_Length_Ptr = b.PySequence_Length_Ptr;
    b.PySequence_Length_Ptr = nullptr;
    a.PySequence_List_Ptr = b.PySequence_List_Ptr;
    b.PySequence_List_Ptr = nullptr;
    a.PySequence_Repeat_Ptr = b.PySequence_Repeat_Ptr;
    b.PySequence_Repeat_Ptr = nullptr;
    a.PySequence_SetItem_Ptr = b.PySequence_SetItem_Ptr;
    b.PySequence_SetItem_Ptr = nullptr;
    a.PySequence_SetSlice_Ptr = b.PySequence_SetSlice_Ptr;
    b.PySequence_SetSlice_Ptr = nullptr;
    a.PySequence_Size_Ptr = b.PySequence_Size_Ptr;
    b.PySequence_Size_Ptr = nullptr;
    a.PySequence_Tuple_Ptr = b.PySequence_Tuple_Ptr;
    b.PySequence_Tuple_Ptr = nullptr;
    a.PySet_Add_Ptr = b.PySet_Add_Ptr;
    b.PySet_Add_Ptr = nullptr;
    a.PySet_Clear_Ptr = b.PySet_Clear_Ptr;
    b.PySet_Clear_Ptr = nullptr;
    a.PySet_Contains_Ptr = b.PySet_Contains_Ptr;
    b.PySet_Contains_Ptr = nullptr;
    a.PySet_Discard_Ptr = b.PySet_Discard_Ptr;
    b.PySet_Discard_Ptr = nullptr;
    a.PySet_New_Ptr = b.PySet_New_Ptr;
    b.PySet_New_Ptr = nullptr;
    a.PySet_Pop_Ptr = b.PySet_Pop_Ptr;
    b.PySet_Pop_Ptr = nullptr;
    a.PySet_Size_Ptr = b.PySet_Size_Ptr;
    b.PySet_Size_Ptr = nullptr;
    a.PySlice_AdjustIndices_Ptr = b.PySlice_AdjustIndices_Ptr;
    b.PySlice_AdjustIndices_Ptr = nullptr;
    a.PySlice_GetIndices_Ptr = b.PySlice_GetIndices_Ptr;
    b.PySlice_GetIndices_Ptr = nullptr;
    #ifdef PySlice_GetIndicesEx
    a.PySlice_GetIndicesEx_Ptr = b.PySlice_GetIndicesEx_Ptr;
    b.PySlice_GetIndicesEx_Ptr = nullptr;
    #endif
    a.PySlice_New_Ptr = b.PySlice_New_Ptr;
    b.PySlice_New_Ptr = nullptr;
    a.PySlice_Unpack_Ptr = b.PySlice_Unpack_Ptr;
    b.PySlice_Unpack_Ptr = nullptr;
    a.PyState_AddModule_Ptr = b.PyState_AddModule_Ptr;
    b.PyState_AddModule_Ptr = nullptr;
    a.PyState_FindModule_Ptr = b.PyState_FindModule_Ptr;
    b.PyState_FindModule_Ptr = nullptr;
    a.PyState_RemoveModule_Ptr = b.PyState_RemoveModule_Ptr;
    b.PyState_RemoveModule_Ptr = nullptr;
    a.PyStructSequence_GetItem_Ptr = b.PyStructSequence_GetItem_Ptr;
    b.PyStructSequence_GetItem_Ptr = nullptr;
    a.PyStructSequence_New_Ptr = b.PyStructSequence_New_Ptr;
    b.PyStructSequence_New_Ptr = nullptr;
    a.PyStructSequence_NewType_Ptr = b.PyStructSequence_NewType_Ptr;
    b.PyStructSequence_NewType_Ptr = nullptr;
    a.PyStructSequence_SetItem_Ptr = b.PyStructSequence_SetItem_Ptr;
    b.PyStructSequence_SetItem_Ptr = nullptr;
    a.PySys_AddWarnOption_Ptr = b.PySys_AddWarnOption_Ptr;
    b.PySys_AddWarnOption_Ptr = nullptr;
    a.PySys_AddWarnOptionUnicode_Ptr = b.PySys_AddWarnOptionUnicode_Ptr;
    b.PySys_AddWarnOptionUnicode_Ptr = nullptr;
    a.PySys_AddXOption_Ptr = b.PySys_AddXOption_Ptr;
    b.PySys_AddXOption_Ptr = nullptr;
    a.PySys_FormatStderr_Ptr = b.PySys_FormatStderr_Ptr;
    b.PySys_FormatStderr_Ptr = nullptr;
    a.PySys_FormatStdout_Ptr = b.PySys_FormatStdout_Ptr;
    b.PySys_FormatStdout_Ptr = nullptr;
    a.PySys_GetObject_Ptr = b.PySys_GetObject_Ptr;
    b.PySys_GetObject_Ptr = nullptr;
    a.PySys_GetXOptions_Ptr = b.PySys_GetXOptions_Ptr;
    b.PySys_GetXOptions_Ptr = nullptr;
    a.PySys_HasWarnOptions_Ptr = b.PySys_HasWarnOptions_Ptr;
    b.PySys_HasWarnOptions_Ptr = nullptr;
    a.PySys_ResetWarnOptions_Ptr = b.PySys_ResetWarnOptions_Ptr;
    b.PySys_ResetWarnOptions_Ptr = nullptr;
    a.PySys_SetArgv_Ptr = b.PySys_SetArgv_Ptr;
    b.PySys_SetArgv_Ptr = nullptr;
    a.PySys_SetArgvEx_Ptr = b.PySys_SetArgvEx_Ptr;
    b.PySys_SetArgvEx_Ptr = nullptr;
    a.PySys_SetObject_Ptr = b.PySys_SetObject_Ptr;
    b.PySys_SetObject_Ptr = nullptr;
    a.PySys_SetPath_Ptr = b.PySys_SetPath_Ptr;
    b.PySys_SetPath_Ptr = nullptr;
    a.PySys_WriteStderr_Ptr = b.PySys_WriteStderr_Ptr;
    b.PySys_WriteStderr_Ptr = nullptr;
    a.PySys_WriteStdout_Ptr = b.PySys_WriteStdout_Ptr;
    b.PySys_WriteStdout_Ptr = nullptr;
    a.PyThreadState_Clear_Ptr = b.PyThreadState_Clear_Ptr;
    b.PyThreadState_Clear_Ptr = nullptr;
    a.PyThreadState_Delete_Ptr = b.PyThreadState_Delete_Ptr;
    b.PyThreadState_Delete_Ptr = nullptr;
    a.PyThreadState_Get_Ptr = b.PyThreadState_Get_Ptr;
    b.PyThreadState_Get_Ptr = nullptr;
    a.PyThreadState_GetDict_Ptr = b.PyThreadState_GetDict_Ptr;
    b.PyThreadState_GetDict_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x03090000)
    a.PyThreadState_GetFrame_Ptr = b.PyThreadState_GetFrame_Ptr;
    b.PyThreadState_GetFrame_Ptr = nullptr;
    #endif
    a.PyThreadState_GetID_Ptr = b.PyThreadState_GetID_Ptr;
    b.PyThreadState_GetID_Ptr = nullptr;
    a.PyThreadState_GetInterpreter_Ptr = b.PyThreadState_GetInterpreter_Ptr;
    b.PyThreadState_GetInterpreter_Ptr = nullptr;
    a.PyThreadState_New_Ptr = b.PyThreadState_New_Ptr;
    b.PyThreadState_New_Ptr = nullptr;
    a.PyThreadState_SetAsyncExc_Ptr = b.PyThreadState_SetAsyncExc_Ptr;
    b.PyThreadState_SetAsyncExc_Ptr = nullptr;
    a.PyThreadState_Swap_Ptr = b.PyThreadState_Swap_Ptr;
    b.PyThreadState_Swap_Ptr = nullptr;
    a.PyThread_GetInfo_Ptr = b.PyThread_GetInfo_Ptr;
    b.PyThread_GetInfo_Ptr = nullptr;
    a.PyThread_ReInitTLS_Ptr = b.PyThread_ReInitTLS_Ptr;
    b.PyThread_ReInitTLS_Ptr = nullptr;
    a.PyThread_acquire_lock_Ptr = b.PyThread_acquire_lock_Ptr;
    b.PyThread_acquire_lock_Ptr = nullptr;
    a.PyThread_acquire_lock_timed_Ptr = b.PyThread_acquire_lock_timed_Ptr;
    b.PyThread_acquire_lock_timed_Ptr = nullptr;
    a.PyThread_allocate_lock_Ptr = b.PyThread_allocate_lock_Ptr;
    b.PyThread_allocate_lock_Ptr = nullptr;
    a.PyThread_create_key_Ptr = b.PyThread_create_key_Ptr;
    b.PyThread_create_key_Ptr = nullptr;
    a.PyThread_delete_key_Ptr = b.PyThread_delete_key_Ptr;
    b.PyThread_delete_key_Ptr = nullptr;
    a.PyThread_delete_key_value_Ptr = b.PyThread_delete_key_value_Ptr;
    b.PyThread_delete_key_value_Ptr = nullptr;
    a.PyThread_exit_thread_Ptr = b.PyThread_exit_thread_Ptr;
    b.PyThread_exit_thread_Ptr = nullptr;
    a.PyThread_free_lock_Ptr = b.PyThread_free_lock_Ptr;
    b.PyThread_free_lock_Ptr = nullptr;
    a.PyThread_get_key_value_Ptr = b.PyThread_get_key_value_Ptr;
    b.PyThread_get_key_value_Ptr = nullptr;
    a.PyThread_get_stacksize_Ptr = b.PyThread_get_stacksize_Ptr;
    b.PyThread_get_stacksize_Ptr = nullptr;
    a.PyThread_get_thread_ident_Ptr = b.PyThread_get_thread_ident_Ptr;
    b.PyThread_get_thread_ident_Ptr = nullptr;
    a.PyThread_get_thread_native_id_Ptr = b.PyThread_get_thread_native_id_Ptr;
    b.PyThread_get_thread_native_id_Ptr = nullptr;
    a.PyThread_init_thread_Ptr = b.PyThread_init_thread_Ptr;
    b.PyThread_init_thread_Ptr = nullptr;
    a.PyThread_release_lock_Ptr = b.PyThread_release_lock_Ptr;
    b.PyThread_release_lock_Ptr = nullptr;
    a.PyThread_set_key_value_Ptr = b.PyThread_set_key_value_Ptr;
    b.PyThread_set_key_value_Ptr = nullptr;
    a.PyThread_set_stacksize_Ptr = b.PyThread_set_stacksize_Ptr;
    b.PyThread_set_stacksize_Ptr = nullptr;
    a.PyThread_start_new_thread_Ptr = b.PyThread_start_new_thread_Ptr;
    b.PyThread_start_new_thread_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x03070000)
    a.PyThread_tss_alloc_Ptr = b.PyThread_tss_alloc_Ptr;
    b.PyThread_tss_alloc_Ptr = nullptr;
    a.PyThread_tss_create_Ptr = b.PyThread_tss_create_Ptr;
    b.PyThread_tss_create_Ptr = nullptr;
    a.PyThread_tss_delete_Ptr = b.PyThread_tss_delete_Ptr;
    b.PyThread_tss_delete_Ptr = nullptr;
    a.PyThread_tss_free_Ptr = b.PyThread_tss_free_Ptr;
    b.PyThread_tss_free_Ptr = nullptr;
    a.PyThread_tss_get_Ptr = b.PyThread_tss_get_Ptr;
    b.PyThread_tss_get_Ptr = nullptr;
    a.PyThread_tss_is_created_Ptr = b.PyThread_tss_is_created_Ptr;
    b.PyThread_tss_is_created_Ptr = nullptr;
    a.PyThread_tss_set_Ptr = b.PyThread_tss_set_Ptr;
    b.PyThread_tss_set_Ptr = nullptr;
    #endif
    #if HAS_PYTHON_VERSION(0x03090000)
    a.PyTraceBack_Here_Ptr = b.PyTraceBack_Here_Ptr;
    b.PyTraceBack_Here_Ptr = nullptr;
    #endif
    a.PyTraceBack_Print_Ptr = b.PyTraceBack_Print_Ptr;
    b.PyTraceBack_Print_Ptr = nullptr;
    a.PyTuple_GetItem_Ptr = b.PyTuple_GetItem_Ptr;
    b.PyTuple_GetItem_Ptr = nullptr;
    a.PyTuple_GetSlice_Ptr = b.PyTuple_GetSlice_Ptr;
    b.PyTuple_GetSlice_Ptr = nullptr;
    a.PyTuple_New_Ptr = b.PyTuple_New_Ptr;
    b.PyTuple_New_Ptr = nullptr;
    a.PyTuple_Pack_Ptr = b.PyTuple_Pack_Ptr;
    b.PyTuple_Pack_Ptr = nullptr;
    a.PyTuple_SetItem_Ptr = b.PyTuple_SetItem_Ptr;
    b.PyTuple_SetItem_Ptr = nullptr;
    a.PyTuple_Size_Ptr = b.PyTuple_Size_Ptr;
    b.PyTuple_Size_Ptr = nullptr;
    a.PyType_ClearCache_Ptr = b.PyType_ClearCache_Ptr;
    b.PyType_ClearCache_Ptr = nullptr;
    #if !defined(Py_LIMITED_API)
    a.PyType_Name_Ptr = b.PyType_Name_Ptr;
    b.PyType_Name_Ptr = nullptr;
    #endif
    a.PyType_FromModuleAndSpec_Ptr = b.PyType_FromModuleAndSpec_Ptr;
    b.PyType_FromModuleAndSpec_Ptr = nullptr;
    a.PyType_FromSpec_Ptr = b.PyType_FromSpec_Ptr;
    b.PyType_FromSpec_Ptr = nullptr;
    a.PyType_FromSpecWithBases_Ptr = b.PyType_FromSpecWithBases_Ptr;
    b.PyType_FromSpecWithBases_Ptr = nullptr;
    a.PyType_GenericAlloc_Ptr = b.PyType_GenericAlloc_Ptr;
    b.PyType_GenericAlloc_Ptr = nullptr;
    a.PyType_GenericNew_Ptr = b.PyType_GenericNew_Ptr;
    b.PyType_GenericNew_Ptr = nullptr;
    a.PyType_GetFlags_Ptr = b.PyType_GetFlags_Ptr;
    b.PyType_GetFlags_Ptr = nullptr;
    a.PyType_GetModule_Ptr = b.PyType_GetModule_Ptr;
    b.PyType_GetModule_Ptr = nullptr;
    a.PyType_GetModuleState_Ptr = b.PyType_GetModuleState_Ptr;
    b.PyType_GetModuleState_Ptr = nullptr;
    a.PyType_GetName_Ptr = b.PyType_GetName_Ptr;
    b.PyType_GetName_Ptr = nullptr;
    a.PyType_GetQualName_Ptr = b.PyType_GetQualName_Ptr;
    b.PyType_GetQualName_Ptr = nullptr;
    a.PyType_GetSlot_Ptr = b.PyType_GetSlot_Ptr;
    b.PyType_GetSlot_Ptr = nullptr;
    a.PyType_IsSubtype_Ptr = b.PyType_IsSubtype_Ptr;
    b.PyType_IsSubtype_Ptr = nullptr;
    a.PyType_Modified_Ptr = b.PyType_Modified_Ptr;
    b.PyType_Modified_Ptr = nullptr;
    a.PyType_Ready_Ptr = b.PyType_Ready_Ptr;
    b.PyType_Ready_Ptr = nullptr;
    a.PyUnicodeDecodeError_Create_Ptr = b.PyUnicodeDecodeError_Create_Ptr;
    b.PyUnicodeDecodeError_Create_Ptr = nullptr;
    a.PyUnicodeDecodeError_GetEncoding_Ptr = b.PyUnicodeDecodeError_GetEncoding_Ptr;
    b.PyUnicodeDecodeError_GetEncoding_Ptr = nullptr;
    a.PyUnicodeDecodeError_GetEnd_Ptr = b.PyUnicodeDecodeError_GetEnd_Ptr;
    b.PyUnicodeDecodeError_GetEnd_Ptr = nullptr;
    a.PyUnicodeDecodeError_GetObject_Ptr = b.PyUnicodeDecodeError_GetObject_Ptr;
    b.PyUnicodeDecodeError_GetObject_Ptr = nullptr;
    a.PyUnicodeDecodeError_GetReason_Ptr = b.PyUnicodeDecodeError_GetReason_Ptr;
    b.PyUnicodeDecodeError_GetReason_Ptr = nullptr;
    a.PyUnicodeDecodeError_GetStart_Ptr = b.PyUnicodeDecodeError_GetStart_Ptr;
    b.PyUnicodeDecodeError_GetStart_Ptr = nullptr;
    a.PyUnicodeDecodeError_SetEnd_Ptr = b.PyUnicodeDecodeError_SetEnd_Ptr;
    b.PyUnicodeDecodeError_SetEnd_Ptr = nullptr;
    a.PyUnicodeDecodeError_SetReason_Ptr = b.PyUnicodeDecodeError_SetReason_Ptr;
    b.PyUnicodeDecodeError_SetReason_Ptr = nullptr;
    a.PyUnicodeDecodeError_SetStart_Ptr = b.PyUnicodeDecodeError_SetStart_Ptr;
    b.PyUnicodeDecodeError_SetStart_Ptr = nullptr;
    a.PyUnicodeEncodeError_GetEncoding_Ptr = b.PyUnicodeEncodeError_GetEncoding_Ptr;
    b.PyUnicodeEncodeError_GetEncoding_Ptr = nullptr;
    a.PyUnicodeEncodeError_GetEnd_Ptr = b.PyUnicodeEncodeError_GetEnd_Ptr;
    b.PyUnicodeEncodeError_GetEnd_Ptr = nullptr;
    a.PyUnicodeEncodeError_GetObject_Ptr = b.PyUnicodeEncodeError_GetObject_Ptr;
    b.PyUnicodeEncodeError_GetObject_Ptr = nullptr;
    a.PyUnicodeEncodeError_GetReason_Ptr = b.PyUnicodeEncodeError_GetReason_Ptr;
    b.PyUnicodeEncodeError_GetReason_Ptr = nullptr;
    a.PyUnicodeEncodeError_GetStart_Ptr = b.PyUnicodeEncodeError_GetStart_Ptr;
    b.PyUnicodeEncodeError_GetStart_Ptr = nullptr;
    a.PyUnicodeEncodeError_SetEnd_Ptr = b.PyUnicodeEncodeError_SetEnd_Ptr;
    b.PyUnicodeEncodeError_SetEnd_Ptr = nullptr;
    a.PyUnicodeEncodeError_SetReason_Ptr = b.PyUnicodeEncodeError_SetReason_Ptr;
    b.PyUnicodeEncodeError_SetReason_Ptr = nullptr;
    a.PyUnicodeEncodeError_SetStart_Ptr = b.PyUnicodeEncodeError_SetStart_Ptr;
    b.PyUnicodeEncodeError_SetStart_Ptr = nullptr;
    a.PyUnicodeTranslateError_GetEnd_Ptr = b.PyUnicodeTranslateError_GetEnd_Ptr;
    b.PyUnicodeTranslateError_GetEnd_Ptr = nullptr;
    a.PyUnicodeTranslateError_GetObject_Ptr = b.PyUnicodeTranslateError_GetObject_Ptr;
    b.PyUnicodeTranslateError_GetObject_Ptr = nullptr;
    a.PyUnicodeTranslateError_GetReason_Ptr = b.PyUnicodeTranslateError_GetReason_Ptr;
    b.PyUnicodeTranslateError_GetReason_Ptr = nullptr;
    a.PyUnicodeTranslateError_GetStart_Ptr = b.PyUnicodeTranslateError_GetStart_Ptr;
    b.PyUnicodeTranslateError_GetStart_Ptr = nullptr;
    a.PyUnicodeTranslateError_SetEnd_Ptr = b.PyUnicodeTranslateError_SetEnd_Ptr;
    b.PyUnicodeTranslateError_SetEnd_Ptr = nullptr;
    a.PyUnicodeTranslateError_SetReason_Ptr = b.PyUnicodeTranslateError_SetReason_Ptr;
    b.PyUnicodeTranslateError_SetReason_Ptr = nullptr;
    a.PyUnicodeTranslateError_SetStart_Ptr = b.PyUnicodeTranslateError_SetStart_Ptr;
    b.PyUnicodeTranslateError_SetStart_Ptr = nullptr;
    a.PyUnicode_Append_Ptr = b.PyUnicode_Append_Ptr;
    b.PyUnicode_Append_Ptr = nullptr;
    a.PyUnicode_AppendAndDel_Ptr = b.PyUnicode_AppendAndDel_Ptr;
    b.PyUnicode_AppendAndDel_Ptr = nullptr;
    a.PyUnicode_AsASCIIString_Ptr = b.PyUnicode_AsASCIIString_Ptr;
    b.PyUnicode_AsASCIIString_Ptr = nullptr;
    a.PyUnicode_AsCharmapString_Ptr = b.PyUnicode_AsCharmapString_Ptr;
    b.PyUnicode_AsCharmapString_Ptr = nullptr;
    a.PyUnicode_AsDecodedObject_Ptr = b.PyUnicode_AsDecodedObject_Ptr;
    b.PyUnicode_AsDecodedObject_Ptr = nullptr;
    a.PyUnicode_AsDecodedUnicode_Ptr = b.PyUnicode_AsDecodedUnicode_Ptr;
    b.PyUnicode_AsDecodedUnicode_Ptr = nullptr;
    a.PyUnicode_AsEncodedObject_Ptr = b.PyUnicode_AsEncodedObject_Ptr;
    b.PyUnicode_AsEncodedObject_Ptr = nullptr;
    a.PyUnicode_AsEncodedString_Ptr = b.PyUnicode_AsEncodedString_Ptr;
    b.PyUnicode_AsEncodedString_Ptr = nullptr;
    a.PyUnicode_AsEncodedUnicode_Ptr = b.PyUnicode_AsEncodedUnicode_Ptr;
    b.PyUnicode_AsEncodedUnicode_Ptr = nullptr;
    a.PyUnicode_AsLatin1String_Ptr = b.PyUnicode_AsLatin1String_Ptr;
    b.PyUnicode_AsLatin1String_Ptr = nullptr;
    a.PyUnicode_AsRawUnicodeEscapeString_Ptr = b.PyUnicode_AsRawUnicodeEscapeString_Ptr;
    b.PyUnicode_AsRawUnicodeEscapeString_Ptr = nullptr;
    a.PyUnicode_AsUCS4_Ptr = b.PyUnicode_AsUCS4_Ptr;
    b.PyUnicode_AsUCS4_Ptr = nullptr;
    a.PyUnicode_AsUCS4Copy_Ptr = b.PyUnicode_AsUCS4Copy_Ptr;
    b.PyUnicode_AsUCS4Copy_Ptr = nullptr;
    a.PyUnicode_AsUTF16String_Ptr = b.PyUnicode_AsUTF16String_Ptr;
    b.PyUnicode_AsUTF16String_Ptr = nullptr;
    a.PyUnicode_AsUTF32String_Ptr = b.PyUnicode_AsUTF32String_Ptr;
    b.PyUnicode_AsUTF32String_Ptr = nullptr;
    a.PyUnicode_AsUTF8AndSize_Ptr = b.PyUnicode_AsUTF8AndSize_Ptr;
    b.PyUnicode_AsUTF8AndSize_Ptr = nullptr;
    a.PyUnicode_AsUTF8String_Ptr = b.PyUnicode_AsUTF8String_Ptr;
    b.PyUnicode_AsUTF8String_Ptr = nullptr;
    a.PyUnicode_AsUnicodeEscapeString_Ptr = b.PyUnicode_AsUnicodeEscapeString_Ptr;
    b.PyUnicode_AsUnicodeEscapeString_Ptr = nullptr;
    a.PyUnicode_AsWideChar_Ptr = b.PyUnicode_AsWideChar_Ptr;
    b.PyUnicode_AsWideChar_Ptr = nullptr;
    a.PyUnicode_AsWideCharString_Ptr = b.PyUnicode_AsWideCharString_Ptr;
    b.PyUnicode_AsWideCharString_Ptr = nullptr;
    a.PyUnicode_BuildEncodingMap_Ptr = b.PyUnicode_BuildEncodingMap_Ptr;
    b.PyUnicode_BuildEncodingMap_Ptr = nullptr;
    a.PyUnicode_Compare_Ptr = b.PyUnicode_Compare_Ptr;
    b.PyUnicode_Compare_Ptr = nullptr;
    a.PyUnicode_CompareWithASCIIString_Ptr = b.PyUnicode_CompareWithASCIIString_Ptr;
    b.PyUnicode_CompareWithASCIIString_Ptr = nullptr;
    a.PyUnicode_Concat_Ptr = b.PyUnicode_Concat_Ptr;
    b.PyUnicode_Concat_Ptr = nullptr;
    a.PyUnicode_Contains_Ptr = b.PyUnicode_Contains_Ptr;
    b.PyUnicode_Contains_Ptr = nullptr;
    a.PyUnicode_Count_Ptr = b.PyUnicode_Count_Ptr;
    b.PyUnicode_Count_Ptr = nullptr;
    a.PyUnicode_Decode_Ptr = b.PyUnicode_Decode_Ptr;
    b.PyUnicode_Decode_Ptr = nullptr;
    a.PyUnicode_DecodeASCII_Ptr = b.PyUnicode_DecodeASCII_Ptr;
    b.PyUnicode_DecodeASCII_Ptr = nullptr;
    a.PyUnicode_DecodeCharmap_Ptr = b.PyUnicode_DecodeCharmap_Ptr;
    b.PyUnicode_DecodeCharmap_Ptr = nullptr;
    a.PyUnicode_DecodeFSDefault_Ptr = b.PyUnicode_DecodeFSDefault_Ptr;
    b.PyUnicode_DecodeFSDefault_Ptr = nullptr;
    a.PyUnicode_DecodeFSDefaultAndSize_Ptr = b.PyUnicode_DecodeFSDefaultAndSize_Ptr;
    b.PyUnicode_DecodeFSDefaultAndSize_Ptr = nullptr;
    a.PyUnicode_DecodeLatin1_Ptr = b.PyUnicode_DecodeLatin1_Ptr;
    b.PyUnicode_DecodeLatin1_Ptr = nullptr;
    a.PyUnicode_DecodeLocale_Ptr = b.PyUnicode_DecodeLocale_Ptr;
    b.PyUnicode_DecodeLocale_Ptr = nullptr;
    a.PyUnicode_DecodeLocaleAndSize_Ptr = b.PyUnicode_DecodeLocaleAndSize_Ptr;
    b.PyUnicode_DecodeLocaleAndSize_Ptr = nullptr;
    a.PyUnicode_DecodeRawUnicodeEscape_Ptr = b.PyUnicode_DecodeRawUnicodeEscape_Ptr;
    b.PyUnicode_DecodeRawUnicodeEscape_Ptr = nullptr;
    a.PyUnicode_DecodeUTF16_Ptr = b.PyUnicode_DecodeUTF16_Ptr;
    b.PyUnicode_DecodeUTF16_Ptr = nullptr;
    a.PyUnicode_DecodeUTF16Stateful_Ptr = b.PyUnicode_DecodeUTF16Stateful_Ptr;
    b.PyUnicode_DecodeUTF16Stateful_Ptr = nullptr;
    a.PyUnicode_DecodeUTF32_Ptr = b.PyUnicode_DecodeUTF32_Ptr;
    b.PyUnicode_DecodeUTF32_Ptr = nullptr;
    a.PyUnicode_DecodeUTF32Stateful_Ptr = b.PyUnicode_DecodeUTF32Stateful_Ptr;
    b.PyUnicode_DecodeUTF32Stateful_Ptr = nullptr;
    a.PyUnicode_DecodeUTF7_Ptr = b.PyUnicode_DecodeUTF7_Ptr;
    b.PyUnicode_DecodeUTF7_Ptr = nullptr;
    a.PyUnicode_DecodeUTF7Stateful_Ptr = b.PyUnicode_DecodeUTF7Stateful_Ptr;
    b.PyUnicode_DecodeUTF7Stateful_Ptr = nullptr;
    a.PyUnicode_DecodeUTF8_Ptr = b.PyUnicode_DecodeUTF8_Ptr;
    b.PyUnicode_DecodeUTF8_Ptr = nullptr;
    a.PyUnicode_DecodeUTF8Stateful_Ptr = b.PyUnicode_DecodeUTF8Stateful_Ptr;
    b.PyUnicode_DecodeUTF8Stateful_Ptr = nullptr;
    a.PyUnicode_DecodeUnicodeEscape_Ptr = b.PyUnicode_DecodeUnicodeEscape_Ptr;
    b.PyUnicode_DecodeUnicodeEscape_Ptr = nullptr;
    a.PyUnicode_EncodeFSDefault_Ptr = b.PyUnicode_EncodeFSDefault_Ptr;
    b.PyUnicode_EncodeFSDefault_Ptr = nullptr;
    a.PyUnicode_EncodeLocale_Ptr = b.PyUnicode_EncodeLocale_Ptr;
    b.PyUnicode_EncodeLocale_Ptr = nullptr;
    a.PyUnicode_FSConverter_Ptr = b.PyUnicode_FSConverter_Ptr;
    b.PyUnicode_FSConverter_Ptr = nullptr;
    a.PyUnicode_FSDecoder_Ptr = b.PyUnicode_FSDecoder_Ptr;
    b.PyUnicode_FSDecoder_Ptr = nullptr;
    a.PyUnicode_Find_Ptr = b.PyUnicode_Find_Ptr;
    b.PyUnicode_Find_Ptr = nullptr;
    a.PyUnicode_FindChar_Ptr = b.PyUnicode_FindChar_Ptr;
    b.PyUnicode_FindChar_Ptr = nullptr;
    a.PyUnicode_Format_Ptr = b.PyUnicode_Format_Ptr;
    b.PyUnicode_Format_Ptr = nullptr;
    a.PyUnicode_FromEncodedObject_Ptr = b.PyUnicode_FromEncodedObject_Ptr;
    b.PyUnicode_FromEncodedObject_Ptr = nullptr;
    a.PyUnicode_FromFormat_Ptr = b.PyUnicode_FromFormat_Ptr;
    b.PyUnicode_FromFormat_Ptr = nullptr;
    a.PyUnicode_FromFormatV_Ptr = b.PyUnicode_FromFormatV_Ptr;
    b.PyUnicode_FromFormatV_Ptr = nullptr;
    a.PyUnicode_FromObject_Ptr = b.PyUnicode_FromObject_Ptr;
    b.PyUnicode_FromObject_Ptr = nullptr;
    a.PyUnicode_FromOrdinal_Ptr = b.PyUnicode_FromOrdinal_Ptr;
    b.PyUnicode_FromOrdinal_Ptr = nullptr;
    a.PyUnicode_FromString_Ptr = b.PyUnicode_FromString_Ptr;
    b.PyUnicode_FromString_Ptr = nullptr;
    a.PyUnicode_FromStringAndSize_Ptr = b.PyUnicode_FromStringAndSize_Ptr;
    b.PyUnicode_FromStringAndSize_Ptr = nullptr;
    a.PyUnicode_FromWideChar_Ptr = b.PyUnicode_FromWideChar_Ptr;
    b.PyUnicode_FromWideChar_Ptr = nullptr;
    a.PyUnicode_GetDefaultEncoding_Ptr = b.PyUnicode_GetDefaultEncoding_Ptr;
    b.PyUnicode_GetDefaultEncoding_Ptr = nullptr;
    a.PyUnicode_GetLength_Ptr = b.PyUnicode_GetLength_Ptr;
    b.PyUnicode_GetLength_Ptr = nullptr;
    a.PyUnicode_GetSize_Ptr = b.PyUnicode_GetSize_Ptr;
    b.PyUnicode_GetSize_Ptr = nullptr;
    a.PyUnicode_InternFromString_Ptr = b.PyUnicode_InternFromString_Ptr;
    b.PyUnicode_InternFromString_Ptr = nullptr;
    a.PyUnicode_InternImmortal_Ptr = b.PyUnicode_InternImmortal_Ptr;
    b.PyUnicode_InternImmortal_Ptr = nullptr;
    a.PyUnicode_InternInPlace_Ptr = b.PyUnicode_InternInPlace_Ptr;
    b.PyUnicode_InternInPlace_Ptr = nullptr;
    a.PyUnicode_IsIdentifier_Ptr = b.PyUnicode_IsIdentifier_Ptr;
    b.PyUnicode_IsIdentifier_Ptr = nullptr;
    a.PyUnicode_Join_Ptr = b.PyUnicode_Join_Ptr;
    b.PyUnicode_Join_Ptr = nullptr;
    a.PyUnicode_Partition_Ptr = b.PyUnicode_Partition_Ptr;
    b.PyUnicode_Partition_Ptr = nullptr;
    a.PyUnicode_RPartition_Ptr = b.PyUnicode_RPartition_Ptr;
    b.PyUnicode_RPartition_Ptr = nullptr;
    a.PyUnicode_RSplit_Ptr = b.PyUnicode_RSplit_Ptr;
    b.PyUnicode_RSplit_Ptr = nullptr;
    a.PyUnicode_ReadChar_Ptr = b.PyUnicode_ReadChar_Ptr;
    b.PyUnicode_ReadChar_Ptr = nullptr;
    a.PyUnicode_Replace_Ptr = b.PyUnicode_Replace_Ptr;
    b.PyUnicode_Replace_Ptr = nullptr;
    a.PyUnicode_Resize_Ptr = b.PyUnicode_Resize_Ptr;
    b.PyUnicode_Resize_Ptr = nullptr;
    a.PyUnicode_RichCompare_Ptr = b.PyUnicode_RichCompare_Ptr;
    b.PyUnicode_RichCompare_Ptr = nullptr;
    a.PyUnicode_Split_Ptr = b.PyUnicode_Split_Ptr;
    b.PyUnicode_Split_Ptr = nullptr;
    a.PyUnicode_Splitlines_Ptr = b.PyUnicode_Splitlines_Ptr;
    b.PyUnicode_Splitlines_Ptr = nullptr;
    a.PyUnicode_Substring_Ptr = b.PyUnicode_Substring_Ptr;
    b.PyUnicode_Substring_Ptr = nullptr;
    a.PyUnicode_Tailmatch_Ptr = b.PyUnicode_Tailmatch_Ptr;
    b.PyUnicode_Tailmatch_Ptr = nullptr;
    a.PyUnicode_Translate_Ptr = b.PyUnicode_Translate_Ptr;
    b.PyUnicode_Translate_Ptr = nullptr;
    a.PyUnicode_WriteChar_Ptr = b.PyUnicode_WriteChar_Ptr;
    b.PyUnicode_WriteChar_Ptr = nullptr;
    a.PyWeakref_GetObject_Ptr = b.PyWeakref_GetObject_Ptr;
    b.PyWeakref_GetObject_Ptr = nullptr;
    a.PyWeakref_NewProxy_Ptr = b.PyWeakref_NewProxy_Ptr;
    b.PyWeakref_NewProxy_Ptr = nullptr;
    a.PyWeakref_NewRef_Ptr = b.PyWeakref_NewRef_Ptr;
    b.PyWeakref_NewRef_Ptr = nullptr;
    a.PyWrapper_New_Ptr = b.PyWrapper_New_Ptr;
    b.PyWrapper_New_Ptr = nullptr;
    a.Py_AddPendingCall_Ptr = b.Py_AddPendingCall_Ptr;
    b.Py_AddPendingCall_Ptr = nullptr;
    a.Py_AtExit_Ptr = b.Py_AtExit_Ptr;
    b.Py_AtExit_Ptr = nullptr;
    a.Py_BuildValue_Ptr = b.Py_BuildValue_Ptr;
    b.Py_BuildValue_Ptr = nullptr;
    a.Py_BytesMain_Ptr = b.Py_BytesMain_Ptr;
    b.Py_BytesMain_Ptr = nullptr;
    a.Py_CompileString_Ptr = b.Py_CompileString_Ptr;
    b.Py_CompileString_Ptr = nullptr;
    a.Py_DecRef_Ptr = b.Py_DecRef_Ptr;
    b.Py_DecRef_Ptr = nullptr;
    a.Py_DecodeLocale_Ptr = b.Py_DecodeLocale_Ptr;
    b.Py_DecodeLocale_Ptr = nullptr;
    a.Py_EncodeLocale_Ptr = b.Py_EncodeLocale_Ptr;
    b.Py_EncodeLocale_Ptr = nullptr;
    a.Py_EndInterpreter_Ptr = b.Py_EndInterpreter_Ptr;
    b.Py_EndInterpreter_Ptr = nullptr;
    a.Py_EnterRecursiveCall_Ptr = b.Py_EnterRecursiveCall_Ptr;
    b.Py_EnterRecursiveCall_Ptr = nullptr;
    a.Py_Exit_Ptr = b.Py_Exit_Ptr;
    b.Py_Exit_Ptr = nullptr;
    a.Py_FatalError_Ptr = b.Py_FatalError_Ptr;
    b.Py_FatalError_Ptr = nullptr;
    a.Py_Finalize_Ptr = b.Py_Finalize_Ptr;
    b.Py_Finalize_Ptr = nullptr;
    a.Py_FinalizeEx_Ptr = b.Py_FinalizeEx_Ptr;
    b.Py_FinalizeEx_Ptr = nullptr;
    a.Py_GenericAlias_Ptr = b.Py_GenericAlias_Ptr;
    b.Py_GenericAlias_Ptr = nullptr;
    a.Py_GetBuildInfo_Ptr = b.Py_GetBuildInfo_Ptr;
    b.Py_GetBuildInfo_Ptr = nullptr;
    a.Py_GetCompiler_Ptr = b.Py_GetCompiler_Ptr;
    b.Py_GetCompiler_Ptr = nullptr;
    a.Py_GetCopyright_Ptr = b.Py_GetCopyright_Ptr;
    b.Py_GetCopyright_Ptr = nullptr;
    a.Py_GetExecPrefix_Ptr = b.Py_GetExecPrefix_Ptr;
    b.Py_GetExecPrefix_Ptr = nullptr;
    a.Py_GetPath_Ptr = b.Py_GetPath_Ptr;
    b.Py_GetPath_Ptr = nullptr;
    a.Py_GetPlatform_Ptr = b.Py_GetPlatform_Ptr;
    b.Py_GetPlatform_Ptr = nullptr;
    a.Py_GetPrefix_Ptr = b.Py_GetPrefix_Ptr;
    b.Py_GetPrefix_Ptr = nullptr;
    a.Py_GetProgramFullPath_Ptr = b.Py_GetProgramFullPath_Ptr;
    b.Py_GetProgramFullPath_Ptr = nullptr;
    a.Py_GetProgramName_Ptr = b.Py_GetProgramName_Ptr;
    b.Py_GetProgramName_Ptr = nullptr;
    a.Py_GetPythonHome_Ptr = b.Py_GetPythonHome_Ptr;
    b.Py_GetPythonHome_Ptr = nullptr;
    a.Py_GetRecursionLimit_Ptr = b.Py_GetRecursionLimit_Ptr;
    b.Py_GetRecursionLimit_Ptr = nullptr;
    a.Py_GetVersion_Ptr = b.Py_GetVersion_Ptr;
    b.Py_GetVersion_Ptr = nullptr;
    a.Py_IncRef_Ptr = b.Py_IncRef_Ptr;
    b.Py_IncRef_Ptr = nullptr;
    a.Py_Initialize_Ptr = b.Py_Initialize_Ptr;
    b.Py_Initialize_Ptr = nullptr;
    a.Py_InitializeEx_Ptr = b.Py_InitializeEx_Ptr;
    b.Py_InitializeEx_Ptr = nullptr;
    a.Py_Is_Ptr = b.Py_Is_Ptr;
    b.Py_Is_Ptr = nullptr;
    a.Py_IsInitialized_Ptr = b.Py_IsInitialized_Ptr;
    b.Py_IsInitialized_Ptr = nullptr;
    a.Py_LeaveRecursiveCall_Ptr = b.Py_LeaveRecursiveCall_Ptr;
    b.Py_LeaveRecursiveCall_Ptr = nullptr;
    a.Py_Main_Ptr = b.Py_Main_Ptr;
    b.Py_Main_Ptr = nullptr;
    a.Py_MakePendingCalls_Ptr = b.Py_MakePendingCalls_Ptr;
    b.Py_MakePendingCalls_Ptr = nullptr;
    a.Py_NewInterpreter_Ptr = b.Py_NewInterpreter_Ptr;
    b.Py_NewInterpreter_Ptr = nullptr;
    a.Py_NewRef_Ptr = b.Py_NewRef_Ptr;
    b.Py_NewRef_Ptr = nullptr;
    a.Py_ReprEnter_Ptr = b.Py_ReprEnter_Ptr;
    b.Py_ReprEnter_Ptr = nullptr;
    a.Py_ReprLeave_Ptr = b.Py_ReprLeave_Ptr;
    b.Py_ReprLeave_Ptr = nullptr;
    a.Py_SetPath_Ptr = b.Py_SetPath_Ptr;
    b.Py_SetPath_Ptr = nullptr;
    a.Py_SetProgramName_Ptr = b.Py_SetProgramName_Ptr;
    b.Py_SetProgramName_Ptr = nullptr;
    a.Py_SetPythonHome_Ptr = b.Py_SetPythonHome_Ptr;
    b.Py_SetPythonHome_Ptr = nullptr;
    a.Py_SetRecursionLimit_Ptr = b.Py_SetRecursionLimit_Ptr;
    b.Py_SetRecursionLimit_Ptr = nullptr;
    a.Py_VaBuildValue_Ptr = b.Py_VaBuildValue_Ptr;
    b.Py_VaBuildValue_Ptr = nullptr;
    #if HAS_PYTHON_VERSION(0x030A0000)
    a.Py_XNewRef_Ptr = b.Py_XNewRef_Ptr;
    b.Py_XNewRef_Ptr = nullptr;
    #endif
}

Python::Python() : module(nullptr)
{
    #if defined(_WIN32) || defined(_WIN64)
    //extern HMODULE GetModuleHandle(const char* module_name) noexcept;
    #else
    extern void* GetModuleHandle(const char* module_name) noexcept;
    #endif

    #if defined(_WIN32) || defined(_WIN64)
    std::vector<std::string> module_names = GetLoadedModuleNames("python3");
    if (module_names.empty())
    {
        return;
    }

    for (const auto &module_name : module_names)
    {
        if (module = GetModuleHandle(module_name.c_str()); module != nullptr)
        {
            break;
        }
    }

    auto address_of = [](HMODULE module, const char* fn_name) {
        #if defined(_WIN32) || defined(_WIN64)
        return GetProcAddress(module, fn_name);
        #else
        return dlsym(module, fn_name);
        #endif
    };
    #else
    module = RTLD_DEFAULT;
    auto address_of = [](void* module, const char* fn_name) {
        #if defined(_WIN32) || defined(_WIN64)
        return GetProcAddress(module, fn_name);
        #else
        return dlsym(module, fn_name);
        #endif
    };
    #endif

    /// WARNING: PYTHON PRIVATE API!
    Py_NoneStruct_Ptr = reinterpret_cast<decltype(Py_NoneStruct_Ptr)>(address_of(module, "_Py_NoneStruct"));
    Py_TrueStruct_Ptr = reinterpret_cast<decltype(Py_TrueStruct_Ptr)>(address_of(module, "_Py_TrueStruct"));
    Py_FalseStruct_Ptr = reinterpret_cast<decltype(Py_FalseStruct_Ptr)>(address_of(module, "_Py_FalseStruct"));
    Py_ListType_Ptr = reinterpret_cast<decltype(Py_ListType_Ptr)>(address_of(module, "PyList_Type"));

    PyAIter_Check_Ptr = reinterpret_cast<decltype(PyAIter_Check_Ptr)>(address_of(module, "PyAIter_Check"));
    PyArg_Parse_Ptr = reinterpret_cast<decltype(PyArg_Parse_Ptr)>(address_of(module, "PyArg_Parse"));
    PyArg_ParseTuple_Ptr = reinterpret_cast<decltype(PyArg_ParseTuple_Ptr)>(address_of(module, "PyArg_ParseTuple"));
    PyArg_ParseTupleAndKeywords_Ptr = reinterpret_cast<decltype(PyArg_ParseTupleAndKeywords_Ptr)>(address_of(module, "PyArg_ParseTupleAndKeywords"));
    PyArg_UnpackTuple_Ptr = reinterpret_cast<decltype(PyArg_UnpackTuple_Ptr)>(address_of(module, "PyArg_UnpackTuple"));
    PyArg_VaParse_Ptr = reinterpret_cast<decltype(PyArg_VaParse_Ptr)>(address_of(module, "PyArg_VaParse"));
    PyArg_VaParseTupleAndKeywords_Ptr = reinterpret_cast<decltype(PyArg_VaParseTupleAndKeywords_Ptr)>(address_of(module, "PyArg_VaParseTupleAndKeywords"));
    PyArg_ValidateKeywordArguments_Ptr = reinterpret_cast<decltype(PyArg_ValidateKeywordArguments_Ptr)>(address_of(module, "PyArg_ValidateKeywordArguments"));
    PyBool_FromLong_Ptr = reinterpret_cast<decltype(PyBool_FromLong_Ptr)>(address_of(module, "PyBool_FromLong"));
    #if HAS_PYTHON_VERSION(0x030B1000)
    PyBuffer_FillContiguousStrides_Ptr = reinterpret_cast<decltype(PyBuffer_FillContiguousStrides_Ptr)>(address_of(module, "PyBuffer_FillContiguousStrides"));
    PyBuffer_FillInfo_Ptr = reinterpret_cast<decltype(PyBuffer_FillInfo_Ptr)>(address_of(module, "PyBuffer_FillInfo"));
    PyBuffer_FromContiguous_Ptr = reinterpret_cast<decltype(PyBuffer_FromContiguous_Ptr)>(address_of(module, "PyBuffer_FromContiguous"));
    PyBuffer_GetPointer_Ptr = reinterpret_cast<decltype(PyBuffer_GetPointer_Ptr)>(address_of(module, "PyBuffer_GetPointer"));
    PyBuffer_IsContiguous_Ptr = reinterpret_cast<decltype(PyBuffer_IsContiguous_Ptr)>(address_of(module, "PyBuffer_IsContiguous"));
    PyBuffer_Release_Ptr = reinterpret_cast<decltype(PyBuffer_Release_Ptr)>(address_of(module, "PyBuffer_Release"));
    PyBuffer_SizeFromFormat_Ptr = reinterpret_cast<decltype(PyBuffer_SizeFromFormat_Ptr)>(address_of(module, "PyBuffer_SizeFromFormat"));
    PyBuffer_ToContiguous_Ptr = reinterpret_cast<decltype(PyBuffer_ToContiguous_Ptr)>(address_of(module, "PyBuffer_ToContiguous"));
    #endif
    PyByteArray_AsString_Ptr = reinterpret_cast<decltype(PyByteArray_AsString_Ptr)>(address_of(module, "PyByteArray_AsString"));
    PyByteArray_Concat_Ptr = reinterpret_cast<decltype(PyByteArray_Concat_Ptr)>(address_of(module, "PyByteArray_Concat"));
    PyByteArray_FromObject_Ptr = reinterpret_cast<decltype(PyByteArray_FromObject_Ptr)>(address_of(module, "PyByteArray_FromObject"));
    PyByteArray_FromStringAndSize_Ptr = reinterpret_cast<decltype(PyByteArray_FromStringAndSize_Ptr)>(address_of(module, "PyByteArray_FromStringAndSize"));
    PyByteArray_Resize_Ptr = reinterpret_cast<decltype(PyByteArray_Resize_Ptr)>(address_of(module, "PyByteArray_Resize"));
    PyByteArray_Size_Ptr = reinterpret_cast<decltype(PyByteArray_Size_Ptr)>(address_of(module, "PyByteArray_Size"));
    PyBytes_AsString_Ptr = reinterpret_cast<decltype(PyBytes_AsString_Ptr)>(address_of(module, "PyBytes_AsString"));
    PyBytes_AsStringAndSize_Ptr = reinterpret_cast<decltype(PyBytes_AsStringAndSize_Ptr)>(address_of(module, "PyBytes_AsStringAndSize"));
    PyBytes_Concat_Ptr = reinterpret_cast<decltype(PyBytes_Concat_Ptr)>(address_of(module, "PyBytes_Concat"));
    PyBytes_ConcatAndDel_Ptr = reinterpret_cast<decltype(PyBytes_ConcatAndDel_Ptr)>(address_of(module, "PyBytes_ConcatAndDel"));
    PyBytes_DecodeEscape_Ptr = reinterpret_cast<decltype(PyBytes_DecodeEscape_Ptr)>(address_of(module, "PyBytes_DecodeEscape"));
    PyBytes_FromFormat_Ptr = reinterpret_cast<decltype(PyBytes_FromFormat_Ptr)>(address_of(module, "PyBytes_FromFormat"));
    PyBytes_FromFormatV_Ptr = reinterpret_cast<decltype(PyBytes_FromFormatV_Ptr)>(address_of(module, "PyBytes_FromFormatV"));
    PyBytes_FromObject_Ptr = reinterpret_cast<decltype(PyBytes_FromObject_Ptr)>(address_of(module, "PyBytes_FromObject"));
    PyBytes_FromString_Ptr = reinterpret_cast<decltype(PyBytes_FromString_Ptr)>(address_of(module, "PyBytes_FromString"));
    PyBytes_FromStringAndSize_Ptr = reinterpret_cast<decltype(PyBytes_FromStringAndSize_Ptr)>(address_of(module, "PyBytes_FromStringAndSize"));
    PyBytes_Repr_Ptr = reinterpret_cast<decltype(PyBytes_Repr_Ptr)>(address_of(module, "PyBytes_Repr"));
    PyBytes_Size_Ptr = reinterpret_cast<decltype(PyBytes_Size_Ptr)>(address_of(module, "PyBytes_Size"));
    PyCFunction_Call_Ptr = reinterpret_cast<decltype(PyCFunction_Call_Ptr)>(address_of(module, "PyCFunction_Call"));
    PyCFunction_GetFlags_Ptr = reinterpret_cast<decltype(PyCFunction_GetFlags_Ptr)>(address_of(module, "PyCFunction_GetFlags"));
    PyCFunction_GetFunction_Ptr = reinterpret_cast<decltype(PyCFunction_GetFunction_Ptr)>(address_of(module, "PyCFunction_GetFunction"));
    PyCFunction_GetSelf_Ptr = reinterpret_cast<decltype(PyCFunction_GetSelf_Ptr)>(address_of(module, "PyCFunction_GetSelf"));
    PyCFunction_New_Ptr = reinterpret_cast<decltype(PyCFunction_New_Ptr)>(address_of(module, "PyCFunction_New"));
    #if HAS_PYTHON_VERSION(0x03090000)
    PyCFunction_NewEx_Ptr = reinterpret_cast<decltype(PyCFunction_NewEx_Ptr)>(address_of(module, "PyCFunction_NewEx"));
    #endif
    PyCMethod_New_Ptr = reinterpret_cast<decltype(PyCMethod_New_Ptr)>(address_of(module, "PyCMethod_New"));
    PyCallIter_New_Ptr = reinterpret_cast<decltype(PyCallIter_New_Ptr)>(address_of(module, "PyCallIter_New"));
    PyCallable_Check_Ptr = reinterpret_cast<decltype(PyCallable_Check_Ptr)>(address_of(module, "PyCallable_Check"));
    PyCapsule_GetContext_Ptr = reinterpret_cast<decltype(PyCapsule_GetContext_Ptr)>(address_of(module, "PyCapsule_GetContext"));
    PyCapsule_GetDestructor_Ptr = reinterpret_cast<decltype(PyCapsule_GetDestructor_Ptr)>(address_of(module, "PyCapsule_GetDestructor"));
    PyCapsule_GetName_Ptr = reinterpret_cast<decltype(PyCapsule_GetName_Ptr)>(address_of(module, "PyCapsule_GetName"));
    PyCapsule_GetPointer_Ptr = reinterpret_cast<decltype(PyCapsule_GetPointer_Ptr)>(address_of(module, "PyCapsule_GetPointer"));
    PyCapsule_Import_Ptr = reinterpret_cast<decltype(PyCapsule_Import_Ptr)>(address_of(module, "PyCapsule_Import"));
    PyCapsule_IsValid_Ptr = reinterpret_cast<decltype(PyCapsule_IsValid_Ptr)>(address_of(module, "PyCapsule_IsValid"));
    PyCapsule_New_Ptr = reinterpret_cast<decltype(PyCapsule_New_Ptr)>(address_of(module, "PyCapsule_New"));
    PyCapsule_SetContext_Ptr = reinterpret_cast<decltype(PyCapsule_SetContext_Ptr)>(address_of(module, "PyCapsule_SetContext"));
    PyCapsule_SetDestructor_Ptr = reinterpret_cast<decltype(PyCapsule_SetDestructor_Ptr)>(address_of(module, "PyCapsule_SetDestructor"));
    PyCapsule_SetName_Ptr = reinterpret_cast<decltype(PyCapsule_SetName_Ptr)>(address_of(module, "PyCapsule_SetName"));
    PyCapsule_SetPointer_Ptr = reinterpret_cast<decltype(PyCapsule_SetPointer_Ptr)>(address_of(module, "PyCapsule_SetPointer"));
    PyComplex_FromDoubles_Ptr = reinterpret_cast<decltype(PyComplex_FromDoubles_Ptr)>(address_of(module, "PyComplex_FromDoubles"));
    PyComplex_ImagAsDouble_Ptr = reinterpret_cast<decltype(PyComplex_ImagAsDouble_Ptr)>(address_of(module, "PyComplex_ImagAsDouble"));
    PyComplex_RealAsDouble_Ptr = reinterpret_cast<decltype(PyComplex_RealAsDouble_Ptr)>(address_of(module, "PyComplex_RealAsDouble"));
    PyDescr_NewClassMethod_Ptr = reinterpret_cast<decltype(PyDescr_NewClassMethod_Ptr)>(address_of(module, "PyDescr_NewClassMethod"));
    PyDescr_NewGetSet_Ptr = reinterpret_cast<decltype(PyDescr_NewGetSet_Ptr)>(address_of(module, "PyDescr_NewGetSet"));
    PyDescr_NewMember_Ptr = reinterpret_cast<decltype(PyDescr_NewMember_Ptr)>(address_of(module, "PyDescr_NewMember"));
    PyDescr_NewMethod_Ptr = reinterpret_cast<decltype(PyDescr_NewMethod_Ptr)>(address_of(module, "PyDescr_NewMethod"));
    PyDictProxy_New_Ptr = reinterpret_cast<decltype(PyDictProxy_New_Ptr)>(address_of(module, "PyDictProxy_New"));
    PyDict_Clear_Ptr = reinterpret_cast<decltype(PyDict_Clear_Ptr)>(address_of(module, "PyDict_Clear"));
    PyDict_Contains_Ptr = reinterpret_cast<decltype(PyDict_Contains_Ptr)>(address_of(module, "PyDict_Contains"));
    PyDict_Copy_Ptr = reinterpret_cast<decltype(PyDict_Copy_Ptr)>(address_of(module, "PyDict_Copy"));
    PyDict_DelItem_Ptr = reinterpret_cast<decltype(PyDict_DelItem_Ptr)>(address_of(module, "PyDict_DelItem"));
    PyDict_DelItemString_Ptr = reinterpret_cast<decltype(PyDict_DelItemString_Ptr)>(address_of(module, "PyDict_DelItemString"));
    PyDict_GetItem_Ptr = reinterpret_cast<decltype(PyDict_GetItem_Ptr)>(address_of(module, "PyDict_GetItem"));
    PyDict_GetItemString_Ptr = reinterpret_cast<decltype(PyDict_GetItemString_Ptr)>(address_of(module, "PyDict_GetItemString"));
    PyDict_GetItemWithError_Ptr = reinterpret_cast<decltype(PyDict_GetItemWithError_Ptr)>(address_of(module, "PyDict_GetItemWithError"));
    PyDict_Items_Ptr = reinterpret_cast<decltype(PyDict_Items_Ptr)>(address_of(module, "PyDict_Items"));
    PyDict_Keys_Ptr = reinterpret_cast<decltype(PyDict_Keys_Ptr)>(address_of(module, "PyDict_Keys"));
    PyDict_Merge_Ptr = reinterpret_cast<decltype(PyDict_Merge_Ptr)>(address_of(module, "PyDict_Merge"));
    PyDict_MergeFromSeq2_Ptr = reinterpret_cast<decltype(PyDict_MergeFromSeq2_Ptr)>(address_of(module, "PyDict_MergeFromSeq2"));
    PyDict_New_Ptr = reinterpret_cast<decltype(PyDict_New_Ptr)>(address_of(module, "PyDict_New"));
    PyDict_Next_Ptr = reinterpret_cast<decltype(PyDict_Next_Ptr)>(address_of(module, "PyDict_Next"));
    PyDict_SetItem_Ptr = reinterpret_cast<decltype(PyDict_SetItem_Ptr)>(address_of(module, "PyDict_SetItem"));
    PyDict_SetItemString_Ptr = reinterpret_cast<decltype(PyDict_SetItemString_Ptr)>(address_of(module, "PyDict_SetItemString"));
    PyDict_Size_Ptr = reinterpret_cast<decltype(PyDict_Size_Ptr)>(address_of(module, "PyDict_Size"));
    PyDict_Update_Ptr = reinterpret_cast<decltype(PyDict_Update_Ptr)>(address_of(module, "PyDict_Update"));
    PyDict_Values_Ptr = reinterpret_cast<decltype(PyDict_Values_Ptr)>(address_of(module, "PyDict_Values"));
    PyErr_BadArgument_Ptr = reinterpret_cast<decltype(PyErr_BadArgument_Ptr)>(address_of(module, "PyErr_BadArgument"));
    #ifdef PyErr_BadInternalCall
    _PyErr_BadInternalCall_Ptr = reinterpret_cast<decltype(_PyErr_BadInternalCall_Ptr)>(address_of(module, "_PyErr_BadInternalCall"));
    #endif
    PyErr_CheckSignals_Ptr = reinterpret_cast<decltype(PyErr_CheckSignals_Ptr)>(address_of(module, "PyErr_CheckSignals"));
    PyErr_Clear_Ptr = reinterpret_cast<decltype(PyErr_Clear_Ptr)>(address_of(module, "PyErr_Clear"));
    PyErr_Display_Ptr = reinterpret_cast<decltype(PyErr_Display_Ptr)>(address_of(module, "PyErr_Display"));
    PyErr_ExceptionMatches_Ptr = reinterpret_cast<decltype(PyErr_ExceptionMatches_Ptr)>(address_of(module, "PyErr_ExceptionMatches"));
    PyErr_Fetch_Ptr = reinterpret_cast<decltype(PyErr_Fetch_Ptr)>(address_of(module, "PyErr_Fetch"));
    PyErr_Format_Ptr = reinterpret_cast<decltype(PyErr_Format_Ptr)>(address_of(module, "PyErr_Format"));
    PyErr_FormatV_Ptr = reinterpret_cast<decltype(PyErr_FormatV_Ptr)>(address_of(module, "PyErr_FormatV"));
    PyErr_GetExcInfo_Ptr = reinterpret_cast<decltype(PyErr_GetExcInfo_Ptr)>(address_of(module, "PyErr_GetExcInfo"));
    PyErr_GetHandledException_Ptr = reinterpret_cast<decltype(PyErr_GetHandledException_Ptr)>(address_of(module, "PyErr_GetHandledException"));
    PyErr_GivenExceptionMatches_Ptr = reinterpret_cast<decltype(PyErr_GivenExceptionMatches_Ptr)>(address_of(module, "PyErr_GivenExceptionMatches"));
    PyErr_NewException_Ptr = reinterpret_cast<decltype(PyErr_NewException_Ptr)>(address_of(module, "PyErr_NewException"));
    PyErr_NewExceptionWithDoc_Ptr = reinterpret_cast<decltype(PyErr_NewExceptionWithDoc_Ptr)>(address_of(module, "PyErr_NewExceptionWithDoc"));
    PyErr_NoMemory_Ptr = reinterpret_cast<decltype(PyErr_NoMemory_Ptr)>(address_of(module, "PyErr_NoMemory"));
    PyErr_NormalizeException_Ptr = reinterpret_cast<decltype(PyErr_NormalizeException_Ptr)>(address_of(module, "PyErr_NormalizeException"));
    PyErr_Occurred_Ptr = reinterpret_cast<decltype(PyErr_Occurred_Ptr)>(address_of(module, "PyErr_Occurred"));
    PyErr_Print_Ptr = reinterpret_cast<decltype(PyErr_Print_Ptr)>(address_of(module, "PyErr_Print"));
    PyErr_PrintEx_Ptr = reinterpret_cast<decltype(PyErr_PrintEx_Ptr)>(address_of(module, "PyErr_PrintEx"));
    PyErr_ProgramText_Ptr = reinterpret_cast<decltype(PyErr_ProgramText_Ptr)>(address_of(module, "PyErr_ProgramText"));
    PyErr_ResourceWarning_Ptr = reinterpret_cast<decltype(PyErr_ResourceWarning_Ptr)>(address_of(module, "PyErr_ResourceWarning"));
    PyErr_Restore_Ptr = reinterpret_cast<decltype(PyErr_Restore_Ptr)>(address_of(module, "PyErr_Restore"));
    PyErr_SetExcInfo_Ptr = reinterpret_cast<decltype(PyErr_SetExcInfo_Ptr)>(address_of(module, "PyErr_SetExcInfo"));
    PyErr_SetFromErrno_Ptr = reinterpret_cast<decltype(PyErr_SetFromErrno_Ptr)>(address_of(module, "PyErr_SetFromErrno"));
    PyErr_SetFromErrnoWithFilename_Ptr = reinterpret_cast<decltype(PyErr_SetFromErrnoWithFilename_Ptr)>(address_of(module, "PyErr_SetFromErrnoWithFilename"));
    PyErr_SetFromErrnoWithFilenameObject_Ptr = reinterpret_cast<decltype(PyErr_SetFromErrnoWithFilenameObject_Ptr)>(address_of(module, "PyErr_SetFromErrnoWithFilenameObject"));
    PyErr_SetFromErrnoWithFilenameObjects_Ptr = reinterpret_cast<decltype(PyErr_SetFromErrnoWithFilenameObjects_Ptr)>(address_of(module, "PyErr_SetFromErrnoWithFilenameObjects"));
    PyErr_SetHandledException_Ptr = reinterpret_cast<decltype(PyErr_SetHandledException_Ptr)>(address_of(module, "PyErr_SetHandledException"));
    PyErr_SetImportError_Ptr = reinterpret_cast<decltype(PyErr_SetImportError_Ptr)>(address_of(module, "PyErr_SetImportError"));
    PyErr_SetImportErrorSubclass_Ptr = reinterpret_cast<decltype(PyErr_SetImportErrorSubclass_Ptr)>(address_of(module, "PyErr_SetImportErrorSubclass"));
    PyErr_SetInterrupt_Ptr = reinterpret_cast<decltype(PyErr_SetInterrupt_Ptr)>(address_of(module, "PyErr_SetInterrupt"));
    PyErr_SetInterruptEx_Ptr = reinterpret_cast<decltype(PyErr_SetInterruptEx_Ptr)>(address_of(module, "PyErr_SetInterruptEx"));
    PyErr_SetNone_Ptr = reinterpret_cast<decltype(PyErr_SetNone_Ptr)>(address_of(module, "PyErr_SetNone"));
    PyErr_SetObject_Ptr = reinterpret_cast<decltype(PyErr_SetObject_Ptr)>(address_of(module, "PyErr_SetObject"));
    PyErr_SetString_Ptr = reinterpret_cast<decltype(PyErr_SetString_Ptr)>(address_of(module, "PyErr_SetString"));
    PyErr_SyntaxLocation_Ptr = reinterpret_cast<decltype(PyErr_SyntaxLocation_Ptr)>(address_of(module, "PyErr_SyntaxLocation"));
    PyErr_SyntaxLocationEx_Ptr = reinterpret_cast<decltype(PyErr_SyntaxLocationEx_Ptr)>(address_of(module, "PyErr_SyntaxLocationEx"));
    PyErr_WarnEx_Ptr = reinterpret_cast<decltype(PyErr_WarnEx_Ptr)>(address_of(module, "PyErr_WarnEx"));
    PyErr_WarnExplicit_Ptr = reinterpret_cast<decltype(PyErr_WarnExplicit_Ptr)>(address_of(module, "PyErr_WarnExplicit"));
    PyErr_WarnFormat_Ptr = reinterpret_cast<decltype(PyErr_WarnFormat_Ptr)>(address_of(module, "PyErr_WarnFormat"));
    PyErr_WriteUnraisable_Ptr = reinterpret_cast<decltype(PyErr_WriteUnraisable_Ptr)>(address_of(module, "PyErr_WriteUnraisable"));
    PyEval_AcquireLock_Ptr = reinterpret_cast<decltype(PyEval_AcquireLock_Ptr)>(address_of(module, "PyEval_AcquireLock"));
    PyEval_AcquireThread_Ptr = reinterpret_cast<decltype(PyEval_AcquireThread_Ptr)>(address_of(module, "PyEval_AcquireThread"));
    PyEval_CallFunction_Ptr = reinterpret_cast<decltype(PyEval_CallFunction_Ptr)>(address_of(module, "PyEval_CallFunction"));
    PyEval_CallMethod_Ptr = reinterpret_cast<decltype(PyEval_CallMethod_Ptr)>(address_of(module, "PyEval_CallMethod"));
    PyEval_CallObjectWithKeywords_Ptr = reinterpret_cast<decltype(PyEval_CallObjectWithKeywords_Ptr)>(address_of(module, "PyEval_CallObjectWithKeywords"));
    PyEval_EvalCode_Ptr = reinterpret_cast<decltype(PyEval_EvalCode_Ptr)>(address_of(module, "PyEval_EvalCode"));
    PyEval_EvalCodeEx_Ptr = reinterpret_cast<decltype(PyEval_EvalCodeEx_Ptr)>(address_of(module, "PyEval_EvalCodeEx"));
    #if HAS_PYTHON_VERSION(0x03090000)
    PyEval_EvalFrame_Ptr = reinterpret_cast<decltype(PyEval_EvalFrame_Ptr)>(address_of(module, "PyEval_EvalFrame"));
    PyEval_EvalFrameEx_Ptr = reinterpret_cast<decltype(PyEval_EvalFrameEx_Ptr)>(address_of(module, "PyEval_EvalFrameEx"));
    #endif
    PyEval_GetBuiltins_Ptr = reinterpret_cast<decltype(PyEval_GetBuiltins_Ptr)>(address_of(module, "PyEval_GetBuiltins"));
    #if HAS_PYTHON_VERSION(0x03090000)
    PyEval_GetFrame_Ptr = reinterpret_cast<decltype(PyEval_GetFrame_Ptr)>(address_of(module, "PyEval_GetFrame"));
    #endif
    PyEval_GetFuncDesc_Ptr = reinterpret_cast<decltype(PyEval_GetFuncDesc_Ptr)>(address_of(module, "PyEval_GetFuncDesc"));
    PyEval_GetFuncName_Ptr = reinterpret_cast<decltype(PyEval_GetFuncName_Ptr)>(address_of(module, "PyEval_GetFuncName"));
    PyEval_GetGlobals_Ptr = reinterpret_cast<decltype(PyEval_GetGlobals_Ptr)>(address_of(module, "PyEval_GetGlobals"));
    PyEval_GetLocals_Ptr = reinterpret_cast<decltype(PyEval_GetLocals_Ptr)>(address_of(module, "PyEval_GetLocals"));
    PyEval_InitThreads_Ptr = reinterpret_cast<decltype(PyEval_InitThreads_Ptr)>(address_of(module, "PyEval_InitThreads"));
    PyEval_ReleaseLock_Ptr = reinterpret_cast<decltype(PyEval_ReleaseLock_Ptr)>(address_of(module, "PyEval_ReleaseLock"));
    PyEval_ReleaseThread_Ptr = reinterpret_cast<decltype(PyEval_ReleaseThread_Ptr)>(address_of(module, "PyEval_ReleaseThread"));
    PyEval_RestoreThread_Ptr = reinterpret_cast<decltype(PyEval_RestoreThread_Ptr)>(address_of(module, "PyEval_RestoreThread"));
    PyEval_SaveThread_Ptr = reinterpret_cast<decltype(PyEval_SaveThread_Ptr)>(address_of(module, "PyEval_SaveThread"));
    PyEval_ThreadsInitialized_Ptr = reinterpret_cast<decltype(PyEval_ThreadsInitialized_Ptr)>(address_of(module, "PyEval_ThreadsInitialized"));
    PyExceptionClass_Name_Ptr = reinterpret_cast<decltype(PyExceptionClass_Name_Ptr)>(address_of(module, "PyExceptionClass_Name"));
    PyException_GetCause_Ptr = reinterpret_cast<decltype(PyException_GetCause_Ptr)>(address_of(module, "PyException_GetCause"));
    PyException_GetContext_Ptr = reinterpret_cast<decltype(PyException_GetContext_Ptr)>(address_of(module, "PyException_GetContext"));
    PyException_GetTraceback_Ptr = reinterpret_cast<decltype(PyException_GetTraceback_Ptr)>(address_of(module, "PyException_GetTraceback"));
    PyException_SetCause_Ptr = reinterpret_cast<decltype(PyException_SetCause_Ptr)>(address_of(module, "PyException_SetCause"));
    PyException_SetContext_Ptr = reinterpret_cast<decltype(PyException_SetContext_Ptr)>(address_of(module, "PyException_SetContext"));
    PyException_SetTraceback_Ptr = reinterpret_cast<decltype(PyException_SetTraceback_Ptr)>(address_of(module, "PyException_SetTraceback"));
    PyFile_FromFd_Ptr = reinterpret_cast<decltype(PyFile_FromFd_Ptr)>(address_of(module, "PyFile_FromFd"));
    PyFile_GetLine_Ptr = reinterpret_cast<decltype(PyFile_GetLine_Ptr)>(address_of(module, "PyFile_GetLine"));
    PyFile_WriteObject_Ptr = reinterpret_cast<decltype(PyFile_WriteObject_Ptr)>(address_of(module, "PyFile_WriteObject"));
    PyFile_WriteString_Ptr = reinterpret_cast<decltype(PyFile_WriteString_Ptr)>(address_of(module, "PyFile_WriteString"));
    PyFloat_AsDouble_Ptr = reinterpret_cast<decltype(PyFloat_AsDouble_Ptr)>(address_of(module, "PyFloat_AsDouble"));
    PyFloat_FromDouble_Ptr = reinterpret_cast<decltype(PyFloat_FromDouble_Ptr)>(address_of(module, "PyFloat_FromDouble"));
    PyFloat_FromString_Ptr = reinterpret_cast<decltype(PyFloat_FromString_Ptr)>(address_of(module, "PyFloat_FromString"));
    PyFloat_GetInfo_Ptr = reinterpret_cast<decltype(PyFloat_GetInfo_Ptr)>(address_of(module, "PyFloat_GetInfo"));
    PyFloat_GetMax_Ptr = reinterpret_cast<decltype(PyFloat_GetMax_Ptr)>(address_of(module, "PyFloat_GetMax"));
    PyFloat_GetMin_Ptr = reinterpret_cast<decltype(PyFloat_GetMin_Ptr)>(address_of(module, "PyFloat_GetMin"));
    PyFrozenSet_New_Ptr = reinterpret_cast<decltype(PyFrozenSet_New_Ptr)>(address_of(module, "PyFrozenSet_New"));
    PyGC_Collect_Ptr = reinterpret_cast<decltype(PyGC_Collect_Ptr)>(address_of(module, "PyGC_Collect"));
    PyGC_Disable_Ptr = reinterpret_cast<decltype(PyGC_Disable_Ptr)>(address_of(module, "PyGC_Disable"));
    PyGC_Enable_Ptr = reinterpret_cast<decltype(PyGC_Enable_Ptr)>(address_of(module, "PyGC_Enable"));
    PyGC_IsEnabled_Ptr = reinterpret_cast<decltype(PyGC_IsEnabled_Ptr)>(address_of(module, "PyGC_IsEnabled"));
    PyGILState_Ensure_Ptr = reinterpret_cast<decltype(PyGILState_Ensure_Ptr)>(address_of(module, "PyGILState_Ensure"));
    PyGILState_GetThisThreadState_Ptr = reinterpret_cast<decltype(PyGILState_GetThisThreadState_Ptr)>(address_of(module, "PyGILState_GetThisThreadState"));
    PyGILState_Release_Ptr = reinterpret_cast<decltype(PyGILState_Release_Ptr)>(address_of(module, "PyGILState_Release"));
    PyImport_AddModule_Ptr = reinterpret_cast<decltype(PyImport_AddModule_Ptr)>(address_of(module, "PyImport_AddModule"));
    PyImport_AddModuleObject_Ptr = reinterpret_cast<decltype(PyImport_AddModuleObject_Ptr)>(address_of(module, "PyImport_AddModuleObject"));
    PyImport_AppendInittab_Ptr = reinterpret_cast<decltype(PyImport_AppendInittab_Ptr)>(address_of(module, "PyImport_AppendInittab"));
    PyImport_ExecCodeModule_Ptr = reinterpret_cast<decltype(PyImport_ExecCodeModule_Ptr)>(address_of(module, "PyImport_ExecCodeModule"));
    PyImport_ExecCodeModuleEx_Ptr = reinterpret_cast<decltype(PyImport_ExecCodeModuleEx_Ptr)>(address_of(module, "PyImport_ExecCodeModuleEx"));
    PyImport_ExecCodeModuleObject_Ptr = reinterpret_cast<decltype(PyImport_ExecCodeModuleObject_Ptr)>(address_of(module, "PyImport_ExecCodeModuleObject"));
    PyImport_ExecCodeModuleWithPathnames_Ptr = reinterpret_cast<decltype(PyImport_ExecCodeModuleWithPathnames_Ptr)>(address_of(module, "PyImport_ExecCodeModuleWithPathnames"));
    PyImport_GetImporter_Ptr = reinterpret_cast<decltype(PyImport_GetImporter_Ptr)>(address_of(module, "PyImport_GetImporter"));
    PyImport_GetMagicNumber_Ptr = reinterpret_cast<decltype(PyImport_GetMagicNumber_Ptr)>(address_of(module, "PyImport_GetMagicNumber"));
    PyImport_GetMagicTag_Ptr = reinterpret_cast<decltype(PyImport_GetMagicTag_Ptr)>(address_of(module, "PyImport_GetMagicTag"));
    PyImport_GetModule_Ptr = reinterpret_cast<decltype(PyImport_GetModule_Ptr)>(address_of(module, "PyImport_GetModule"));
    PyImport_GetModuleDict_Ptr = reinterpret_cast<decltype(PyImport_GetModuleDict_Ptr)>(address_of(module, "PyImport_GetModuleDict"));
    PyImport_Import_Ptr = reinterpret_cast<decltype(PyImport_Import_Ptr)>(address_of(module, "PyImport_Import"));
    PyImport_ImportFrozenModule_Ptr = reinterpret_cast<decltype(PyImport_ImportFrozenModule_Ptr)>(address_of(module, "PyImport_ImportFrozenModule"));
    PyImport_ImportFrozenModuleObject_Ptr = reinterpret_cast<decltype(PyImport_ImportFrozenModuleObject_Ptr)>(address_of(module, "PyImport_ImportFrozenModuleObject"));
    PyImport_ImportModule_Ptr = reinterpret_cast<decltype(PyImport_ImportModule_Ptr)>(address_of(module, "PyImport_ImportModule"));
    PyImport_ImportModuleLevel_Ptr = reinterpret_cast<decltype(PyImport_ImportModuleLevel_Ptr)>(address_of(module, "PyImport_ImportModuleLevel"));
    PyImport_ImportModuleLevelObject_Ptr = reinterpret_cast<decltype(PyImport_ImportModuleLevelObject_Ptr)>(address_of(module, "PyImport_ImportModuleLevelObject"));
    PyImport_ImportModuleNoBlock_Ptr = reinterpret_cast<decltype(PyImport_ImportModuleNoBlock_Ptr)>(address_of(module, "PyImport_ImportModuleNoBlock"));
    PyImport_ReloadModule_Ptr = reinterpret_cast<decltype(PyImport_ReloadModule_Ptr)>(address_of(module, "PyImport_ReloadModule"));
    PyIndex_Check_Ptr = reinterpret_cast<decltype(PyIndex_Check_Ptr)>(address_of(module, "PyIndex_Check"));
    PyInterpreterState_Clear_Ptr = reinterpret_cast<decltype(PyInterpreterState_Clear_Ptr)>(address_of(module, "PyInterpreterState_Clear"));
    PyInterpreterState_Delete_Ptr = reinterpret_cast<decltype(PyInterpreterState_Delete_Ptr)>(address_of(module, "PyInterpreterState_Delete"));
    PyInterpreterState_Get_Ptr = reinterpret_cast<decltype(PyInterpreterState_Get_Ptr)>(address_of(module, "PyInterpreterState_Get"));
    PyInterpreterState_GetDict_Ptr = reinterpret_cast<decltype(PyInterpreterState_GetDict_Ptr)>(address_of(module, "PyInterpreterState_GetDict"));
    PyInterpreterState_GetID_Ptr = reinterpret_cast<decltype(PyInterpreterState_GetID_Ptr)>(address_of(module, "PyInterpreterState_GetID"));
    PyInterpreterState_New_Ptr = reinterpret_cast<decltype(PyInterpreterState_New_Ptr)>(address_of(module, "PyInterpreterState_New"));
    PyIter_Check_Ptr = reinterpret_cast<decltype(PyIter_Check_Ptr)>(address_of(module, "PyIter_Check"));
    PyIter_Next_Ptr = reinterpret_cast<decltype(PyIter_Next_Ptr)>(address_of(module, "PyIter_Next"));
    #if HAS_PYTHON_VERSION(0x030A0000)
    PyIter_Send_Ptr = reinterpret_cast<decltype(PyIter_Send_Ptr)>(address_of(module, "PyIter_Send"));
    #endif
    PyList_Append_Ptr = reinterpret_cast<decltype(PyList_Append_Ptr)>(address_of(module, "PyList_Append"));
    PyList_AsTuple_Ptr = reinterpret_cast<decltype(PyList_AsTuple_Ptr)>(address_of(module, "PyList_AsTuple"));
    PyList_GetItem_Ptr = reinterpret_cast<decltype(PyList_GetItem_Ptr)>(address_of(module, "PyList_GetItem"));
    PyList_GetSlice_Ptr = reinterpret_cast<decltype(PyList_GetSlice_Ptr)>(address_of(module, "PyList_GetSlice"));
    PyList_Insert_Ptr = reinterpret_cast<decltype(PyList_Insert_Ptr)>(address_of(module, "PyList_Insert"));
    PyList_New_Ptr = reinterpret_cast<decltype(PyList_New_Ptr)>(address_of(module, "PyList_New"));
    PyList_Reverse_Ptr = reinterpret_cast<decltype(PyList_Reverse_Ptr)>(address_of(module, "PyList_Reverse"));
    PyList_SetItem_Ptr = reinterpret_cast<decltype(PyList_SetItem_Ptr)>(address_of(module, "PyList_SetItem"));
    PyList_SetSlice_Ptr = reinterpret_cast<decltype(PyList_SetSlice_Ptr)>(address_of(module, "PyList_SetSlice"));
    PyList_Size_Ptr = reinterpret_cast<decltype(PyList_Size_Ptr)>(address_of(module, "PyList_Size"));
    PyList_Sort_Ptr = reinterpret_cast<decltype(PyList_Sort_Ptr)>(address_of(module, "PyList_Sort"));
    PyLong_AsDouble_Ptr = reinterpret_cast<decltype(PyLong_AsDouble_Ptr)>(address_of(module, "PyLong_AsDouble"));
    PyLong_AsLong_Ptr = reinterpret_cast<decltype(PyLong_AsLong_Ptr)>(address_of(module, "PyLong_AsLong"));
    PyLong_AsLongAndOverflow_Ptr = reinterpret_cast<decltype(PyLong_AsLongAndOverflow_Ptr)>(address_of(module, "PyLong_AsLongAndOverflow"));
    PyLong_AsLongLong_Ptr = reinterpret_cast<decltype(PyLong_AsLongLong_Ptr)>(address_of(module, "PyLong_AsLongLong"));
    PyLong_AsLongLongAndOverflow_Ptr = reinterpret_cast<decltype(PyLong_AsLongLongAndOverflow_Ptr)>(address_of(module, "PyLong_AsLongLongAndOverflow"));
    PyLong_AsSize_t_Ptr = reinterpret_cast<decltype(PyLong_AsSize_t_Ptr)>(address_of(module, "PyLong_AsSize_t"));
    PyLong_AsSsize_t_Ptr = reinterpret_cast<decltype(PyLong_AsSsize_t_Ptr)>(address_of(module, "PyLong_AsSsize_t"));
    PyLong_AsUnsignedLong_Ptr = reinterpret_cast<decltype(PyLong_AsUnsignedLong_Ptr)>(address_of(module, "PyLong_AsUnsignedLong"));
    PyLong_AsUnsignedLongLong_Ptr = reinterpret_cast<decltype(PyLong_AsUnsignedLongLong_Ptr)>(address_of(module, "PyLong_AsUnsignedLongLong"));
    PyLong_AsUnsignedLongLongMask_Ptr = reinterpret_cast<decltype(PyLong_AsUnsignedLongLongMask_Ptr)>(address_of(module, "PyLong_AsUnsignedLongLongMask"));
    PyLong_AsUnsignedLongMask_Ptr = reinterpret_cast<decltype(PyLong_AsUnsignedLongMask_Ptr)>(address_of(module, "PyLong_AsUnsignedLongMask"));
    PyLong_AsVoidPtr_Ptr = reinterpret_cast<decltype(PyLong_AsVoidPtr_Ptr)>(address_of(module, "PyLong_AsVoidPtr"));
    PyLong_FromDouble_Ptr = reinterpret_cast<decltype(PyLong_FromDouble_Ptr)>(address_of(module, "PyLong_FromDouble"));
    PyLong_FromLong_Ptr = reinterpret_cast<decltype(PyLong_FromLong_Ptr)>(address_of(module, "PyLong_FromLong"));
    PyLong_FromLongLong_Ptr = reinterpret_cast<decltype(PyLong_FromLongLong_Ptr)>(address_of(module, "PyLong_FromLongLong"));
    PyLong_FromSize_t_Ptr = reinterpret_cast<decltype(PyLong_FromSize_t_Ptr)>(address_of(module, "PyLong_FromSize_t"));
    PyLong_FromSsize_t_Ptr = reinterpret_cast<decltype(PyLong_FromSsize_t_Ptr)>(address_of(module, "PyLong_FromSsize_t"));
    PyLong_FromString_Ptr = reinterpret_cast<decltype(PyLong_FromString_Ptr)>(address_of(module, "PyLong_FromString"));
    PyLong_FromUnsignedLong_Ptr = reinterpret_cast<decltype(PyLong_FromUnsignedLong_Ptr)>(address_of(module, "PyLong_FromUnsignedLong"));
    PyLong_FromUnsignedLongLong_Ptr = reinterpret_cast<decltype(PyLong_FromUnsignedLongLong_Ptr)>(address_of(module, "PyLong_FromUnsignedLongLong"));
    PyLong_FromVoidPtr_Ptr = reinterpret_cast<decltype(PyLong_FromVoidPtr_Ptr)>(address_of(module, "PyLong_FromVoidPtr"));
    PyLong_GetInfo_Ptr = reinterpret_cast<decltype(PyLong_GetInfo_Ptr)>(address_of(module, "PyLong_GetInfo"));
    PyMapping_Check_Ptr = reinterpret_cast<decltype(PyMapping_Check_Ptr)>(address_of(module, "PyMapping_Check"));
    PyMapping_GetItemString_Ptr = reinterpret_cast<decltype(PyMapping_GetItemString_Ptr)>(address_of(module, "PyMapping_GetItemString"));
    PyMapping_HasKey_Ptr = reinterpret_cast<decltype(PyMapping_HasKey_Ptr)>(address_of(module, "PyMapping_HasKey"));
    PyMapping_HasKeyString_Ptr = reinterpret_cast<decltype(PyMapping_HasKeyString_Ptr)>(address_of(module, "PyMapping_HasKeyString"));
    PyMapping_Items_Ptr = reinterpret_cast<decltype(PyMapping_Items_Ptr)>(address_of(module, "PyMapping_Items"));
    PyMapping_Keys_Ptr = reinterpret_cast<decltype(PyMapping_Keys_Ptr)>(address_of(module, "PyMapping_Keys"));
    #ifdef PyMapping_Length
    PyMapping_Length_Ptr = reinterpret_cast<decltype(PyMapping_Length_Ptr)>(address_of(module, "PyMapping_Size"));
    #else
    PyMapping_Length_Ptr = nullptr;
    #endif
    PyMapping_SetItemString_Ptr = reinterpret_cast<decltype(PyMapping_SetItemString_Ptr)>(address_of(module, "PyMapping_SetItemString"));
    PyMapping_Size_Ptr = reinterpret_cast<decltype(PyMapping_Size_Ptr)>(address_of(module, "PyMapping_Size"));
    PyMapping_Values_Ptr = reinterpret_cast<decltype(PyMapping_Values_Ptr)>(address_of(module, "PyMapping_Values"));
    PyMem_Calloc_Ptr = reinterpret_cast<decltype(PyMem_Calloc_Ptr)>(address_of(module, "PyMem_Calloc"));
    PyMem_Free_Ptr = reinterpret_cast<decltype(PyMem_Free_Ptr)>(address_of(module, "PyMem_Free"));
    PyMem_Malloc_Ptr = reinterpret_cast<decltype(PyMem_Malloc_Ptr)>(address_of(module, "PyMem_Malloc"));
    PyMem_Realloc_Ptr = reinterpret_cast<decltype(PyMem_Realloc_Ptr)>(address_of(module, "PyMem_Realloc"));
    #if HAS_PYTHON_VERSION(0x030B1000)
    PyMemoryView_FromBuffer_Ptr = reinterpret_cast<decltype(PyMemoryView_FromBuffer_Ptr)>(address_of(module, "PyMemoryView_FromBuffer"));
    #endif
    PyMemoryView_FromMemory_Ptr = reinterpret_cast<decltype(PyMemoryView_FromMemory_Ptr)>(address_of(module, "PyMemoryView_FromMemory"));
    PyMemoryView_FromObject_Ptr = reinterpret_cast<decltype(PyMemoryView_FromObject_Ptr)>(address_of(module, "PyMemoryView_FromObject"));
    PyMemoryView_GetContiguous_Ptr = reinterpret_cast<decltype(PyMemoryView_GetContiguous_Ptr)>(address_of(module, "PyMemoryView_GetContiguous"));
    PyModuleDef_Init_Ptr = reinterpret_cast<decltype(PyModuleDef_Init_Ptr)>(address_of(module, "PyModuleDef_Init"));
    PyModule_AddFunctions_Ptr = reinterpret_cast<decltype(PyModule_AddFunctions_Ptr)>(address_of(module, "PyModule_AddFunctions"));
    PyModule_AddIntConstant_Ptr = reinterpret_cast<decltype(PyModule_AddIntConstant_Ptr)>(address_of(module, "PyModule_AddIntConstant"));
    PyModule_AddObject_Ptr = reinterpret_cast<decltype(PyModule_AddObject_Ptr)>(address_of(module, "PyModule_AddObject"));
    PyModule_AddObjectRef_Ptr = reinterpret_cast<decltype(PyModule_AddObjectRef_Ptr)>(address_of(module, "PyModule_AddObjectRef"));
    PyModule_AddStringConstant_Ptr = reinterpret_cast<decltype(PyModule_AddStringConstant_Ptr)>(address_of(module, "PyModule_AddStringConstant"));
    PyModule_AddType_Ptr = reinterpret_cast<decltype(PyModule_AddType_Ptr)>(address_of(module, "PyModule_AddType"));
    PyModule_Create2_Ptr = reinterpret_cast<decltype(PyModule_Create2_Ptr)>(address_of(module, "PyModule_Create2"));
    PyModule_ExecDef_Ptr = reinterpret_cast<decltype(PyModule_ExecDef_Ptr)>(address_of(module, "PyModule_ExecDef"));
    PyModule_FromDefAndSpec2_Ptr = reinterpret_cast<decltype(PyModule_FromDefAndSpec2_Ptr)>(address_of(module, "PyModule_FromDefAndSpec2"));
    PyModule_GetDef_Ptr = reinterpret_cast<decltype(PyModule_GetDef_Ptr)>(address_of(module, "PyModule_GetDef"));
    PyModule_GetDict_Ptr = reinterpret_cast<decltype(PyModule_GetDict_Ptr)>(address_of(module, "PyModule_GetDict"));
    PyModule_GetFilename_Ptr = reinterpret_cast<decltype(PyModule_GetFilename_Ptr)>(address_of(module, "PyModule_GetFilename"));
    PyModule_GetFilenameObject_Ptr = reinterpret_cast<decltype(PyModule_GetFilenameObject_Ptr)>(address_of(module, "PyModule_GetFilenameObject"));
    PyModule_GetName_Ptr = reinterpret_cast<decltype(PyModule_GetName_Ptr)>(address_of(module, "PyModule_GetName"));
    PyModule_GetNameObject_Ptr = reinterpret_cast<decltype(PyModule_GetNameObject_Ptr)>(address_of(module, "PyModule_GetNameObject"));
    PyModule_GetState_Ptr = reinterpret_cast<decltype(PyModule_GetState_Ptr)>(address_of(module, "PyModule_GetState"));
    PyModule_New_Ptr = reinterpret_cast<decltype(PyModule_New_Ptr)>(address_of(module, "PyModule_New"));
    PyModule_NewObject_Ptr = reinterpret_cast<decltype(PyModule_NewObject_Ptr)>(address_of(module, "PyModule_NewObject"));
    PyModule_SetDocString_Ptr = reinterpret_cast<decltype(PyModule_SetDocString_Ptr)>(address_of(module, "PyModule_SetDocString"));
    PyNumber_Absolute_Ptr = reinterpret_cast<decltype(PyNumber_Absolute_Ptr)>(address_of(module, "PyNumber_Absolute"));
    PyNumber_Add_Ptr = reinterpret_cast<decltype(PyNumber_Add_Ptr)>(address_of(module, "PyNumber_Add"));
    PyNumber_And_Ptr = reinterpret_cast<decltype(PyNumber_And_Ptr)>(address_of(module, "PyNumber_And"));
    PyNumber_AsSsize_t_Ptr = reinterpret_cast<decltype(PyNumber_AsSsize_t_Ptr)>(address_of(module, "PyNumber_AsSsize_t"));
    PyNumber_Check_Ptr = reinterpret_cast<decltype(PyNumber_Check_Ptr)>(address_of(module, "PyNumber_Check"));
    PyNumber_Divmod_Ptr = reinterpret_cast<decltype(PyNumber_Divmod_Ptr)>(address_of(module, "PyNumber_Divmod"));
    PyNumber_Float_Ptr = reinterpret_cast<decltype(PyNumber_Float_Ptr)>(address_of(module, "PyNumber_Float"));
    PyNumber_FloorDivide_Ptr = reinterpret_cast<decltype(PyNumber_FloorDivide_Ptr)>(address_of(module, "PyNumber_FloorDivide"));
    PyNumber_InPlaceAdd_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceAdd_Ptr)>(address_of(module, "PyNumber_InPlaceAdd"));
    PyNumber_InPlaceAnd_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceAnd_Ptr)>(address_of(module, "PyNumber_InPlaceAnd"));
    PyNumber_InPlaceFloorDivide_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceFloorDivide_Ptr)>(address_of(module, "PyNumber_InPlaceFloorDivide"));
    PyNumber_InPlaceLshift_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceLshift_Ptr)>(address_of(module, "PyNumber_InPlaceLshift"));
    PyNumber_InPlaceMatrixMultiply_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceMatrixMultiply_Ptr)>(address_of(module, "PyNumber_InPlaceMatrixMultiply"));
    PyNumber_InPlaceMultiply_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceMultiply_Ptr)>(address_of(module, "PyNumber_InPlaceMultiply"));
    PyNumber_InPlaceOr_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceOr_Ptr)>(address_of(module, "PyNumber_InPlaceOr"));
    PyNumber_InPlacePower_Ptr = reinterpret_cast<decltype(PyNumber_InPlacePower_Ptr)>(address_of(module, "PyNumber_InPlacePower"));
    PyNumber_InPlaceRemainder_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceRemainder_Ptr)>(address_of(module, "PyNumber_InPlaceRemainder"));
    PyNumber_InPlaceRshift_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceRshift_Ptr)>(address_of(module, "PyNumber_InPlaceRshift"));
    PyNumber_InPlaceSubtract_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceSubtract_Ptr)>(address_of(module, "PyNumber_InPlaceSubtract"));
    PyNumber_InPlaceTrueDivide_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceTrueDivide_Ptr)>(address_of(module, "PyNumber_InPlaceTrueDivide"));
    PyNumber_InPlaceXor_Ptr = reinterpret_cast<decltype(PyNumber_InPlaceXor_Ptr)>(address_of(module, "PyNumber_InPlaceXor"));
    PyNumber_Index_Ptr = reinterpret_cast<decltype(PyNumber_Index_Ptr)>(address_of(module, "PyNumber_Index"));
    PyNumber_Invert_Ptr = reinterpret_cast<decltype(PyNumber_Invert_Ptr)>(address_of(module, "PyNumber_Invert"));
    PyNumber_Long_Ptr = reinterpret_cast<decltype(PyNumber_Long_Ptr)>(address_of(module, "PyNumber_Long"));
    PyNumber_Lshift_Ptr = reinterpret_cast<decltype(PyNumber_Lshift_Ptr)>(address_of(module, "PyNumber_Lshift"));
    PyNumber_MatrixMultiply_Ptr = reinterpret_cast<decltype(PyNumber_MatrixMultiply_Ptr)>(address_of(module, "PyNumber_MatrixMultiply"));
    PyNumber_Multiply_Ptr = reinterpret_cast<decltype(PyNumber_Multiply_Ptr)>(address_of(module, "PyNumber_Multiply"));
    PyNumber_Negative_Ptr = reinterpret_cast<decltype(PyNumber_Negative_Ptr)>(address_of(module, "PyNumber_Negative"));
    PyNumber_Or_Ptr = reinterpret_cast<decltype(PyNumber_Or_Ptr)>(address_of(module, "PyNumber_Or"));
    PyNumber_Positive_Ptr = reinterpret_cast<decltype(PyNumber_Positive_Ptr)>(address_of(module, "PyNumber_Positive"));
    PyNumber_Power_Ptr = reinterpret_cast<decltype(PyNumber_Power_Ptr)>(address_of(module, "PyNumber_Power"));
    PyNumber_Remainder_Ptr = reinterpret_cast<decltype(PyNumber_Remainder_Ptr)>(address_of(module, "PyNumber_Remainder"));
    PyNumber_Rshift_Ptr = reinterpret_cast<decltype(PyNumber_Rshift_Ptr)>(address_of(module, "PyNumber_Rshift"));
    PyNumber_Subtract_Ptr = reinterpret_cast<decltype(PyNumber_Subtract_Ptr)>(address_of(module, "PyNumber_Subtract"));
    PyNumber_ToBase_Ptr = reinterpret_cast<decltype(PyNumber_ToBase_Ptr)>(address_of(module, "PyNumber_ToBase"));
    PyNumber_TrueDivide_Ptr = reinterpret_cast<decltype(PyNumber_TrueDivide_Ptr)>(address_of(module, "PyNumber_TrueDivide"));
    PyNumber_Xor_Ptr = reinterpret_cast<decltype(PyNumber_Xor_Ptr)>(address_of(module, "PyNumber_Xor"));
    PyOS_AfterFork_Ptr = reinterpret_cast<decltype(PyOS_AfterFork_Ptr)>(address_of(module, "PyOS_AfterFork"));
    PyOS_AfterFork_Child_Ptr = reinterpret_cast<decltype(PyOS_AfterFork_Child_Ptr)>(address_of(module, "PyOS_AfterFork_Child"));
    PyOS_AfterFork_Parent_Ptr = reinterpret_cast<decltype(PyOS_AfterFork_Parent_Ptr)>(address_of(module, "PyOS_AfterFork_Parent"));
    PyOS_BeforeFork_Ptr = reinterpret_cast<decltype(PyOS_BeforeFork_Ptr)>(address_of(module, "PyOS_BeforeFork"));
    PyOS_FSPath_Ptr = reinterpret_cast<decltype(PyOS_FSPath_Ptr)>(address_of(module, "PyOS_FSPath"));
    PyOS_InterruptOccurred_Ptr = reinterpret_cast<decltype(PyOS_InterruptOccurred_Ptr)>(address_of(module, "PyOS_InterruptOccurred"));
    PyOS_double_to_string_Ptr = reinterpret_cast<decltype(PyOS_double_to_string_Ptr)>(address_of(module, "PyOS_double_to_string"));
    PyOS_getsig_Ptr = reinterpret_cast<decltype(PyOS_getsig_Ptr)>(address_of(module, "PyOS_getsig"));
    PyOS_mystricmp_Ptr = reinterpret_cast<decltype(PyOS_mystricmp_Ptr)>(address_of(module, "PyOS_mystricmp"));
    PyOS_mystrnicmp_Ptr = reinterpret_cast<decltype(PyOS_mystrnicmp_Ptr)>(address_of(module, "PyOS_mystrnicmp"));
    PyOS_setsig_Ptr = reinterpret_cast<decltype(PyOS_setsig_Ptr)>(address_of(module, "PyOS_setsig"));
    PyOS_snprintf_Ptr = reinterpret_cast<decltype(PyOS_snprintf_Ptr)>(address_of(module, "PyOS_snprintf"));
    PyOS_string_to_double_Ptr = reinterpret_cast<decltype(PyOS_string_to_double_Ptr)>(address_of(module, "PyOS_string_to_double"));
    PyOS_strtol_Ptr = reinterpret_cast<decltype(PyOS_strtol_Ptr)>(address_of(module, "PyOS_strtol"));
    PyOS_strtoul_Ptr = reinterpret_cast<decltype(PyOS_strtoul_Ptr)>(address_of(module, "PyOS_strtoul"));
    PyOS_vsnprintf_Ptr = reinterpret_cast<decltype(PyOS_vsnprintf_Ptr)>(address_of(module, "PyOS_vsnprintf"));
    PyObject_ASCII_Ptr = reinterpret_cast<decltype(PyObject_ASCII_Ptr)>(address_of(module, "PyObject_ASCII"));
    PyObject_AsCharBuffer_Ptr = reinterpret_cast<decltype(PyObject_AsCharBuffer_Ptr)>(address_of(module, "PyObject_AsCharBuffer"));
    PyObject_AsFileDescriptor_Ptr = reinterpret_cast<decltype(PyObject_AsFileDescriptor_Ptr)>(address_of(module, "PyObject_AsFileDescriptor"));
    PyObject_AsReadBuffer_Ptr = reinterpret_cast<decltype(PyObject_AsReadBuffer_Ptr)>(address_of(module, "PyObject_AsReadBuffer"));
    PyObject_AsWriteBuffer_Ptr = reinterpret_cast<decltype(PyObject_AsWriteBuffer_Ptr)>(address_of(module, "PyObject_AsWriteBuffer"));
    PyObject_Bytes_Ptr = reinterpret_cast<decltype(PyObject_Bytes_Ptr)>(address_of(module, "PyObject_Bytes"));
    PyObject_Call_Ptr = reinterpret_cast<decltype(PyObject_Call_Ptr)>(address_of(module, "PyObject_Call"));
    PyObject_CallFunction_Ptr = reinterpret_cast<decltype(PyObject_CallFunction_Ptr)>(address_of(module, "PyObject_CallFunction"));
    PyObject_CallFunctionObjArgs_Ptr = reinterpret_cast<decltype(PyObject_CallFunctionObjArgs_Ptr)>(address_of(module, "PyObject_CallFunctionObjArgs"));
    PyObject_CallMethod_Ptr = reinterpret_cast<decltype(PyObject_CallMethod_Ptr)>(address_of(module, "PyObject_CallMethod"));
    PyObject_CallMethodObjArgs_Ptr = reinterpret_cast<decltype(PyObject_CallMethodObjArgs_Ptr)>(address_of(module, "PyObject_CallMethodObjArgs"));
    PyObject_CallNoArgs_Ptr = reinterpret_cast<decltype(PyObject_CallNoArgs_Ptr)>(address_of(module, "PyObject_CallNoArgs"));
    PyObject_CallObject_Ptr = reinterpret_cast<decltype(PyObject_CallObject_Ptr)>(address_of(module, "PyObject_CallObject"));
    PyObject_Calloc_Ptr = reinterpret_cast<decltype(PyObject_Calloc_Ptr)>(address_of(module, "PyObject_Calloc"));
    PyObject_CheckBuffer_Ptr = reinterpret_cast<decltype(PyObject_CheckBuffer_Ptr)>(address_of(module, "PyObject_CheckBuffer"));
    PyObject_CheckReadBuffer_Ptr = reinterpret_cast<decltype(PyObject_CheckReadBuffer_Ptr)>(address_of(module, "PyObject_CheckReadBuffer"));
    PyObject_ClearWeakRefs_Ptr = reinterpret_cast<decltype(PyObject_ClearWeakRefs_Ptr)>(address_of(module, "PyObject_ClearWeakRefs"));
    PyObject_CopyData_Ptr = reinterpret_cast<decltype(PyObject_CopyData_Ptr)>(address_of(module, "PyObject_CopyData"));
    PyObject_DelItem_Ptr = reinterpret_cast<decltype(PyObject_DelItem_Ptr)>(address_of(module, "PyObject_DelItem"));
    PyObject_DelItemString_Ptr = reinterpret_cast<decltype(PyObject_DelItemString_Ptr)>(address_of(module, "PyObject_DelItemString"));
    PyObject_Dir_Ptr = reinterpret_cast<decltype(PyObject_Dir_Ptr)>(address_of(module, "PyObject_Dir"));
    PyObject_Format_Ptr = reinterpret_cast<decltype(PyObject_Format_Ptr)>(address_of(module, "PyObject_Format"));
    PyObject_Free_Ptr = reinterpret_cast<decltype(PyObject_Free_Ptr)>(address_of(module, "PyObject_Free"));
    PyObject_GC_Del_Ptr = reinterpret_cast<decltype(PyObject_GC_Del_Ptr)>(address_of(module, "PyObject_GC_Del"));
    PyObject_GC_IsFinalized_Ptr = reinterpret_cast<decltype(PyObject_GC_IsFinalized_Ptr)>(address_of(module, "PyObject_GC_IsFinalized"));
    PyObject_GC_IsTracked_Ptr = reinterpret_cast<decltype(PyObject_GC_IsTracked_Ptr)>(address_of(module, "PyObject_GC_IsTracked"));
    PyObject_GC_Track_Ptr = reinterpret_cast<decltype(PyObject_GC_Track_Ptr)>(address_of(module, "PyObject_GC_Track"));
    PyObject_GC_UnTrack_Ptr = reinterpret_cast<decltype(PyObject_GC_UnTrack_Ptr)>(address_of(module, "PyObject_GC_UnTrack"));
    PyObject_GenericGetAttr_Ptr = reinterpret_cast<decltype(PyObject_GenericGetAttr_Ptr)>(address_of(module, "PyObject_GenericGetAttr"));
    PyObject_GenericGetDict_Ptr = reinterpret_cast<decltype(PyObject_GenericGetDict_Ptr)>(address_of(module, "PyObject_GenericGetDict"));
    PyObject_GenericSetAttr_Ptr = reinterpret_cast<decltype(PyObject_GenericSetAttr_Ptr)>(address_of(module, "PyObject_GenericSetAttr"));
    PyObject_GenericSetDict_Ptr = reinterpret_cast<decltype(PyObject_GenericSetDict_Ptr)>(address_of(module, "PyObject_GenericSetDict"));
    PyObject_GetAIter_Ptr = reinterpret_cast<decltype(PyObject_GetAIter_Ptr)>(address_of(module, "PyObject_GetAIter"));
    PyObject_GetAttr_Ptr = reinterpret_cast<decltype(PyObject_GetAttr_Ptr)>(address_of(module, "PyObject_GetAttr"));
    PyObject_GetAttrString_Ptr = reinterpret_cast<decltype(PyObject_GetAttrString_Ptr)>(address_of(module, "PyObject_GetAttrString"));
    #if HAS_PYTHON_VERSION(0x030B1000)
    PyObject_GetBuffer_Ptr = reinterpret_cast<decltype(PyObject_GetBuffer_Ptr)>(address_of(module, "PyObject_GetBuffer"));
    #endif
    PyObject_GetItem_Ptr = reinterpret_cast<decltype(PyObject_GetItem_Ptr)>(address_of(module, "PyObject_GetItem"));
    PyObject_GetIter_Ptr = reinterpret_cast<decltype(PyObject_GetIter_Ptr)>(address_of(module, "PyObject_GetIter"));
    PyObject_HasAttr_Ptr = reinterpret_cast<decltype(PyObject_HasAttr_Ptr)>(address_of(module, "PyObject_HasAttr"));
    PyObject_HasAttrString_Ptr = reinterpret_cast<decltype(PyObject_HasAttrString_Ptr)>(address_of(module, "PyObject_HasAttrString"));
    PyObject_Hash_Ptr = reinterpret_cast<decltype(PyObject_Hash_Ptr)>(address_of(module, "PyObject_Hash"));
    PyObject_HashNotImplemented_Ptr = reinterpret_cast<decltype(PyObject_HashNotImplemented_Ptr)>(address_of(module, "PyObject_HashNotImplemented"));
    PyObject_Init_Ptr = reinterpret_cast<decltype(PyObject_Init_Ptr)>(address_of(module, "PyObject_Init"));
    PyObject_InitVar_Ptr = reinterpret_cast<decltype(PyObject_InitVar_Ptr)>(address_of(module, "PyObject_InitVar"));
    PyObject_IsInstance_Ptr = reinterpret_cast<decltype(PyObject_IsInstance_Ptr)>(address_of(module, "PyObject_IsInstance"));
    PyObject_IsSubclass_Ptr = reinterpret_cast<decltype(PyObject_IsSubclass_Ptr)>(address_of(module, "PyObject_IsSubclass"));
    PyObject_IsTrue_Ptr = reinterpret_cast<decltype(PyObject_IsTrue_Ptr)>(address_of(module, "PyObject_IsTrue"));
    PyObject_Length_Ptr = reinterpret_cast<decltype(PyObject_Length_Ptr)>(address_of(module, "PyObject_Length"));
    PyObject_Malloc_Ptr = reinterpret_cast<decltype(PyObject_Malloc_Ptr)>(address_of(module, "PyObject_Malloc"));
    PyObject_Not_Ptr = reinterpret_cast<decltype(PyObject_Not_Ptr)>(address_of(module, "PyObject_Not"));
    PyObject_Realloc_Ptr = reinterpret_cast<decltype(PyObject_Realloc_Ptr)>(address_of(module, "PyObject_Realloc"));
    PyObject_Repr_Ptr = reinterpret_cast<decltype(PyObject_Repr_Ptr)>(address_of(module, "PyObject_Repr"));
    PyObject_RichCompare_Ptr = reinterpret_cast<decltype(PyObject_RichCompare_Ptr)>(address_of(module, "PyObject_RichCompare"));
    PyObject_RichCompareBool_Ptr = reinterpret_cast<decltype(PyObject_RichCompareBool_Ptr)>(address_of(module, "PyObject_RichCompareBool"));
    PyObject_SelfIter_Ptr = reinterpret_cast<decltype(PyObject_SelfIter_Ptr)>(address_of(module, "PyObject_SelfIter"));
    PyObject_SetAttr_Ptr = reinterpret_cast<decltype(PyObject_SetAttr_Ptr)>(address_of(module, "PyObject_SetAttr"));
    PyObject_SetAttrString_Ptr = reinterpret_cast<decltype(PyObject_SetAttrString_Ptr)>(address_of(module, "PyObject_SetAttrString"));
    PyObject_SetItem_Ptr = reinterpret_cast<decltype(PyObject_SetItem_Ptr)>(address_of(module, "PyObject_SetItem"));
    PyObject_Size_Ptr = reinterpret_cast<decltype(PyObject_Size_Ptr)>(address_of(module, "PyObject_Size"));
    PyObject_Str_Ptr = reinterpret_cast<decltype(PyObject_Str_Ptr)>(address_of(module, "PyObject_Str"));
    PyObject_Type_Ptr = reinterpret_cast<decltype(PyObject_Type_Ptr)>(address_of(module, "PyObject_Type"));
    #if HAS_PYTHON_VERSION(0x030B0000)
    PyObject_TypeCheck_Ptr = reinterpret_cast<decltype(PyObject_TypeCheck_Ptr)>(address_of(module, "PyObject_TypeCheck"));
    #endif
    PySeqIter_New_Ptr = reinterpret_cast<decltype(PySeqIter_New_Ptr)>(address_of(module, "PySeqIter_New"));
    PySequence_Check_Ptr = reinterpret_cast<decltype(PySequence_Check_Ptr)>(address_of(module, "PySequence_Check"));
    PySequence_Concat_Ptr = reinterpret_cast<decltype(PySequence_Concat_Ptr)>(address_of(module, "PySequence_Concat"));
    PySequence_Contains_Ptr = reinterpret_cast<decltype(PySequence_Contains_Ptr)>(address_of(module, "PySequence_Contains"));
    PySequence_Count_Ptr = reinterpret_cast<decltype(PySequence_Count_Ptr)>(address_of(module, "PySequence_Count"));
    PySequence_DelItem_Ptr = reinterpret_cast<decltype(PySequence_DelItem_Ptr)>(address_of(module, "PySequence_DelItem"));
    PySequence_DelSlice_Ptr = reinterpret_cast<decltype(PySequence_DelSlice_Ptr)>(address_of(module, "PySequence_DelSlice"));
    PySequence_Fast_Ptr = reinterpret_cast<decltype(PySequence_Fast_Ptr)>(address_of(module, "PySequence_Fast"));
    PySequence_GetItem_Ptr = reinterpret_cast<decltype(PySequence_GetItem_Ptr)>(address_of(module, "PySequence_GetItem"));
    PySequence_GetSlice_Ptr = reinterpret_cast<decltype(PySequence_GetSlice_Ptr)>(address_of(module, "PySequence_GetSlice"));
    PySequence_In_Ptr = reinterpret_cast<decltype(PySequence_In_Ptr)>(address_of(module, "PySequence_In"));
    PySequence_InPlaceConcat_Ptr = reinterpret_cast<decltype(PySequence_InPlaceConcat_Ptr)>(address_of(module, "PySequence_InPlaceConcat"));
    PySequence_InPlaceRepeat_Ptr = reinterpret_cast<decltype(PySequence_InPlaceRepeat_Ptr)>(address_of(module, "PySequence_InPlaceRepeat"));
    PySequence_Index_Ptr = reinterpret_cast<decltype(PySequence_Index_Ptr)>(address_of(module, "PySequence_Index"));
    PySequence_Length_Ptr = reinterpret_cast<decltype(PySequence_Length_Ptr)>(address_of(module, "PySequence_Length"));
    PySequence_List_Ptr = reinterpret_cast<decltype(PySequence_List_Ptr)>(address_of(module, "PySequence_List"));
    PySequence_Repeat_Ptr = reinterpret_cast<decltype(PySequence_Repeat_Ptr)>(address_of(module, "PySequence_Repeat"));
    PySequence_SetItem_Ptr = reinterpret_cast<decltype(PySequence_SetItem_Ptr)>(address_of(module, "PySequence_SetItem"));
    PySequence_SetSlice_Ptr = reinterpret_cast<decltype(PySequence_SetSlice_Ptr)>(address_of(module, "PySequence_SetSlice"));
    PySequence_Size_Ptr = reinterpret_cast<decltype(PySequence_Size_Ptr)>(address_of(module, "PySequence_Size"));
    PySequence_Tuple_Ptr = reinterpret_cast<decltype(PySequence_Tuple_Ptr)>(address_of(module, "PySequence_Tuple"));
    PySet_Add_Ptr = reinterpret_cast<decltype(PySet_Add_Ptr)>(address_of(module, "PySet_Add"));
    PySet_Clear_Ptr = reinterpret_cast<decltype(PySet_Clear_Ptr)>(address_of(module, "PySet_Clear"));
    PySet_Contains_Ptr = reinterpret_cast<decltype(PySet_Contains_Ptr)>(address_of(module, "PySet_Contains"));
    PySet_Discard_Ptr = reinterpret_cast<decltype(PySet_Discard_Ptr)>(address_of(module, "PySet_Discard"));
    PySet_New_Ptr = reinterpret_cast<decltype(PySet_New_Ptr)>(address_of(module, "PySet_New"));
    PySet_Pop_Ptr = reinterpret_cast<decltype(PySet_Pop_Ptr)>(address_of(module, "PySet_Pop"));
    PySet_Size_Ptr = reinterpret_cast<decltype(PySet_Size_Ptr)>(address_of(module, "PySet_Size"));
    PySlice_AdjustIndices_Ptr = reinterpret_cast<decltype(PySlice_AdjustIndices_Ptr)>(address_of(module, "PySlice_AdjustIndices"));
    PySlice_GetIndices_Ptr = reinterpret_cast<decltype(PySlice_GetIndices_Ptr)>(address_of(module, "PySlice_GetIndices"));
    #ifdef PySlice_GetIndicesEx
    PySlice_GetIndicesEx_Ptr = reinterpret_cast<decltype(PySlice_GetIndicesEx_Ptr)>(address_of(module, "PySlice_GetIndicesEx"));
    #endif
    PySlice_New_Ptr = reinterpret_cast<decltype(PySlice_New_Ptr)>(address_of(module, "PySlice_New"));
    PySlice_Unpack_Ptr = reinterpret_cast<decltype(PySlice_Unpack_Ptr)>(address_of(module, "PySlice_Unpack"));
    PyState_AddModule_Ptr = reinterpret_cast<decltype(PyState_AddModule_Ptr)>(address_of(module, "PyState_AddModule"));
    PyState_FindModule_Ptr = reinterpret_cast<decltype(PyState_FindModule_Ptr)>(address_of(module, "PyState_FindModule"));
    PyState_RemoveModule_Ptr = reinterpret_cast<decltype(PyState_RemoveModule_Ptr)>(address_of(module, "PyState_RemoveModule"));
    PyStructSequence_GetItem_Ptr = reinterpret_cast<decltype(PyStructSequence_GetItem_Ptr)>(address_of(module, "PyStructSequence_GetItem"));
    PyStructSequence_New_Ptr = reinterpret_cast<decltype(PyStructSequence_New_Ptr)>(address_of(module, "PyStructSequence_New"));
    PyStructSequence_NewType_Ptr = reinterpret_cast<decltype(PyStructSequence_NewType_Ptr)>(address_of(module, "PyStructSequence_NewType"));
    PyStructSequence_SetItem_Ptr = reinterpret_cast<decltype(PyStructSequence_SetItem_Ptr)>(address_of(module, "PyStructSequence_SetItem"));
    PySys_AddWarnOption_Ptr = reinterpret_cast<decltype(PySys_AddWarnOption_Ptr)>(address_of(module, "PySys_AddWarnOption"));
    PySys_AddWarnOptionUnicode_Ptr = reinterpret_cast<decltype(PySys_AddWarnOptionUnicode_Ptr)>(address_of(module, "PySys_AddWarnOptionUnicode"));
    PySys_AddXOption_Ptr = reinterpret_cast<decltype(PySys_AddXOption_Ptr)>(address_of(module, "PySys_AddXOption"));
    PySys_FormatStderr_Ptr = reinterpret_cast<decltype(PySys_FormatStderr_Ptr)>(address_of(module, "PySys_FormatStderr"));
    PySys_FormatStdout_Ptr = reinterpret_cast<decltype(PySys_FormatStdout_Ptr)>(address_of(module, "PySys_FormatStdout"));
    PySys_GetObject_Ptr = reinterpret_cast<decltype(PySys_GetObject_Ptr)>(address_of(module, "PySys_GetObject"));
    PySys_GetXOptions_Ptr = reinterpret_cast<decltype(PySys_GetXOptions_Ptr)>(address_of(module, "PySys_GetXOptions"));
    PySys_HasWarnOptions_Ptr = reinterpret_cast<decltype(PySys_HasWarnOptions_Ptr)>(address_of(module, "PySys_HasWarnOptions"));
    PySys_ResetWarnOptions_Ptr = reinterpret_cast<decltype(PySys_ResetWarnOptions_Ptr)>(address_of(module, "PySys_ResetWarnOptions"));
    PySys_SetArgv_Ptr = reinterpret_cast<decltype(PySys_SetArgv_Ptr)>(address_of(module, "PySys_SetArgv"));
    PySys_SetArgvEx_Ptr = reinterpret_cast<decltype(PySys_SetArgvEx_Ptr)>(address_of(module, "PySys_SetArgvEx"));
    PySys_SetObject_Ptr = reinterpret_cast<decltype(PySys_SetObject_Ptr)>(address_of(module, "PySys_SetObject"));
    PySys_SetPath_Ptr = reinterpret_cast<decltype(PySys_SetPath_Ptr)>(address_of(module, "PySys_SetPath"));
    PySys_WriteStderr_Ptr = reinterpret_cast<decltype(PySys_WriteStderr_Ptr)>(address_of(module, "PySys_WriteStderr"));
    PySys_WriteStdout_Ptr = reinterpret_cast<decltype(PySys_WriteStdout_Ptr)>(address_of(module, "PySys_WriteStdout"));
    PyThreadState_Clear_Ptr = reinterpret_cast<decltype(PyThreadState_Clear_Ptr)>(address_of(module, "PyThreadState_Clear"));
    PyThreadState_Delete_Ptr = reinterpret_cast<decltype(PyThreadState_Delete_Ptr)>(address_of(module, "PyThreadState_Delete"));
    PyThreadState_Get_Ptr = reinterpret_cast<decltype(PyThreadState_Get_Ptr)>(address_of(module, "PyThreadState_Get"));
    PyThreadState_GetDict_Ptr = reinterpret_cast<decltype(PyThreadState_GetDict_Ptr)>(address_of(module, "PyThreadState_GetDict"));
    #if HAS_PYTHON_VERSION(0x03090000)
    PyThreadState_GetFrame_Ptr = reinterpret_cast<decltype(PyThreadState_GetFrame_Ptr)>(address_of(module, "PyThreadState_GetFrame"));
    #endif
    PyThreadState_GetID_Ptr = reinterpret_cast<decltype(PyThreadState_GetID_Ptr)>(address_of(module, "PyThreadState_GetID"));
    PyThreadState_GetInterpreter_Ptr = reinterpret_cast<decltype(PyThreadState_GetInterpreter_Ptr)>(address_of(module, "PyThreadState_GetInterpreter"));
    PyThreadState_New_Ptr = reinterpret_cast<decltype(PyThreadState_New_Ptr)>(address_of(module, "PyThreadState_New"));
    PyThreadState_SetAsyncExc_Ptr = reinterpret_cast<decltype(PyThreadState_SetAsyncExc_Ptr)>(address_of(module, "PyThreadState_SetAsyncExc"));
    PyThreadState_Swap_Ptr = reinterpret_cast<decltype(PyThreadState_Swap_Ptr)>(address_of(module, "PyThreadState_Swap"));
    PyThread_GetInfo_Ptr = reinterpret_cast<decltype(PyThread_GetInfo_Ptr)>(address_of(module, "PyThread_GetInfo"));
    PyThread_ReInitTLS_Ptr = reinterpret_cast<decltype(PyThread_ReInitTLS_Ptr)>(address_of(module, "PyThread_ReInitTLS"));
    PyThread_acquire_lock_Ptr = reinterpret_cast<decltype(PyThread_acquire_lock_Ptr)>(address_of(module, "PyThread_acquire_lock"));
    PyThread_acquire_lock_timed_Ptr = reinterpret_cast<decltype(PyThread_acquire_lock_timed_Ptr)>(address_of(module, "PyThread_acquire_lock_timed"));
    PyThread_allocate_lock_Ptr = reinterpret_cast<decltype(PyThread_allocate_lock_Ptr)>(address_of(module, "PyThread_allocate_lock"));
    PyThread_create_key_Ptr = reinterpret_cast<decltype(PyThread_create_key_Ptr)>(address_of(module, "PyThread_create_key"));
    PyThread_delete_key_Ptr = reinterpret_cast<decltype(PyThread_delete_key_Ptr)>(address_of(module, "PyThread_delete_key"));
    PyThread_delete_key_value_Ptr = reinterpret_cast<decltype(PyThread_delete_key_value_Ptr)>(address_of(module, "PyThread_delete_key_value"));
    PyThread_exit_thread_Ptr = reinterpret_cast<decltype(PyThread_exit_thread_Ptr)>(address_of(module, "PyThread_exit_thread"));
    PyThread_free_lock_Ptr = reinterpret_cast<decltype(PyThread_free_lock_Ptr)>(address_of(module, "PyThread_free_lock"));
    PyThread_get_key_value_Ptr = reinterpret_cast<decltype(PyThread_get_key_value_Ptr)>(address_of(module, "PyThread_get_key_value"));
    PyThread_get_stacksize_Ptr = reinterpret_cast<decltype(PyThread_get_stacksize_Ptr)>(address_of(module, "PyThread_get_stacksize"));
    PyThread_get_thread_ident_Ptr = reinterpret_cast<decltype(PyThread_get_thread_ident_Ptr)>(address_of(module, "PyThread_get_thread_ident"));
    PyThread_get_thread_native_id_Ptr = reinterpret_cast<decltype(PyThread_get_thread_native_id_Ptr)>(address_of(module, "PyThread_get_thread_native_id"));
    PyThread_init_thread_Ptr = reinterpret_cast<decltype(PyThread_init_thread_Ptr)>(address_of(module, "PyThread_init_thread"));
    PyThread_release_lock_Ptr = reinterpret_cast<decltype(PyThread_release_lock_Ptr)>(address_of(module, "PyThread_release_lock"));
    PyThread_set_key_value_Ptr = reinterpret_cast<decltype(PyThread_set_key_value_Ptr)>(address_of(module, "PyThread_set_key_value"));
    PyThread_set_stacksize_Ptr = reinterpret_cast<decltype(PyThread_set_stacksize_Ptr)>(address_of(module, "PyThread_set_stacksize"));
    PyThread_start_new_thread_Ptr = reinterpret_cast<decltype(PyThread_start_new_thread_Ptr)>(address_of(module, "PyThread_start_new_thread"));
    #if HAS_PYTHON_VERSION(0x03070000)
    PyThread_tss_alloc_Ptr = reinterpret_cast<decltype(PyThread_tss_alloc_Ptr)>(address_of(module, "PyThread_tss_alloc"));
    PyThread_tss_create_Ptr = reinterpret_cast<decltype(PyThread_tss_create_Ptr)>(address_of(module, "PyThread_tss_create"));
    PyThread_tss_delete_Ptr = reinterpret_cast<decltype(PyThread_tss_delete_Ptr)>(address_of(module, "PyThread_tss_delete"));
    PyThread_tss_free_Ptr = reinterpret_cast<decltype(PyThread_tss_free_Ptr)>(address_of(module, "PyThread_tss_free"));
    PyThread_tss_get_Ptr = reinterpret_cast<decltype(PyThread_tss_get_Ptr)>(address_of(module, "PyThread_tss_get"));
    PyThread_tss_is_created_Ptr = reinterpret_cast<decltype(PyThread_tss_is_created_Ptr)>(address_of(module, "PyThread_tss_is_created"));
    PyThread_tss_set_Ptr = reinterpret_cast<decltype(PyThread_tss_set_Ptr)>(address_of(module, "PyThread_tss_set"));
    #endif
    #if HAS_PYTHON_VERSION(0x03090000)
    PyTraceBack_Here_Ptr = reinterpret_cast<decltype(PyTraceBack_Here_Ptr)>(address_of(module, "PyTraceBack_Here"));
    #endif
    PyTraceBack_Print_Ptr = reinterpret_cast<decltype(PyTraceBack_Print_Ptr)>(address_of(module, "PyTraceBack_Print"));
    PyTuple_GetItem_Ptr = reinterpret_cast<decltype(PyTuple_GetItem_Ptr)>(address_of(module, "PyTuple_GetItem"));
    PyTuple_GetSlice_Ptr = reinterpret_cast<decltype(PyTuple_GetSlice_Ptr)>(address_of(module, "PyTuple_GetSlice"));
    PyTuple_New_Ptr = reinterpret_cast<decltype(PyTuple_New_Ptr)>(address_of(module, "PyTuple_New"));
    PyTuple_Pack_Ptr = reinterpret_cast<decltype(PyTuple_Pack_Ptr)>(address_of(module, "PyTuple_Pack"));
    PyTuple_SetItem_Ptr = reinterpret_cast<decltype(PyTuple_SetItem_Ptr)>(address_of(module, "PyTuple_SetItem"));
    PyTuple_Size_Ptr = reinterpret_cast<decltype(PyTuple_Size_Ptr)>(address_of(module, "PyTuple_Size"));
    PyType_ClearCache_Ptr = reinterpret_cast<decltype(PyType_ClearCache_Ptr)>(address_of(module, "PyType_ClearCache"));
    #if !defined(Py_LIMITED_API)
    PyType_Name_Ptr = reinterpret_cast<decltype(PyType_Name_Ptr)>(address_of(module, "_PyType_Name"));
    #endif
    PyType_FromModuleAndSpec_Ptr = reinterpret_cast<decltype(PyType_FromModuleAndSpec_Ptr)>(address_of(module, "PyType_FromModuleAndSpec"));
    PyType_FromSpec_Ptr = reinterpret_cast<decltype(PyType_FromSpec_Ptr)>(address_of(module, "PyType_FromSpec"));
    PyType_FromSpecWithBases_Ptr = reinterpret_cast<decltype(PyType_FromSpecWithBases_Ptr)>(address_of(module, "PyType_FromSpecWithBases"));
    PyType_GenericAlloc_Ptr = reinterpret_cast<decltype(PyType_GenericAlloc_Ptr)>(address_of(module, "PyType_GenericAlloc"));
    PyType_GenericNew_Ptr = reinterpret_cast<decltype(PyType_GenericNew_Ptr)>(address_of(module, "PyType_GenericNew"));
    PyType_GetFlags_Ptr = reinterpret_cast<decltype(PyType_GetFlags_Ptr)>(address_of(module, "PyType_GetFlags"));
    PyType_GetModule_Ptr = reinterpret_cast<decltype(PyType_GetModule_Ptr)>(address_of(module, "PyType_GetModule"));
    PyType_GetModuleState_Ptr = reinterpret_cast<decltype(PyType_GetModuleState_Ptr)>(address_of(module, "PyType_GetModuleState"));
    PyType_GetName_Ptr = reinterpret_cast<decltype(PyType_GetName_Ptr)>(address_of(module, "PyType_GetName"));
    PyType_GetQualName_Ptr = reinterpret_cast<decltype(PyType_GetQualName_Ptr)>(address_of(module, "PyType_GetQualName"));
    PyType_GetSlot_Ptr = reinterpret_cast<decltype(PyType_GetSlot_Ptr)>(address_of(module, "PyType_GetSlot"));
    PyType_IsSubtype_Ptr = reinterpret_cast<decltype(PyType_IsSubtype_Ptr)>(address_of(module, "PyType_IsSubtype"));
    PyType_Modified_Ptr = reinterpret_cast<decltype(PyType_Modified_Ptr)>(address_of(module, "PyType_Modified"));
    PyType_Ready_Ptr = reinterpret_cast<decltype(PyType_Ready_Ptr)>(address_of(module, "PyType_Ready"));
    PyUnicodeDecodeError_Create_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_Create_Ptr)>(address_of(module, "PyUnicodeDecodeError_Create"));
    PyUnicodeDecodeError_GetEncoding_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_GetEncoding_Ptr)>(address_of(module, "PyUnicodeDecodeError_GetEncoding"));
    PyUnicodeDecodeError_GetEnd_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_GetEnd_Ptr)>(address_of(module, "PyUnicodeDecodeError_GetEnd"));
    PyUnicodeDecodeError_GetObject_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_GetObject_Ptr)>(address_of(module, "PyUnicodeDecodeError_GetObject"));
    PyUnicodeDecodeError_GetReason_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_GetReason_Ptr)>(address_of(module, "PyUnicodeDecodeError_GetReason"));
    PyUnicodeDecodeError_GetStart_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_GetStart_Ptr)>(address_of(module, "PyUnicodeDecodeError_GetStart"));
    PyUnicodeDecodeError_SetEnd_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_SetEnd_Ptr)>(address_of(module, "PyUnicodeDecodeError_SetEnd"));
    PyUnicodeDecodeError_SetReason_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_SetReason_Ptr)>(address_of(module, "PyUnicodeDecodeError_SetReason"));
    PyUnicodeDecodeError_SetStart_Ptr = reinterpret_cast<decltype(PyUnicodeDecodeError_SetStart_Ptr)>(address_of(module, "PyUnicodeDecodeError_SetStart"));
    PyUnicodeEncodeError_GetEncoding_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_GetEncoding_Ptr)>(address_of(module, "PyUnicodeEncodeError_GetEncoding"));
    PyUnicodeEncodeError_GetEnd_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_GetEnd_Ptr)>(address_of(module, "PyUnicodeEncodeError_GetEnd"));
    PyUnicodeEncodeError_GetObject_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_GetObject_Ptr)>(address_of(module, "PyUnicodeEncodeError_GetObject"));
    PyUnicodeEncodeError_GetReason_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_GetReason_Ptr)>(address_of(module, "PyUnicodeEncodeError_GetReason"));
    PyUnicodeEncodeError_GetStart_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_GetStart_Ptr)>(address_of(module, "PyUnicodeEncodeError_GetStart"));
    PyUnicodeEncodeError_SetEnd_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_SetEnd_Ptr)>(address_of(module, "PyUnicodeEncodeError_SetEnd"));
    PyUnicodeEncodeError_SetReason_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_SetReason_Ptr)>(address_of(module, "PyUnicodeEncodeError_SetReason"));
    PyUnicodeEncodeError_SetStart_Ptr = reinterpret_cast<decltype(PyUnicodeEncodeError_SetStart_Ptr)>(address_of(module, "PyUnicodeEncodeError_SetStart"));
    PyUnicodeTranslateError_GetEnd_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_GetEnd_Ptr)>(address_of(module, "PyUnicodeTranslateError_GetEnd"));
    PyUnicodeTranslateError_GetObject_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_GetObject_Ptr)>(address_of(module, "PyUnicodeTranslateError_GetObject"));
    PyUnicodeTranslateError_GetReason_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_GetReason_Ptr)>(address_of(module, "PyUnicodeTranslateError_GetReason"));
    PyUnicodeTranslateError_GetStart_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_GetStart_Ptr)>(address_of(module, "PyUnicodeTranslateError_GetStart"));
    PyUnicodeTranslateError_SetEnd_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_SetEnd_Ptr)>(address_of(module, "PyUnicodeTranslateError_SetEnd"));
    PyUnicodeTranslateError_SetReason_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_SetReason_Ptr)>(address_of(module, "PyUnicodeTranslateError_SetReason"));
    PyUnicodeTranslateError_SetStart_Ptr = reinterpret_cast<decltype(PyUnicodeTranslateError_SetStart_Ptr)>(address_of(module, "PyUnicodeTranslateError_SetStart"));
    PyUnicode_Append_Ptr = reinterpret_cast<decltype(PyUnicode_Append_Ptr)>(address_of(module, "PyUnicode_Append"));
    PyUnicode_AppendAndDel_Ptr = reinterpret_cast<decltype(PyUnicode_AppendAndDel_Ptr)>(address_of(module, "PyUnicode_AppendAndDel"));
    PyUnicode_AsASCIIString_Ptr = reinterpret_cast<decltype(PyUnicode_AsASCIIString_Ptr)>(address_of(module, "PyUnicode_AsASCIIString"));
    PyUnicode_AsCharmapString_Ptr = reinterpret_cast<decltype(PyUnicode_AsCharmapString_Ptr)>(address_of(module, "PyUnicode_AsCharmapString"));
    PyUnicode_AsDecodedObject_Ptr = reinterpret_cast<decltype(PyUnicode_AsDecodedObject_Ptr)>(address_of(module, "PyUnicode_AsDecodedObject"));
    PyUnicode_AsDecodedUnicode_Ptr = reinterpret_cast<decltype(PyUnicode_AsDecodedUnicode_Ptr)>(address_of(module, "PyUnicode_AsDecodedUnicode"));
    PyUnicode_AsEncodedObject_Ptr = reinterpret_cast<decltype(PyUnicode_AsEncodedObject_Ptr)>(address_of(module, "PyUnicode_AsEncodedObject"));
    PyUnicode_AsEncodedString_Ptr = reinterpret_cast<decltype(PyUnicode_AsEncodedString_Ptr)>(address_of(module, "PyUnicode_AsEncodedString"));
    PyUnicode_AsEncodedUnicode_Ptr = reinterpret_cast<decltype(PyUnicode_AsEncodedUnicode_Ptr)>(address_of(module, "PyUnicode_AsEncodedUnicode"));
    PyUnicode_AsLatin1String_Ptr = reinterpret_cast<decltype(PyUnicode_AsLatin1String_Ptr)>(address_of(module, "PyUnicode_AsLatin1String"));
    PyUnicode_AsRawUnicodeEscapeString_Ptr = reinterpret_cast<decltype(PyUnicode_AsRawUnicodeEscapeString_Ptr)>(address_of(module, "PyUnicode_AsRawUnicodeEscapeString"));
    PyUnicode_AsUCS4_Ptr = reinterpret_cast<decltype(PyUnicode_AsUCS4_Ptr)>(address_of(module, "PyUnicode_AsUCS4"));
    PyUnicode_AsUCS4Copy_Ptr = reinterpret_cast<decltype(PyUnicode_AsUCS4Copy_Ptr)>(address_of(module, "PyUnicode_AsUCS4Copy"));
    PyUnicode_AsUTF16String_Ptr = reinterpret_cast<decltype(PyUnicode_AsUTF16String_Ptr)>(address_of(module, "PyUnicode_AsUTF16String"));
    PyUnicode_AsUTF32String_Ptr = reinterpret_cast<decltype(PyUnicode_AsUTF32String_Ptr)>(address_of(module, "PyUnicode_AsUTF32String"));
    PyUnicode_AsUTF8AndSize_Ptr = reinterpret_cast<decltype(PyUnicode_AsUTF8AndSize_Ptr)>(address_of(module, "PyUnicode_AsUTF8AndSize"));
    PyUnicode_AsUTF8String_Ptr = reinterpret_cast<decltype(PyUnicode_AsUTF8String_Ptr)>(address_of(module, "PyUnicode_AsUTF8String"));
    PyUnicode_AsUnicodeEscapeString_Ptr = reinterpret_cast<decltype(PyUnicode_AsUnicodeEscapeString_Ptr)>(address_of(module, "PyUnicode_AsUnicodeEscapeString"));
    PyUnicode_AsWideChar_Ptr = reinterpret_cast<decltype(PyUnicode_AsWideChar_Ptr)>(address_of(module, "PyUnicode_AsWideChar"));
    PyUnicode_AsWideCharString_Ptr = reinterpret_cast<decltype(PyUnicode_AsWideCharString_Ptr)>(address_of(module, "PyUnicode_AsWideCharString"));
    PyUnicode_BuildEncodingMap_Ptr = reinterpret_cast<decltype(PyUnicode_BuildEncodingMap_Ptr)>(address_of(module, "PyUnicode_BuildEncodingMap"));
    PyUnicode_Compare_Ptr = reinterpret_cast<decltype(PyUnicode_Compare_Ptr)>(address_of(module, "PyUnicode_Compare"));
    PyUnicode_CompareWithASCIIString_Ptr = reinterpret_cast<decltype(PyUnicode_CompareWithASCIIString_Ptr)>(address_of(module, "PyUnicode_CompareWithASCIIString"));
    PyUnicode_Concat_Ptr = reinterpret_cast<decltype(PyUnicode_Concat_Ptr)>(address_of(module, "PyUnicode_Concat"));
    PyUnicode_Contains_Ptr = reinterpret_cast<decltype(PyUnicode_Contains_Ptr)>(address_of(module, "PyUnicode_Contains"));
    PyUnicode_Count_Ptr = reinterpret_cast<decltype(PyUnicode_Count_Ptr)>(address_of(module, "PyUnicode_Count"));
    PyUnicode_Decode_Ptr = reinterpret_cast<decltype(PyUnicode_Decode_Ptr)>(address_of(module, "PyUnicode_Decode"));
    PyUnicode_DecodeASCII_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeASCII_Ptr)>(address_of(module, "PyUnicode_DecodeASCII"));
    PyUnicode_DecodeCharmap_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeCharmap_Ptr)>(address_of(module, "PyUnicode_DecodeCharmap"));
    PyUnicode_DecodeFSDefault_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeFSDefault_Ptr)>(address_of(module, "PyUnicode_DecodeFSDefault"));
    PyUnicode_DecodeFSDefaultAndSize_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeFSDefaultAndSize_Ptr)>(address_of(module, "PyUnicode_DecodeFSDefaultAndSize"));
    PyUnicode_DecodeLatin1_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeLatin1_Ptr)>(address_of(module, "PyUnicode_DecodeLatin1"));
    PyUnicode_DecodeLocale_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeLocale_Ptr)>(address_of(module, "PyUnicode_DecodeLocale"));
    PyUnicode_DecodeLocaleAndSize_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeLocaleAndSize_Ptr)>(address_of(module, "PyUnicode_DecodeLocaleAndSize"));
    PyUnicode_DecodeRawUnicodeEscape_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeRawUnicodeEscape_Ptr)>(address_of(module, "PyUnicode_DecodeRawUnicodeEscape"));
    PyUnicode_DecodeUTF16_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF16_Ptr)>(address_of(module, "PyUnicode_DecodeUTF16"));
    PyUnicode_DecodeUTF16Stateful_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF16Stateful_Ptr)>(address_of(module, "PyUnicode_DecodeUTF16Stateful"));
    PyUnicode_DecodeUTF32_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF32_Ptr)>(address_of(module, "PyUnicode_DecodeUTF32"));
    PyUnicode_DecodeUTF32Stateful_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF32Stateful_Ptr)>(address_of(module, "PyUnicode_DecodeUTF32Stateful"));
    PyUnicode_DecodeUTF7_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF7_Ptr)>(address_of(module, "PyUnicode_DecodeUTF7"));
    PyUnicode_DecodeUTF7Stateful_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF7Stateful_Ptr)>(address_of(module, "PyUnicode_DecodeUTF7Stateful"));
    PyUnicode_DecodeUTF8_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF8_Ptr)>(address_of(module, "PyUnicode_DecodeUTF8"));
    PyUnicode_DecodeUTF8Stateful_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUTF8Stateful_Ptr)>(address_of(module, "PyUnicode_DecodeUTF8Stateful"));
    PyUnicode_DecodeUnicodeEscape_Ptr = reinterpret_cast<decltype(PyUnicode_DecodeUnicodeEscape_Ptr)>(address_of(module, "PyUnicode_DecodeUnicodeEscape"));
    PyUnicode_EncodeFSDefault_Ptr = reinterpret_cast<decltype(PyUnicode_EncodeFSDefault_Ptr)>(address_of(module, "PyUnicode_EncodeFSDefault"));
    PyUnicode_EncodeLocale_Ptr = reinterpret_cast<decltype(PyUnicode_EncodeLocale_Ptr)>(address_of(module, "PyUnicode_EncodeLocale"));
    PyUnicode_FSConverter_Ptr = reinterpret_cast<decltype(PyUnicode_FSConverter_Ptr)>(address_of(module, "PyUnicode_FSConverter"));
    PyUnicode_FSDecoder_Ptr = reinterpret_cast<decltype(PyUnicode_FSDecoder_Ptr)>(address_of(module, "PyUnicode_FSDecoder"));
    PyUnicode_Find_Ptr = reinterpret_cast<decltype(PyUnicode_Find_Ptr)>(address_of(module, "PyUnicode_Find"));
    PyUnicode_FindChar_Ptr = reinterpret_cast<decltype(PyUnicode_FindChar_Ptr)>(address_of(module, "PyUnicode_FindChar"));
    PyUnicode_Format_Ptr = reinterpret_cast<decltype(PyUnicode_Format_Ptr)>(address_of(module, "PyUnicode_Format"));
    PyUnicode_FromEncodedObject_Ptr = reinterpret_cast<decltype(PyUnicode_FromEncodedObject_Ptr)>(address_of(module, "PyUnicode_FromEncodedObject"));
    PyUnicode_FromFormat_Ptr = reinterpret_cast<decltype(PyUnicode_FromFormat_Ptr)>(address_of(module, "PyUnicode_FromFormat"));
    PyUnicode_FromFormatV_Ptr = reinterpret_cast<decltype(PyUnicode_FromFormatV_Ptr)>(address_of(module, "PyUnicode_FromFormatV"));
    PyUnicode_FromObject_Ptr = reinterpret_cast<decltype(PyUnicode_FromObject_Ptr)>(address_of(module, "PyUnicode_FromObject"));
    PyUnicode_FromOrdinal_Ptr = reinterpret_cast<decltype(PyUnicode_FromOrdinal_Ptr)>(address_of(module, "PyUnicode_FromOrdinal"));
    PyUnicode_FromString_Ptr = reinterpret_cast<decltype(PyUnicode_FromString_Ptr)>(address_of(module, "PyUnicode_FromString"));
    PyUnicode_FromStringAndSize_Ptr = reinterpret_cast<decltype(PyUnicode_FromStringAndSize_Ptr)>(address_of(module, "PyUnicode_FromStringAndSize"));
    PyUnicode_FromWideChar_Ptr = reinterpret_cast<decltype(PyUnicode_FromWideChar_Ptr)>(address_of(module, "PyUnicode_FromWideChar"));
    PyUnicode_GetDefaultEncoding_Ptr = reinterpret_cast<decltype(PyUnicode_GetDefaultEncoding_Ptr)>(address_of(module, "PyUnicode_GetDefaultEncoding"));
    PyUnicode_GetLength_Ptr = reinterpret_cast<decltype(PyUnicode_GetLength_Ptr)>(address_of(module, "PyUnicode_GetLength"));
    PyUnicode_GetSize_Ptr = reinterpret_cast<decltype(PyUnicode_GetSize_Ptr)>(address_of(module, "PyUnicode_GetSize"));
    PyUnicode_InternFromString_Ptr = reinterpret_cast<decltype(PyUnicode_InternFromString_Ptr)>(address_of(module, "PyUnicode_InternFromString"));
    PyUnicode_InternImmortal_Ptr = reinterpret_cast<decltype(PyUnicode_InternImmortal_Ptr)>(address_of(module, "PyUnicode_InternImmortal"));
    PyUnicode_InternInPlace_Ptr = reinterpret_cast<decltype(PyUnicode_InternInPlace_Ptr)>(address_of(module, "PyUnicode_InternInPlace"));
    PyUnicode_IsIdentifier_Ptr = reinterpret_cast<decltype(PyUnicode_IsIdentifier_Ptr)>(address_of(module, "PyUnicode_IsIdentifier"));
    PyUnicode_Join_Ptr = reinterpret_cast<decltype(PyUnicode_Join_Ptr)>(address_of(module, "PyUnicode_Join"));
    PyUnicode_Partition_Ptr = reinterpret_cast<decltype(PyUnicode_Partition_Ptr)>(address_of(module, "PyUnicode_Partition"));
    PyUnicode_RPartition_Ptr = reinterpret_cast<decltype(PyUnicode_RPartition_Ptr)>(address_of(module, "PyUnicode_RPartition"));
    PyUnicode_RSplit_Ptr = reinterpret_cast<decltype(PyUnicode_RSplit_Ptr)>(address_of(module, "PyUnicode_RSplit"));
    PyUnicode_ReadChar_Ptr = reinterpret_cast<decltype(PyUnicode_ReadChar_Ptr)>(address_of(module, "PyUnicode_ReadChar"));
    PyUnicode_Replace_Ptr = reinterpret_cast<decltype(PyUnicode_Replace_Ptr)>(address_of(module, "PyUnicode_Replace"));
    PyUnicode_Resize_Ptr = reinterpret_cast<decltype(PyUnicode_Resize_Ptr)>(address_of(module, "PyUnicode_Resize"));
    PyUnicode_RichCompare_Ptr = reinterpret_cast<decltype(PyUnicode_RichCompare_Ptr)>(address_of(module, "PyUnicode_RichCompare"));
    PyUnicode_Split_Ptr = reinterpret_cast<decltype(PyUnicode_Split_Ptr)>(address_of(module, "PyUnicode_Split"));
    PyUnicode_Splitlines_Ptr = reinterpret_cast<decltype(PyUnicode_Splitlines_Ptr)>(address_of(module, "PyUnicode_Splitlines"));
    PyUnicode_Substring_Ptr = reinterpret_cast<decltype(PyUnicode_Substring_Ptr)>(address_of(module, "PyUnicode_Substring"));
    PyUnicode_Tailmatch_Ptr = reinterpret_cast<decltype(PyUnicode_Tailmatch_Ptr)>(address_of(module, "PyUnicode_Tailmatch"));
    PyUnicode_Translate_Ptr = reinterpret_cast<decltype(PyUnicode_Translate_Ptr)>(address_of(module, "PyUnicode_Translate"));
    PyUnicode_WriteChar_Ptr = reinterpret_cast<decltype(PyUnicode_WriteChar_Ptr)>(address_of(module, "PyUnicode_WriteChar"));
    PyWeakref_GetObject_Ptr = reinterpret_cast<decltype(PyWeakref_GetObject_Ptr)>(address_of(module, "PyWeakref_GetObject"));
    PyWeakref_NewProxy_Ptr = reinterpret_cast<decltype(PyWeakref_NewProxy_Ptr)>(address_of(module, "PyWeakref_NewProxy"));
    PyWeakref_NewRef_Ptr = reinterpret_cast<decltype(PyWeakref_NewRef_Ptr)>(address_of(module, "PyWeakref_NewRef"));
    PyWrapper_New_Ptr = reinterpret_cast<decltype(PyWrapper_New_Ptr)>(address_of(module, "PyWrapper_New"));
    Py_AddPendingCall_Ptr = reinterpret_cast<decltype(Py_AddPendingCall_Ptr)>(address_of(module, "Py_AddPendingCall"));
    Py_AtExit_Ptr = reinterpret_cast<decltype(Py_AtExit_Ptr)>(address_of(module, "Py_AtExit"));
    Py_BuildValue_Ptr = reinterpret_cast<decltype(Py_BuildValue_Ptr)>(address_of(module, "Py_BuildValue"));
    Py_BytesMain_Ptr = reinterpret_cast<decltype(Py_BytesMain_Ptr)>(address_of(module, "Py_BytesMain"));
    Py_CompileString_Ptr = reinterpret_cast<decltype(Py_CompileString_Ptr)>(address_of(module, "Py_CompileString"));
    Py_DecRef_Ptr = reinterpret_cast<decltype(Py_DecRef_Ptr)>(address_of(module, "Py_DecRef"));
    Py_DecodeLocale_Ptr = reinterpret_cast<decltype(Py_DecodeLocale_Ptr)>(address_of(module, "Py_DecodeLocale"));
    Py_EncodeLocale_Ptr = reinterpret_cast<decltype(Py_EncodeLocale_Ptr)>(address_of(module, "Py_EncodeLocale"));
    Py_EndInterpreter_Ptr = reinterpret_cast<decltype(Py_EndInterpreter_Ptr)>(address_of(module, "Py_EndInterpreter"));
    Py_EnterRecursiveCall_Ptr = reinterpret_cast<decltype(Py_EnterRecursiveCall_Ptr)>(address_of(module, "Py_EnterRecursiveCall"));
    Py_Exit_Ptr = reinterpret_cast<decltype(Py_Exit_Ptr)>(address_of(module, "Py_Exit"));
    Py_FatalError_Ptr = reinterpret_cast<decltype(Py_FatalError_Ptr)>(address_of(module, "Py_FatalError"));
    Py_Finalize_Ptr = reinterpret_cast<decltype(Py_Finalize_Ptr)>(address_of(module, "Py_Finalize"));
    Py_FinalizeEx_Ptr = reinterpret_cast<decltype(Py_FinalizeEx_Ptr)>(address_of(module, "Py_FinalizeEx"));
    Py_GenericAlias_Ptr = reinterpret_cast<decltype(Py_GenericAlias_Ptr)>(address_of(module, "Py_GenericAlias"));
    Py_GetBuildInfo_Ptr = reinterpret_cast<decltype(Py_GetBuildInfo_Ptr)>(address_of(module, "Py_GetBuildInfo"));
    Py_GetCompiler_Ptr = reinterpret_cast<decltype(Py_GetCompiler_Ptr)>(address_of(module, "Py_GetCompiler"));
    Py_GetCopyright_Ptr = reinterpret_cast<decltype(Py_GetCopyright_Ptr)>(address_of(module, "Py_GetCopyright"));
    Py_GetExecPrefix_Ptr = reinterpret_cast<decltype(Py_GetExecPrefix_Ptr)>(address_of(module, "Py_GetExecPrefix"));
    Py_GetPath_Ptr = reinterpret_cast<decltype(Py_GetPath_Ptr)>(address_of(module, "Py_GetPath"));
    Py_GetPlatform_Ptr = reinterpret_cast<decltype(Py_GetPlatform_Ptr)>(address_of(module, "Py_GetPlatform"));
    Py_GetPrefix_Ptr = reinterpret_cast<decltype(Py_GetPrefix_Ptr)>(address_of(module, "Py_GetPrefix"));
    Py_GetProgramFullPath_Ptr = reinterpret_cast<decltype(Py_GetProgramFullPath_Ptr)>(address_of(module, "Py_GetProgramFullPath"));
    Py_GetProgramName_Ptr = reinterpret_cast<decltype(Py_GetProgramName_Ptr)>(address_of(module, "Py_GetProgramName"));
    Py_GetPythonHome_Ptr = reinterpret_cast<decltype(Py_GetPythonHome_Ptr)>(address_of(module, "Py_GetPythonHome"));
    Py_GetRecursionLimit_Ptr = reinterpret_cast<decltype(Py_GetRecursionLimit_Ptr)>(address_of(module, "Py_GetRecursionLimit"));
    Py_GetVersion_Ptr = reinterpret_cast<decltype(Py_GetVersion_Ptr)>(address_of(module, "Py_GetVersion"));
    Py_IncRef_Ptr = reinterpret_cast<decltype(Py_IncRef_Ptr)>(address_of(module, "Py_IncRef"));
    Py_Initialize_Ptr = reinterpret_cast<decltype(Py_Initialize_Ptr)>(address_of(module, "Py_Initialize"));
    Py_InitializeEx_Ptr = reinterpret_cast<decltype(Py_InitializeEx_Ptr)>(address_of(module, "Py_InitializeEx"));
    Py_Is_Ptr = reinterpret_cast<decltype(Py_Is_Ptr)>(address_of(module, "Py_Is"));
    Py_IsInitialized_Ptr = reinterpret_cast<decltype(Py_IsInitialized_Ptr)>(address_of(module, "Py_IsInitialized"));
    Py_LeaveRecursiveCall_Ptr = reinterpret_cast<decltype(Py_LeaveRecursiveCall_Ptr)>(address_of(module, "Py_LeaveRecursiveCall"));
    Py_Main_Ptr = reinterpret_cast<decltype(Py_Main_Ptr)>(address_of(module, "Py_Main"));
    Py_MakePendingCalls_Ptr = reinterpret_cast<decltype(Py_MakePendingCalls_Ptr)>(address_of(module, "Py_MakePendingCalls"));
    Py_NewInterpreter_Ptr = reinterpret_cast<decltype(Py_NewInterpreter_Ptr)>(address_of(module, "Py_NewInterpreter"));
    Py_NewRef_Ptr = reinterpret_cast<decltype(Py_NewRef_Ptr)>(address_of(module, "Py_NewRef"));
    Py_ReprEnter_Ptr = reinterpret_cast<decltype(Py_ReprEnter_Ptr)>(address_of(module, "Py_ReprEnter"));
    Py_ReprLeave_Ptr = reinterpret_cast<decltype(Py_ReprLeave_Ptr)>(address_of(module, "Py_ReprLeave"));
    Py_SetPath_Ptr = reinterpret_cast<decltype(Py_SetPath_Ptr)>(address_of(module, "Py_SetPath"));
    Py_SetProgramName_Ptr = reinterpret_cast<decltype(Py_SetProgramName_Ptr)>(address_of(module, "Py_SetProgramName"));
    Py_SetPythonHome_Ptr = reinterpret_cast<decltype(Py_SetPythonHome_Ptr)>(address_of(module, "Py_SetPythonHome"));
    Py_SetRecursionLimit_Ptr = reinterpret_cast<decltype(Py_SetRecursionLimit_Ptr)>(address_of(module, "Py_SetRecursionLimit"));
    Py_VaBuildValue_Ptr = reinterpret_cast<decltype(Py_VaBuildValue_Ptr)>(address_of(module, "Py_VaBuildValue"));
    #if HAS_PYTHON_VERSION(0x030A0000)
    Py_XNewRef_Ptr = reinterpret_cast<decltype(Py_XNewRef_Ptr)>(address_of(module, "Py_XNewRef"));
    #endif
}

Python::Python(Python &&other)
{
    steal(*this, other);
}

Python& Python::operator = (Python&& other)
{
    steal(*this, other);
    return *this;
}

bool Python::IsPythonLoaded() const
{
    return module != nullptr;
}

int Python::PyAIter_Check(PyObject* o)
{
    return (*PyAIter_Check_Ptr)(o);
}

int Python::PyArg_ParseTuple(PyObject* args, const char* format, ...)
{
    va_list arguments;
    va_start(arguments, format);
    int result = PyArg_VaParse(args, format, arguments);
    va_end(arguments);
    return result;
}

int Python::PyArg_ParseTupleAndKeywords(PyObject* args, PyObject* kw, const char* format, char* keywords[], ...)
{
    va_list arguments;
    va_start(arguments, keywords);
    int result = PyArg_VaParseTupleAndKeywords(args, kw, format, keywords, arguments);
    va_end(arguments);
    return result;
}

int Python::PyArg_VaParse(PyObject* args, const char* format, va_list vargs)
{
    return (*PyArg_VaParse_Ptr)(args, format, vargs);
}

int Python::PyArg_VaParseTupleAndKeywords(PyObject* args, PyObject* kw, const char* format, char* keywords[], va_list vargs)
{
    return (*PyArg_VaParseTupleAndKeywords_Ptr)(args, kw, format, keywords, vargs);
}

int Python::PyArg_ValidateKeywordArguments(PyObject* keywords)
{
    return (*PyArg_ValidateKeywordArguments_Ptr)(keywords);
}

PyObject* Python::PyBool_FromLong(long v)
{
    return (*PyBool_FromLong_Ptr)(v);
}

#if HAS_PYTHON_VERSION(0x030B1000)
void Python::PyBuffer_FillContiguousStrides(int ndims, Py_ssize_t* shape, Py_ssize_t* strides, int itemsize, char fort)
{
    return (*PyBuffer_FillContiguousStrides_Ptr)(ndims, shape, strides, itemsize, fort);
}

int Python::PyBuffer_FillInfo(Py_buffer* view, PyObject* o, void* buf, Py_ssize_t len, int readonly, int flags)
{
    return (*PyBuffer_FillInfo_Ptr)(view, o, buf, len, readonly, flags);
}

int Python::PyBuffer_FromContiguous(const Py_buffer* view, const void* buf, Py_ssize_t len, char order)
{
    return (*PyBuffer_FromContiguous_Ptr)(view, buf, len, order);
}

void* Python::PyBuffer_GetPointer(const Py_buffer* view, const Py_ssize_t* indices)
{
    return (*PyBuffer_GetPointer_Ptr)(view, indices);
}

int Python::PyBuffer_IsContiguous(const Py_buffer* view, char fort)
{
    return (*PyBuffer_IsContiguous_Ptr)(view, fort);
}

void Python::PyBuffer_Release(Py_buffer* view)
{
    return (*PyBuffer_Release_Ptr)(view);
}

Py_ssize_t Python::PyBuffer_SizeFromFormat(const char* format)
{
    return (*PyBuffer_SizeFromFormat_Ptr)(format);
}

int Python::PyBuffer_ToContiguous(void* buf, const Py_buffer* view, Py_ssize_t len, char order)
{
    return (*PyBuffer_ToContiguous_Ptr)(buf, view, len, order);
}
#endif

char* Python::PyByteArray_AsString(PyObject* bytearray)
{
    return (*PyByteArray_AsString_Ptr)(bytearray);
}

PyObject* Python::PyByteArray_Concat(PyObject* a, PyObject* b)
{
    return (*PyByteArray_Concat_Ptr)(a, b);
}

PyObject* Python::PyByteArray_FromObject(PyObject* bytearray)
{
    return (*PyByteArray_FromObject_Ptr)(bytearray);
}

PyObject* Python::PyByteArray_FromStringAndSize(const char* string, Py_ssize_t len)
{
    return (*PyByteArray_FromStringAndSize_Ptr)(string, len);
}

int Python::PyByteArray_Resize(PyObject* bytearray, Py_ssize_t len)
{
    return (*PyByteArray_Resize_Ptr)(bytearray, len);
}

Py_ssize_t Python::PyByteArray_Size(PyObject* bytearray)
{
    return (*PyByteArray_Size_Ptr)(bytearray);
}

char* Python::PyBytes_AsString(PyObject* bytearray)
{
    return (*PyBytes_AsString_Ptr)(bytearray);
}

int Python::PyBytes_AsStringAndSize(PyObject* obj, char** s, Py_ssize_t* len)
{
    return (*PyBytes_AsStringAndSize_Ptr)(obj, s, len);
}

void Python::PyBytes_Concat(PyObject** bytes, PyObject* newpart)
{
    return (*PyBytes_Concat_Ptr)(bytes, newpart);
}

void Python::PyBytes_ConcatAndDel(PyObject** bytes, PyObject* newpart)
{
    return (*PyBytes_ConcatAndDel_Ptr)(bytes, newpart);
}

PyObject* Python::PyBytes_DecodeEscape(const char* arg1, Py_ssize_t arg2, const char* arg3, Py_ssize_t arg4, const char* arg5)
{
    return (*PyBytes_DecodeEscape_Ptr)(arg1, arg2, arg3, arg4, arg5);
}

PyObject* Python::PyBytes_FromFormat(const char *format, ...)
{
    va_list arguments;
    va_start(arguments, format);
    PyObject* result = PyBytes_FromFormatV(format, arguments);
    va_end(arguments);
    return result;
}

PyObject* Python::PyBytes_FromFormatV(const char* format, va_list vargs)
{
    return (*PyBytes_FromFormatV_Ptr)(format, vargs);
}

PyObject* Python::PyBytes_FromObject(PyObject* o)
{
    return (*PyBytes_FromObject_Ptr)(o);
}

PyObject* Python::PyBytes_FromString(const char* string)
{
    return (*PyBytes_FromString_Ptr)(string);
}

PyObject* Python::PyBytes_FromStringAndSize(const char* string, Py_ssize_t len)
{
    return (*PyBytes_FromStringAndSize_Ptr)(string, len);
}

PyObject* Python::PyBytes_Repr(PyObject* o, int smartquotes)
{
    return (*PyBytes_Repr_Ptr)(o, smartquotes);
}

Py_ssize_t Python::PyBytes_Size(PyObject* o)
{
    return (*PyBytes_Size_Ptr)(o);
}

PyObject* Python::PyCFunction_Call(PyObject* func, PyObject* arg, PyObject* kw)
{
    return (*PyCFunction_Call_Ptr)(func, arg, kw);
}

int Python::PyCFunction_GetFlags(PyObject* op)
{
    return (*PyCFunction_GetFlags_Ptr)(op);
}

PyCFunction Python::PyCFunction_GetFunction(PyObject* op)
{
    return (*PyCFunction_GetFunction_Ptr)(op);
}

PyObject* Python::PyCFunction_GetSelf(PyObject* op)
{
    return (*PyCFunction_GetSelf_Ptr)(op);
}

PyObject* (Python::PyCFunction_New)(PyMethodDef* ml, PyObject* self)
{
    #if HAS_PYTHON_VERSION(0x03050000)
    return PyCFunction_NewEx(ml, self, nullptr);
    #else
    return (*PyCMethod_New_Ptr)(ml, self, nullptr, nullptr);
    #endif
}

#if HAS_PYTHON_VERSION(0x03050000)
PyObject* (Python::PyCFunction_NewEx)(PyMethodDef* ml, PyObject* self, PyObject* module)
{
    #if HAS_PYTHON_VERSION(0x03090000)
    return (*PyCFunction_NewEx_Ptr)(ml, self, module);
    #else
    return (*PyCMethod_New_Ptr)(ml, self, module, nullptr);
    #endif
}
#endif

PyObject* Python::PyCMethod_New(PyMethodDef* ml, PyObject* self, PyObject* module, PyTypeObject* type)
{
    return (*PyCMethod_New_Ptr)(ml, self, module, type);
}

PyObject* Python::PyCallIter_New(PyObject* callable, PyObject* sentinel)
{
    return (*PyCallIter_New_Ptr)(callable, sentinel);
}

int Python::PyCallable_Check(PyObject* o)
{
    return (*PyCallable_Check_Ptr)(o);
}

void* Python::PyCapsule_GetContext(PyObject* capsule)
{
    return (*PyCapsule_GetContext_Ptr)(capsule);
}

PyCapsule_Destructor Python::PyCapsule_GetDestructor(PyObject* capsule)
{
    return (*PyCapsule_GetDestructor_Ptr)(capsule);
}

const char* Python::PyCapsule_GetName(PyObject* capsule)
{
    return (*PyCapsule_GetName_Ptr)(capsule);
}

void* Python::PyCapsule_GetPointer(PyObject* capsule, const char* name)
{
    return (*PyCapsule_GetPointer_Ptr)(capsule, name);
}

void* Python::PyCapsule_Import(const char* name, int no_block)
{
    return (*PyCapsule_Import_Ptr)(name, no_block);
}

int Python::PyCapsule_IsValid(PyObject* capsule, const char* name)
{
    return (*PyCapsule_IsValid_Ptr)(capsule, name);
}

PyObject* Python::PyCapsule_New(void* pointer, const char* name, PyCapsule_Destructor destructor)
{
    return (*PyCapsule_New_Ptr)(pointer, name, destructor);
}

int Python::PyCapsule_SetContext(PyObject* capsule, void* context)
{
    return (*PyCapsule_SetContext_Ptr)(capsule, context);
}

int Python::PyCapsule_SetDestructor(PyObject* capsule, PyCapsule_Destructor destructor)
{
    return (*PyCapsule_SetDestructor_Ptr)(capsule, destructor);
}

int Python::PyCapsule_SetName(PyObject* capsule, const char* name)
{
    return (*PyCapsule_SetName_Ptr)(capsule, name);
}

int Python::PyCapsule_SetPointer(PyObject* capsule, void* pointer)
{
    return (*PyCapsule_SetPointer_Ptr)(capsule, pointer);
}

PyObject* Python::PyComplex_FromDoubles(double real, double imag)
{
    return (*PyComplex_FromDoubles_Ptr)(real, imag);
}

double Python::PyComplex_ImagAsDouble(PyObject* op)
{
    return (*PyComplex_ImagAsDouble_Ptr)(op);
}

double Python::PyComplex_RealAsDouble(PyObject* op)
{
    return (*PyComplex_RealAsDouble_Ptr)(op);
}

PyObject* Python::PyDescr_NewClassMethod(PyTypeObject* type, PyMethodDef* method)
{
    return (*PyDescr_NewClassMethod_Ptr)(type, method);
}

PyObject* Python::PyDescr_NewGetSet(PyTypeObject* type, PyGetSetDef* getset)
{
    return (*PyDescr_NewGetSet_Ptr)(type, getset);
}

PyObject* Python::PyDescr_NewMember(PyTypeObject* type, PyMemberDef* meth)
{
    return (*PyDescr_NewMember_Ptr)(type, meth);
}

PyObject* Python::PyDescr_NewMethod(PyTypeObject* type, PyMethodDef* meth)
{
    return (*PyDescr_NewMethod_Ptr)(type, meth);
}

PyObject* Python::PyDictProxy_New(PyObject* mapping)
{
    return (*PyDictProxy_New_Ptr)(mapping);
}

void Python::PyDict_Clear(PyObject* mp)
{
    return (*PyDict_Clear_Ptr)(mp);
}

int Python::PyDict_Contains(PyObject* mp, PyObject* key)
{
    return (*PyDict_Contains_Ptr)(mp, key);
}

PyObject* Python::PyDict_Copy(PyObject* mp)
{
    return (*PyDict_Copy_Ptr)(mp);
}

int Python::PyDict_DelItem(PyObject* mp, PyObject* key)
{
    return (*PyDict_DelItem_Ptr)(mp, key);
}

int Python::PyDict_DelItemString(PyObject* dp, const char* key)
{
    return (*PyDict_DelItemString_Ptr)(dp, key);
}

PyObject* Python::PyDict_GetItem(PyObject* mp, PyObject* key)
{
    return (*PyDict_GetItem_Ptr)(mp, key);
}

PyObject* Python::PyDict_GetItemString(PyObject* dp, const char* key)
{
    return (*PyDict_GetItemString_Ptr)(dp, key);
}

PyObject* Python::PyDict_GetItemWithError(PyObject* mp, PyObject* key)
{
    return (*PyDict_GetItemWithError_Ptr)(mp, key);
}

PyObject* Python::PyDict_Items(PyObject* mp)
{
    return (*PyDict_Items_Ptr)(mp);
}

PyObject* Python::PyDict_Keys(PyObject* mp)
{
    return (*PyDict_Keys_Ptr)(mp);
}

int Python::PyDict_Merge(PyObject* mp, PyObject* other, int override)
{
    return (*PyDict_Merge_Ptr)(mp, other, override);
}

int Python::PyDict_MergeFromSeq2(PyObject* d, PyObject* seq2, int override)
{
    return (*PyDict_MergeFromSeq2_Ptr)(d, seq2, override);
}

PyObject* Python::PyDict_New()
{
    return (*PyDict_New_Ptr)();
}

int Python::PyDict_Next(PyObject* mp, Py_ssize_t* pos, PyObject** key, PyObject** value)
{
    return (*PyDict_Next_Ptr)(mp, pos, key, value);
}

int Python::PyDict_SetItem(PyObject* mp, PyObject* key, PyObject* item)
{
    return (*PyDict_SetItem_Ptr)(mp, key, item);
}

int Python::PyDict_SetItemString(PyObject* dp, const char* key, PyObject* item)
{
    return (*PyDict_SetItemString_Ptr)(dp, key, item);
}

Py_ssize_t Python::PyDict_Size(PyObject* mp)
{
    return (*PyDict_Size_Ptr)(mp);
}

int Python::PyDict_Update(PyObject* mp, PyObject* other)
{
    return (*PyDict_Update_Ptr)(mp, other);
}

PyObject* Python::PyDict_Values(PyObject* mp)
{
    return (*PyDict_Values_Ptr)(mp);
}

int Python::PyErr_BadArgument()
{
    return (*PyErr_BadArgument_Ptr)();
}

#ifdef PyErr_BadInternalCall
void (Python::PyErr_BadInternalCall)()
{
    return (*_PyErr_BadInternalCall_Ptr)(__FILE__, __LINE__);
}
#endif

int Python::PyErr_CheckSignals()
{
    return (*PyErr_CheckSignals_Ptr)();
}

void Python::PyErr_Clear()
{
    return (*PyErr_Clear_Ptr)();
}

void Python::PyErr_Display(PyObject* exc, PyObject* val, PyObject* tb)
{
    return (*PyErr_Display_Ptr)(exc, val, tb);
}

int Python::PyErr_ExceptionMatches(PyObject* exc)
{
    return (*PyErr_ExceptionMatches_Ptr)(exc);
}

void Python::PyErr_Fetch(PyObject** ptype, PyObject** pvalue, PyObject** ptraceback)
{
    return (*PyErr_Fetch_Ptr)(ptype, pvalue, ptraceback);
}

PyObject* Python::PyErr_Format(PyObject* exception, const char* format, ...)
{
    va_list arguments;
    va_start(arguments, format);
    PyObject* result = PyErr_FormatV(exception, format, arguments);
    va_end(arguments);
    return result;
}

PyObject* Python::PyErr_FormatV(PyObject* exception, const char* format, va_list vargs)
{
    return (*PyErr_FormatV_Ptr)(exception, format, vargs);
}

void Python::PyErr_GetExcInfo(PyObject** ptype, PyObject** pvalue, PyObject** ptraceback)
{
    return (*PyErr_GetExcInfo_Ptr)(ptype, pvalue, ptraceback);
}

PyObject* Python::PyErr_GetHandledException()
{
    return (*PyErr_GetHandledException_Ptr)();
}

int Python::PyErr_GivenExceptionMatches(PyObject* given, PyObject* exc)
{
    return (*PyErr_GivenExceptionMatches_Ptr)(given, exc);
}

PyObject* Python::PyErr_NewException(const char* name, PyObject* base, PyObject* dict)
{
    return (*PyErr_NewException_Ptr)(name, base, dict);
}

PyObject* Python::PyErr_NewExceptionWithDoc(const char* name, const char* doc, PyObject* base, PyObject* dict)
{
    return (*PyErr_NewExceptionWithDoc_Ptr)(name, doc, base, dict);
}

PyObject* Python::PyErr_NoMemory()
{
    return (*PyErr_NoMemory_Ptr)();
}

void Python::PyErr_NormalizeException(PyObject** exc, PyObject** val, PyObject** tb)
{
    return (*PyErr_NormalizeException_Ptr)(exc, val, tb);
}

PyObject* Python::PyErr_Occurred()
{
    return (*PyErr_Occurred_Ptr)();
}

void Python::PyErr_Print()
{
    return (*PyErr_Print_Ptr)();
}

void Python::PyErr_PrintEx(int set_sys_last_vars)
{
    return (*PyErr_PrintEx_Ptr)(set_sys_last_vars);
}

PyObject* Python::PyErr_ProgramText(const char* filename, int lineno)
{
    return (*PyErr_ProgramText_Ptr)(filename, lineno);
}

void Python::PyErr_Restore(PyObject* type, PyObject* value, PyObject* traceback)
{
    return (*PyErr_Restore_Ptr)(type, value, traceback);
}

void Python::PyErr_SetExcInfo(PyObject* type, PyObject* value, PyObject* traceback)
{
    return (*PyErr_SetExcInfo_Ptr)(type, value, traceback);
}

PyObject* Python::PyErr_SetFromErrno(PyObject* type)
{
    return (*PyErr_SetFromErrno_Ptr)(type);
}

PyObject *Python::PyErr_SetFromErrnoWithFilename(PyObject *type, const char *filename)
{
    return (*PyErr_SetFromErrnoWithFilename_Ptr)(type, filename);
}

PyObject* Python::PyErr_SetFromErrnoWithFilenameObject(PyObject* type, PyObject* filenameObject)
{
    return (*PyErr_SetFromErrnoWithFilenameObject_Ptr)(type, filenameObject);
}

PyObject* Python::PyErr_SetFromErrnoWithFilenameObjects(PyObject* type, PyObject* filenameObject, PyObject* filenameObject2)
{
    return (*PyErr_SetFromErrnoWithFilenameObjects_Ptr)(type, filenameObject, filenameObject2);
}

PyObject* Python::PyErr_SetImportError(PyObject *msg, PyObject *name, PyObject *path)
{
    return (*PyErr_SetImportError_Ptr)(msg, name, path);
}

void Python::PyErr_SetHandledException(PyObject* exc)
{
    return (*PyErr_SetHandledException_Ptr)(exc);
}

PyObject* Python::PyErr_SetImportErrorSubclass(PyObject* exception, PyObject* msg, PyObject* name, PyObject* path)
{
    return (*PyErr_SetImportErrorSubclass_Ptr)(exception, msg, name, path);
}

void Python::PyErr_SetInterrupt()
{
    return (*PyErr_SetInterrupt_Ptr)();
}

int Python::PyErr_SetInterruptEx(int signum)
{
    return (*PyErr_SetInterruptEx_Ptr)(signum);
}

void Python::PyErr_SetNone(PyObject* type)
{
    return (*PyErr_SetNone_Ptr)(type);
}

void Python::PyErr_SetObject(PyObject* type, PyObject* value)
{
    return (*PyErr_SetObject_Ptr)(type, value);
}

void Python::PyErr_SetString(PyObject* exception, const char* string)
{
    return (*PyErr_SetString_Ptr)(exception, string);
}

void Python::PyErr_SyntaxLocation(const char* filename, int lineno)
{
    return (*PyErr_SyntaxLocation_Ptr)(filename, lineno);
}

void Python::PyErr_SyntaxLocationEx(const char* filename, int lineno, int col_offset)
{
    return (*PyErr_SyntaxLocationEx_Ptr)(filename, lineno, col_offset);
}

int Python::PyErr_WarnEx(PyObject* category, const char* message, Py_ssize_t stack_level)
{
    return (*PyErr_WarnEx_Ptr)(category, message, stack_level);
}

int Python::PyErr_WarnExplicit(PyObject* category, const char* message, const char* filename, int lineno, const char* module, PyObject* registry)
{
    return (*PyErr_WarnExplicit_Ptr)(category, message, filename, lineno, module, registry);
}

void Python::PyErr_WriteUnraisable(PyObject* obj)
{
    return (*PyErr_WriteUnraisable_Ptr)(obj);
}

void Python::PyEval_AcquireLock()
{
    return (*PyEval_AcquireLock_Ptr)();
}

void Python::PyEval_AcquireThread(PyThreadState* tstate)
{
    return (*PyEval_AcquireThread_Ptr)(tstate);
}

PyObject* Python::PyEval_CallObjectWithKeywords(PyObject* callable, PyObject* args, PyObject* kwargs)
{
    return (*PyEval_CallObjectWithKeywords_Ptr)(callable, args, kwargs);
}

PyObject* Python::PyEval_EvalCode(PyObject* co, PyObject* globals, PyObject* locals)
{
    return (*PyEval_EvalCode_Ptr)(co, globals, locals);
}

PyObject* Python::PyEval_EvalCodeEx(PyObject* co, PyObject* globals, PyObject* locals, PyObject*const* args, int argc, PyObject*const* kwds, int kwdc, PyObject*const* defs, int defc, PyObject* kwdefs, PyObject* closure)
{
    return (*PyEval_EvalCodeEx_Ptr)(co, globals, locals, args, argc, kwds, kwdc, defs, defc, kwdefs, closure);
}

#if HAS_PYTHON_VERSION(0x03090000)
PyObject* Python::PyEval_EvalFrame(PyFrameObject* f)
{
    return (*PyEval_EvalFrame_Ptr)(f);
}

PyObject* Python::PyEval_EvalFrameEx(PyFrameObject* f, int exc)
{
    return (*PyEval_EvalFrameEx_Ptr)(f, exc);
}
#endif

PyObject* Python::PyEval_GetBuiltins()
{
    return (*PyEval_GetBuiltins_Ptr)();
}

#if HAS_PYTHON_VERSION(0x03090000)
PyFrameObject* Python::PyEval_GetFrame()
{
    return (*PyEval_GetFrame_Ptr)();
}
#endif

const char* Python::PyEval_GetFuncDesc(PyObject* func)
{
    return (*PyEval_GetFuncDesc_Ptr)(func);
}

const char* Python::PyEval_GetFuncName(PyObject* func)
{
    return (*PyEval_GetFuncName_Ptr)(func);
}

PyObject* Python::PyEval_GetGlobals()
{
    return (*PyEval_GetGlobals_Ptr)();
}

PyObject* Python::PyEval_GetLocals()
{
    return (*PyEval_GetLocals_Ptr)();
}

void Python::PyEval_InitThreads()
{
    return (*PyEval_InitThreads_Ptr)();
}

void Python::PyEval_ReleaseLock()
{
    return (*PyEval_ReleaseLock_Ptr)();
}

void Python::PyEval_ReleaseThread(PyThreadState* tstate)
{
    return (*PyEval_ReleaseThread_Ptr)(tstate);
}

void Python::PyEval_RestoreThread(PyThreadState* tstate)
{
    return (*PyEval_RestoreThread_Ptr)(tstate);
}

PyThreadState* Python::PyEval_SaveThread()
{
    return (*PyEval_SaveThread_Ptr)();
}

int Python::PyEval_ThreadsInitialized()
{
    return (*PyEval_ThreadsInitialized_Ptr)();
}

const char* (Python::PyExceptionClass_Name)(PyObject* exc)
{
    return (*PyExceptionClass_Name_Ptr)(exc);
}

PyObject* Python::PyException_GetCause(PyObject* exc)
{
    return (*PyException_GetCause_Ptr)(exc);
}

PyObject* Python::PyException_GetContext(PyObject* exc)
{
    return (*PyException_GetContext_Ptr)(exc);
}

PyObject* Python::PyException_GetTraceback(PyObject* exc)
{
    return (*PyException_GetTraceback_Ptr)(exc);
}

void Python::PyException_SetCause(PyObject* exc, PyObject* cause)
{
    return (*PyException_SetCause_Ptr)(exc, cause);
}

void Python::PyException_SetContext(PyObject* exc, PyObject* context)
{
    return (*PyException_SetContext_Ptr)(exc, context);
}

int Python::PyException_SetTraceback(PyObject* exc, PyObject* traceback)
{
    return (*PyException_SetTraceback_Ptr)(exc, traceback);
}

PyObject* Python::PyFile_FromFd(int fd, const char* name, const char* mode, int buffering, const char* encoding, const char* errors, const char* newline, int closefd)
{
    return (*PyFile_FromFd_Ptr)(fd, name, mode, buffering, encoding, errors, newline, closefd);
}

PyObject* Python::PyFile_GetLine(PyObject* p, int n)
{
    return (*PyFile_GetLine_Ptr)(p, n);
}

int Python::PyFile_WriteObject(PyObject* obj, PyObject* p, int flags)
{
    return (*PyFile_WriteObject_Ptr)(obj, p, flags);
}

int Python::PyFile_WriteString(const char* s, PyObject* p)
{
    return (*PyFile_WriteString_Ptr)(s, p);
}

double Python::PyFloat_AsDouble(PyObject* pyfloat)
{
    return (*PyFloat_AsDouble_Ptr)(pyfloat);
}

PyObject* Python::PyFloat_FromDouble(double dbl)
{
    return (*PyFloat_FromDouble_Ptr)(dbl);
}

PyObject* Python::PyFloat_FromString(PyObject* str)
{
    return (*PyFloat_FromString_Ptr)(str);
}

PyObject* Python::PyFloat_GetInfo()
{
    return (*PyFloat_GetInfo_Ptr)();
}

double Python::PyFloat_GetMax()
{
    return (*PyFloat_GetMax_Ptr)();
}

double Python::PyFloat_GetMin()
{
    return (*PyFloat_GetMin_Ptr)();
}

PyObject* Python::PyFrozenSet_New(PyObject* iterable)
{
    return (*PyFrozenSet_New_Ptr)(iterable);
}

Py_ssize_t Python::PyGC_Collect()
{
    return (*PyGC_Collect_Ptr)();
}

int Python::PyGC_Disable()
{
    return (*PyGC_Disable_Ptr)();
}

int Python::PyGC_Enable()
{
    return (*PyGC_Enable_Ptr)();
}

int Python::PyGC_IsEnabled()
{
    return (*PyGC_IsEnabled_Ptr)();
}

PyGILState_STATE Python::PyGILState_Ensure()
{
    return (*PyGILState_Ensure_Ptr)();
}

PyThreadState* Python::PyGILState_GetThisThreadState()
{
    return (*PyGILState_GetThisThreadState_Ptr)();
}

void Python::PyGILState_Release(PyGILState_STATE state)
{
    return (*PyGILState_Release_Ptr)(state);
}

PyObject* Python::PyImport_AddModule(const char* name)
{
    return (*PyImport_AddModule_Ptr)(name);
}

PyObject* Python::PyImport_AddModuleObject(PyObject* name)
{
    return (*PyImport_AddModuleObject_Ptr)(name);
}

int Python::PyImport_AppendInittab(const char* name, PyObject*(*initfunc)())
{
    return (*PyImport_AppendInittab_Ptr)(name, initfunc);
}

PyObject* Python::PyImport_ExecCodeModule(const char* name, PyObject* co)
{
    return (*PyImport_ExecCodeModule_Ptr)(name, co);
}

PyObject* Python::PyImport_ExecCodeModuleEx(const char* name, PyObject* co, const char* pathname)
{
    return (*PyImport_ExecCodeModuleEx_Ptr)(name, co, pathname);
}

PyObject* Python::PyImport_ExecCodeModuleObject(PyObject* name, PyObject* co, PyObject* pathname, PyObject* cpathname)
{
    return (*PyImport_ExecCodeModuleObject_Ptr)(name, co, pathname, cpathname);
}

PyObject* Python::PyImport_ExecCodeModuleWithPathnames(const char* name, PyObject* co, const char* pathname, const char* cpathname)
{
    return (*PyImport_ExecCodeModuleWithPathnames_Ptr)(name, co, pathname, cpathname);
}

PyObject* Python::PyImport_GetImporter(PyObject* path)
{
    return (*PyImport_GetImporter_Ptr)(path);
}

long Python::PyImport_GetMagicNumber()
{
    return (*PyImport_GetMagicNumber_Ptr)();
}

const char* Python::PyImport_GetMagicTag()
{
    return (*PyImport_GetMagicTag_Ptr)();
}

PyObject* Python::PyImport_GetModule(PyObject *name)
{
    return (*PyImport_GetModule_Ptr)(name);
}

PyObject* Python::PyImport_GetModuleDict()
{
    return (*PyImport_GetModuleDict_Ptr)();
}

PyObject* Python::PyImport_Import(PyObject* name)
{
    return (*PyImport_Import_Ptr)(name);
}

PyObject* Python::PyImport_ImportModule(const char* name)
{
    return (*PyImport_ImportModule_Ptr)(name);
}

int Python::PyImport_ImportFrozenModule(const char* name)
{
    return (*PyImport_ImportFrozenModule_Ptr)(name);
}

int Python::PyImport_ImportFrozenModuleObject(PyObject* name)
{
    return (*PyImport_ImportFrozenModuleObject_Ptr)(name);
}

PyObject* Python::PyImport_ImportModuleLevel(const char* name, PyObject* globals, PyObject* locals, PyObject* fromlist, int level)
{
    return (*PyImport_ImportModuleLevel_Ptr)(name, globals, locals, fromlist, level);
}

PyObject* Python::PyImport_ImportModuleLevelObject(PyObject* name, PyObject* globals, PyObject* locals, PyObject* fromlist, int level)
{
    return (*PyImport_ImportModuleLevelObject_Ptr)(name, globals, locals, fromlist, level);
}

PyObject* Python::PyImport_ImportModuleNoBlock(const char* name)
{
    return (*PyImport_ImportModuleNoBlock_Ptr)(name);
}

PyObject* Python::PyImport_ReloadModule(PyObject* m)
{
    return (*PyImport_ReloadModule_Ptr)(m);
}

int (Python::PyIndex_Check)(PyObject* o)
{
    return (*PyIndex_Check_Ptr)(o);
}

void Python::PyInterpreterState_Clear(PyInterpreterState* state)
{
    return (*PyInterpreterState_Clear_Ptr)(state);
}

void Python::PyInterpreterState_Delete(PyInterpreterState* state)
{
    return (*PyInterpreterState_Delete_Ptr)(state);
}

PyInterpreterState* Python::PyInterpreterState_Get()
{
    return (*PyInterpreterState_Get_Ptr)();
}

PyObject* Python::PyInterpreterState_GetDict(PyInterpreterState* state)
{
    return (*PyInterpreterState_GetDict_Ptr)(state);
}

int64_t Python::PyInterpreterState_GetID(PyInterpreterState* state)
{
    return (*PyInterpreterState_GetID_Ptr)(state);
}

PyInterpreterState* Python::PyInterpreterState_New()
{
    return (*PyInterpreterState_New_Ptr)();
}

int (Python::PyIter_Check)(PyObject* o)
{
    return (*PyIter_Check_Ptr)(o);
}

PyObject* Python::PyIter_Next(PyObject* o)
{
    return (*PyIter_Next_Ptr)(o);
}

#if HAS_PYTHON_VERSION(0x030A0000)
PySendResult Python::PyIter_Send(PyObject* iter, PyObject* arg, PyObject** presult)
{
    return (*PyIter_Send_Ptr)(iter, arg, presult);
}
#endif

int Python::PyList_Append(PyObject* list, PyObject* item)
{
    return (*PyList_Append_Ptr)(list, item);
}

PyObject* Python::PyList_AsTuple(PyObject* list)
{
    return (*PyList_AsTuple_Ptr)(list);
}

PyObject* Python::PyList_GetItem(PyObject* list, Py_ssize_t index)
{
    return (*PyList_GetItem_Ptr)(list, index);
}

PyObject* Python::PyList_GetSlice(PyObject* list, Py_ssize_t low, Py_ssize_t high)
{
    return (*PyList_GetSlice_Ptr)(list, low, high);
}

int Python::PyList_Insert(PyObject* list, Py_ssize_t index, PyObject* item)
{
    return (*PyList_Insert_Ptr)(list, index, item);
}

PyObject* Python::PyList_New(Py_ssize_t size)
{
    return (*PyList_New_Ptr)(size);
}

int Python::PyList_Reverse(PyObject* list)
{
    return (*PyList_Reverse_Ptr)(list);
}

int Python::PyList_SetItem(PyObject* list, Py_ssize_t index, PyObject* item)
{
    return (*PyList_SetItem_Ptr)(list, index, item);
}

int Python::PyList_SetSlice(PyObject* list, Py_ssize_t low, Py_ssize_t high, PyObject* item)
{
    return (*PyList_SetSlice_Ptr)(list, low, high, item);
}

Py_ssize_t Python::PyList_Size(PyObject* list)
{
    return (*PyList_Size_Ptr)(list);
}

int Python::PyList_Sort(PyObject* list)
{
    return (*PyList_Sort_Ptr)(list);
}

double Python::PyLong_AsDouble(PyObject* pylong)
{
    return (*PyLong_AsDouble_Ptr)(pylong);
}

long Python::PyLong_AsLong(PyObject* pylong)
{
    return (*PyLong_AsLong_Ptr)(pylong);
}

long Python::PyLong_AsLongAndOverflow(PyObject* pylong, int* overflow)
{
    return (*PyLong_AsLongAndOverflow_Ptr)(pylong, overflow);
}

long long Python::PyLong_AsLongLong(PyObject* pylong)
{
    return (*PyLong_AsLongLong_Ptr)(pylong);
}

long long Python::PyLong_AsLongLongAndOverflow(PyObject* pylong, int* overflow)
{
    return (*PyLong_AsLongLongAndOverflow_Ptr)(pylong, overflow);
}

size_t Python::PyLong_AsSize_t(PyObject* pylong)
{
    return (*PyLong_AsSize_t_Ptr)(pylong);
}

Py_ssize_t Python::PyLong_AsSsize_t(PyObject* pylong)
{
    return (*PyLong_AsSsize_t_Ptr)(pylong);
}

unsigned long Python::PyLong_AsUnsignedLong(PyObject* pylong)
{
    return (*PyLong_AsUnsignedLong_Ptr)(pylong);
}

unsigned long long Python::PyLong_AsUnsignedLongLong(PyObject* pylong)
{
    return (*PyLong_AsUnsignedLongLong_Ptr)(pylong);
}

unsigned long long Python::PyLong_AsUnsignedLongLongMask(PyObject* pylong)
{
    return (*PyLong_AsUnsignedLongLongMask_Ptr)(pylong);
}

unsigned long Python::PyLong_AsUnsignedLongMask(PyObject* pylong)
{
    return (*PyLong_AsUnsignedLongMask_Ptr)(pylong);
}

void* Python::PyLong_AsVoidPtr(PyObject* pylong)
{
    return (*PyLong_AsVoidPtr_Ptr)(pylong);
}

PyObject* Python::PyLong_FromDouble(double dbl)
{
    return (*PyLong_FromDouble_Ptr)(dbl);
}

PyObject* Python::PyLong_FromLong(long lng)
{
    return (*PyLong_FromLong_Ptr)(lng);
}

PyObject* Python::PyLong_FromLongLong(long long longlong)
{
    return (*PyLong_FromLongLong_Ptr)(longlong);
}

PyObject* Python::PyLong_FromSize_t(size_t size)
{
    return (*PyLong_FromSize_t_Ptr)(size);
}

PyObject* Python::PyLong_FromSsize_t(Py_ssize_t size)
{
    return (*PyLong_FromSsize_t_Ptr)(size);
}

PyObject* Python::PyLong_FromString(const char* str, char** pend, int base)
{
    return (*PyLong_FromString_Ptr)(str, pend, base);
}

PyObject* Python::PyLong_FromUnsignedLong(unsigned long ulong)
{
    return (*PyLong_FromUnsignedLong_Ptr)(ulong);
}

PyObject* Python::PyLong_FromUnsignedLongLong(unsigned long long ulonglong)
{
    return (*PyLong_FromUnsignedLongLong_Ptr)(ulonglong);
}

PyObject* Python::PyLong_FromVoidPtr(void* ptr)
{
    return (*PyLong_FromVoidPtr_Ptr)(ptr);
}

PyObject* Python::PyLong_GetInfo()
{
    return (*PyLong_GetInfo_Ptr)();
}

int Python::PyMapping_Check(PyObject* o)
{
    return (*PyMapping_Check_Ptr)(o);
}

PyObject* Python::PyMapping_GetItemString(PyObject* o, const char* key)
{
    return (*PyMapping_GetItemString_Ptr)(o, key);
}

int Python::PyMapping_HasKey(PyObject* o, PyObject* key)
{
    return (*PyMapping_HasKey_Ptr)(o, key);
}

int Python::PyMapping_HasKeyString(PyObject* o, const char* key)
{
    return (*PyMapping_HasKeyString_Ptr)(o, key);
}

PyObject* Python::PyMapping_Items(PyObject* o)
{
    return (*PyMapping_Items_Ptr)(o);
}

PyObject* Python::PyMapping_Keys(PyObject* o)
{
    return (*PyMapping_Keys_Ptr)(o);
}

Py_ssize_t Python::PyMapping_Length(PyObject* o)
{
    return (*PyMapping_Length_Ptr)(o);
}

int Python::PyMapping_SetItemString(PyObject* o, const char* key, PyObject* value)
{
    return (*PyMapping_SetItemString_Ptr)(o, key, value);
}

Py_ssize_t Python::PyMapping_Size(PyObject* o)
{
    return (*PyMapping_Size_Ptr)(o);
}

PyObject* Python::PyMapping_Values(PyObject* o)
{
    return (*PyMapping_Values_Ptr)(o);
}

void* Python::PyMem_Calloc(size_t nelem, size_t elsize)
{
    return (*PyMem_Calloc_Ptr)(nelem, elsize);
}

void Python::PyMem_Free(void* ptr)
{
    return (*PyMem_Free_Ptr)(ptr);
}

void* Python::PyMem_Malloc(size_t size)
{
    return (*PyMem_Malloc_Ptr)(size);
}

void* Python::PyMem_Realloc(void* ptr, size_t new_size)
{
    return (*PyMem_Realloc_Ptr)(ptr, new_size);
}

#if HAS_PYTHON_VERSION(0x030B1000)
PyObject* Python::PyMemoryView_FromBuffer(const Py_buffer* info)
{
    return (*PyMemoryView_FromBuffer_Ptr)(info);
}
#endif

PyObject* Python::PyMemoryView_FromMemory(char* mem, Py_ssize_t size, int flags)
{
    return (*PyMemoryView_FromMemory_Ptr)(mem, size, flags);
}

PyObject* Python::PyMemoryView_FromObject(PyObject* base)
{
    return (*PyMemoryView_FromObject_Ptr)(base);
}

PyObject* Python::PyMemoryView_GetContiguous(PyObject* base, int buffertype, char order)
{
    return (*PyMemoryView_GetContiguous_Ptr)(base, buffertype, order);
}

PyObject* Python::PyModuleDef_Init(PyModuleDef* def)
{
    return (*PyModuleDef_Init_Ptr)(def);
}

int Python::PyModule_AddFunctions(PyObject* module, PyMethodDef* functions)
{
    return (*PyModule_AddFunctions_Ptr)(module, functions);
}

int Python::PyModule_AddIntConstant(PyObject* module, const char* name, long value)
{
    return (*PyModule_AddIntConstant_Ptr)(module, name, value);
}

int Python::PyModule_AddObject(PyObject* module, const char* name, PyObject* value)
{
    return (*PyModule_AddObject_Ptr)(module, name, value);
}

int Python::PyModule_AddObjectRef(PyObject* mod, const char* name, PyObject* value)
{
    return (*PyModule_AddObjectRef_Ptr)(mod, name, value);
}

int Python::PyModule_AddStringConstant(PyObject* module, const char* name, const char* value)
{
    return (*PyModule_AddStringConstant_Ptr)(module, name, value);
}

int Python::PyModule_AddType(PyObject* module, PyTypeObject* type)
{
    return (*PyModule_AddType_Ptr)(module, type);
}

PyObject* Python::PyModule_Create2(PyModuleDef* def, int apiver)
{
    return (*PyModule_Create2_Ptr)(def, apiver);
}

int Python::PyModule_ExecDef(PyObject* module, PyModuleDef* def)
{
    return (*PyModule_ExecDef_Ptr)(module, def);
}

PyObject* Python::PyModule_FromDefAndSpec2(PyModuleDef* def, PyObject* spec, int module_api_version)
{
    return (*PyModule_FromDefAndSpec2_Ptr)(def, spec, module_api_version);
}

PyModuleDef* Python::PyModule_GetDef(PyObject* module)
{
    return (*PyModule_GetDef_Ptr)(module);
}

PyObject* Python::PyModule_GetDict(PyObject* module)
{
    return (*PyModule_GetDict_Ptr)(module);
}

const char* Python::PyModule_GetFilename(PyObject* module)
{
    return (*PyModule_GetFilename_Ptr)(module);
}

PyObject* Python::PyModule_GetFilenameObject(PyObject* module)
{
    return (*PyModule_GetFilenameObject_Ptr)(module);
}

const char* Python::PyModule_GetName(PyObject* module)
{
    return (*PyModule_GetName_Ptr)(module);
}

PyObject* Python::PyModule_GetNameObject(PyObject* module)
{
    return (*PyModule_GetNameObject_Ptr)(module);
}

void* Python::PyModule_GetState(PyObject* module)
{
    return (*PyModule_GetState_Ptr)(module);
}

PyObject* Python::PyModule_New(const char* name)
{
    return (*PyModule_New_Ptr)(name);
}

PyObject* Python::PyModule_NewObject(PyObject* name)
{
    return (*PyModule_NewObject_Ptr)(name);
}

int Python::PyModule_SetDocString(PyObject* module, const char* docs)
{
    return (*PyModule_SetDocString_Ptr)(module, docs);
}

PyObject* Python::PyNumber_Absolute(PyObject* o)
{
    return (*PyNumber_Absolute_Ptr)(o);
}

PyObject* Python::PyNumber_Add(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Add_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_And(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_And_Ptr)(o1, o2);
}

Py_ssize_t Python::PyNumber_AsSsize_t(PyObject* o, PyObject* exc)
{
    return (*PyNumber_AsSsize_t_Ptr)(o, exc);
}

int Python::PyNumber_Check(PyObject* o)
{
    return (*PyNumber_Check_Ptr)(o);
}

PyObject* Python::PyNumber_Divmod(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Divmod_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Float(PyObject* o)
{
    return (*PyNumber_Float_Ptr)(o);
}

PyObject* Python::PyNumber_FloorDivide(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_FloorDivide_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceAdd(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceAdd_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceAnd(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceAnd_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceFloorDivide(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceFloorDivide_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceLshift(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceLshift_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceMatrixMultiply(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceMatrixMultiply_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceMultiply(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceMultiply_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceOr(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceOr_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlacePower(PyObject* o1, PyObject* o2, PyObject* o3)
{
    return (*PyNumber_InPlacePower_Ptr)(o1, o2, o3);
}

PyObject* Python::PyNumber_InPlaceRemainder(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceRemainder_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceRshift(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceRshift_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceSubtract(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceSubtract_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceTrueDivide(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceTrueDivide_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_InPlaceXor(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_InPlaceXor_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Index(PyObject* o)
{
    return (*PyNumber_Index_Ptr)(o);
}

PyObject* Python::PyNumber_Invert(PyObject* o)
{
    return (*PyNumber_Invert_Ptr)(o);
}

PyObject* Python::PyNumber_Long(PyObject* o)
{
    return (*PyNumber_Long_Ptr)(o);
}

PyObject* Python::PyNumber_Lshift(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Lshift_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_MatrixMultiply(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_MatrixMultiply_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Multiply(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Multiply_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Negative(PyObject* o)
{
    return (*PyNumber_Negative_Ptr)(o);
}

PyObject* Python::PyNumber_Or(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Or_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Positive(PyObject* o)
{
    return (*PyNumber_Positive_Ptr)(o);
}

PyObject* Python::PyNumber_Power(PyObject* o1, PyObject* o2, PyObject* o3)
{
    return (*PyNumber_Power_Ptr)(o1, o2, o3);
}

PyObject* Python::PyNumber_Remainder(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Remainder_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Rshift(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Rshift_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Subtract(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Subtract_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_ToBase(PyObject* n, int base)
{
    return (*PyNumber_ToBase_Ptr)(n, base);
}

PyObject* Python::PyNumber_TrueDivide(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_TrueDivide_Ptr)(o1, o2);
}

PyObject* Python::PyNumber_Xor(PyObject* o1, PyObject* o2)
{
    return (*PyNumber_Xor_Ptr)(o1, o2);
}

void Python::PyOS_AfterFork()
{
    return (*PyOS_AfterFork_Ptr)();
}

void Python::PyOS_AfterFork_Child()
{
    return (*PyOS_AfterFork_Child_Ptr)();
}

void Python::PyOS_AfterFork_Parent()
{
    return (*PyOS_AfterFork_Parent_Ptr)();
}

void Python::PyOS_BeforeFork()
{
    return (*PyOS_BeforeFork_Ptr)();
}

PyObject* Python::PyOS_FSPath(PyObject* path)
{
    return (*PyOS_FSPath_Ptr)(path);
}

int Python::PyOS_InterruptOccurred()
{
    return (*PyOS_InterruptOccurred_Ptr)();
}

char* Python::PyOS_double_to_string(double val, char format_code, int precision, int flags, int* type)
{
    return (*PyOS_double_to_string_Ptr)(val, format_code, precision, flags, type);
}

PyOS_sighandler_t Python::PyOS_getsig(int i)
{
    return (*PyOS_getsig_Ptr)(i);
}

int Python::PyOS_mystricmp(const char* a, const char* b)
{
    return (*PyOS_mystricmp_Ptr)(a, b);
}

int Python::PyOS_mystrnicmp(const char* a, const char* b, Py_ssize_t max_len)
{
    return (*PyOS_mystrnicmp_Ptr)(a, b, max_len);
}

PyOS_sighandler_t Python::PyOS_setsig(int i, PyOS_sighandler_t handler)
{
    return (*PyOS_setsig_Ptr)(i, handler);
}

double Python::PyOS_string_to_double(const char* str, char** endptr, PyObject* overflow_exception)
{
    return (*PyOS_string_to_double_Ptr)(str, endptr, overflow_exception);
}

long Python::PyOS_strtol(const char* str, char** endptr, int base)
{
    return (*PyOS_strtol_Ptr)(str, endptr, base);
}

unsigned long Python::PyOS_strtoul(const char* str, char** endptr, int base)
{
    return (*PyOS_strtoul_Ptr)(str, endptr, base);
}

int Python::PyOS_vsnprintf(char* str, size_t size, const char* format, va_list va)
{
    return (*PyOS_vsnprintf_Ptr)(str, size, format, va);
}

PyObject* Python::PyObject_ASCII(PyObject* o)
{
    return (*PyObject_ASCII_Ptr)(o);
}

int Python::PyObject_AsCharBuffer(PyObject* obj, const char** buffer, Py_ssize_t* buffer_len)
{
    return (*PyObject_AsCharBuffer_Ptr)(obj, buffer, buffer_len);
}

int Python::PyObject_AsFileDescriptor(PyObject* o)
{
    return (*PyObject_AsFileDescriptor_Ptr)(o);
}

int Python::PyObject_AsReadBuffer(PyObject* obj, const void** buffer, Py_ssize_t* buffer_len)
{
    return (*PyObject_AsReadBuffer_Ptr)(obj, buffer, buffer_len);
}

int Python::PyObject_AsWriteBuffer(PyObject* obj, void** buffer, Py_ssize_t* buffer_len)
{
    return (*PyObject_AsWriteBuffer_Ptr)(obj, buffer, buffer_len);
}

PyObject* Python::PyObject_Bytes(PyObject* o)
{
    return (*PyObject_Bytes_Ptr)(o);
}

PyObject* Python::PyObject_Call(PyObject* callable, PyObject* args, PyObject* kwargs)
{
    return (*PyObject_Call_Ptr)(callable, args, kwargs);
}

PyObject* Python::PyObject_CallNoArgs(PyObject* func)
{
    return (*PyObject_CallNoArgs_Ptr)(func);
}

PyObject* Python::PyObject_CallObject(PyObject* callable, PyObject* args)
{
    return (*PyObject_CallObject_Ptr)(callable, args);
}

void* Python::PyObject_Calloc(size_t nelem, size_t elsize)
{
    return (*PyObject_Calloc_Ptr)(nelem, elsize);
}

int Python::PyObject_CheckBuffer(PyObject* obj)
{
    return (*PyObject_CheckBuffer_Ptr)(obj);
}

int Python::PyObject_CheckReadBuffer(PyObject* obj)
{
    return (*PyObject_CheckReadBuffer_Ptr)(obj);
}

void Python::PyObject_ClearWeakRefs(PyObject* obj)
{
    return (*PyObject_ClearWeakRefs_Ptr)(obj);
}

int Python::PyObject_CopyData(PyObject* dest, PyObject* src)
{
    return (*PyObject_CopyData_Ptr)(dest, src);
}

int Python::PyObject_DelItem(PyObject* o, PyObject* key)
{
    return (*PyObject_DelItem_Ptr)(o, key);
}

int Python::PyObject_DelItemString(PyObject* o, const char* key)
{
    return (*PyObject_DelItemString_Ptr)(o, key);
}

PyObject* Python::PyObject_Dir(PyObject* obj)
{
    return (*PyObject_Dir_Ptr)(obj);
}

PyObject* Python::PyObject_Format(PyObject* obj, PyObject* format_spec)
{
    return (*PyObject_Format_Ptr)(obj, format_spec);
}

void Python::PyObject_Free(void* ptr)
{
    return (*PyObject_Free_Ptr)(ptr);
}

void Python::PyObject_GC_Del(void* op)
{
    return (*PyObject_GC_Del_Ptr)(op);
}

int Python::PyObject_GC_IsFinalized(PyObject* op)
{
    return (*PyObject_GC_IsFinalized_Ptr)(op);
}

int Python::PyObject_GC_IsTracked(PyObject* op)
{
    return (*PyObject_GC_IsTracked_Ptr)(op);
}

void Python::PyObject_GC_Track(void* op)
{
    return (*PyObject_GC_Track_Ptr)(op);
}

void Python::PyObject_GC_UnTrack(void* op)
{
    return (*PyObject_GC_UnTrack_Ptr)(op);
}

PyObject* Python::PyObject_GenericGetAttr(PyObject* o, PyObject* name)
{
    return (*PyObject_GenericGetAttr_Ptr)(o, name);
}

PyObject* Python::PyObject_GenericGetDict(PyObject* o, void* context)
{
    return (*PyObject_GenericGetDict_Ptr)(o, context);
}

int Python::PyObject_GenericSetAttr(PyObject* o, PyObject* name, PyObject* value)
{
    return (*PyObject_GenericSetAttr_Ptr)(o, name, value);
}

int Python::PyObject_GenericSetDict(PyObject* o, PyObject* value, void* context)
{
    return (*PyObject_GenericSetDict_Ptr)(o, value, context);
}

PyObject* Python::PyObject_GetAIter(PyObject* o)
{
    return (*PyObject_GetAIter_Ptr)(o);
}

PyObject* Python::PyObject_GetAttr(PyObject* o, PyObject* attr_name)
{
    return (*PyObject_GetAttr_Ptr)(o, attr_name);
}

PyObject* Python::PyObject_GetAttrString(PyObject* o, const char* attr)
{
    return (*PyObject_GetAttrString_Ptr)(o, attr);
}

#if HAS_PYTHON_VERSION(0x030B1000)
int Python::PyObject_GetBuffer(PyObject* obj, Py_buffer* view, int flags)
{
    return (*PyObject_GetBuffer_Ptr)(obj, view, flags);
}
#endif

PyObject* Python::PyObject_GetItem(PyObject* o, PyObject* key)
{
    return (*PyObject_GetItem_Ptr)(o, key);
}

PyObject* Python::PyObject_GetIter(PyObject* o)
{
    return (*PyObject_GetIter_Ptr)(o);
}

int Python::PyObject_HasAttr(PyObject* o, PyObject* attr_name)
{
    return (*PyObject_HasAttr_Ptr)(o, attr_name);
}

int Python::PyObject_HasAttrString(PyObject* o , const char* attr)
{
    return (*PyObject_HasAttrString_Ptr)(o, attr);
}

Py_hash_t Python::PyObject_Hash(PyObject* o)
{
    return (*PyObject_Hash_Ptr)(o);
}

Py_hash_t Python::PyObject_HashNotImplemented(PyObject* o)
{
    return (*PyObject_HashNotImplemented_Ptr)(o);
}

PyObject* Python::PyObject_Init(PyObject* o, PyTypeObject* type)
{
    return (*PyObject_Init_Ptr)(o, type);
}

PyVarObject* Python::PyObject_InitVar(PyVarObject* op, PyTypeObject* type, Py_ssize_t size)
{
    return (*PyObject_InitVar_Ptr)(op, type, size);
}

int Python::PyObject_IsInstance(PyObject* object, PyObject* typeorclass)
{
    return (*PyObject_IsInstance_Ptr)(object, typeorclass);
}

int Python::PyObject_IsSubclass(PyObject* object, PyObject* typeorclass)
{
    return (*PyObject_IsSubclass_Ptr)(object, typeorclass);
}

int Python::PyObject_IsTrue(PyObject* o)
{
    return (*PyObject_IsTrue_Ptr)(o);
}

Py_ssize_t Python::PyObject_Length(PyObject* o)
{
    return (*PyObject_Length_Ptr)(o);
}

void* Python::PyObject_Malloc(size_t size)
{
    return (*PyObject_Malloc_Ptr)(size);
}

int Python::PyObject_Not(PyObject* o)
{
    return (*PyObject_Not_Ptr)(o);
}

void* Python::PyObject_Realloc(void* ptr, size_t new_size)
{
    return (*PyObject_Realloc_Ptr)(ptr, new_size);
}

PyObject* Python::PyObject_Repr(PyObject* o)
{
    return (*PyObject_Repr_Ptr)(o);
}

PyObject* Python::PyObject_RichCompare(PyObject* a, PyObject* b, int opid)
{
    return (*PyObject_RichCompare_Ptr)(a, b, opid);
}

int Python::PyObject_RichCompareBool(PyObject* a, PyObject* b, int opid)
{
    return (*PyObject_RichCompareBool_Ptr)(a, b, opid);
}

PyObject* Python::PyObject_SelfIter(PyObject* o)
{
    return (*PyObject_SelfIter_Ptr)(o);
}

int Python::PyObject_SetAttr(PyObject* o, PyObject* attr_name, PyObject* v)
{
    return (*PyObject_SetAttr_Ptr)(o, attr_name, v);
}

int Python::PyObject_SetAttrString(PyObject* o, const char* attr_name, PyObject* value)
{
    return (*PyObject_SetAttrString_Ptr)(o, attr_name, value);
}

int Python::PyObject_SetItem(PyObject* o, PyObject* key, PyObject* v)
{
    return (*PyObject_SetItem_Ptr)(o, key, v);
}

Py_ssize_t Python::PyObject_Size(PyObject* o)
{
    return (*PyObject_Size_Ptr)(o);
}

PyObject* Python::PyObject_Str(PyObject* o)
{
    return (*PyObject_Str_Ptr)(o);
}

PyObject* Python::PyObject_Type(PyObject* o)
{
    return (*PyObject_Type_Ptr)(o);
}

#if HAS_PYTHON_VERSION(0x030B0000)
int (Python::PyObject_TypeCheck)(PyObject* o, PyTypeObject* type)
{
    return (*PyObject_TypeCheck_Ptr)(o, type);
}
#endif

PyObject* Python::PySeqIter_New(PyObject* o)
{
    return (*PySeqIter_New_Ptr)(o);
}

int Python::PySequence_Check(PyObject* o)
{
    return (*PySequence_Check_Ptr)(o);
}

PyObject* Python::PySequence_Concat(PyObject* o1, PyObject* o2)
{
    return (*PySequence_Concat_Ptr)(o1, o2);
}

int Python::PySequence_Contains(PyObject* seq, PyObject* ob)
{
    return (*PySequence_Contains_Ptr)(seq, ob);
}

Py_ssize_t Python::PySequence_Count(PyObject* o, PyObject* value)
{
    return (*PySequence_Count_Ptr)(o, value);
}

int Python::PySequence_DelItem(PyObject* o, Py_ssize_t i)
{
    return (*PySequence_DelItem_Ptr)(o, i);
}

int Python::PySequence_DelSlice(PyObject* o, Py_ssize_t i1, Py_ssize_t i2)
{
    return (*PySequence_DelSlice_Ptr)(o, i1, i2);
}

PyObject* Python::PySequence_Fast(PyObject* o, const char* m)
{
    return (*PySequence_Fast_Ptr)(o, m);
}

PyObject* Python::PySequence_GetItem(PyObject* o, Py_ssize_t i)
{
    return (*PySequence_GetItem_Ptr)(o, i);
}

PyObject* Python::PySequence_GetSlice(PyObject* o, Py_ssize_t i1, Py_ssize_t i2)
{
    return (*PySequence_GetSlice_Ptr)(o, i1, i2);
}

int Python::PySequence_In(PyObject* o, PyObject* value)
{
    return (*PySequence_In_Ptr)(o, value);
}

PyObject* Python::PySequence_InPlaceConcat(PyObject* o1, PyObject* o2)
{
    return (*PySequence_InPlaceConcat_Ptr)(o1, o2);
}

PyObject* Python::PySequence_InPlaceRepeat(PyObject* o, Py_ssize_t count)
{
    return (*PySequence_InPlaceRepeat_Ptr)(o, count);
}

Py_ssize_t Python::PySequence_Index(PyObject* o, PyObject* value)
{
    return (*PySequence_Index_Ptr)(o, value);
}

Py_ssize_t Python::PySequence_Length(PyObject* o)
{
    return (*PySequence_Length_Ptr)(o);
}

PyObject* Python::PySequence_List(PyObject* o)
{
    return (*PySequence_List_Ptr)(o);
}

PyObject* Python::PySequence_Repeat(PyObject* o, Py_ssize_t count)
{
    return (*PySequence_Repeat_Ptr)(o, count);
}

int Python::PySequence_SetItem(PyObject* o, Py_ssize_t i, PyObject* v)
{
    return (*PySequence_SetItem_Ptr)(o, i, v);
}

int Python::PySequence_SetSlice(PyObject* o, Py_ssize_t i1, Py_ssize_t i2, PyObject* v)
{
    return (*PySequence_SetSlice_Ptr)(o, i1, i2, v);
}

Py_ssize_t Python::PySequence_Size(PyObject* o)
{
    return (*PySequence_Size_Ptr)(o);
}

PyObject* Python::PySequence_Tuple(PyObject* o)
{
    return (*PySequence_Tuple_Ptr)(o);
}

int Python::PySet_Add(PyObject* set, PyObject* key)
{
    return (*PySet_Add_Ptr)(set, key);
}

int Python::PySet_Clear(PyObject* set)
{
    return (*PySet_Clear_Ptr)(set);
}

int Python::PySet_Contains(PyObject* anyset, PyObject* key)
{
    return (*PySet_Contains_Ptr)(anyset, key);
}

int Python::PySet_Discard(PyObject* set, PyObject* key)
{
    return (*PySet_Discard_Ptr)(set, key);
}

PyObject* Python::PySet_New(PyObject* iterable)
{
    return (*PySet_New_Ptr)(iterable);
}

PyObject* Python::PySet_Pop(PyObject* set)
{
    return (*PySet_Pop_Ptr)(set);
}

Py_ssize_t Python::PySet_Size(PyObject* anyset)
{
    return (*PySet_Size_Ptr)(anyset);
}

Py_ssize_t Python::PySlice_AdjustIndices(Py_ssize_t length, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t step)
{
    return (*PySlice_AdjustIndices_Ptr)(length, start, stop, step);
}

int Python::PySlice_GetIndices(PyObject* r, Py_ssize_t length, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t* step)
{
    return (*PySlice_GetIndices_Ptr)(r, length, start, stop, step);
}

#ifdef PySlice_GetIndicesEx
int (Python::PySlice_GetIndicesEx)(PyObject* r, Py_ssize_t length, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t* step, Py_ssize_t* slicelength)
{
    return (*PySlice_GetIndicesEx_Ptr)(r, length, start, stop, step, slicelength);
}
#endif

PyObject* Python::PySlice_New(PyObject* start, PyObject* stop, PyObject* step)
{
    return (*PySlice_New_Ptr)(start, stop, step);
}

int Python::PySlice_Unpack(PyObject* slice, Py_ssize_t* start, Py_ssize_t* stop, Py_ssize_t* step)
{
    return (*PySlice_Unpack_Ptr)(slice, start, stop, step);
}

int Python::PyState_AddModule(PyObject* module, PyModuleDef* def)
{
    return (*PyState_AddModule_Ptr)(module, def);
}

PyObject* Python::PyState_FindModule(PyModuleDef* def)
{
    return (*PyState_FindModule_Ptr)(def);
}

int Python::PyState_RemoveModule(PyModuleDef* def)
{
    return (*PyState_RemoveModule_Ptr)(def);
}

PyObject* Python::PyStructSequence_GetItem(PyObject* p, Py_ssize_t pos)
{
    return (*PyStructSequence_GetItem_Ptr)(p, pos);
}

PyObject* Python::PyStructSequence_New(PyTypeObject* type)
{
    return (*PyStructSequence_New_Ptr)(type);
}

PyTypeObject* Python::PyStructSequence_NewType(PyStructSequence_Desc* desc)
{
    return (*PyStructSequence_NewType_Ptr)(desc);
}

void Python::PyStructSequence_SetItem(PyObject* p, Py_ssize_t pos, PyObject* o)
{
    return (*PyStructSequence_SetItem_Ptr)(p, pos, o);
}

void Python::PySys_AddWarnOption(const wchar_t* s)
{
    return (*PySys_AddWarnOption_Ptr)(s);
}

void Python::PySys_AddWarnOptionUnicode(PyObject* unicode)
{
    return (*PySys_AddWarnOptionUnicode_Ptr)(unicode);
}

void Python::PySys_AddXOption(const wchar_t* option)
{
    return (*PySys_AddXOption_Ptr)(option);
}

PyObject* Python::PySys_GetObject(const char* name)
{
    return (*PySys_GetObject_Ptr)(name);
}

PyObject* Python::PySys_GetXOptions()
{
    return (*PySys_GetXOptions_Ptr)();
}

int Python::PySys_HasWarnOptions()
{
    return (*PySys_HasWarnOptions_Ptr)();
}

void Python::PySys_ResetWarnOptions()
{
    return (*PySys_ResetWarnOptions_Ptr)();
}

void Python::PySys_SetArgv(int argc, wchar_t** argv)
{
    return (*PySys_SetArgv_Ptr)(argc, argv);
}

void Python::PySys_SetArgvEx(int argc, wchar_t** argv, int updatepath)
{
    return (*PySys_SetArgvEx_Ptr)(argc, argv, updatepath);
}

int Python::PySys_SetObject(const char* name, PyObject* v)
{
    return (*PySys_SetObject_Ptr)(name, v);
}

void Python::PySys_SetPath(const wchar_t* path)
{
    return (*PySys_SetPath_Ptr)(path);
}

void Python::PyThreadState_Clear(PyThreadState* tstate)
{
    return (*PyThreadState_Clear_Ptr)(tstate);
}

void Python::PyThreadState_Delete(PyThreadState* tstate)
{
    return (*PyThreadState_Delete_Ptr)(tstate);
}

PyThreadState* Python::PyThreadState_Get()
{
    return (*PyThreadState_Get_Ptr)();
}

PyObject* Python::PyThreadState_GetDict()
{
    return (*PyThreadState_GetDict_Ptr)();
}

#if HAS_PYTHON_VERSION(0x03090000)
PyFrameObject* Python::PyThreadState_GetFrame(PyThreadState* tstate)
{
    return (*PyThreadState_GetFrame_Ptr)(tstate);
}
#endif

uint64_t Python::PyThreadState_GetID(PyThreadState* tstate)
{
    return (*PyThreadState_GetID_Ptr)(tstate);
}

PyInterpreterState* Python::PyThreadState_GetInterpreter(PyThreadState* tstate)
{
    return (*PyThreadState_GetInterpreter_Ptr)(tstate);
}

PyThreadState* Python::PyThreadState_New(PyInterpreterState* interp)
{
    return (*PyThreadState_New_Ptr)(interp);
}

int Python::PyThreadState_SetAsyncExc(unsigned long id, PyObject* exc)
{
    return (*PyThreadState_SetAsyncExc_Ptr)(id, exc);
}

PyThreadState* Python::PyThreadState_Swap(PyThreadState* tstate)
{
    return (*PyThreadState_Swap_Ptr)(tstate);
}

PyObject* Python::PyThread_GetInfo()
{
    return (*PyThread_GetInfo_Ptr)();
}

void Python::PyThread_ReInitTLS()
{
    return (*PyThread_ReInitTLS_Ptr)();
}

int Python::PyThread_acquire_lock(PyThread_type_lock lock, int intr_flag)
{
    return (*PyThread_acquire_lock_Ptr)(lock, intr_flag);
}

PyLockStatus Python::PyThread_acquire_lock_timed(PyThread_type_lock lock, long long microseconds, int intr_flag)
{
    return (*PyThread_acquire_lock_timed_Ptr)(lock, microseconds, intr_flag);
}

PyThread_type_lock Python::PyThread_allocate_lock()
{
    return (*PyThread_allocate_lock_Ptr)();
}

int Python::PyThread_create_key()
{
    return (*PyThread_create_key_Ptr)();
}

void Python::PyThread_delete_key(int key)
{
    return (*PyThread_delete_key_Ptr)(key);
}

void Python::PyThread_delete_key_value(int key)
{
    return (*PyThread_delete_key_value_Ptr)(key);
}

void Python::PyThread_exit_thread()
{
    return (*PyThread_exit_thread_Ptr)();
}

void Python::PyThread_free_lock(PyThread_type_lock lock)
{
    return (*PyThread_free_lock_Ptr)(lock);
}

void* Python::PyThread_get_key_value(int key)
{
    return (*PyThread_get_key_value_Ptr)(key);
}

size_t Python::PyThread_get_stacksize()
{
    return (*PyThread_get_stacksize_Ptr)();
}

unsigned long Python::PyThread_get_thread_ident()
{
    return (*PyThread_get_thread_ident_Ptr)();
}

unsigned long Python::PyThread_get_thread_native_id()
{
    return (*PyThread_get_thread_native_id_Ptr)();
}

void Python::PyThread_init_thread()
{
    return (*PyThread_init_thread_Ptr)();
}

void Python::PyThread_release_lock(PyThread_type_lock lock)
{
    return (*PyThread_release_lock_Ptr)(lock);
}

int Python::PyThread_set_key_value(int key, void* value)
{
    return (*PyThread_set_key_value_Ptr)(key, value);
}

int Python::PyThread_set_stacksize(size_t size)
{
    return (*PyThread_set_stacksize_Ptr)(size);
}

unsigned long Python::PyThread_start_new_thread(void (*initfunc)(void*) , void* pdata)
{
    return (*PyThread_start_new_thread_Ptr)(initfunc, pdata);
}

#if HAS_PYTHON_VERSION(0x03070000)
Py_tss_t* Python::PyThread_tss_alloc()
{
    return (*PyThread_tss_alloc_Ptr)();
}

int Python::PyThread_tss_create(Py_tss_t* key)
{
    return (*PyThread_tss_create_Ptr)(key);
}

void Python::PyThread_tss_delete(Py_tss_t* key)
{
    return (*PyThread_tss_delete_Ptr)(key);
}

void Python::PyThread_tss_free(Py_tss_t* key)
{
    return (*PyThread_tss_free_Ptr)(key);
}

void* Python::PyThread_tss_get(Py_tss_t* key)
{
    return (*PyThread_tss_get_Ptr)(key);
}

int Python::PyThread_tss_is_created(Py_tss_t* key)
{
    return (*PyThread_tss_is_created_Ptr)(key);
}

int Python::PyThread_tss_set(Py_tss_t* key, void* value)
{
    return (*PyThread_tss_set_Ptr)(key, value);
}
#endif

#if HAS_PYTHON_VERSION(0x03090000)
int Python::PyTraceBack_Here(PyFrameObject* f)
{
    return (*PyTraceBack_Here_Ptr)(f);
}
#endif

int Python::PyTraceBack_Print(PyObject* v, PyObject* f)
{
    return (*PyTraceBack_Print_Ptr)(v, f);
}

PyObject* Python::PyTuple_GetItem(PyObject* tuple, Py_ssize_t index)
{
    return (*PyTuple_GetItem_Ptr)(tuple, index);
}

PyObject* Python::PyTuple_GetSlice(PyObject* tuple, Py_ssize_t low, Py_ssize_t high)
{
    return (*PyTuple_GetSlice_Ptr)(tuple, low, high);
}

PyObject* Python::PyTuple_New(Py_ssize_t size)
{
    return (*PyTuple_New_Ptr)(size);
}

int Python::PyTuple_SetItem(PyObject* tuple, Py_ssize_t index, PyObject* item)
{
    return (*PyTuple_SetItem_Ptr)(tuple, index, item);
}

Py_ssize_t Python::PyTuple_Size(PyObject* tuple)
{
    return (*PyTuple_Size_Ptr)(tuple);
}

unsigned int Python::PyType_ClearCache()
{
    return (*PyType_ClearCache_Ptr)();
}

#if !defined(Py_LIMITED_API)
const char* Python::_PyType_Name(PyTypeObject* type)
{
    return (*PyType_Name_Ptr)(type);
}
#endif

PyObject* Python::PyType_FromModuleAndSpec(PyObject* module, PyType_Spec* spec, PyObject* bases)
{
    return (*PyType_FromModuleAndSpec_Ptr)(module, spec, bases);
}

PyObject* Python::PyType_FromSpec(PyType_Spec* spec)
{
    return (*PyType_FromSpec_Ptr)(spec);
}

PyObject* Python::PyType_FromSpecWithBases(PyType_Spec* spec, PyObject* bases)
{
    return (*PyType_FromSpecWithBases_Ptr)(spec, bases);
}

PyObject* Python::PyType_GenericAlloc(PyTypeObject* type, Py_ssize_t nitems)
{
    return (*PyType_GenericAlloc_Ptr)(type, nitems);
}

PyObject* Python::PyType_GenericNew(PyTypeObject* type, PyObject* args, PyObject* kwds)
{
    return (*PyType_GenericNew_Ptr)(type, args, kwds);
}

unsigned long Python::PyType_GetFlags(PyTypeObject* type)
{
    return (*PyType_GetFlags_Ptr)(type);
}

PyObject* Python::PyType_GetModule(PyTypeObject* type)
{
    return (*PyType_GetModule_Ptr)(type);
}

void* Python::PyType_GetModuleState(PyTypeObject* type)
{
    return (*PyType_GetModuleState_Ptr)(type);
}

PyObject* Python::PyType_GetName(PyTypeObject* type)
{
    return (*PyType_GetName_Ptr)(type);
}

PyObject* Python::PyType_GetQualName(PyTypeObject* type)
{
    return (*PyType_GetQualName_Ptr)(type);
}

void* Python::PyType_GetSlot(PyTypeObject* type, int slot)
{
    return (*PyType_GetSlot_Ptr)(type, slot);
}

int Python::PyType_IsSubtype(PyTypeObject* child, PyTypeObject* parent)
{
    return (*PyType_IsSubtype_Ptr)(child, parent);
}

void Python::PyType_Modified(PyTypeObject* type)
{
    return (*PyType_Modified_Ptr)(type);
}

int Python::PyType_Ready(PyTypeObject* type)
{
    return (*PyType_Ready_Ptr)(type);
}

PyObject* Python::PyUnicodeDecodeError_Create(const char* encoding, const char* object, Py_ssize_t length, Py_ssize_t start, Py_ssize_t end, const char* reason)
{
    return (*PyUnicodeDecodeError_Create_Ptr)(encoding, object, length, start, end, reason);
}

PyObject* Python::PyUnicodeDecodeError_GetEncoding(PyObject* exc)
{
    return (*PyUnicodeDecodeError_GetEncoding_Ptr)(exc);
}

int Python::PyUnicodeDecodeError_GetEnd(PyObject* exc, Py_ssize_t* end)
{
    return (*PyUnicodeDecodeError_GetEnd_Ptr)(exc, end);
}

PyObject* Python::PyUnicodeDecodeError_GetObject(PyObject* exc)
{
    return (*PyUnicodeDecodeError_GetObject_Ptr)(exc);
}

PyObject* Python::PyUnicodeDecodeError_GetReason(PyObject* exc)
{
    return (*PyUnicodeDecodeError_GetReason_Ptr)(exc);
}

int Python::PyUnicodeDecodeError_GetStart(PyObject* exc, Py_ssize_t* start)
{
    return (*PyUnicodeDecodeError_GetStart_Ptr)(exc, start);
}

int Python::PyUnicodeDecodeError_SetEnd(PyObject* exc, Py_ssize_t end)
{
    return (*PyUnicodeDecodeError_SetEnd_Ptr)(exc, end);
}

int Python::PyUnicodeDecodeError_SetReason(PyObject* exc, const char* reason)
{
    return (*PyUnicodeDecodeError_SetReason_Ptr)(exc, reason);
}

int Python::PyUnicodeDecodeError_SetStart(PyObject* exc, Py_ssize_t start)
{
    return (*PyUnicodeDecodeError_SetStart_Ptr)(exc, start);
}

PyObject* Python::PyUnicodeEncodeError_GetEncoding(PyObject* exc)
{
    return (*PyUnicodeEncodeError_GetEncoding_Ptr)(exc);
}

int Python::PyUnicodeEncodeError_GetEnd(PyObject* exc, Py_ssize_t* end)
{
    return (*PyUnicodeEncodeError_GetEnd_Ptr)(exc, end);
}

PyObject* Python::PyUnicodeEncodeError_GetObject(PyObject* exc)
{
    return (*PyUnicodeEncodeError_GetObject_Ptr)(exc);
}

PyObject* Python::PyUnicodeEncodeError_GetReason(PyObject* exc)
{
    return (*PyUnicodeEncodeError_GetReason_Ptr)(exc);
}

int Python::PyUnicodeEncodeError_GetStart(PyObject* exc, Py_ssize_t* start)
{
    return (*PyUnicodeEncodeError_GetStart_Ptr)(exc, start);
}

int Python::PyUnicodeEncodeError_SetEnd(PyObject* exc, Py_ssize_t end)
{
    return (*PyUnicodeEncodeError_SetEnd_Ptr)(exc, end);
}

int Python::PyUnicodeEncodeError_SetReason(PyObject* exc, const char* reason)
{
    return (*PyUnicodeEncodeError_SetReason_Ptr)(exc, reason);
}

int Python::PyUnicodeEncodeError_SetStart(PyObject* exc, Py_ssize_t start)
{
    return (*PyUnicodeEncodeError_SetStart_Ptr)(exc, start);
}

int Python::PyUnicodeTranslateError_GetEnd(PyObject* exc, Py_ssize_t* end)
{
    return (*PyUnicodeTranslateError_GetEnd_Ptr)(exc, end);
}

PyObject* Python::PyUnicodeTranslateError_GetObject(PyObject* exc)
{
    return (*PyUnicodeTranslateError_GetObject_Ptr)(exc);
}

PyObject* Python::PyUnicodeTranslateError_GetReason(PyObject* exc)
{
    return (*PyUnicodeTranslateError_GetReason_Ptr)(exc);
}

int Python::PyUnicodeTranslateError_GetStart(PyObject* exc, Py_ssize_t* start)
{
    return (*PyUnicodeTranslateError_GetStart_Ptr)(exc, start);
}

int Python::PyUnicodeTranslateError_SetEnd(PyObject* exc, Py_ssize_t end)
{
    return (*PyUnicodeTranslateError_SetEnd_Ptr)(exc, end);
}

int Python::PyUnicodeTranslateError_SetReason(PyObject* exc, const char* reason)
{
    return (*PyUnicodeTranslateError_SetReason_Ptr)(exc, reason);
}

int Python::PyUnicodeTranslateError_SetStart(PyObject* exc, Py_ssize_t start)
{
    return (*PyUnicodeTranslateError_SetStart_Ptr)(exc, start);
}

void Python::PyUnicode_Append(PyObject** pleft, PyObject* right)
{
    return (*PyUnicode_Append_Ptr)(pleft, right);
}

void Python::PyUnicode_AppendAndDel(PyObject** pleft, PyObject* right)
{
    return (*PyUnicode_AppendAndDel_Ptr)(pleft, right);
}

PyObject* Python::PyUnicode_AsASCIIString(PyObject* unicode)
{
    return (*PyUnicode_AsASCIIString_Ptr)(unicode);
}

PyObject* Python::PyUnicode_AsCharmapString(PyObject* unicode, PyObject* mapping)
{
    return (*PyUnicode_AsCharmapString_Ptr)(unicode, mapping);
}

PyObject* Python::PyUnicode_AsDecodedObject(PyObject* unicode, const char* encoding, const char* errors)
{
    return (*PyUnicode_AsDecodedObject_Ptr)(unicode, encoding, errors);
}

PyObject* Python::PyUnicode_AsDecodedUnicode(PyObject* unicode, const char* encoding, const char* errors)
{
    return (*PyUnicode_AsDecodedUnicode_Ptr)(unicode, encoding, errors);
}

PyObject* Python::PyUnicode_AsEncodedObject(PyObject* unicode, const char* encoding, const char* errors)
{
    return (*PyUnicode_AsEncodedObject_Ptr)(unicode, encoding, errors);
}

PyObject* Python::PyUnicode_AsEncodedString(PyObject* unicode, const char* encoding, const char* errors)
{
    return (*PyUnicode_AsEncodedString_Ptr)(unicode, encoding, errors);
}

PyObject* Python::PyUnicode_AsEncodedUnicode(PyObject* unicode, const char* encoding, const char* errors)
{
    return (*PyUnicode_AsEncodedUnicode_Ptr)(unicode, encoding, errors);
}

PyObject* Python::PyUnicode_AsLatin1String(PyObject* unicode)
{
    return (*PyUnicode_AsLatin1String_Ptr)(unicode);
}

PyObject* Python::PyUnicode_AsRawUnicodeEscapeString(PyObject* unicode)
{
    return (*PyUnicode_AsRawUnicodeEscapeString_Ptr)(unicode);
}

Py_UCS4* Python::PyUnicode_AsUCS4(PyObject* unicode, Py_UCS4* buffer, Py_ssize_t buflen, int copy_null)
{
    return (*PyUnicode_AsUCS4_Ptr)(unicode, buffer, buflen, copy_null);
}

Py_UCS4* Python::PyUnicode_AsUCS4Copy(PyObject* unicode)
{
    return (*PyUnicode_AsUCS4Copy_Ptr)(unicode);
}

PyObject* Python::PyUnicode_AsUTF16String(PyObject* unicode)
{
    return (*PyUnicode_AsUTF16String_Ptr)(unicode);
}

PyObject* Python::PyUnicode_AsUTF32String(PyObject* unicode)
{
    return (*PyUnicode_AsUTF32String_Ptr)(unicode);
}

const char* Python::PyUnicode_AsUTF8AndSize(PyObject* unicode, Py_ssize_t* size)
{
    return (*PyUnicode_AsUTF8AndSize_Ptr)(unicode, size);
}

PyObject* Python::PyUnicode_AsUTF8String(PyObject* unicode)
{
    return (*PyUnicode_AsUTF8String_Ptr)(unicode);
}

PyObject* Python::PyUnicode_AsUnicodeEscapeString(PyObject* unicode)
{
    return (*PyUnicode_AsUnicodeEscapeString_Ptr)(unicode);
}

Py_ssize_t Python::PyUnicode_AsWideChar(PyObject* unicode, wchar_t* w, Py_ssize_t size)
{
    return (*PyUnicode_AsWideChar_Ptr)(unicode, w, size);
}

wchar_t* Python::PyUnicode_AsWideCharString(PyObject* unicode, Py_ssize_t* size)
{
    return (*PyUnicode_AsWideCharString_Ptr)(unicode, size);
}

PyObject* Python::PyUnicode_BuildEncodingMap(PyObject* string)
{
    return (*PyUnicode_BuildEncodingMap_Ptr)(string);
}

int Python::PyUnicode_Compare(PyObject* left, PyObject* right)
{
    return (*PyUnicode_Compare_Ptr)(left, right);
}

int Python::PyUnicode_CompareWithASCIIString(PyObject* left, const char* right)
{
    return (*PyUnicode_CompareWithASCIIString_Ptr)(left, right);
}

PyObject* Python::PyUnicode_Concat(PyObject* left, PyObject* right)
{
    return (*PyUnicode_Concat_Ptr)(left, right);
}

int Python::PyUnicode_Contains(PyObject* container, PyObject* element)
{
    return (*PyUnicode_Contains_Ptr)(container, element);
}

Py_ssize_t Python::PyUnicode_Count(PyObject* str, PyObject* substr, Py_ssize_t start, Py_ssize_t end)
{
    return (*PyUnicode_Count_Ptr)(str, substr, start, end);
}

PyObject* Python::PyUnicode_Decode(const char* s, Py_ssize_t size, const char* encoding, const char* errors)
{
    return (*PyUnicode_Decode_Ptr)(s, size, encoding, errors);
}

PyObject* Python::PyUnicode_DecodeASCII(const char* string, Py_ssize_t length, const char* errors)
{
    return (*PyUnicode_DecodeASCII_Ptr)(string, length, errors);
}

PyObject* Python::PyUnicode_DecodeCharmap(const char* string, Py_ssize_t length, PyObject* mapping, const char* errors)
{
    return (*PyUnicode_DecodeCharmap_Ptr)(string, length, mapping, errors);
}

PyObject* Python::PyUnicode_DecodeFSDefault(const char* s)
{
    return (*PyUnicode_DecodeFSDefault_Ptr)(s);
}

PyObject* Python::PyUnicode_DecodeFSDefaultAndSize(const char* s, Py_ssize_t size)
{
    return (*PyUnicode_DecodeFSDefaultAndSize_Ptr)(s, size);
}

PyObject* Python::PyUnicode_DecodeLatin1(const char* string, Py_ssize_t length, const char* errors)
{
    return (*PyUnicode_DecodeLatin1_Ptr)(string, length, errors);
}

PyObject* Python::PyUnicode_DecodeLocale(const char* str, const char* errors)
{
    return (*PyUnicode_DecodeLocale_Ptr)(str, errors);
}

PyObject* Python::PyUnicode_DecodeLocaleAndSize(const char* str, Py_ssize_t len, const char* errors)
{
    return (*PyUnicode_DecodeLocaleAndSize_Ptr)(str, len, errors);
}

PyObject* Python::PyUnicode_DecodeRawUnicodeEscape(const char* string, Py_ssize_t length, const char* errors)
{
    return (*PyUnicode_DecodeRawUnicodeEscape_Ptr)(string, length, errors);
}

PyObject* Python::PyUnicode_DecodeUTF16(const char* string, Py_ssize_t length, const char* errors, int* byteorder)
{
    return (*PyUnicode_DecodeUTF16_Ptr)(string, length, errors, byteorder);
}

PyObject* Python::PyUnicode_DecodeUTF16Stateful(const char* string, Py_ssize_t length, const char* errors, int* byteorder, Py_ssize_t* consumed)
{
    return (*PyUnicode_DecodeUTF16Stateful_Ptr)(string, length, errors, byteorder, consumed);
}

PyObject* Python::PyUnicode_DecodeUTF32(const char* string, Py_ssize_t length, const char* errors, int* byteorder)
{
    return (*PyUnicode_DecodeUTF32_Ptr)(string, length, errors, byteorder);
}

PyObject* Python::PyUnicode_DecodeUTF32Stateful(const char* string, Py_ssize_t length, const char* errors, int* byteorder, Py_ssize_t* consumed)
{
    return (*PyUnicode_DecodeUTF32Stateful_Ptr)(string, length, errors, byteorder, consumed);
}

PyObject* Python::PyUnicode_DecodeUTF7(const char* string, Py_ssize_t length, const char* errors)
{
    return (*PyUnicode_DecodeUTF7_Ptr)(string, length, errors);
}

PyObject* Python::PyUnicode_DecodeUTF7Stateful(const char* string, Py_ssize_t length, const char* errors, Py_ssize_t* consumed)
{
    return (*PyUnicode_DecodeUTF7Stateful_Ptr)(string, length, errors, consumed);
}

PyObject* Python::PyUnicode_DecodeUTF8(const char* string, Py_ssize_t length, const char* errors)
{
    return (*PyUnicode_DecodeUTF8_Ptr)(string, length, errors);
}

PyObject* Python::PyUnicode_DecodeUTF8Stateful(const char* string, Py_ssize_t length, const char* errors, Py_ssize_t* consumed)
{
    return (*PyUnicode_DecodeUTF8Stateful_Ptr)(string, length, errors, consumed);
}

PyObject* Python::PyUnicode_DecodeUnicodeEscape(const char* string, Py_ssize_t length, const char* errors)
{
    return (*PyUnicode_DecodeUnicodeEscape_Ptr)(string, length, errors);
}

PyObject* Python::PyUnicode_EncodeFSDefault(PyObject* unicode)
{
    return (*PyUnicode_EncodeFSDefault_Ptr)(unicode);
}

PyObject* Python::PyUnicode_EncodeLocale(PyObject* unicode, const char* errors)
{
    return (*PyUnicode_EncodeLocale_Ptr)(unicode, errors);
}

int Python::PyUnicode_FSConverter(PyObject* obj, void* result)
{
    return (*PyUnicode_FSConverter_Ptr)(obj, result);
}

int Python::PyUnicode_FSDecoder(PyObject* obj, void* result)
{
    return (*PyUnicode_FSDecoder_Ptr)(obj, result);
}

Py_ssize_t Python::PyUnicode_Find(PyObject* str, PyObject* substr, Py_ssize_t start, Py_ssize_t end, int direction)
{
    return (*PyUnicode_Find_Ptr)(str, substr, start, end, direction);
}

Py_ssize_t Python::PyUnicode_FindChar(PyObject* str, Py_UCS4 ch, Py_ssize_t start, Py_ssize_t end, int direction)
{
    return (*PyUnicode_FindChar_Ptr)(str, ch, start, end, direction);
}

PyObject* Python::PyUnicode_Format(PyObject* format, PyObject* args)
{
    return (*PyUnicode_Format_Ptr)(format, args);
}

PyObject* Python::PyUnicode_FromEncodedObject(PyObject* obj, const char* encoding, const char* errors)
{
    return (*PyUnicode_FromEncodedObject_Ptr)(obj, encoding, errors);
}

PyObject* Python::PyUnicode_FromFormat(const char* format, ...)
{
    va_list arguments;
    va_start(arguments, format);
    PyObject* result = PyUnicode_FromFormatV(format, arguments);
    va_end(arguments);
    return result;
}

PyObject* Python::PyUnicode_FromFormatV(const char* format, va_list vargs)
{
    return (*PyUnicode_FromFormatV_Ptr)(format, vargs);
}

PyObject* Python::PyUnicode_FromObject(PyObject* obj)
{
    return (*PyUnicode_FromObject_Ptr)(obj);
}

PyObject* Python::PyUnicode_FromOrdinal(int ordinal)
{
    return (*PyUnicode_FromOrdinal_Ptr)(ordinal);
}

PyObject* Python::PyUnicode_FromString(const char* u)
{
    return (*PyUnicode_FromString_Ptr)(u);
}

PyObject* Python::PyUnicode_FromStringAndSize(const char* u, Py_ssize_t size)
{
    return (*PyUnicode_FromStringAndSize_Ptr)(u, size);
}

PyObject* Python::PyUnicode_FromWideChar(const wchar_t* w, Py_ssize_t size)
{
    return (*PyUnicode_FromWideChar_Ptr)(w, size);
}

const char* Python::PyUnicode_GetDefaultEncoding()
{
    return (*PyUnicode_GetDefaultEncoding_Ptr)();
}

Py_ssize_t Python::PyUnicode_GetLength(PyObject* unicode)
{
    return (*PyUnicode_GetLength_Ptr)(unicode);
}

Py_ssize_t Python::PyUnicode_GetSize(PyObject* unicode)
{
    return (*PyUnicode_GetSize_Ptr)(unicode);
}

PyObject* Python::PyUnicode_InternFromString(const char* u)
{
    return (*PyUnicode_InternFromString_Ptr)(u);
}

void Python::PyUnicode_InternImmortal(PyObject** unicode)
{
    return (*PyUnicode_InternImmortal_Ptr)(unicode);
}

void Python::PyUnicode_InternInPlace(PyObject** unicode)
{
    return (*PyUnicode_InternInPlace_Ptr)(unicode);
}

int Python::PyUnicode_IsIdentifier(PyObject* s)
{
    return (*PyUnicode_IsIdentifier_Ptr)(s);
}

PyObject* Python::PyUnicode_Join(PyObject* separator, PyObject* seq)
{
    return (*PyUnicode_Join_Ptr)(separator, seq);
}

PyObject* Python::PyUnicode_Partition(PyObject* s, PyObject* sep)
{
    return (*PyUnicode_Partition_Ptr)(s, sep);
}

PyObject* Python::PyUnicode_RPartition(PyObject* s, PyObject* sep)
{
    return (*PyUnicode_RPartition_Ptr)(s, sep);
}

PyObject* Python::PyUnicode_RSplit(PyObject* s, PyObject* sep, Py_ssize_t maxsplit)
{
    return (*PyUnicode_RSplit_Ptr)(s, sep, maxsplit);
}

Py_UCS4 Python::PyUnicode_ReadChar(PyObject* unicode, Py_ssize_t index)
{
    return (*PyUnicode_ReadChar_Ptr)(unicode, index);
}

PyObject* Python::PyUnicode_Replace(PyObject* str, PyObject* substr, PyObject* replstr, Py_ssize_t maxcount)
{
    return (*PyUnicode_Replace_Ptr)(str, substr, replstr, maxcount);
}

int Python::PyUnicode_Resize(PyObject** unicode, Py_ssize_t length)
{
    return (*PyUnicode_Resize_Ptr)(unicode, length);
}

PyObject* Python::PyUnicode_RichCompare(PyObject* left, PyObject* right, int op)
{
    return (*PyUnicode_RichCompare_Ptr)(left, right, op);
}

PyObject* Python::PyUnicode_Split(PyObject* s, PyObject* sep, Py_ssize_t maxsplit)
{
    return (*PyUnicode_Split_Ptr)(s, sep, maxsplit);
}

PyObject* Python::PyUnicode_Splitlines(PyObject* s, int keepends)
{
    return (*PyUnicode_Splitlines_Ptr)(s, keepends);
}

PyObject* Python::PyUnicode_Substring(PyObject* str, Py_ssize_t start, Py_ssize_t end)
{
    return (*PyUnicode_Substring_Ptr)(str, start, end);
}

Py_ssize_t Python::PyUnicode_Tailmatch(PyObject* str, PyObject* substr, Py_ssize_t start, Py_ssize_t end, int direction)
{
    return (*PyUnicode_Tailmatch_Ptr)(str, substr, start, end, direction);
}

PyObject* Python::PyUnicode_Translate(PyObject* str, PyObject* table, const char* errors)
{
    return (*PyUnicode_Translate_Ptr)(str, table, errors);
}

int Python::PyUnicode_WriteChar(PyObject* unicode, Py_ssize_t index, Py_UCS4 character)
{
    return (*PyUnicode_WriteChar_Ptr)(unicode, index, character);
}

PyObject* Python::PyWeakref_GetObject(PyObject* ref)
{
    return (*PyWeakref_GetObject_Ptr)(ref);
}

PyObject* Python::PyWeakref_NewProxy(PyObject* ob, PyObject* callback)
{
    return (*PyWeakref_NewProxy_Ptr)(ob, callback);
}

PyObject* Python::PyWeakref_NewRef(PyObject* ob, PyObject* callback)
{
    return (*PyWeakref_NewRef_Ptr)(ob, callback);
}

PyObject* Python::PyWrapper_New(PyObject* descr, PyObject* self)
{
    return (*PyWrapper_New_Ptr)(descr, self);
}

int Python::Py_AddPendingCall(int (*func)(void*), void* arg)
{
    return (*Py_AddPendingCall_Ptr)(func, arg);
}

int Python::Py_AtExit(void (*func)())
{
    return (*Py_AtExit_Ptr)(func);
}

int Python::Py_BytesMain(int argc, char** argv)
{
    return (*Py_BytesMain_Ptr)(argc, argv);
}

PyObject* (Python::Py_CompileString)(const char* str, const char* filename, int start)
{
    return (*Py_CompileString_Ptr)(str, filename, start);
}

void Python::Py_DecRef(PyObject* obj)
{
    return (*Py_DecRef_Ptr)(obj);
}

wchar_t* Python::Py_DecodeLocale(const char* arg, size_t* size)
{
    return (*Py_DecodeLocale_Ptr)(arg, size);
}

char* Python::Py_EncodeLocale(const wchar_t* text, size_t* error_pos)
{
    return (*Py_EncodeLocale_Ptr)(text, error_pos);
}

void Python::Py_EndInterpreter(PyThreadState* tstate)
{
    return (*Py_EndInterpreter_Ptr)(tstate);
}

int (Python::Py_EnterRecursiveCall)(const char* where)
{
    return (*Py_EnterRecursiveCall_Ptr)(where);
}

void Python::Py_Exit(int exit_code)
{
    return (*Py_Exit_Ptr)(exit_code);
}

void (Python::Py_FatalError)(const char* message)
{
    return (*Py_FatalError_Ptr)(message);
}

void Python::Py_Finalize()
{
    return (*Py_Finalize_Ptr)();
}

int Python::Py_FinalizeEx()
{
    return (*Py_FinalizeEx_Ptr)();
}

PyObject* Python::Py_GenericAlias(PyObject* origin, PyObject* args)
{
    return (*Py_GenericAlias_Ptr)(origin, args);
}

const char* Python::Py_GetBuildInfo()
{
    return (*Py_GetBuildInfo_Ptr)();
}

const char* Python::Py_GetCompiler()
{
    return (*Py_GetCompiler_Ptr)();
}

const char* Python::Py_GetCopyright()
{
    return (*Py_GetCopyright_Ptr)();
}

wchar_t* Python::Py_GetExecPrefix()
{
    return (*Py_GetExecPrefix_Ptr)();
}

wchar_t* Python::Py_GetPath()
{
    return (*Py_GetPath_Ptr)();
}

const char* Python::Py_GetPlatform()
{
    return (*Py_GetPlatform_Ptr)();
}

wchar_t* Python::Py_GetPrefix()
{
    return (*Py_GetPrefix_Ptr)();
}

wchar_t* Python::Py_GetProgramFullPath()
{
    return (*Py_GetProgramFullPath_Ptr)();
}

wchar_t* Python::Py_GetProgramName()
{
    return (*Py_GetProgramName_Ptr)();
}

wchar_t* Python::Py_GetPythonHome()
{
    return (*Py_GetPythonHome_Ptr)();
}

int Python::Py_GetRecursionLimit()
{
    return (*Py_GetRecursionLimit_Ptr)();
}

const char* Python::Py_GetVersion()
{
    return (*Py_GetVersion_Ptr)();
}

void Python::Py_IncRef(PyObject* obj)
{
    return (*Py_IncRef_Ptr)(obj);
}

void Python::Py_Initialize()
{
    return (*Py_Initialize_Ptr)();
}

void Python::Py_InitializeEx(int initsigs)
{
    return (*Py_InitializeEx_Ptr)(initsigs);
}

int (Python::Py_Is)(PyObject* x, PyObject* y)
{
    return x == y; //(*Py_Is_Ptr)(x, y);
}

int (Python::Py_IsFalse)(PyObject* x)
{
    return (Py_Is)((x), static_cast<PyObject*>(Py_FalseStruct_Ptr));
}

int Python::Py_IsInitialized()
{
    return (*Py_IsInitialized_Ptr)();
}

int (Python::Py_IsNone)(PyObject* x)
{
    return (Py_Is)((x), static_cast<PyObject*>(Py_NoneStruct_Ptr));
}

int (Python::Py_IsTrue)(PyObject* x)
{
    return (Py_Is)((x), static_cast<PyObject*>(Py_TrueStruct_Ptr));
}

int (Python::Py_IS_TYPE)(PyObject* o, PyTypeObject* type)
{
    return (Py_TYPE)(o) == type;  //(*Py_IS_TYPE_Ptr)(o, type);
}

PyTypeObject* (Python::Py_TYPE)(PyObject *o)
{
    return o->ob_type;  //(*Py_TYPE_Ptr)(o);
}

void (Python::Py_LeaveRecursiveCall)()
{
    return (*Py_LeaveRecursiveCall_Ptr)();
}

int Python::Py_Main(int argc, wchar_t** argv)
{
    return (*Py_Main_Ptr)(argc, argv);
}

int Python::Py_MakePendingCalls()
{
    return (*Py_MakePendingCalls_Ptr)();
}

PyThreadState* Python::Py_NewInterpreter()
{
    return (*Py_NewInterpreter_Ptr)();
}

#ifdef Py_NewRef
PyObject* (Python::Py_NewRef)(PyObject* obj)
{
    return (*Py_NewRef_Ptr)(obj);
}
#endif

int Python::Py_ReprEnter(PyObject* object)
{
    return (*Py_ReprEnter_Ptr)(object);
}

void Python::Py_ReprLeave(PyObject* object)
{
    return (*Py_ReprLeave_Ptr)(object);
}

void Python::Py_SetPath(const wchar_t* path)
{
    return (*Py_SetPath_Ptr)(path);
}

void Python::Py_SetProgramName(const wchar_t* name)
{
    return (*Py_SetProgramName_Ptr)(name);
}

void Python::Py_SetPythonHome(const wchar_t* home)
{
    return (*Py_SetPythonHome_Ptr)(home);
}

void Python::Py_SetRecursionLimit(int limit)
{
    return (*Py_SetRecursionLimit_Ptr)(limit);
}

PyObject* Python::Py_VaBuildValue(const char* format, va_list vargs)
{
    return (*Py_VaBuildValue_Ptr)(format, vargs);
}

#if HAS_PYTHON_VERSION(0x030A0000)
PyObject* (Python::Py_XNewRef)(PyObject* obj)
{
    return (*Py_XNewRef_Ptr)(obj);
}
#endif

#endif