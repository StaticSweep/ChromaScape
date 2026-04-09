//
// Created by Brandon on 2022-09-21.
//

#include "PythonJavaObject.hxx"

#include <sstream>
#include <ios>
#include <iomanip>

#if defined(USE_PYBIND11)
nanobind::object Python_Reflect_Object(PyJavaObject* self, const std::string& cls, const std::string& field, const std::string& desc) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    jobject result = eios->control_center->reflect_object({object, cls, field, desc});
    return python_create_object(self, result);
}

nanobind::object Python_Reflect_IsSame_Object(PyJavaObject* self, const std::shared_ptr<PyJavaObject>& other) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jboolean result = eios->control_center->reflect_is_objects_equal(self->object, other->object);
    return nanobind::bool_(result);
}

nanobind::object Python_Reflect_InstanceOf(PyJavaObject* self, const std::string& cls) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    jboolean result = eios->control_center->reflect_instance_of(object, cls);
    return nanobind::bool_(result);
}

nanobind::object Python_Reflect_Bool(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    jboolean result = eios->control_center->reflect_boolean({object, cls, field, "Z"});
    return nanobind::bool_(result);
}

nanobind::object Python_Reflect_Char(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    char result = eios->control_center->reflect_char({object, cls, field, "C"});
    return nanobind::cast(std::string(1, result));
}

nanobind::object Python_Reflect_Byte(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    std::uint8_t result = eios->control_center->reflect_byte({object, cls, field, "B"});
    return nanobind::int_(result);
}

nanobind::object Python_Reflect_Short(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    std::int16_t result = eios->control_center->reflect_short({object, cls, field, "S"});
    return nanobind::int_(result);
}

nanobind::object Python_Reflect_Int(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    std::int32_t result = eios->control_center->reflect_int({object, cls, field, "I"});
    return nanobind::int_(result);
}

nanobind::object Python_Reflect_Long(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    std::int64_t result = eios->control_center->reflect_long({object, cls, field, "J"});
    return nanobind::int_(result);
}

nanobind::object Python_Reflect_Float(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    float result = eios->control_center->reflect_float({object, cls, field, "F"});
    return nanobind::float_(result);
}

nanobind::object Python_Reflect_Double(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    double result = eios->control_center->reflect_double({object, cls, field, "D"});
    return nanobind::float_(result);
}

nanobind::object Python_Reflect_String(PyJavaObject* self, const std::string& cls, const std::string& field) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;
    std::string result = eios->control_center->reflect_string({object, cls, field, "Ljava/lang/String;"});
    return nanobind::cast(result);
}

// MARK: - Array Functions

nanobind::object Python_Reflect_Array(PyJavaObject* self, const std::string& cls, const std::string& field, const std::string& desc) noexcept
{
    EIOS* eios = self->eios->native_eios;
    jobject object = self->object;

    std::size_t array_size = 0;
    jarray array = eios->control_center->reflect_array({object, cls, field, desc}, &array_size);
    return python_create_array(self, array, array_size);
}

void Python_JavaObject_Release_Object(PyJavaObject* self) noexcept
{
    if (self->eios && self->object)
    {
        EIOS* eios = self->eios->native_eios;
        jobject object = self->object;

        eios->control_center->reflect_release_object(object);

        self->eios = nullptr;
        self->object = nullptr;
    }
}

nanobind::object PyJavaObject_Str(PyJavaObject* self)
{
    auto eios = self->eios;
    jobject object = self->object;
    std::ostringstream stream;

    std::ios state(nullptr);
    state.copyfmt(stream);

    stream << std::setfill('0') << std::uppercase << std::hex;
    stream << "JavaObject(";
    stream << "0x" << reinterpret_cast<std::uintptr_t>(object);
    stream << "): ";
    stream.copyfmt(state);

    stream << "{"<<"\n";
    stream<< "    eios: " << eios << "\n";
    stream<< "    object: " << object << "\n";
    stream<< "}";

    return nanobind::cast(stream.str());
}

