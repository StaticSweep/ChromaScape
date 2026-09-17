//
// Created by Brandon on 2022-09-21.
//

#ifndef REMOTEINPUT_PYTHONCOMMON_TEMPLATES_HXX
#define REMOTEINPUT_PYTHONCOMMON_TEMPLATES_HXX

#include "Python.hxx"

extern std::unique_ptr<Python> python;

#if !defined(METH_FASTCALL)
// Non-Keywords Args

template<typename T>
struct PyFunctionType
{
    typedef typename std::tuple_element<0, typename function_arguments_type<T>::type>::type self_type;
    typedef PyObject* (*fn_type)(self_type, PyObject*[], Py_ssize_t) noexcept;
    typedef PyObject* (*fn_keyword_type)(self_type, PyObject*[], Py_ssize_t, PyObject*) noexcept;
};

template<typename T, T fn>
typename std::enable_if<std::is_same<
        decltype(fn),
        typename PyFunctionType<T>::fn_type
        >::value, PyObject*>::type
python_fastcall(PyObject* self, PyObject* args)
{
    #if defined(Py_LIMITED_API)
    typedef struct
    {
        PyObject_VAR_HEAD
        PyObject *ob_item[1];
    } PyTupleObject;
    #endif

    typedef typename PyFunctionType<decltype(fn)>::self_type self_type;
    return fn(reinterpret_cast<self_type>(self),
              reinterpret_cast<PyTupleObject*>(args)->ob_item,
              python->PyTuple_Size(args));
}

// Keyword Args

template<typename T, T fn>
typename std::enable_if<std::is_same<
        decltype(fn),
        typename PyFunctionType<T>::fn_keyword_type
        >::value, PyObject*>::type
python_fastcall(PyObject* self, PyObject* args, PyObject* kwnames)
{
    #if defined(Py_LIMITED_API)
    typedef struct
    {
        PyObject_VAR_HEAD
        PyObject *ob_item[1];
    } PyTupleObject;
    #endif

    Py_ssize_t args_size = python->PyTuple_Size(args);
    Py_ssize_t keywords_size = python->PyDict_Size(kwnames);
    PyObject* keys = python->PyList_AsTuple(python->PyDict_Keys(kwnames));
    PyObject* values = python->PyDict_Values(kwnames);

    std::vector<PyObject*> arguments;
    arguments.reserve(args_size + keywords_size);
    for (std::size_t i = 0; i < arguments.size(); ++i)
    {
        arguments.push_back(reinterpret_cast<PyTupleObject*>(args)->ob_item[i]);
    }

    for (Py_ssize_t i = 0; i < keywords_size; ++i)
    {
        arguments.push_back(python->PyList_GetItem(values, i));
    }

    typedef typename PyFunctionType<decltype(fn)>::self_type self_type;
    return fn(reinterpret_cast<self_type>(self),
              &arguments[0],
              args_size,
              keys);
}

#define PYTHON_FASTCALL(f) (PyCFunction)python_fastcall<decltype(f), f>
#define METH_FASTCALL METH_VARARGS
#else
#define PYTHON_FASTCALL(f) reinterpret_cast<PyCFunction>(f)
#endif

