//
// Created by Brandon on 2023-12-18.
//

#include "PythonJavaList.hxx"
#include "PythonCommon.hxx"

#if defined(USE_PYBIND11)
void (*PyList_tp_dealloc)(PyObject*) = nullptr;

void PyList_Dealloc(PyObject* self)
{
    PyListObject* list = reinterpret_cast<PyListObject*>(self);
    Py_ssize_t size = reinterpret_cast<PyVarObject*>(list)->ob_size; //Py_SIZE(py_java_list);

    if (list->ob_item && size > 0)
    {
        PyEIOS* eios = nullptr;
        PyObject** items = list->ob_item;

        std::vector<jobject> java_objects;
        java_objects.reserve(size);

        for (Py_ssize_t i = 0; i < size; ++i)
        {
            PyObject* object = items[i];
            if (object->ob_refcnt == 1) //Py_REFCNT
            {
                PyRemoteInputType type = GetPythonObjectType(object);
                if (type == PyRemoteInputType::JAVA_OBJECT)
                {
                    PyJavaObject* py_java_object = reinterpret_cast<PyJavaObject*>(object);
                    java_objects.push_back(py_java_object->object);
                    py_java_object->object = nullptr;

                    if (!eios)
                    {
                        eios = py_java_object->eios;
                    }
                }
                else if (type == PyRemoteInputType::JAVA_ARRAY)
                {
                    PyJavaArray* py_java_array = reinterpret_cast<PyJavaArray*>(object);
                    java_objects.push_back(py_java_array->array);
                    py_java_array->array = nullptr;

                    if (!eios)
                    {
                        eios = py_java_array->eios;
                    }
                }
            }
        }

        if (!java_objects.empty() && eios)
        {
            eios->native_eios->control_center->reflect_release_objects(&java_objects[0], java_objects.size());
        }
    }

    if (PyList_tp_dealloc)
    {
        PyList_tp_dealloc(self);
    }
}

PyObject* create_java_list(Py_ssize_t length)
{
    PyObject* list = PyList_New(length);
    static PyTypeObject* type = reinterpret_cast<PyTypeObject*>(Py_TYPE(list));
    static destructor original_des = type->tp_dealloc;
    PyList_tp_dealloc = original_des;
    if (PyList_tp_dealloc)
    {
        type->tp_dealloc = PyList_Dealloc;
    }

    return list;
}
#else
#include <memory>

extern std::unique_ptr<Python> python;

#if defined(Py_LIMITED_API)
typedef struct {
    PyObject_VAR_HEAD
    PyObject **ob_item;
    Py_ssize_t allocated;
} PyListObject;
#endif

void (*PyList_tp_dealloc)(PyObject*) = nullptr;

void PyList_Dealloc(PyObject* self)
{
    PyListObject* list = reinterpret_cast<PyListObject*>(self);
    Py_ssize_t size = reinterpret_cast<PyVarObject*>(list)->ob_size; //Py_SIZE(py_java_list);

    if (list->ob_item && size > 0)
    {
        PyEIOS* eios = nullptr;
        PyObject** items = list->ob_item;

        std::vector<jobject> java_objects;
        java_objects.reserve(size);

        for (Py_ssize_t i = 0; i < size; ++i)
        {
            PyObject* object = items[i];
            if (object->ob_refcnt == 1) //Py_REFCNT
            {
                PyRemoteInputType type = GetPythonObjectType(object);
                if (type == PyRemoteInputType::JAVA_OBJECT)
                {
                    PyJavaObject* py_java_object = reinterpret_cast<PyJavaObject*>(object);
                    java_objects.push_back(py_java_object->object);
                    py_java_object->object = nullptr;

                    if (!eios)
                    {
                        eios = py_java_object->eios;
                    }
                }
                else if (type == PyRemoteInputType::JAVA_ARRAY)
                {
                    PyJavaArray* py_java_array = reinterpret_cast<PyJavaArray*>(object);
                    java_objects.push_back(py_java_array->array);
                    py_java_array->array = nullptr;

                    if (!eios)
                    {
                        eios = py_java_array->eios;
                    }
                }
            }
        }

        if (!java_objects.empty() && eios)
        {
            eios->native_eios->control_center->reflect_release_objects(&java_objects[0], java_objects.size());
        }
    }

    if (PyList_tp_dealloc)
    {
        PyList_tp_dealloc(self);
    }
}

PyObject* create_java_list(Py_ssize_t length)
{
    #if defined(Py_LIMITED_API)
    struct PyTypeObject
    {
        PyObject_VAR_HEAD
        const char *tp_name; /* For printing, in format "<module>.<name>" */
        Py_ssize_t tp_basicsize, tp_itemsize; /* For allocation */
        destructor tp_dealloc;
    };
    #endif

    PyObject* list = python->PyList_New(length);
    static PyTypeObject* type = reinterpret_cast<PyTypeObject*>((python->Py_TYPE)(list));
    static destructor original_des = type->tp_dealloc;
    PyList_tp_dealloc = original_des;
    if (PyList_tp_dealloc)
    {
        type->tp_dealloc = PyList_Dealloc;
    }
    return list;
}
#endif