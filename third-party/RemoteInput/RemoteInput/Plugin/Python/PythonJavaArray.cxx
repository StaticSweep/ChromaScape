//
// Created by Brandon on 2023-12-10.
//

#include "PythonJavaArray.hxx"
#include "PythonJavaList.hxx"

#include <sstream>
#include <ios>
#include <iomanip>

#if defined(USE_PYBIND11)
#include <nanobind/stl/string.h>
#endif

#if defined(USE_PYBIND11)
nanobind::object read_array_type(Stream &stream, PyJavaArray* object, ReflectionType type, std::size_t dimensions);

nanobind::object Python_JavaArray_GetLength(PyJavaArray* self) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jarray array = self->array;
    std::size_t length = eios->control_center->reflect_array_size(array);
    return nanobind::int_(length);
}

nanobind::object Python_JavaArray_Get1D(PyJavaArray* self, const nanobind::object& type_object, const nanobind::object& indices_object, const nanobind::object& index_object, const nanobind::object& length_object) noexcept
{
    ReflectionType type = ReflectionType::OBJECT;
    if (!type_object.is_none())
    {
        type = nanobind::cast<ReflectionType>(type_object);
    }

    EIOS* eios = self->eios->native_eios;
    jarray array = self->array;
    std::size_t index = 0;
    std::size_t length = 0;

    if (indices_object.is_none() && index_object.is_none() && length_object.is_none())
    {
        // Read entire array
        Stream &stream = eios->control_center->reflect_array_all(array, type, 1)->data_stream();
        return read_array_type(stream, self, type, 1);
    }

    if (!indices_object.is_none())
    {
        // Read array indexed by indices
        auto indices = nanobind::cast<std::vector<std::int32_t>>(indices_object);
        Stream &stream = eios->control_center->reflect_array_indices(array, type, &indices[0], indices.size())->data_stream();
        return read_array_type(stream, self, type, 1);
    }

    if (!index_object.is_none())
    {
        index = nanobind::cast<std::size_t>(index_object);
    }

    if (length_object)
    {
        length = nanobind::cast<std::size_t>(length_object);
    }

    if (!index_object.is_none() && length_object.is_none())
    {
        // Read array[index]
        Stream &stream = eios->control_center->reflect_array(array, type, 1, index)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    length = std::min(std::max<std::size_t>(length, 1), self->size);

    // Read array of [index..<length]
    Stream &stream = eios->control_center->reflect_array(array, type, length, index)->data_stream();
    return read_array_type(stream, self, type, 1);
}

nanobind::object Python_JavaArray_Get2D(PyJavaArray* self, const nanobind::object& type_object, const nanobind::object& x_object, const nanobind::object& y_object) noexcept
{
    ReflectionType type = ReflectionType::OBJECT;
    if (!type_object.is_none())
    {
        type = nanobind::cast<ReflectionType>(type_object);
    }

    EIOS* eios = self->eios->native_eios;
    jarray array = self->array;

    // Array[x][y]
    if (!x_object.is_none() && !y_object.is_none())
    {
        std::size_t x = nanobind::cast<std::size_t>(x_object);
        std::size_t y = nanobind::cast<std::size_t>(y_object);

        Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    // Array[][]
    Stream &stream = eios->control_center->reflect_array_all(array, type, 2)->data_stream();
    return read_array_type(stream, self, type, 2);
}

nanobind::object Python_JavaArray_Get3D(PyJavaArray* self, const nanobind::object& type_object, const nanobind::object& x_object, const nanobind::object& y_object, const nanobind::object& z_object) noexcept
{
    ReflectionType type = ReflectionType::OBJECT;
    if (!type_object.is_none())
    {
        type = nanobind::cast<ReflectionType>(type_object);
    }

    EIOS* eios = self->eios->native_eios;
    jarray array = self->array;

    // Array[x][y][z]
    if (!x_object.is_none() && !y_object.is_none() && !z_object.is_none())
    {
        std::size_t x = nanobind::cast<std::size_t>(x_object);
        std::size_t y = nanobind::cast<std::size_t>(y_object);
        std::size_t z = nanobind::cast<std::size_t>(z_object);

        Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y, z)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    // Array[][][]
    Stream &stream = eios->control_center->reflect_array_all(array, type, 3)->data_stream();
    return read_array_type(stream, self, type, 3);
}

nanobind::object Python_JavaArray_Get4D(PyJavaArray* self, const nanobind::object& type_object, const nanobind::object& x_object, const nanobind::object& y_object, const nanobind::object& z_object, const nanobind::object& w_object) noexcept
{
    ReflectionType type = ReflectionType::OBJECT;
    if (!type_object.is_none())
    {
        type = nanobind::cast<ReflectionType>(type_object);
    }

    EIOS* eios = self->eios->native_eios;
    jarray array = self->array;

    // Array[x][y][z][w]
    if (!x_object.is_none() && !y_object.is_none() && !z_object.is_none() && !w_object.is_none())
    {
        std::size_t x = nanobind::cast<std::size_t>(x_object);
        std::size_t y = nanobind::cast<std::size_t>(y_object);
        std::size_t z = nanobind::cast<std::size_t>(z_object);
        std::size_t w = nanobind::cast<std::size_t>(z_object);

        Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y, z, w)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    // Array[][][][]
    Stream &stream = eios->control_center->reflect_array_all(array, type, 4)->data_stream();
    return read_array_type(stream, self, type, 4);
}

void Python_JavaArray_Release_Object(PyJavaArray* self) noexcept
{
    if (self->eios && self->array)
    {
        EIOS* eios = self->eios->native_eios;
        jarray object = self->array;

        eios->control_center->reflect_release_object(object);

        self->eios = nullptr;
        self->array = nullptr;
        self->size = 0;
    }
}

nanobind::object PyJavaArray_Str(PyJavaArray* self)
{
    std::ostringstream stream;

    std::ios state(nullptr);
    state.copyfmt(stream);

    stream << std::setfill('0') << std::uppercase << std::hex;
    stream << "JavaArray(";
    stream << "0x" << reinterpret_cast<std::uintptr_t>(self);
    stream << "): ";
    stream.copyfmt(state);

    stream << "{"<<"\n";
    stream<< "    eios: " << self->eios << "\n";
    stream<< "    array: " << self->array << "\n";
    stream<< "    size:  " << self->size << "\n";
    stream<< "}";

    return nanobind::cast(stream.str());
}

void declare_python_java_array(nanobind::module_ &module)
{
    nanobind::class_<PyJavaArray>(module, "JavaArray")
        // .def(nanobind::init<>()) // Cannot instantiate from Python
        .def_ro("eios", &PyJavaArray::eios)
        .def_ro("array", &PyJavaArray::array)
        .def_ro("size", &PyJavaArray::size)
        .def("get_length", &Python_JavaArray_GetLength)
        .def("get_1d", &Python_JavaArray_Get1D,
             nanobind::arg("type") = nanobind::none(),
             nanobind::arg("indices") = nanobind::none(),
             nanobind::arg("index") = nanobind::none(),
             nanobind::arg("length") = nanobind::none())
        .def("get_2d", &Python_JavaArray_Get2D,
             nanobind::arg("type") = nanobind::none(),
             nanobind::arg("x") = nanobind::none(),
             nanobind::arg("y") = nanobind::none())
        .def("get_3d", &Python_JavaArray_Get3D,
             nanobind::arg("type") = nanobind::none(),
             nanobind::arg("x") = nanobind::none(),
             nanobind::arg("y") = nanobind::none(),
             nanobind::arg("z") = nanobind::none())
        .def("get_4d", &Python_JavaArray_Get4D,
             nanobind::arg("type") = nanobind::none(),
             nanobind::arg("x") = nanobind::none(),
             nanobind::arg("y") = nanobind::none(),
             nanobind::arg("z") = nanobind::none(),
             nanobind::arg("w") = nanobind::none())
        .def("release_object", &Python_JavaArray_Release_Object)
        .def("__str__", &PyJavaArray_Str);
}

template<typename T>
nanobind::object read_array_type(Stream &stream, PyJavaArray* array)
{
    if constexpr(std::is_same<T, jobject>::value)
    {
        std::size_t length = stream.read<std::size_t>();
        PyObject* list = create_java_list(length);

        for (std::size_t i = 0; i < length; ++i)
        {
            PyList_SetItem(list, i, python_create_object(array, stream.read<jobject>()).release().ptr());
        }

        return nanobind::steal(list);
    }
    else if constexpr(std::is_same<T, jarray>::value)
    {
        std::size_t length = stream.read<std::size_t>();
        PyObject* list = create_java_list(length);

        for (std::size_t i = 0; i < length; ++i)
        {
            PyList_SetItem(list, i, python_create_array(array, stream.read<jarray>(), length).release().ptr());
        }

        return nanobind::steal(list);
    }
    else
    {
        return nanobind::cast(stream.read<std::vector<T>>());
    }
}

nanobind::object read_array_type(Stream &stream, PyJavaArray* object, ReflectionType type, std::size_t dimensions)
{
    if (dimensions == 0)
    {
        std::size_t size = stream.read<std::size_t>();
        if (size == 0)
        {
            return nanobind::none();
        }

        switch(type)
        {
            case ReflectionType::CHAR: return nanobind::cast(std::string(1, stream.read<char>()));
            case ReflectionType::BYTE: return nanobind::int_(stream.read<std::uint8_t>());
            case ReflectionType::BOOL: return nanobind::bool_(stream.read<bool>());
            case ReflectionType::SHORT: return nanobind::int_(stream.read<std::int16_t>());
            case ReflectionType::INT: return nanobind::int_(stream.read<std::int32_t>());
            case ReflectionType::LONG: return nanobind::int_(stream.read<std::int64_t>());
            case ReflectionType::FLOAT: return nanobind::float_(stream.read<float>());
            case ReflectionType::DOUBLE: return nanobind::float_(stream.read<double>());
            case ReflectionType::STRING: return nanobind::cast(stream.read<std::string>());
            case ReflectionType::OBJECT: return python_create_object(object, stream.read<jobject>());
            case ReflectionType::ARRAY: return python_create_array(object, stream.read<jarray>(), 0);
            default: return nanobind::none();
        }
    }

    if (dimensions == 1)
    {
        switch(type)
        {
            case ReflectionType::CHAR: return read_array_type<char>(stream, object);
            case ReflectionType::BYTE: return read_array_type<std::uint8_t>(stream, object);
            case ReflectionType::BOOL: return read_array_type<bool>(stream, object);
            case ReflectionType::SHORT: return read_array_type<std::int16_t>(stream, object);
            case ReflectionType::INT: return read_array_type<std::int32_t>(stream, object);
            case ReflectionType::LONG: return read_array_type<std::int64_t>(stream, object);
            case ReflectionType::FLOAT: return read_array_type<float>(stream, object);
            case ReflectionType::DOUBLE: return read_array_type<double>(stream, object);
            case ReflectionType::STRING: return read_array_type<std::string>(stream, object);
            case ReflectionType::OBJECT: return read_array_type<jobject>(stream, object);
            case ReflectionType::ARRAY: return read_array_type<jarray>(stream, object);
            default: return nanobind::none();
        }
    }

    std::size_t length = stream.read<std::size_t>();
    PyObject* list = create_java_list(length);

    for (std::size_t i = 0; i < length; ++i)
    {
        PyList_SetItem(list, i, read_array_type(stream, object, type, dimensions - 1).release().ptr());
    }

    return nanobind::steal(list);
}
#else
int PyJavaArray_Clear(PyObject* object)
{
    PyJavaArray* py_java_array = reinterpret_cast<PyJavaArray*>(object);
    if (py_java_array->eios)
    {
        (python->Py_CLEAR)(reinterpret_cast<PyObject*>(py_java_array->eios));
    }
    py_java_array->eios = nullptr;
    py_java_array->array = nullptr;
    return 0;
}

void PyJavaArray_Dealloc(PyObject* object)
{
    EIOS* eios = PythonUnwrapEIOS(reinterpret_cast<PyJavaArray *>(object)->eios);
    jarray array = reinterpret_cast<PyJavaArray *>(object)->array;

    if (eios && array)
    {
        eios->control_center->reflect_release_object(array);
    }

    PyJavaArray_Clear(object);
    python->PyObject_Free(object);
}

PyObject* PyJavaArray_Str(PyObject* object)
{
    PyJavaArray* py_java_array = reinterpret_cast<PyJavaArray*>(object);
    std::ostringstream stream;

    std::ios state(nullptr);
    state.copyfmt(stream);

    stream << std::setfill('0') << std::uppercase << std::hex;
    stream << "JavaArray(";
    stream << "0x" << reinterpret_cast<std::uintptr_t>(object);
    stream << "): ";
    stream.copyfmt(state);

    stream << "{"<<"\n";
    stream<< "    eios: " << py_java_array->eios << "\n";
    stream<< "    array: " << py_java_array->array << "\n";
    stream<< "}";

    std::string result = stream.str();
    return python->PyUnicode_FromStringAndSize(result.c_str(), result.size());
}

PyMemberDef PyJavaArray_Members[] = {
        {nullptr}  /* Sentinel */
};

PyGetSetDef PyJavaArray_PropertyMembers[] = {
        {(char*)"eios", [](PyObject* object, void *closure) -> PyObject* {
            if (object)
            {
                PyObject* eios = reinterpret_cast<PyObject*>(PythonGetEIOS(object));
                (python->Py_INCREF)(eios);
                return eios;
            }

            (python->Py_INCREF)(python->Py_GetNone_Object());
            return python->Py_GetNone_Object();
        }, nullptr, PyDoc_STR("EIOS Object"), nullptr},

        {(char*)"size", [](PyObject* object, void *closure) -> PyObject* {
            return object ? python->PyLong_FromLong(reinterpret_cast<PyJavaArray*>(object)->size) : python->PyLong_FromLong(0);
        }, nullptr, PyDoc_STR("Array Size"), nullptr},

        {nullptr}  /* Sentinel */
};

PyMethodDef PyJavaArray_Methods[] = {
        {(char*)"get_length",      PYTHON_FASTCALL(Python_JavaArray_GetLength), METH_FASTCALL,      (char*)""},
        {(char*)"get_1d",          PYTHON_FASTCALL(Python_JavaArray_Get1D), METH_FASTCALL | METH_KEYWORDS,      (char*)""},
        {(char*)"get_2d",          PYTHON_FASTCALL(Python_JavaArray_Get2D), METH_FASTCALL | METH_KEYWORDS,      (char*)""},
        {(char*)"get_3d",          PYTHON_FASTCALL(Python_JavaArray_Get3D), METH_FASTCALL | METH_KEYWORDS,      (char*)""},
        {(char*)"get_4d",          PYTHON_FASTCALL(Python_JavaArray_Get4D), METH_FASTCALL | METH_KEYWORDS,      (char*)""},
        {(char*)"release",         PYTHON_FASTCALL(Python_JavaArray_Release_Object), METH_FASTCALL,      (char*)""},
        {nullptr}  /* Sentinel */
};

PyType_Slot PyJavaArray_Slots[] = {
        {Py_tp_init, nullptr},
        {Py_tp_members, PyJavaArray_Members},
        {Py_tp_methods, PyJavaArray_Methods},
        {Py_tp_getset, PyJavaArray_PropertyMembers},
        {Py_tp_dealloc, reinterpret_cast<void*>(&PyJavaArray_Dealloc)},
        {Py_tp_clear, reinterpret_cast<void*>(&PyJavaArray_Clear)},
        {Py_tp_str, reinterpret_cast<void*>(&PyJavaArray_Str)},
        {Py_tp_doc, static_cast<void*>(const_cast<char*>(PyDoc_STR("JavaArray Structure")))},
        {Py_tp_setattr, nullptr},
        {0, NULL},
};

PyType_Spec PyJavaArray_Spec {
        "remote_input.JavaArray",
        sizeof(PyJavaArray),
        0,
        #if HAS_PYTHON_VERSION(0x030A0000)
        Py_TPFLAGS_DEFAULT | Py_TPFLAGS_BASETYPE | Py_TPFLAGS_IMMUTABLETYPE | Py_TPFLAGS_DISALLOW_INSTANTIATION,
        #else
        Py_TPFLAGS_DEFAULT | Py_TPFLAGS_BASETYPE,
        #endif
        PyJavaArray_Slots
};

// MARK: EXPORTS

PyObject* read_array_type(Stream &stream, PyJavaArray* object, ReflectionType type, std::size_t dimensions);

PyTypeObject* PyJavaArray_Type() noexcept
{
    static PyTypeObject* type = reinterpret_cast<PyTypeObject*>(python->PyType_FromSpec(&PyJavaArray_Spec));
    return type;
}

template<typename T>
typename std::enable_if<std::is_same<T, jarray>::value, T>::type from_python_object(PyJavaArray* object)
{
    return PythonUnwrapJavaArray(object);
}

PyObject* python_create_object(PyJavaArray* self, jobject object) noexcept
{
    return reinterpret_cast<PyObject*>(PythonWrapJavaObject(self->eios, object));
}

PyObject* python_create_array(PyJavaArray* self, jarray object, std::size_t size) noexcept
{
    return reinterpret_cast<PyObject*>(PythonWrapJavaArray(self->eios, object, size));
}

EIOS* python_get_eios(PyJavaArray* object)
{
    return PythonGetEIOS(reinterpret_cast<PyObject*>(object))->native_eios;
}

template<std::size_t keywords_size>
std::vector<PyObject*> python_parse_arguments(const char* (&keywords)[keywords_size], PyObject* args[], Py_ssize_t args_length, PyObject* kwnames)
{
    std::vector<PyObject*> result;
    result.resize(args_length + keywords_size);

    for (std::size_t i = 0; i < args_length; ++i)
    {
        if (!(python->Py_IsNone)(args[i]))
        {
            result[i] = args[i];
        }
    }

    if (kwnames)
    {
        for (std::size_t i = 0; i < python->PyTuple_Size(kwnames); ++i)
        {
            std::string keyword = from_python_object<std::string>(python->PyTuple_GetItem(kwnames, i));
            if (auto it = std::find(std::begin(keywords), std::end(keywords), keyword); it != std::end(keywords))
            {
                PyObject* argument = args[i + args_length];
                if (!(python->Py_IsNone)(argument))
                {
                    result[std::distance(std::begin(keywords), it)] = argument;
                }
            }
        }
    }
    return result;
}

PyObject* Python_JavaArray_GetLength(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jarray array = from_python_object<jarray>(self);
    std::size_t length = eios->control_center->reflect_array_size(array);
    return to_python_object(length);
}

PyObject* Python_JavaArray_Get1D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept
{
    static const char* kwlist[] = {"type", "indices", "index", "length", nullptr};
    std::vector<PyObject*> arguments = python_parse_arguments(kwlist, args, args_length, kwnames);

    ReflectionType type = ReflectionType::OBJECT;
    if (arguments[0])
    {
        type = from_python_object<ReflectionType>(arguments[0]);
    }

    PyObject* indices_object = arguments[1];
    PyObject* index_object = arguments[2];
    PyObject* length_object = arguments[3];

    EIOS* eios = python_get_eios(self);
    jarray array = from_python_object<jarray>(self);
    std::size_t index = 0;
    std::size_t length = 0;

    if (!index_object && !length_object && !indices_object)
    {
        // Read entire array
        Stream &stream = eios->control_center->reflect_array_all(array, type, 1)->data_stream();
        return read_array_type(stream, self, type, 1);
    }

    if (indices_object)
    {
        // Read array indexed by indices
        std::vector<std::int32_t> indices = from_python_array<std::int32_t>(indices_object);
        Stream &stream = eios->control_center->reflect_array_indices(array, type, &indices[0], indices.size())->data_stream();
        return read_array_type(stream, self, type, 1);
    }

    if (index_object)
    {
        index = from_python_object<std::size_t>(index_object);
    }

    if (length_object)
    {
        length = from_python_object<std::size_t>(length_object);
    }

    if (index_object && !length_object)
    {
        // Read array[index]
        Stream &stream = eios->control_center->reflect_array(array, type, 1, index)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    length = std::min(std::max<std::size_t>(length, 1), self->size);

    // Read array of [index..<length]
    Stream &stream = eios->control_center->reflect_array(array, type, length, index)->data_stream();
    return read_array_type(stream, self, type, 1);
}

PyObject* Python_JavaArray_Get2D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept
{
    static const char* kwlist[] = {"type", "x", "y", nullptr};
    std::vector<PyObject*> arguments = python_parse_arguments(kwlist, args, args_length, kwnames);

    ReflectionType type = ReflectionType::OBJECT;
    if (arguments[0])
    {
        type = from_python_object<ReflectionType>(arguments[0]);
    }

    EIOS* eios = python_get_eios(self);
    jarray array = from_python_object<jarray>(self);

    PyObject* x_object = arguments[1];
    PyObject* y_object = arguments[2];

    // Array[x][y]
    if (x_object && y_object)
    {
        std::size_t x = from_python_object<std::size_t>(x_object);
        std::size_t y = from_python_object<std::size_t>(y_object);

        Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    // Array[][]
    Stream &stream = eios->control_center->reflect_array_all(array, type, 2)->data_stream();
    return read_array_type(stream, self, type, 2);
}

PyObject* Python_JavaArray_Get3D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept
{
    static const char* kwlist[] = {"type", "x", "y", "z", nullptr};
    std::vector<PyObject*> arguments = python_parse_arguments(kwlist, args, args_length, kwnames);

    ReflectionType type = ReflectionType::OBJECT;
    if (arguments[0])
    {
        type = from_python_object<ReflectionType>(arguments[0]);
    }

    PyObject* x_object = arguments[1];
    PyObject* y_object = arguments[2];
    PyObject* z_object = arguments[3];

    EIOS* eios = python_get_eios(self);
    jarray array = from_python_object<jarray>(self);

    // Array[x][y][z]
    if (x_object && y_object && z_object)
    {
        std::size_t x = from_python_object<std::size_t>(x_object);
        std::size_t y = from_python_object<std::size_t>(y_object);
        std::size_t z = from_python_object<std::size_t>(z_object);

        Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y, z)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    // Array[][][]
    Stream &stream = eios->control_center->reflect_array_all(array, type, 3)->data_stream();
    return read_array_type(stream, self, type, 3);
}

PyObject* Python_JavaArray_Get4D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept
{
    static const char* kwlist[] = {"type", "x", "y", "z", "w", nullptr};
    std::vector<PyObject*> arguments = python_parse_arguments(kwlist, args, args_length, kwnames);

    ReflectionType type = ReflectionType::OBJECT;
    if (arguments[0])
    {
        type = from_python_object<ReflectionType>(arguments[0]);
    }

    PyObject* x_object = arguments[1];
    PyObject* y_object = arguments[2];
    PyObject* z_object = arguments[3];
    PyObject* w_object = arguments[4];

    EIOS* eios = python_get_eios(self);
    jarray array = from_python_object<jarray>(self);

    // Array[x][y][z][w]
    if (x_object && y_object && z_object && w_object)
    {
        std::size_t x = from_python_object<std::size_t>(x_object);
        std::size_t y = from_python_object<std::size_t>(y_object);
        std::size_t z = from_python_object<std::size_t>(z_object);
        std::size_t w = from_python_object<std::size_t>(z_object);

        Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y, z, w)->data_stream();
        return read_array_type(stream, self, type, 0);
    }

    // Array[][][][]
    Stream &stream = eios->control_center->reflect_array_all(array, type, 4)->data_stream();
    return read_array_type(stream, self, type, 4);
}

PyObject* Python_JavaArray_Release_Object(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    if (self->eios && self->array)
    {
        EIOS* eios = python_get_eios(self);
        jarray object = from_python_object<jarray>(self);
        eios->control_center->reflect_release_object(object);
        PyJavaArray_Clear(reinterpret_cast<PyObject*>(self));
    }

    (python->Py_INCREF)(python->Py_GetNone_Object());
    return python->Py_GetNone_Object();
}

template<typename T>
PyObject* read_array_type(Stream &stream, PyJavaArray* object)
{
    extern PyObject* create_java_list(Py_ssize_t length);

    if constexpr(std::is_same<T, std::string>::value)
    {
        return to_python_array(stream.read<std::vector<std::string>>());
    }
    else if constexpr(std::is_same<T, bool>::value)
    {
        return to_python_array(stream.read<std::vector<bool>>());
    }
    else if constexpr(std::is_same<T, jobject>::value)
    {
        std::size_t length = stream.read<std::size_t>();
        PyObject* list = create_java_list(length); //python->PyList_New(length);
        for (std::size_t i = 0; i < length; ++i)
        {
            python->PyList_SetItem(list, i, python_create_object(object, stream.read<jobject>()));
        }
        return list;
    }
    else if constexpr(std::is_same<T, jarray>::value)
    {
        std::size_t length = stream.read<std::size_t>();
        PyObject* list = create_java_list(length); //python->PyList_New(length);
        for (std::size_t i = 0; i < length; ++i)
        {
            python->PyList_SetItem(list, i, python_create_array(object, stream.read<jarray>(), length));
        }
        return list;
    }
    else
    {
        return to_python_array(stream.read<std::vector<T>>());

//        std::size_t length = stream.read<std::size_t>();
//        std::size_t element_size = ControlCenter::reflect_size_for_type(type);
//        PyObject* array_module = python->PyImport_ImportModule("array");
//        PyObject* array_type = python->PyObject_GetAttrString(array_module, "array");
//        (python->Py_DECREF)(array_module);
//
//        PyObject* result = python->PyObject_CallFunction(array_type, "s[l]", "l", 0);
//        (python->Py_DECREF)(array_type);
//
//        PyObject *pysize = python->PyLong_FromSsize_t(length);
//        PyObject *py_array = python->PyNumber_Multiply(result, pysize);
//        (python->Py_DECREF)(pysize);
//
//        PyObject *buffer_info = python->PyObject_CallMethod(py_array, "buffer_info", "");
//        PyObject* pyaddr = python->PyTuple_GetItem(buffer_info, 0);
//        void *addr = python->PyLong_AsVoidPtr(pyaddr);
//
//        stream.read(addr, length * element_size);
//        return py_array;
    }
}

PyObject* read_array_type(Stream &stream, PyJavaArray* object, ReflectionType type, std::size_t dimensions)
{
    if (dimensions == 0)
    {
        std::size_t size = stream.read<std::size_t>();
        if (size == 0)
        {
            (python->Py_INCREF)(python->Py_GetNone_Object());
            return python->Py_GetNone_Object();
        }

        switch(type)
        {
            case ReflectionType::CHAR: return to_python_object(stream.read<char>());
            case ReflectionType::BYTE: return to_python_object(stream.read<std::uint8_t>());
            case ReflectionType::BOOL: return to_python_object(stream.read<bool>());
            case ReflectionType::SHORT: return to_python_object(stream.read<std::int16_t>());
            case ReflectionType::INT: return to_python_object(stream.read<std::int32_t>());
            case ReflectionType::LONG: return to_python_object(stream.read<std::int64_t>());
            case ReflectionType::FLOAT: return to_python_object(stream.read<float>());
            case ReflectionType::DOUBLE: return to_python_object(stream.read<double>());
            case ReflectionType::STRING: return to_python_object(stream.read<std::string>());
            case ReflectionType::OBJECT: return python_create_object(object, stream.read<jobject>());
            case ReflectionType::ARRAY: return python_create_array(object, stream.read<jarray>(), 0);
            default: (python->Py_INCREF)(python->Py_GetNone_Object()); return python->Py_GetNone_Object();
        }
    }

    if (dimensions == 1)
    {
        switch(type)
        {
            case ReflectionType::CHAR: return read_array_type<char>(stream, object);
            case ReflectionType::BYTE: return read_array_type<std::uint8_t>(stream, object);
            case ReflectionType::BOOL: return read_array_type<bool>(stream, object);
            case ReflectionType::SHORT: return read_array_type<std::int16_t>(stream, object);
            case ReflectionType::INT: return read_array_type<std::int32_t>(stream, object);
            case ReflectionType::LONG: return read_array_type<std::int64_t>(stream, object);
            case ReflectionType::FLOAT: return read_array_type<float>(stream, object);
            case ReflectionType::DOUBLE: return read_array_type<double>(stream, object);
            case ReflectionType::STRING: return read_array_type<std::string>(stream, object);
            case ReflectionType::OBJECT: return read_array_type<jobject>(stream, object);
            case ReflectionType::ARRAY: return read_array_type<jarray>(stream, object);
            default: (python->Py_INCREF)(python->Py_GetNone_Object()); return python->Py_GetNone_Object();
        }
    }

    std::size_t length = stream.read<std::size_t>();
    PyObject* result = python->PyList_New(length);

    for (std::size_t i = 0; i < length; ++i)
    {
        python->PyList_SetItem(result, i, read_array_type(stream, object, type, dimensions - 1));
    }

    return result;
}
#endif