template<typename T>
bool type_python_is_valid(PyObject* object, std::string& error)
{
    auto python_object_name = [](PyObject* object) -> std::string {
        #if defined(Py_LIMITED_API) && Py_LIMITED_API >= 0x030B1000
        PyObject* name = python->PyType_GetName(reinterpret_cast<PyTypeObject*>(python->PyObject_Type(object)));
        Py_ssize_t size = 0;
        const char* buffer = python->PyUnicode_AsUTF8AndSize(name, &size);
        if (buffer && size > 0)
        {
            return std::string(buffer, size);
        }
        return "UNKNOWN TYPE";
        #elif defined(Py_LIMITED_API) && Py_LIMITED_API >= 0x03020000
        PyObject* name = python->PyObject_GetAttrString(object, "__name__");
        if (name)
        {
            PyObject* utf8_string = python->PyUnicode_AsUTF8String(name);
            if (utf8_string)
            {
                Py_ssize_t size = 0;
                char* buffer = nullptr;
                python->PyBytes_AsStringAndSize(utf8_string, &buffer, &size);
                return std::string(buffer, size);;
            }
        }
        return "UNKNOWN TYPE";
        #elif !defined(Py_LIMITED_API)
        return python->_PyType_Name(reinterpret_cast<PyTypeObject*>(python->PyObject_Type(object)));
        #else
        PyObject* name = python->PyObject_GetAttrString(object, "__name__");
        if (name)
        {
            Py_ssize_t size = 0;
            const char* buffer = python->PyUnicode_AsUTF8AndSize(name, &size);
            if (buffer && size > 0)
            {
                return std::string(buffer, size);
            }
        }
        return "UNKNOWN TYPE";
        #endif
    };

    if constexpr(std::is_same<T, bool>::value)
    {
        error = "Expected Boolean Type. Received: ";
        error += python_object_name(object);
        return PyBool_Check(object);
    }
    else if constexpr(std::is_same<T, char>::value)
    {
        error = "Expected Char Type. Received: ";
        error += python_object_name(object);
        return PyUnicode_CheckExact(object);
    }
    else if constexpr(is_same_of<T, std::int8_t, std::int16_t, std::int32_t, std::int64_t>::value)
    {
        error = "Expected Int/Long Type. Received: ";
        error += python_object_name(object);
        return PyLong_CheckExact(object);
    }
    else if constexpr(is_same_of<T, std::uint8_t, std::uint16_t, std::uint32_t, std::uint64_t, std::size_t>::value)
    {
        error = "Expected Unsigned Int/Long Type. Received: ";
        error += python_object_name(object);
        return PyLong_CheckExact(object);
    }
    else if constexpr(is_same_of<T, float, double>::value)
    {
        error = "Expected Float/Double Type. Received: ";
        error += python_object_name(object);
        return PyFloat_Check(object);
    }
    else if constexpr(is_same_of<T, std::string, const char*>::value)
    {
        error = "Expected String Type. Received: ";
        error += python_object_name(object);
        return PyUnicode_CheckExact(object);
    }
    else if constexpr(std::is_same<T, std::vector<std::uint8_t>>::value)
    {
        error = "Expected List of bytes Type. Received: ";
        error += python_object_name(object);
        return PyObject_TypeCheck(object, &PyList_Type);
    }
    else if constexpr(std::is_same<T, EIOS*>::value)
    {
        error = "Expected EIOS Type. Received: ";
        error += python_object_name(object);
        #if defined(Py_LIMITED_API)
        return python->PyObject_IsInstance(object, reinterpret_cast<PyObject*>(PyEIOS_Type()));
        #else
        return python->PyObject_TypeCheck(object, PyEIOS_Type());
        #endif
    }
    else if constexpr(std::is_same<T, jobject>::value)
    {
        #if defined(Py_LIMITED_API) && Py_LIMITED_API >= 0x030A0000
        if ((python->Py_IsNone)(object))
        {
            return true;
        }
        #else
        if (object == Py_None)
        {
            return true;
        }
        #endif

        error = "Expected JavaObject Type. Received: ";
        error += python_object_name(object);
        #if defined(Py_LIMITED_API)
        return python->PyObject_IsInstance(object, reinterpret_cast<PyObject*>(PyJavaObject_Type()));
        #else
        return python->PyObject_TypeCheck(object, PyJavaObject_Type());
        #endif
    }
    else if constexpr(std::is_same<T, std::vector<jobject>>::value)
    {
        if ((python->PyList_Check)(object))
        {
            PyObject *iter = python->PyObject_GetIter(object);
            if (!iter)
            {
                return false;
            }

            while (PyObject* next = python->PyIter_Next(iter))
            {
                if (!type_python_is_valid<jobject>(next, error))
                {
                    return false;
                }
            }
            return true;
        }

        error = "Expected Array of JavaObject. Received: ";
        error += python_object_name(object);
        return false;
    }
    else if constexpr(std::is_void<T>::value)
    {
        error = "Expected Void Type. Received: ";
        error += python_object_name(object);
        static_assert(!std::is_void<T>::value, "INVALID TYPE: VOID!");
        return false;
    }
    else if constexpr(std::is_pointer<T>::value)
    {
        error = "Expected Pointer Type. Received: ";
        error += python_object_name(object);
        static_assert(!std::is_pointer<T>::value, "INVALID TYPE: POINTER!");
        return false;
    }
    else if constexpr(std::is_reference<T>::value)
    {
        error = "Expected Reference Type. Received: ";
        error += python_object_name(object);
        static_assert(!std::is_reference<T>::value, "INVALID TYPE: REFERENCE!");
        return false;
    }
    else
    {
        error = "Expected ??? Type. Received: ";
        error += python_object_name(object);
        static_assert(!std::is_pointer<T>::value && !std::is_reference<T>::value, "INVALID TYPE!");
        return false;
    }
}