void declare_python_java_object(nanobind::module_ &module)
{
    nanobind::class_<PyJavaObject>(module, "JavaObject")
        // .def(pybind11::init<>()) // Cannot instantiate from Python
        .def_ro("eios", &PyJavaObject::eios)
        .def_ro("object", &PyJavaObject::object)
        .def("reflect_object", &Python_Reflect_Object,
             nanobind::arg("cls"), nanobind::arg("field"), nanobind::arg("desc"))
        .def("is_same_object", &Python_Reflect_IsSame_Object,
             nanobind::arg("other"))
        .def("instance_of", &Python_Reflect_InstanceOf,
             nanobind::arg("cls"))
        .def("reflect_bool", &Python_Reflect_Bool,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_char", &Python_Reflect_Char,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_byte", &Python_Reflect_Byte,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_short", &Python_Reflect_Short,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_int", &Python_Reflect_Int,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_long", &Python_Reflect_Long,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_float", &Python_Reflect_Float,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_double", &Python_Reflect_Double,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_string", &Python_Reflect_String,
             nanobind::arg("cls"), nanobind::arg("field"))
        .def("reflect_array", &Python_Reflect_Array,
             nanobind::arg("cls"), nanobind::arg("field"), nanobind::arg("desc"))
        .def("__del__", &Python_JavaObject_Release_Object)
        .def("__str__", &PyJavaObject_Str);
}
#else
int PyJavaObject_Clear(PyObject* object)
{
    PyJavaObject* py_java_object = reinterpret_cast<PyJavaObject*>(object);
    if (py_java_object->eios)
    {
        (python->Py_CLEAR)(reinterpret_cast<PyObject*>(py_java_object->eios));
    }
    py_java_object->eios = nullptr;
    py_java_object->object = nullptr;
    return 0;
}

void PyJavaObject_Dealloc(PyObject* object)
{
    EIOS* eios = PythonUnwrapEIOS(reinterpret_cast<PyJavaObject *>(object)->eios);
    jobject java_object = reinterpret_cast<PyJavaObject *>(object)->object;

    if (eios && java_object)
    {
        eios->control_center->reflect_release_object(java_object);
    }

    PyJavaObject_Clear(object);
    python->PyObject_Free(object);
}

PyObject* PyJavaObject_Str(PyObject* object)
{
    PyJavaObject* py_java_object = reinterpret_cast<PyJavaObject*>(object);
    std::ostringstream stream;

    std::ios state(nullptr);
    state.copyfmt(stream);

    stream << std::setfill('0') << std::uppercase << std::hex;
    stream << "JavaObject(";
    stream << "0x" << reinterpret_cast<std::uintptr_t>(object);
    stream << "): ";
    stream.copyfmt(state);

    stream << "{"<<"\n";
    stream<< "    eios: " << py_java_object->eios << "\n";
    stream<< "    object: " << py_java_object->object << "\n";
    stream<< "}";

    std::string result = stream.str();
    return python->PyUnicode_FromStringAndSize(result.c_str(), result.size());
}

PyMemberDef PyJavaObject_Members[] = {
        {nullptr}  /* Sentinel */
};

PyGetSetDef PyJavaObject_PropertyMembers[] = {
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

        {nullptr}  /* Sentinel */
};

