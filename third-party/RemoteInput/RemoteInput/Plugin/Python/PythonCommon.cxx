//
// Created by Brandon on 2022-09-21.
//

#include "PythonCommon.hxx"

#if defined(USE_PYBIND11)
nanobind::object python_create_eios(EIOS* eios) noexcept
{
    if (eios)
    {
        auto py_eios_object = new PyEIOS();
        py_eios_object->pid = eios->pid;
        py_eios_object->native_eios = eios;

        return nanobind::cast(py_eios_object);
    }
    return nanobind::none();
}

nanobind::object python_create_object(PyEIOS* self, jobject object) noexcept
{
    if (object)
    {
        auto py_java_object = new PyJavaObject();
        py_java_object->eios = self;
        py_java_object->object = object;

        return nanobind::cast(py_java_object);
    }
    return nanobind::none();
}

nanobind::object python_create_object(PyJavaObject* self, jobject object) noexcept
{
    if (object)
    {
        auto py_java_object = new PyJavaObject();
        py_java_object->eios = self->eios;
        py_java_object->object = object;

        return nanobind::cast(py_java_object);
    }
    return nanobind::none();
}

nanobind::object python_create_object(PyJavaArray* self, jobject object) noexcept
{
    if (object)
    {
        auto py_java_object = new PyJavaObject();
        py_java_object->eios = self->eios;
        py_java_object->object = object;

        return nanobind::cast(py_java_object);
    }
    return nanobind::none();
}

nanobind::object python_create_array(PyEIOS* self, jarray array, std::size_t array_size) noexcept
{
    if (array)
    {
        auto py_java_array = new PyJavaArray();
        py_java_array->eios = self;
        py_java_array->array = array;
        py_java_array->size = array_size;

        return nanobind::cast(py_java_array);
    }
    return nanobind::none();
}

nanobind::object python_create_array(PyJavaObject* self, jarray array, std::size_t array_size) noexcept
{
    if (array)
    {
        auto py_java_array = new PyJavaArray();
        py_java_array->eios = self->eios;
        py_java_array->array = array;
        py_java_array->size = array_size;

        return nanobind::cast(py_java_array);
    }
    return nanobind::none();
}

nanobind::object python_create_array(PyJavaArray* self, jarray array, std::size_t array_size) noexcept
{
    if (array)
    {
        auto py_java_array = new PyJavaArray();
        py_java_array->eios = self->eios;
        py_java_array->array = array;
        py_java_array->size = array_size;

        return nanobind::cast(py_java_array);
    }
    return nanobind::none();
}

PyEIOS::~PyEIOS()
{
    if (native_eios)
    {
        EIOS_ReleaseTarget(this->native_eios);
        this->native_eios = nullptr;
    }
}

PyJavaObject::~PyJavaObject()
{
    if (this->eios && this->object)
    {
        this->eios->native_eios->control_center->reflect_release_object(this->object);
        this->object = nullptr;
        this->eios = nullptr;
        this->object = nullptr;
    }
}

PyJavaArray::~PyJavaArray()
{
    if (this->eios && this->array)
    {
        this->eios->native_eios->control_center->reflect_release_object(this->array);
        this->eios = nullptr;
        this->array = nullptr;
        this->size = 0;
    }
}

template<typename T>
PyTypeObject* PyTypeFromType() noexcept
{
    static PyTypeObject* type = nullptr;
    if (!type)
    {
        type = Py_TYPE(nanobind::cast(T()).ptr());
        return type;
    }
    return nullptr;
}

PyRemoteInputType GetPythonObjectType(PyObject* object) noexcept
{
    if (Py_IS_TYPE(object, PyTypeFromType<PyEIOS>()))
    {
        return PyRemoteInputType::EIOS;
    }

    if (Py_IS_TYPE(object, PyTypeFromType<PyJavaObject>()))
    {
        return PyRemoteInputType::JAVA_OBJECT;
    }

    if (Py_IS_TYPE(object, PyTypeFromType<PyJavaArray>()))
    {
        return PyRemoteInputType::JAVA_ARRAY;
    }

    return PyRemoteInputType::OTHER;
}