template<typename T>
T from_python_object(PyObject* object)
{
    if constexpr(std::is_same<T, bool>::value)
    {
        return python->PyLong_AsLong(object);
    }
    else if constexpr(std::is_same<T, char>::value)
    {
        #if defined(Py_LIMITED_API) && Py_LIMITED_API >= 0x030A0000
        Py_ssize_t  size = 0;
        const char* buffer = python->PyUnicode_AsUTF8AndSize(object, &size);
        if (buffer && size == 1)
        {
            return *buffer;
        }
        return -1;
        // return *PyUnicode_AsUTF8(object);
        #else
        PyObject* utf8_string = python->PyUnicode_AsUTF8String(object);
        if (utf8_string)
        {
            Py_ssize_t size = 0;
            char* buffer = nullptr;
            python->PyBytes_AsStringAndSize(utf8_string, &buffer, &size);
            (python->Py_XDECREF)(utf8_string);
            return *buffer;
        }
        return -1;
        #endif
    }
    if constexpr(is_same_of<T, bool, char, std::int8_t, std::int16_t, std::int32_t>::value)
    {
        return python->PyLong_AsLong(object);
    }
    else if constexpr(std::is_same<T, std::int64_t>::value)
    {
        return python->PyLong_AsLongLong(object);
    }
    else if constexpr(is_same_of<T, std::uint8_t, std::uint16_t, std::uint32_t>::value)
    {
        return python->PyLong_AsUnsignedLong(object);
    }
    else if constexpr(std::is_same<T, std::uint64_t>::value)
    {
        return python->PyLong_AsUnsignedLongLong(object);
    }
    else if constexpr(std::is_same<T, std::size_t>::value)
    {
        return python->PyLong_AsSize_t(object);
    }
    else if constexpr(is_same_of<T, float, double>::value)
    {
        return static_cast<T>(python->PyFloat_AsDouble(object));
    }
    else if constexpr(std::is_same<T, std::string>::value)
    {
        #if defined(Py_LIMITED_API) && Py_LIMITED_API >= 0x030A0000
        Py_ssize_t size = 0;
        const char* buffer = python->PyUnicode_AsUTF8AndSize(object, &size);
        if (buffer && size > 0)
        {
            return std::string(buffer, size);
        }
        return std::string();
        //PyBytes_AsStringAndSize(object, &buffer, &size) != -1;
        #else
        PyObject* utf8_string = python->PyUnicode_AsUTF8String(object);
        if (utf8_string)
        {
            Py_ssize_t size = 0;
            char* buffer = nullptr;
            python->PyBytes_AsStringAndSize(utf8_string, &buffer, &size);
            (python->Py_XDECREF)(utf8_string);
            return std::string(buffer, size);
        }
        return std::string();
        #endif
    }
    else if constexpr(std::is_same<T, const char*>::value)
    {
        #if defined(Py_LIMITED_API) && Py_LIMITED_API >= 0x030A0000
        return python->PyUnicode_AsUTF8AndSize(object, nullptr);
        #else
        PyObject* utf8_string = python->PyUnicode_AsUTF8String(object);
        if (utf8_string)
        {
            Py_ssize_t size = 0;
            char* buffer = nullptr;
            python->PyBytes_AsStringAndSize(utf8_string, &buffer, &size);
            (python->Py_XDECREF)(utf8_string);
            return buffer;
        }
        return nullptr;
        #endif
    }
    else if constexpr(std::is_same<T, std::vector<std::uint8_t>>::value)
    {
        char* buffer = nullptr;
        Py_ssize_t size = 0;
        if (python->PyBytes_AsStringAndSize(object, &buffer, &size) != -1)
        {
            return std::vector<std::uint8_t>(buffer, buffer + size);
        }
        return std::vector<std::uint8_t>();
    }
    else if constexpr(std::is_same<T, EIOS*>::value)
    {
        return PythonUnwrapEIOS(reinterpret_cast<PyEIOS*>(object));
    }
    else if constexpr(std::is_same<T, jobject>::value)
    {
        return PythonUnwrapJavaObject(reinterpret_cast<PyJavaObject*>(object));
    }
    else if constexpr(std::is_same<T, jarray>::value)
    {
        return PythonUnwrapJavaArray(reinterpret_cast<PyJavaArray*>(object));
    }
    else if constexpr(std::is_enum<T>::value)
    {
        return static_cast<T>(from_python_object<typename std::underlying_type<T>::type>(object));
    }
    else if constexpr(std::is_void<T>::value)
    {
        static_assert(!std::is_void<T>::value, "INVALID TYPE: VOID!");
        return nullptr;
    }
    else if constexpr(std::is_pointer<T>::value)
    {
        static_assert(!std::is_pointer<T>::value, "INVALID TYPE: POINTER!");
        return nullptr;
    }
    else if constexpr(std::is_reference<T>::value)
    {
        static_assert(!std::is_reference<T>::value, "INVALID TYPE: REFERENCE!");
        return nullptr;
    }
    else
    {
        static_assert(!std::is_pointer<T>::value && !std::is_reference<T>::value, "INVALID TYPE!");
        return nullptr;
    }
}