PyMethodDef PyJavaObject_Methods[] = {
        {(char*)"reflect_object",            PYTHON_FASTCALL(Python_Reflect_Object), METH_FASTCALL,      (char*)""},
        {(char*)"is_same_object",            PYTHON_FASTCALL(Python_Reflect_IsSame_Object), METH_FASTCALL,      (char*)""},
        {(char*)"is_instance_of",            PYTHON_FASTCALL(Python_Reflect_InstanceOf), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_bool",            PYTHON_FASTCALL(Python_Reflect_Bool), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_char",            PYTHON_FASTCALL(Python_Reflect_Char), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_byte",            PYTHON_FASTCALL(Python_Reflect_Byte), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_short",            PYTHON_FASTCALL(Python_Reflect_Short), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_int",            PYTHON_FASTCALL(Python_Reflect_Int), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_long",            PYTHON_FASTCALL(Python_Reflect_Long), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_float",            PYTHON_FASTCALL(Python_Reflect_Float), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_double",            PYTHON_FASTCALL(Python_Reflect_Double), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_string",            PYTHON_FASTCALL(Python_Reflect_String), METH_FASTCALL,      (char*)""},
        {(char*)"reflect_array",            PYTHON_FASTCALL(Python_Reflect_Array), METH_FASTCALL,      (char*)""},
        {(char*)"release",            PYTHON_FASTCALL(Python_JavaObject_Release_Object), METH_FASTCALL,      (char*)""},
        {nullptr}  /* Sentinel */
};

PyType_Slot PyJavaObject_Slots[] = {
        {Py_tp_init, nullptr},
        {Py_tp_members, PyJavaObject_Members},
        {Py_tp_methods, PyJavaObject_Methods},
        {Py_tp_getset, PyJavaObject_PropertyMembers},
        {Py_tp_dealloc, reinterpret_cast<void*>(&PyJavaObject_Dealloc)},
        {Py_tp_clear, reinterpret_cast<void*>(&PyJavaObject_Clear)},
        {Py_tp_str, reinterpret_cast<void*>(&PyJavaObject_Str)},
        {Py_tp_doc, static_cast<void*>(const_cast<char*>(PyDoc_STR("JavaObject Structure")))},
        {Py_tp_setattr, nullptr},
        {0, NULL},
};

PyType_Spec PyJavaObject_Spec {
    "remote_input.JavaObject",
    sizeof(PyJavaObject),
    0,
    #if HAS_PYTHON_VERSION(0x030A0000)
    Py_TPFLAGS_DEFAULT | Py_TPFLAGS_BASETYPE | Py_TPFLAGS_IMMUTABLETYPE | Py_TPFLAGS_DISALLOW_INSTANTIATION,
    #else
    Py_TPFLAGS_DEFAULT | Py_TPFLAGS_BASETYPE,
    #endif
    PyJavaObject_Slots
};

// MARK: EXPORTS

PyTypeObject* PyJavaObject_Type() noexcept
{
    static PyTypeObject* type = reinterpret_cast<PyTypeObject*>(python->PyType_FromSpec(&PyJavaObject_Spec));
    return type;
}

// MARK: Functions

template<typename T>
typename std::enable_if<std::is_same<T, jobject>::value, T>::type from_python_object(PyJavaObject* object)
{
    return PythonUnwrapJavaObject(object);
}

EIOS* python_get_eios(PyJavaObject* object)
{
    return PythonGetEIOS(reinterpret_cast<PyObject*>(object))->native_eios;
}

PyObject* python_create_object(PyJavaObject* self, jobject object) noexcept
{
    PyEIOS* eios = PythonGetEIOS(reinterpret_cast<PyObject*>(self));
    return reinterpret_cast<PyObject*>(PythonWrapJavaObject(eios, object));
}

PyObject* Python_Reflect_Object(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::string desc = from_python_object<std::string>(args[2]);
    jobject result = eios->control_center->reflect_object({object, cls, field, desc});
    return python_create_object(self, result);
}

PyObject* Python_Reflect_IsSame_Object(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject a = from_python_object<jobject>(self);
    jobject b = from_python_object<jobject>(args[0]);
    jboolean result = eios->control_center->reflect_is_objects_equal(a, b);
    return to_python_object(result);
}

PyObject* Python_Reflect_InstanceOf(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    jboolean result = eios->control_center->reflect_instance_of(object, cls);
    return to_python_object(result);
}

PyObject* Python_Reflect_Bool(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    jboolean result = eios->control_center->reflect_boolean({object, cls, field, "Z"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Char(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    char result = eios->control_center->reflect_char({object, cls, field, "C"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Byte(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::uint8_t result = eios->control_center->reflect_byte({object, cls, field, "B"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Short(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::int16_t result = eios->control_center->reflect_short({object, cls, field, "S"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Int(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::int32_t result = eios->control_center->reflect_int({object, cls, field, "I"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Long(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::int64_t result = eios->control_center->reflect_long({object, cls, field, "J"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Float(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    float result = eios->control_center->reflect_float({object, cls, field, "F"});
    return to_python_object(result);
}

PyObject* Python_Reflect_Double(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    double result = eios->control_center->reflect_double({object, cls, field, "D"});
    return to_python_object(result);
}

PyObject* Python_Reflect_String(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::string result = eios->control_center->reflect_string({object, cls, field, "Ljava/lang/String;"});
    return to_python_object(result);
}

// MARK: - Array Functions

PyObject* Python_Reflect_Array(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    EIOS* eios = python_get_eios(self);
    jobject object = from_python_object<jobject>(self);
    std::string cls = from_python_object<std::string>(args[0]);
    std::string field = from_python_object<std::string>(args[1]);
    std::string desc = from_python_object<std::string>(args[2]);

    std::size_t array_size = 0;
    jarray array = eios->control_center->reflect_array({object, cls, field, desc}, &array_size);
    return reinterpret_cast<PyObject*>(PythonWrapJavaArray(PythonGetEIOS(reinterpret_cast<PyObject*>(self)), array, array_size));
}

PyObject* Python_JavaObject_Release_Object(PyJavaObject* self, PyObject* args[], Py_ssize_t args_length) noexcept
{
    if (self->eios && self->object)
    {
        EIOS* eios = python_get_eios(self);
        jobject object = from_python_object<jobject>(self);
        eios->control_center->reflect_release_object(object);
        PyJavaObject_Clear(reinterpret_cast<PyObject*>(self));
    }

    (python->Py_INCREF)(python->Py_GetNone_Object());
    return python->Py_GetNone_Object();
}
#endif