//PyRemoteInputType GetPythonObjectType(PyObject* object) noexcept
//{
//    if (nanobind::isinstance<PyEIOS>(object))
//    {
//        return PyRemoteInputType::EIOS;
//    }
//
//    if (nanobind::isinstance<PyJavaObject>(object))
//    {
//        return PyRemoteInputType::JAVA_OBJECT;
//    }
//
//    if (nanobind::isinstance<PyJavaArray>(object))
//    {
//        return PyRemoteInputType::JAVA_ARRAY;
//    }
//
//    return PyRemoteInputType::OTHER;
//}
#else
PyRemoteInputType GetPythonObjectType(PyObject* object) noexcept
{
    if ((python->Py_IS_TYPE)(object, PyEIOS_Type()))
    {
        return PyRemoteInputType::EIOS;
    }

    if ((python->Py_IS_TYPE)(object, PyJavaObject_Type()))
    {
        return PyRemoteInputType::JAVA_OBJECT;
    }

    if ((python->Py_IS_TYPE)(object, PyJavaArray_Type()))
    {
        return PyRemoteInputType::JAVA_ARRAY;
    }

    return PyRemoteInputType::OTHER;
}

PyObject* PythonWrapEIOS(EIOS* eios) noexcept
{
    if (eios)
    {
        PyEIOS* py_eios = (python->PyObject_New<PyEIOS>)(PyEIOS_Type());
        py_eios->pid = eios->pid;
        py_eios->native_eios = eios;
        return reinterpret_cast<PyObject*>(py_eios);
    }

    (python->Py_INCREF)(python->Py_GetNone_Object());
    return python->Py_GetNone_Object();
}

EIOS* PythonUnwrapEIOS(PyEIOS* eios) noexcept
{
    if (eios)
    {
        return eios->native_eios;
    }
    return nullptr;
}

PyEIOS* PythonGetEIOS(PyObject* object)
{
    PyRemoteInputType type = GetPythonObjectType(object);
    switch (type)
    {
        case PyRemoteInputType::EIOS:
            return reinterpret_cast<PyEIOS*>(object);

        case PyRemoteInputType::JAVA_OBJECT:
            return reinterpret_cast<PyJavaObject*>(object)->eios;

        case PyRemoteInputType::JAVA_ARRAY:
            return reinterpret_cast<PyJavaArray*>(object)->eios;

        default:
            return nullptr;
    }
}

PyJavaObject* PythonWrapJavaObject(PyEIOS* eios, jobject object) noexcept
{
    if (object)
    {
        PyTypeObject* PyJavaObjectType = PyJavaObject_Type();
        PyJavaObject* py_java_object = (python->PyObject_New<PyJavaObject>)(PyJavaObjectType);
        py_java_object->eios = eios;
        py_java_object->object = object;
        (python->Py_INCREF)(reinterpret_cast<PyObject*>(eios));
        return py_java_object;
    }

    (python->Py_INCREF)(python->Py_GetNone_Object());
    return reinterpret_cast<PyJavaObject*>(python->Py_GetNone_Object());
}

jobject PythonUnwrapJavaObject(PyJavaObject* object) noexcept
{
    if (!object)
    {
        return nullptr;
    }

    if (GetPythonObjectType(reinterpret_cast<PyObject*>(object)) != PyRemoteInputType::JAVA_OBJECT)
    {
        return nullptr;
    }

    return object->object;
}

PyJavaArray* PythonWrapJavaArray(PyEIOS* eios, jarray array, std::size_t size) noexcept
{
    if (array)
    {
        PyTypeObject* PyJavaArrayType = PyJavaArray_Type();
        PyJavaArray* py_java_array = (python->PyObject_New<PyJavaArray>)(PyJavaArrayType);
        py_java_array->eios = eios;
        py_java_array->array = array;
        py_java_array->size = size;
        (python->Py_INCREF)(reinterpret_cast<PyObject*>(eios));
        return py_java_array;
    }

    (python->Py_INCREF)(python->Py_GetNone_Object());
    return reinterpret_cast<PyJavaArray*>(python->Py_GetNone_Object());
}

jarray PythonUnwrapJavaArray(PyJavaArray* array) noexcept
{
    if (!array)
    {
        return nullptr;
    }

    if (GetPythonObjectType(reinterpret_cast<PyObject*>(array)) != PyRemoteInputType::JAVA_ARRAY)
    {
        return nullptr;
    }

    return array->array;
}
#endif