template<typename T>
std::vector<T> from_python_array(PyObject* object)
{
    std::vector<T> result;
    if ((python->PyList_Check)(object))
    {
        PyObject *iter = python->PyObject_GetIter(object);
        if (!iter)
        {
            return result;
        }

        while (PyObject* next = python->PyIter_Next(iter))
        {
            if constexpr(std::is_same<T, PyJavaObject*>::value)
            {
                result.push_back(reinterpret_cast<PyJavaObject*>(next));
            }
            else if constexpr(std::is_same<T, PyJavaArray*>::value)
            {
                result.push_back(reinterpret_cast<PyJavaObject*>(next));
            }
            else
            {
                result.push_back(from_python_object<T>(next));
            }
        }

        (python->Py_DECREF)(iter);
    }
    return result;
}

template<typename T>
PyObject* to_python_object(const T& value)
{
    if constexpr(is_same_of<T, bool, char, std::int8_t, std::int16_t, std::int32_t>::value)
    {
        return python->PyLong_FromLong(value);
    }
    else if constexpr(std::is_same<T, std::int64_t>::value)
    {
        return python->PyLong_FromLongLong(value);
    }
    else if constexpr(is_same_of<T, std::uint8_t, std::uint16_t, std::uint32_t>::value)
    {
        return python->PyLong_FromUnsignedLong(value);
    }
    else if constexpr(std::is_same<T, std::uint64_t>::value)
    {
        return python->PyLong_FromUnsignedLongLong(value);
    }
    else if constexpr(std::is_same<T, std::size_t>::value)
    {
        return python->PyLong_FromSize_t(value);
    }
    else if constexpr(is_same_of<T, float, double>::value)
    {
        return python->PyFloat_FromDouble(value);
    }
    else if constexpr(std::is_same<T, std::string>::value)
    {
        return python->PyUnicode_FromStringAndSize(value.c_str(), value.size());
    }
    else if constexpr(std::is_same<T, const char*>::value)
    {
        if (value)
        {
            return python->PyUnicode_FromString(value);
        }

        (python->Py_INCREF)(python->Py_GetNone_Object());
        return python->Py_GetNone_Object();
    }
    else if constexpr(is_same_of<T, EIOS*>::value)
    {
        return PythonWrapEIOS(value);
    }
    else if constexpr(std::is_enum<T>::value)
    {
        typedef typename std::underlying_type<T>::type type;
        return to_python_object<type>(static_cast<type>(value));
    }
    else
    {
        static_assert("INVALID TYPE!");
        return nullptr;
    }
}

template<typename T>
PyObject* to_python_array(const std::vector<T>& values)
{
    PyObject* result = python->PyList_New(values.size());
    for (std::size_t i = 0; i < values.size(); ++i)
    {
        if constexpr(is_same_of<T, PyObject*, PyJavaObject*, PyEIOS*>::value)
        {
            python->PyList_SetItem(result, i, values[i]);
        }
        else
        {
            python->PyList_SetItem(result, i, to_python_object(values[i]));
        }
    }
    return result;
}

#endif //REMOTEINPUT_PYTHONCOMMON_TEMPLATES_HXX