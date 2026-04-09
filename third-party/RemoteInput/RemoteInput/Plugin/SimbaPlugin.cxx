#include "SimbaPlugin.hxx"
#include <memory>
#include <cstring>
#include <unordered_map>
#include <unordered_set>
#include <type_traits>

#include "ControlCenter.hxx"
#include "RemoteVM.hxx"
#include "Module.hxx"
#include "NativePlugin.hxx"
#include "TypeTraits.hxx"
#include "TypeTraits_Functional.hxx"

#if defined(_WIN32) || defined(_WIN64)
extern HMODULE module;
#endif

#if defined(PASCAL_CALLING_CONVENTION)
TMemoryManager PLUGIN_MEMORY_MANAGER = {0};
#endif

// MARK: - Simba v1.4 - Deprecated
TSimbaMethods PLUGIN_SYNC_METHODS = {nullptr};
TSimbaMemoryAllocators PLUGIN_MEMORY_ALLOCATORS = {nullptr};

// MARK: - Simba v1.4+
TSimbaInfomation PLUGIN_SIMBA_INFO = {0};
TSimbaMethodsExtended PLUGIN_SIMBA_METHODS = {0};

// Internal
extern std::unique_ptr<ControlCenter> control_center;


// MARK: - DECLARATIONS

template<typename T>
int PascalHigh(T* Arr) noexcept;

template<typename T>
int PascalLength(T* Arr) noexcept;

template<typename T>
T* AllocateArray(std::size_t size, std::size_t element_size = sizeof(T)) noexcept;

template<typename T>
T* GetArray(void* ptr, std::size_t* size) noexcept;

template<typename T>
std::vector<T> GetArrayAsVector(void* ptr) noexcept;

template<typename T>
T* AllocateString(std::size_t size, std::size_t element_size = sizeof(T)) noexcept;

template<typename T>
T* GetString(void* ptr, std::size_t* size) noexcept;

template<typename T>
T PascalRead(void* ptr) noexcept;

template<typename T>
void PascalWrite(void* ptr, T result) noexcept;

void PascalWrite(EIOS* eios, Stream &stream, void* ptr, ReflectionType type) noexcept;


// MARK: - EXPORTS

int GetPluginABIVersion() noexcept
{
    return 2;
}

int GetFunctionCount() noexcept
{
    return PascalExportCount;
}

int GetTypeCount() noexcept
{
    return PascalTypeCount;
}

int GetFunctionInfo(int Index, void** Address, char** Definition) noexcept
{
    if (Index < PascalExportCount)
    {
        #if defined(_WIN32) || defined(_WIN64)
        *Address = (void*)GetProcAddress(module, PascalExports[Index * 2]);
        #else
        *Address = (void*)dlsym(RTLD_DEFAULT, PascalExports[Index * 2]);
        #endif
        strcpy(*Definition, PascalExports[Index * 2 + 1]);
        return Index;
    }
    return -1;
}

int GetTypeInfo(int Index, char** Type, char** Definition) noexcept
{
    if (Index < PascalTypeCount)
    {
        strcpy(*Type, PascalTypes[Index * 2 + 0]);
        strcpy(*Definition, PascalTypes[Index * 2 + 1]);
        return Index;
    }
    return -1;
}

#if defined(PASCAL_CALLING_CONVENTION)
void SetPluginMemManager(TMemoryManager MemMgr) noexcept
{
    PLUGIN_MEMORY_MANAGER = MemMgr;
}
#endif

void SetPluginSimbaMethods(TSimbaMethods Methods) noexcept
{
    PLUGIN_SYNC_METHODS = Methods;
}

void SetPluginSimbaMemoryAllocators(TSimbaMemoryAllocators Allocators) noexcept
{
    PLUGIN_MEMORY_ALLOCATORS = Allocators;
}

void RegisterSimbaPlugin(TSimbaInfomation* Information, TSimbaMethodsExtended* Methods) noexcept
{
    if (Information->SimbaMajor < 1500)
    {
        std::memcpy(&PLUGIN_SIMBA_INFO, Information, offsetof(TSimbaInfomation, Compiler));
        std::memcpy(&PLUGIN_SIMBA_METHODS, Methods, offsetof(TSimbaMethodsExtended, RaiseException));
    }
    else
    {
        std::memcpy(&PLUGIN_SIMBA_INFO, Information, sizeof(TSimbaInfomation));
        std::memcpy(&PLUGIN_SIMBA_METHODS, Methods, sizeof(TSimbaMethodsExtended));
    }
}

void OnAttach(void* info) noexcept
{
    control_center.reset();
    EIOS_KillZombieClients();
}

void OnDetach() noexcept
{
    control_center.reset();
    EIOS_KillZombieClients();
}


// MARK: - Reflection

STRUCT_PACK(typedef struct
{
    std::make_signed_t<std::size_t> refCount;
    std::make_signed_t<std::size_t> length;
    char data[];
}) PascalArray;

#if defined(FPC2)
STRUCT_PACK(typedef struct
{
    std::make_signed_t<std::size_t> refCount;
    std::make_signed_t<std::size_t> length;
    char data[];
}) PascalString;
#else
    #if defined(_MSC_VER)
    #pragma pack(push, 1)
    typedef struct
    {
        std::uint16_t codePage;
        std::uint16_t elementSize;
        #if defined(__x86_64__)
        std::uint32_t dummy;
        #endif
        std::make_signed_t<std::size_t> refCount;
        std::make_signed_t<std::size_t> length;
        char data[];
    } PascalString;
    #pragma pack(pop)
    #else
    typedef struct
    {
        std::uint16_t codePage;
        std::uint16_t elementSize;
        #if defined(__x86_64__)
        std::uint32_t dummy;
        #endif
        std::make_signed_t<std::size_t> refCount;
        std::make_signed_t<std::size_t> length;
        char data[];
    } __attribute__((__packed__)) PascalString;
    #endif
#endif // defined

STRUCT_PACK(typedef struct
{
    char* cls;
    char* field;
    char* desc;
}) PascalField;

#if defined(_MSC_VER)
#pragma pack(push, 1)
template<typename T, std::size_t size>
struct StaticPascalArray
{
    const std::make_signed_t<std::size_t> refCount = -1;
    const std::make_signed_t<std::size_t> length = size;
    T data[size] = {0};
};
#pragma pack(pop)
#else
template<typename T, std::size_t size>
struct __attribute__((__packed__)) StaticPascalArray
{
    const std::make_signed_t<std::size_t> refCount = -1;
    const std::make_signed_t<std::size_t> length = size;
    T data[size] = {0};
};
#endif

template<typename F, typename... Extras>
void call_pascal_function(void** Params, void** Result, F&& fn, Extras... extras)
{
    using Args = typename function_arguments_type<F>::type;
    using R = typename function_return_type<F>::type;
    //using R = typename decltype(std::function{fn})::result_type;

    std::uint32_t ArgumentIndex = 0;
    auto arguments = tuple_transform([&Params, &ArgumentIndex](auto arg) {
        return PascalRead<decltype(arg)>(Params[ArgumentIndex++]);
    }, std::tuple<Args>());

    auto native_arguments = std::tuple_cat(tuple_remove_last_n<sizeof...(Extras)>(arguments), std::make_tuple(extras...));

    if constexpr(std::is_same<void, R>::value)
    {
        return std::apply([fn](auto... args) {
            return fn(args...);
        }, native_arguments);
    }
    else
    {
        R result = std::apply([fn](auto... args) {
            return fn(args...);
        }, native_arguments);

        PascalWrite(Result, result);
    }
}

// MARK: Single Functions

void Pascal_Reflect_ClassLoader(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);

        PascalWrite(Result, eios->control_center->reflect_class_loader(object));
    }
}

void Pascal_Reflect_Equal(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject first = PascalRead<jobject>(Params[1]);
        jobject second = PascalRead<jobject>(Params[2]);

        PascalWrite(Result, eios->control_center->reflect_is_objects_equal(first, second));
    }
}

void Pascal_Reflect_InstanceOf(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        char* cls = PascalRead<char*>(Params[2]);

        PascalWrite(Result, eios->control_center->reflect_instance_of(object, cls));
    }
}

void Pascal_Reflect_Object(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_object(hook));
    }
}

void Pascal_Reflect_Release_Object(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        if (object)
        {
            eios->control_center->reflect_release_object(object);
        }
    }
}

void Pascal_Reflect_Release_Objects(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        std::size_t length = 0;
        jobject* objects = GetArray<jobject>(PascalRead<void*>(Params[1]), &length);

        //Make sure we're not freeing null objects..
        if (objects && length > 0)
        {
            //This is the fastest possible way to remove duplicates..
            //Do NOT use `unordered_set` constructor instead of the for-loop.
            //It is slower than emplace/insert.
            std::unordered_set<jobject> set;
            for (std::size_t i = 0; i < length; ++i)
            {
                set.insert(objects[i]);
            }

            //Remove null objects
            set.erase(nullptr);

            //Create a contiguous array of objects for the client.
            std::vector<jobject> objects;
            objects.assign(set.begin(), set.end());

            //Make sure we're not freeing null objects..
            if (objects.size())
            {
                eios->control_center->reflect_release_objects(objects.data(), objects.size());
            }
        }
    }
}

void Pascal_Reflect_Boolean(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_boolean(hook));
    }
}

void Pascal_Reflect_Char(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_char(hook));
    }
}

void Pascal_Reflect_Byte(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_byte(hook));
    }
}

void Pascal_Reflect_Short(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_short(hook));
    }
}

void Pascal_Reflect_Int(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_int(hook));
    }
}

void Pascal_Reflect_Long(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_long(hook));
    }
}

void Pascal_Reflect_Float(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_float(hook));
    }
}

void Pascal_Reflect_Double(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        PascalWrite(Result, eios->control_center->reflect_double(hook));
    }
}

void Pascal_Reflect_String(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        PascalField* field = PascalRead<PascalField*>(Params[2]);

        ReflectionHook hook{object, field->cls, field->field, field->desc};
        std::string result = eios->control_center->reflect_string(hook);

        if (!result.empty())
        {
            char* output = AllocateString<char>(result.length());
            std::memcpy(output, &result[0], result.length());
            output[result.length()] = '\0';

            PascalWrite(Result, output);
        }
    }
}


// MARK: - Array Functions

void* read_array(Stream &stream, ReflectionType type)
{
    std::size_t length = stream.read<std::size_t>();

    if (type == ReflectionType::STRING)
    {
        std::size_t element_size = ControlCenter::reflect_size_for_type(type);
        void** buffer = AllocateArray<void*>(length, element_size);

        for (std::size_t i = 0; i < length; ++i)
        {
            buffer[i] = nullptr;
            std::string string = stream.read<std::string>();
            if (!string.empty())
            {
                char* output = AllocateString<char>(string.length());
                std::memcpy(output, &string[0], string.length());
                output[string.length()] = '\0';

                buffer[i] = output;
            }
        }
        return buffer;
    }

    std::size_t element_size = ControlCenter::reflect_size_for_type(type);
    void* buffer = AllocateArray<void>(length, element_size);
    stream.read(buffer, length * element_size);
    return buffer;
}

void Pascal_Reflect_Array(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jobject object = PascalRead<jobject>(Params[1]);
    PascalField* field = PascalRead<PascalField*>(Params[2]);

    ReflectionHook hook{object, field->cls, field->field, field->desc};
    PascalWrite(Result, eios->control_center->reflect_array(hook));
}

void Pascal_Reflect_Array_With_Size(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jobject object = PascalRead<jobject>(Params[1]);
    std::size_t* output_size = PascalRead<std::size_t*>(Params[2]);
    PascalField* field = PascalRead<PascalField*>(Params[3]);

    ReflectionHook hook{object, field->cls, field->field, field->desc};
    PascalWrite(Result, eios->control_center->reflect_array(hook, output_size));
}

void Pascal_Reflect_Array_Size(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    PascalWrite(Result, eios->control_center->reflect_array_size(array));
}

void Pascal_Reflect_Array_Indices(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    std::size_t length = 0;
    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::int32_t* indices = GetArray<std::int32_t>(PascalRead<void*>(Params[3]), &length);

    if (!indices || length == 0)
    {
        return;
    }

    ImageData* image_data = eios->control_center->reflect_array_indices(array, type, indices, length);
    PascalWrite(Result, read_array(image_data->data_stream(), type));
}

void Pascal_Reflect_Array_SingleIndex(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t index = PascalRead<std::size_t>(Params[3]);

    Stream &stream = eios->control_center->reflect_array(array, type, 1, index)->data_stream();
    if (stream.read<std::size_t>() == 0)
    {
        PascalWrite(Result, nullptr);
        return;
    }
    PascalWrite(eios, stream, Result, type);
}

void Pascal_Reflect_Array_SingleIndex2D(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t x = PascalRead<std::int32_t>(Params[3]);
    std::size_t y = PascalRead<std::int32_t>(Params[4]);

    Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y)->data_stream();
    if (stream.read<std::size_t>() == 0)
    {
        PascalWrite(Result, nullptr);
        return;
    }
    PascalWrite(eios, stream, Result, type);
}

void Pascal_Reflect_Array_SingleIndex3D(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t x = PascalRead<std::int32_t>(Params[3]);
    std::size_t y = PascalRead<std::int32_t>(Params[4]);
    std::size_t z = PascalRead<std::int32_t>(Params[5]);

    Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y, z)->data_stream();
    if (stream.read<std::size_t>() == 0)
    {
        PascalWrite(Result, nullptr);
        return;
    }
    PascalWrite(eios, stream, Result, type);
}

void Pascal_Reflect_Array_SingleIndex4D(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t x = PascalRead<std::int32_t>(Params[3]);
    std::size_t y = PascalRead<std::int32_t>(Params[4]);
    std::size_t z = PascalRead<std::int32_t>(Params[5]);
    std::size_t w = PascalRead<std::int32_t>(Params[6]);

    Stream &stream = eios->control_center->reflect_array(array, type, 1, x, y, z, w)->data_stream();
    if (stream.read<std::size_t>() == 0)
    {
        PascalWrite(Result, nullptr);
        return;
    }
    PascalWrite(eios, stream, Result, type);
}

void Pascal_Reflect_Array_Index(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t index = PascalRead<std::size_t>(Params[3]);
    std::size_t length = PascalRead<std::size_t>(Params[4]);

    if (length == 0)
    {
        return;
    }

    ImageData* image_data = eios->control_center->reflect_array(array, type, length, index);
    PascalWrite(Result, read_array(image_data->data_stream(), type));
}

void Pascal_Reflect_Array_Index2D(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t length = PascalRead<std::size_t>(Params[3]);
    std::size_t x = PascalRead<std::int32_t>(Params[4]);
    std::size_t y = PascalRead<std::int32_t>(Params[5]);

    if (length == 0)
    {
        return;
    }

    ImageData* image_data = eios->control_center->reflect_array(array, type, length, x, y);
    PascalWrite(Result, read_array(image_data->data_stream(), type));
}

void Pascal_Reflect_Array_Index3D(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t length = PascalRead<std::size_t>(Params[3]);
    std::size_t x = PascalRead<std::int32_t>(Params[4]);
    std::size_t y = PascalRead<std::int32_t>(Params[5]);
    std::size_t z = PascalRead<std::int32_t>(Params[6]);

    if (length == 0)
    {
        return;
    }

    ImageData* image_data = eios->control_center->reflect_array(array, type, length, x, y, z);
    PascalWrite(Result, read_array(image_data->data_stream(), type));
}

void Pascal_Reflect_Array_Index4D(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    jarray array = PascalRead<jarray>(Params[1]);
    ReflectionType type = PascalRead<ReflectionType>(Params[2]);
    std::size_t length = PascalRead<std::size_t>(Params[3]);
    std::size_t x = PascalRead<std::int32_t>(Params[4]);
    std::size_t y = PascalRead<std::int32_t>(Params[5]);
    std::size_t z = PascalRead<std::int32_t>(Params[6]);
    std::size_t w = PascalRead<std::int32_t>(Params[7]);

    if (length == 0)
    {
        return;
    }

    ImageData* image_data = eios->control_center->reflect_array(array, type, length, x, y, z, w);
    PascalWrite(Result, read_array(image_data->data_stream(), type));
}

void Pascal_Reflect_Class_Name(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        std::string result = eios->control_center->reflect_class_name(object);

        if (!result.empty())
        {
            char *output = AllocateString<char>(result.length());
            std::memcpy(output, &result[0], result.length());
            output[result.length()] = '\0';

            PascalWrite(Result, output);
        }
    }
}

void Pascal_Reflect_Class_Type(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);

    if (eios)
    {
        jobject object = PascalRead<jobject>(Params[1]);
        std::string result = eios->control_center->reflect_class_type(object);

        if (!result.empty())
        {
            char *output = AllocateString<char>(result.length());
            std::memcpy(output, &result[0], result.length());
            output[result.length()] = '\0';

            PascalWrite(Result, output);
        }
    }
}

void Pascal_GetImageFormat(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, eios->control_center->get_image_format());
}

void Pascal_SetImageFormat(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    ImageFormat format = PascalRead<ImageFormat>(Params[1]);
    EIOS_SetImageFormat(eios, format);
}

void Pascal_GetDebugImageBuffer(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, eios->control_center->get_debug_image());
}

void Pascal_SetGraphicsDebugging(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    bool enabled = PascalRead<bool>(Params[1]);
    EIOS_SetGraphicsDebugging(eios, enabled);
}

void Pascal_PairClient(void** Params, void** Result) noexcept
{
    std::int32_t pid = PascalRead<std::int32_t>(Params[0]);
    PascalWrite(Result, EIOS_PairClient(pid));
}

void Pascal_KillClientPID(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    EIOS_KillClient(eios);
}

void Pascal_KillClient(void** Params, void** Result) noexcept
{
    std::int32_t pid = PascalRead<std::int32_t>(Params[0]);
    EIOS_KillClientPID(pid);
}

void Pascal_ReleaseClient(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    EIOS_ReleaseClient(eios);
}

void Pascal_GetClients(void** Params, void** Result) noexcept
{
    bool unpaired_only = PascalRead<bool>(Params[0]);
    PascalWrite(Result, EIOS_GetClients(unpaired_only));
}

void Pascal_GetClientPID(void** Params, void** Result) noexcept
{
    std::size_t index = PascalRead<std::size_t>(Params[0]);
    PascalWrite(Result, EIOS_GetClientPID(index));
}

void Pascal_Inject(void** Params, void** Result) noexcept
{
    EIOS_Inject(PascalRead<const char*>(Params[0]));
}

void Pascal_Inject_PID(void** Params, void** Result) noexcept
{
    EIOS_Inject_PID(PascalRead<std::int32_t>(Params[0]));
}

void Pascal_HasFocus(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, EIOS_HasFocus(eios));
}

void Pascal_GainFocus(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    EIOS_GainFocus(eios);
}

void Pascal_LoseFocus(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    EIOS_LoseFocus(eios);
}

void Pascal_IsKeyboardInputEnabled(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, EIOS_IsKeyboardInputEnabled(eios));
}

void Pascal_SetKeyboardInputEnabled(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    bool enabled = PascalRead<bool>(Params[1]);
    EIOS_SetKeyboardInputEnabled(eios, enabled);
}

void Pascal_IsMouseInputEnabled(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, EIOS_IsMouseInputEnabled(eios));
}

void Pascal_SetMouseInputEnabled(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    bool enabled = PascalRead<bool>(Params[1]);
    EIOS_SetMouseInputEnabled(eios, enabled);
}

void Pascal_GetRealMousePosition(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    std::int32_t* x = static_cast<std::int32_t*>(Params[1]);
    std::int32_t* y = static_cast<std::int32_t*>(Params[2]);
    if (x && y)
    {
        EIOS_GetRealMousePosition(eios, x, y);
    }
}

void Pascal_GetKeyboardSpeed(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, EIOS_GetKeyboardSpeed(eios));
}

void Pascal_SetKeyboardSpeed(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    std::int32_t speed = PascalRead<std::int32_t>(Params[1]);
    EIOS_SetKeyboardSpeed(eios, speed);
}

void Pascal_GetKeyboardRepeatDelay(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, EIOS_GetKeyboardRepeatDelay(eios));
}

void Pascal_SetKeyboardRepeatDelay(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    std::int32_t delay = PascalRead<std::int32_t>(Params[1]);
    EIOS_SetKeyboardRepeatDelay(eios, delay);
}

void Pascal_GetGraphicsScaling(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    PascalWrite(Result, EIOS_GetGraphicsScaling(eios));
}

void Pascal_SetGraphicsScaling(void** Params, void** Result) noexcept
{
    EIOS* eios = PascalRead<EIOS*>(Params[0]);
    bool enabled = PascalRead<bool>(Params[1]);
    EIOS_SetGraphicsScaling(eios, enabled);
}


//MARK: - RemoteVM
void Pascal_RemoteVM_Init(void** Params, void** Result)
{
    EIOS* eios = PascalRead<EIOS*>(Params[1]);
    RemoteVM** remote_vm = static_cast<RemoteVM**>(Params[0]);
    if (eios && eios->control_center)
    {
        *remote_vm = eios->control_center->create_remote_vm().release();
    }
}

void Pascal_RemoteVM_InitEx(void** Params, void** Result)
{
#if defined(_WIN32) || defined(_WIN64)
    static Module module = Module("jvm.dll");
#elif defined(__APPLE__)
    static Module module = Module("libjvm.dylib");
    #else
    static Module module = Module("libjvm.so");
#endif // defined

    jint (JNICALL *JNI_GetCreatedJavaVMs)(JavaVM**, jsize, jsize*) = nullptr;
    jint (JNICALL *JNI_GetDefaultJavaVMInitArgs)(void* args) = nullptr;
    jint (JNICALL *JNI_CreateJavaVM)(JavaVM** pvm, void** penv, void* args) = nullptr;

    if (!module ||
        !module.AddressOf(JNI_GetCreatedJavaVMs, "JNI_GetCreatedJavaVMs") ||
        !module.AddressOf(JNI_GetDefaultJavaVMInitArgs, "JNI_GetDefaultJavaVMInitArgs") ||
        !module.AddressOf(JNI_CreateJavaVM, "JNI_CreateJavaVM"))
    {
        return;
    }

    RemoteVM** remote_vm = static_cast<RemoteVM**>(Params[0]);
    std::vector<char*> arguments = GetArrayAsVector<char*>(PascalRead<void*>(Params[1]));

    JavaVMInitArgs jvm_args;
    std::unique_ptr<JavaVMOption[]> options = !arguments.empty() ? std::make_unique<JavaVMOption[]>(arguments.size()) : nullptr;

    for (std::size_t i = 0; i < arguments.size(); ++i)
    {
        options[i].optionString = arguments[i];
    }

    JNI_GetDefaultJavaVMInitArgs(&jvm_args);
    jvm_args.version = JNI_VERSION_1_8;
    jvm_args.nOptions = arguments.size();
    jvm_args.options = options.get();
    jvm_args.ignoreUnrecognized = false;

    jint num_vms = 0;
    const jint max_vms = 5;
    JavaVM* vms[max_vms] = {0};
    if (JNI_GetCreatedJavaVMs(vms, max_vms, &num_vms) == JNI_OK)
    {
        for (int i = 0; i < num_vms; ++i)
        {
            if (vms[i])
            {
                JNIEnv* env = nullptr;
                vms[i]->GetEnv(reinterpret_cast<void**>(&env), jvm_args.version);
                if (!env)
                {
                    vms[i]->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
                }

                if (env)
                {
                    *remote_vm = new RemoteVM(env, nullptr, nullptr, nullptr);
                }
                return;
            }
        }
    }

    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &jvm_args) == JNI_OK)
    {
        if (env)
        {
            *remote_vm = new RemoteVM(env, nullptr, nullptr, nullptr);
            return;
        }

        vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
        if (env)
        {
            *remote_vm = new RemoteVM(env, nullptr, nullptr, nullptr);
            return;
        }

        vm->DestroyJavaVM();
    }
}

void Pascal_RemoteVM_Free(void** Params, void** Result)
{
    RemoteVM** remote_vm = static_cast<RemoteVM**>(Params[0]);
    if (remote_vm && *remote_vm)
    {
        if (!(*remote_vm)->is_remote())
        {
            JavaVM* vm = (*remote_vm)->GetJavaVM();
            if (vm)
            {
                JNIEnv* env = nullptr;
                vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
                if (env)
                {
                    vm->DetachCurrentThread();
                }
                vm->DestroyJavaVM();
            }

        }
        delete *remote_vm;
        *remote_vm = nullptr;
    }
}

void Pascal_RemoteVM_MaxMemoryChunkSize(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    PascalWrite(Result, remote_vm->MaxMemoryChunkSize());
}

void Pascal_RemoteVM_AllocateMemory(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    std::size_t size = PascalRead<std::size_t>(Params[1]);
    PascalWrite(Result, remote_vm->AllocateMemory(size));
}

void Pascal_RemoteVM_ReadMemory(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    void* destintation = PascalRead<void*>(Params[1]);
    void* source = PascalRead<void*>(Params[2]);
    std::size_t size = PascalRead<std::size_t>(Params[3]);
    PascalWrite(Result, remote_vm->ReadMemory(destintation, source, size));
}

void Pascal_RemoteVM_WriteMemory(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    void* destintation = PascalRead<void*>(Params[1]);
    void* source = PascalRead<void*>(Params[2]);
    std::size_t size = PascalRead<std::size_t>(Params[3]);
    PascalWrite(Result, remote_vm->WriteMemory(destintation, source, size));
}

void Pascal_RemoteVM_FreeMemory(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    void* memory = PascalRead<void*>(Params[1]);
    remote_vm->FreeMemory(memory);
}


void Pascal_RemoteVM_GetVersion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    PascalWrite(Result, remote_vm->GetVersion());
}

void Pascal_RemoteVM_DefineClass(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    const char* name = PascalRead<const char*>(Params[1]);
    jobject loader = PascalRead<jobject>(Params[2]);
    jbyte* buf = PascalRead<jbyte*>(Params[3]);
    jsize len = PascalRead<jsize>(Params[4]);
    PascalWrite(Result, remote_vm->DefineClass(name, loader, buf, len));
}

void Pascal_RemoteVM_FindClass(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    const char* name = PascalRead<const char*>(Params[1]);
    PascalWrite(Result, remote_vm->FindClass(name));
}

void Pascal_RemoteVM_FromReflectedMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject method = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->FromReflectedMethod(method));
}

void Pascal_RemoteVM_FromReflectedField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject field = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->FromReflectedField(field));
}

void Pascal_RemoteVM_ToReflectedMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass cls = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    jboolean isStatic = PascalRead<jboolean>(Params[3]);
    PascalWrite(Result, remote_vm->ToReflectedMethod(cls, methodID, isStatic));
}

void Pascal_RemoteVM_GetSuperclass(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass sub = PascalRead<jclass>(Params[1]);
    PascalWrite(Result, remote_vm->GetSuperclass(sub));
}

void Pascal_RemoteVM_IsAssignableFrom(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass sub = PascalRead<jclass>(Params[1]);
    jclass sup = PascalRead<jclass>(Params[2]);
    PascalWrite(Result, remote_vm->IsAssignableFrom(sub, sup));
}

void Pascal_RemoteVM_ToReflectedField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass cls = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jboolean isStatic = PascalRead<jboolean>(Params[3]);
    PascalWrite(Result, remote_vm->ToReflectedField(cls, fieldID, isStatic));
}

void Pascal_RemoteVM_Throw(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jthrowable obj = PascalRead<jthrowable>(Params[1]);
    PascalWrite(Result, remote_vm->Throw(obj));
}

void Pascal_RemoteVM_ThrowNew(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    const char* msg = PascalRead<const char*>(Params[2]);
    PascalWrite(Result, remote_vm->ThrowNew(clazz, msg));
}

void Pascal_RemoteVM_GetExceptionMessage(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    std::string result = remote_vm->GetExceptionMessage();

    if (!result.empty())
    {
        char* output = AllocateString<char>(result.length());
        std::memcpy(output, &result[0], result.length());
        output[result.length()] = '\0';

        PascalWrite(Result, output);
    }
}

void Pascal_RemoteVM_FatalError(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    const char* msg = PascalRead<const char*>(Params[1]);
    remote_vm->FatalError(msg);
}

void Pascal_RemoteVM_DeleteGlobalRef(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject gref = PascalRead<jobject>(Params[1]);
    remote_vm->DeleteGlobalRef(gref);
}

void Pascal_RemoteVM_IsSameObject(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj1 = PascalRead<jobject>(Params[1]);
    jobject obj2 = PascalRead<jobject>(Params[2]);
    PascalWrite(Result, remote_vm->IsSameObject(obj1, obj2));
}

void Pascal_RemoteVM_AllocObject(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    PascalWrite(Result, remote_vm->AllocObject(clazz));
}

void Pascal_RemoteVM_NewObject(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->NewObject(clazz, methodID, args));
}

void Pascal_RemoteVM_GetObjectClass(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->GetObjectClass(obj));
}

void Pascal_RemoteVM_IsInstanceOf(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    PascalWrite(Result, remote_vm->IsInstanceOf(obj, clazz));
}

void Pascal_RemoteVM_GetMethodID(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    const char* name = PascalRead<const char*>(Params[2]);
    const char* sig = PascalRead<const char*>(Params[3]);
    PascalWrite(Result, remote_vm->GetMethodID(clazz, name, sig));
}

void Pascal_RemoteVM_CallObjectMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallObjectMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallBooleanMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallBooleanMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallByteMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallByteMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallCharMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallCharMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallShortMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallShortMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallIntMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);
    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallIntMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallLongMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallLongMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallFloatMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallFloatMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallDoubleMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallDoubleMethod(obj, methodID, args));
}

void Pascal_RemoteVM_CallVoidMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    remote_vm->CallVoidMethod(obj, methodID, args);
}

void Pascal_RemoteVM_CallNonvirtualObjectMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualObjectMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualBooleanMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualBooleanMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualByteMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualByteMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualCharMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualCharMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualShortMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualShortMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualIntMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualIntMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualLongMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualLongMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualFloatMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualFloatMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualDoubleMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    PascalWrite(Result, remote_vm->CallNonvirtualDoubleMethod(obj, clazz, methodID, args));
}

void Pascal_RemoteVM_CallNonvirtualVoidMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jmethodID methodID = PascalRead<jmethodID>(Params[3]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[4]));
    remote_vm->CallNonvirtualVoidMethod(obj, clazz, methodID, args);
}

void Pascal_RemoteVM_GetFieldID(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    const char* name = PascalRead<const char*>(Params[2]);
    const char* sig = PascalRead<const char*>(Params[3]);
    PascalWrite(Result, remote_vm->GetFieldID(clazz, name, sig));
}

void Pascal_RemoteVM_GetObjectField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetObjectField(obj, fieldID));
}

void Pascal_RemoteVM_GetBooleanField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetBooleanField(obj, fieldID));
}

void Pascal_RemoteVM_GetByteField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetByteField(obj, fieldID));
}

void Pascal_RemoteVM_GetCharField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetCharField(obj, fieldID));
}

void Pascal_RemoteVM_GetShortField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetShortField(obj, fieldID));
}

void Pascal_RemoteVM_GetIntField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetIntField(obj, fieldID));
}

void Pascal_RemoteVM_GetLongField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetLongField(obj, fieldID));
}

void Pascal_RemoteVM_GetFloatField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetFloatField(obj, fieldID));
}

void Pascal_RemoteVM_GetDoubleField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetDoubleField(obj, fieldID));
}

void Pascal_RemoteVM_SetObjectField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jobject value = PascalRead<jobject>(Params[3]);
    remote_vm->SetObjectField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetBooleanField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jboolean value = PascalRead<jboolean>(Params[3]);
    remote_vm->SetBooleanField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetByteField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jbyte value = PascalRead<jbyte>(Params[3]);
    remote_vm->SetByteField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetCharField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jchar value = PascalRead<jchar>(Params[3]);
    remote_vm->SetCharField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetShortField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jshort value = PascalRead<jshort>(Params[3]);
    remote_vm->SetShortField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetIntField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jint value = PascalRead<jint>(Params[3]);
    remote_vm->SetIntField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetLongField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jlong value = PascalRead<jlong>(Params[3]);
    remote_vm->SetLongField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetFloatField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jfloat value = PascalRead<jfloat>(Params[3]);
    remote_vm->SetFloatField(obj, fieldID, value);
}

void Pascal_RemoteVM_SetDoubleField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jdouble value = PascalRead<jdouble>(Params[3]);
    remote_vm->SetDoubleField(obj, fieldID, value);
}

void Pascal_RemoteVM_GetStaticMethodID(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    const char* name = PascalRead<const char*>(Params[2]);
    const char* sig = PascalRead<const char*>(Params[3]);
    PascalWrite(Result, remote_vm->GetStaticMethodID(clazz, name, sig));
}

void Pascal_RemoteVM_CallStaticObjectMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticObjectMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticBooleanMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticBooleanMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticByteMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticByteMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticCharMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticCharMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticShortMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticShortMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticIntMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticIntMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticLongMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticLongMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticFloatMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticFloatMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticDoubleMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    PascalWrite(Result, remote_vm->CallStaticDoubleMethod(clazz, methodID, args));
}

void Pascal_RemoteVM_CallStaticVoidMethod(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jmethodID methodID = PascalRead<jmethodID>(Params[2]);

    std::vector<jvalue> args = GetArrayAsVector<jvalue>(PascalRead<void*>(Params[3]));
    remote_vm->CallStaticVoidMethod(clazz, methodID, args);
}

void Pascal_RemoteVM_GetStaticFieldID(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    const char* name = PascalRead<const char*>(Params[2]);
    const char* sig = PascalRead<const char*>(Params[3]);
    PascalWrite(Result, remote_vm->GetStaticFieldID(clazz, name, sig));
}

void Pascal_RemoteVM_GetStaticObjectField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticObjectField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticBooleanField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticBooleanField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticByteField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticByteField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticCharField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticCharField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticShortField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticShortField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticIntField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticIntField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticLongField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticLongField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticFloatField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticFloatField(clazz, fieldID));
}

void Pascal_RemoteVM_GetStaticDoubleField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    PascalWrite(Result, remote_vm->GetStaticDoubleField(clazz, fieldID));
}

void Pascal_RemoteVM_SetStaticObjectField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jobject value = PascalRead<jobject>(Params[3]);
    remote_vm->SetStaticObjectField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticBooleanField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jboolean value = PascalRead<jboolean>(Params[3]);
    remote_vm->SetStaticBooleanField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticByteField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jbyte value = PascalRead<jbyte>(Params[3]);
    remote_vm->SetStaticByteField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticCharField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jchar value = PascalRead<jchar>(Params[3]);
    remote_vm->SetStaticCharField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticShortField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jshort value = PascalRead<jshort>(Params[3]);
    remote_vm->SetStaticShortField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticIntField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jint value = PascalRead<jint>(Params[3]);
    remote_vm->SetStaticIntField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticLongField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jlong value = PascalRead<jlong>(Params[3]);
    remote_vm->SetStaticLongField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticFloatField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jfloat value = PascalRead<jfloat>(Params[3]);
    remote_vm->SetStaticFloatField(clazz, fieldID, value);
}

void Pascal_RemoteVM_SetStaticDoubleField(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jclass clazz = PascalRead<jclass>(Params[1]);
    jfieldID fieldID = PascalRead<jfieldID>(Params[2]);
    jdouble value = PascalRead<jdouble>(Params[3]);
    remote_vm->SetStaticDoubleField(clazz, fieldID, value);
}

void Pascal_RemoteVM_NewString(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    const wchar_t* unicode = PascalRead<const wchar_t*>(Params[1]);
    PascalWrite(Result, remote_vm->NewString(unicode));
}

void Pascal_RemoteVM_GetStringLength(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jstring str = PascalRead<jstring>(Params[1]);
    PascalWrite(Result, remote_vm->GetStringLength(str));
}

void Pascal_RemoteVM_GetStringChars(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jstring str = PascalRead<jstring>(Params[1]);

    std::wstring result = remote_vm->GetStringChars(str);

    if (!result.empty())
    {
        wchar_t* output = AllocateString<wchar_t>(result.length());
        std::memcpy(output, &result[0], result.length());
        output[result.length()] = '\0';
        PascalWrite(Result, output);
    }
}

void Pascal_RemoteVM_NewStringUTF(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    const char* utf = PascalRead<const char*>(Params[1]);
    PascalWrite(Result, remote_vm->NewStringUTF(utf));
}

void Pascal_RemoteVM_GetStringUTFLength(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jstring str = PascalRead<jstring>(Params[1]);
    PascalWrite(Result, remote_vm->GetStringUTFLength(str));
}

void Pascal_RemoteVM_GetStringUTFChars(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jstring str = PascalRead<jstring>(Params[1]);

    std::string result = remote_vm->GetStringUTFChars(str);

    if (!result.empty())
    {
        char* output = AllocateString<char>(result.length());
        std::memcpy(output, &result[0], result.length());
        output[result.length()] = '\0';
        PascalWrite(Result, output);
    }
}

void Pascal_RemoteVM_GetArrayLength(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jarray array = PascalRead<jarray>(Params[1]);
    PascalWrite(Result, remote_vm->GetArrayLength(array));
}

void Pascal_RemoteVM_NewObjectArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    jclass clazz = PascalRead<jclass>(Params[2]);
    jobject init = PascalRead<jobject>(Params[3]);
    PascalWrite(Result, remote_vm->NewObjectArray(len, clazz, init));
}

void Pascal_RemoteVM_NewBooleanArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewBooleanArray(len));
}

void Pascal_RemoteVM_NewByteArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewByteArray(len));
}

void Pascal_RemoteVM_NewCharArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewCharArray(len));
}

void Pascal_RemoteVM_NewShortArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewShortArray(len));
}

void Pascal_RemoteVM_NewIntArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewIntArray(len));
}

void Pascal_RemoteVM_NewLongArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewLongArray(len));
}

void Pascal_RemoteVM_NewFloatArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewFloatArray(len));
}

void Pascal_RemoteVM_NewDoubleArray(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jsize len = PascalRead<jsize>(Params[1]);
    PascalWrite(Result, remote_vm->NewDoubleArray(len));
}

void Pascal_RemoteVM_GetObjectArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobjectArray array = PascalRead<jobjectArray>(Params[1]);
    std::vector<jobject> elements = remote_vm->GetObjectArrayElements(array);

    jobject* buffer = AllocateArray<jobject>(elements.size());
    std::memcpy(buffer, &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_SetObjectArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobjectArray array = PascalRead<jobjectArray>(Params[1]);
    jsize index = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jobject* objects = GetArray<jobject>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetObjectArrayElements(array, index, std::vector<jobject>(objects, objects + length));
}

void Pascal_RemoteVM_GetBooleanArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jbooleanArray array = PascalRead<jbooleanArray>(Params[1]);
    std::vector<jboolean> elements = remote_vm->GetBooleanArrayElements(array);

    jboolean* buffer = AllocateArray<jboolean>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetByteArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jbyteArray array = PascalRead<jbyteArray>(Params[1]);
    std::vector<jbyte> elements = remote_vm->GetByteArrayElements(array);

    jbyte* buffer = AllocateArray<jbyte>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetCharArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jcharArray array = PascalRead<jcharArray>(Params[1]);
    std::vector<jchar> elements = remote_vm->GetCharArrayElements(array);

    jchar* buffer = AllocateArray<jchar>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetShortArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jshortArray array = PascalRead<jshortArray>(Params[1]);
    std::vector<jshort> elements = remote_vm->GetShortArrayElements(array);

    jshort* buffer = AllocateArray<jshort>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetIntArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jintArray array = PascalRead<jintArray>(Params[1]);
    std::vector<jint> elements = remote_vm->GetIntArrayElements(array);

    jint* buffer = AllocateArray<jint>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetLongArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jlongArray array = PascalRead<jlongArray>(Params[1]);
    std::vector<jlong> elements = remote_vm->GetLongArrayElements(array);

    jlong* buffer = AllocateArray<jlong>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetFloatArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jfloatArray array = PascalRead<jfloatArray>(Params[1]);
    std::vector<jfloat> elements = remote_vm->GetFloatArrayElements(array);

    jfloat* buffer = AllocateArray<jfloat>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetDoubleArrayElements(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jdoubleArray array = PascalRead<jdoubleArray>(Params[1]);
    std::vector<jdouble> elements = remote_vm->GetDoubleArrayElements(array);

    jdouble* buffer = AllocateArray<jdouble>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetBooleanArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jbooleanArray array = PascalRead<jbooleanArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jboolean> elements = remote_vm->GetBooleanArrayRegion(array, start, len);

    jboolean* buffer = AllocateArray<jboolean>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetByteArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jbyteArray array = PascalRead<jbyteArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jbyte> elements = remote_vm->GetByteArrayRegion(array, start, len);

    jbyte* buffer = AllocateArray<jbyte>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetCharArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jcharArray array = PascalRead<jcharArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jchar> elements = remote_vm->GetCharArrayRegion(array, start, len);

    jchar* buffer = AllocateArray<jchar>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetShortArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jshortArray array = PascalRead<jshortArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jshort> elements = remote_vm->GetShortArrayRegion(array, start, len);

    jshort* buffer = AllocateArray<jshort>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetIntArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jintArray array = PascalRead<jintArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jint> elements = remote_vm->GetIntArrayRegion(array, start, len);

    jint* buffer = AllocateArray<jint>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetLongArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jlongArray array = PascalRead<jlongArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jlong> elements = remote_vm->GetLongArrayRegion(array, start, len);

    jlong* buffer = AllocateArray<jlong>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetFloatArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jfloatArray array = PascalRead<jfloatArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jfloat> elements = remote_vm->GetFloatArrayRegion(array, start, len);

    jfloat* buffer = AllocateArray<jfloat>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_GetDoubleArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jdoubleArray array = PascalRead<jdoubleArray >(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);
    jsize len = PascalRead<jsize>(Params[3]);
    std::vector<jdouble> elements = remote_vm->GetDoubleArrayRegion(array, start, len);

    jdouble * buffer = AllocateArray<jdouble>(elements.size());
    std::memcpy(&buffer[0], &elements[0], sizeof(elements[0]) * elements.size());
    PascalWrite(Result, buffer);
}

void Pascal_RemoteVM_SetBooleanArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jbooleanArray array = PascalRead<jbooleanArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jboolean* elements = GetArray<jboolean>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetBooleanArrayRegion(array, start, std::vector<jboolean>(elements, elements + length));
}

void Pascal_RemoteVM_SetByteArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jbyteArray array = PascalRead<jbyteArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jbyte * elements = GetArray<jbyte >(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetByteArrayRegion(array, start, std::vector<jbyte>(elements, elements + length));
}

void Pascal_RemoteVM_SetCharArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jcharArray array = PascalRead<jcharArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jchar* elements = GetArray<jchar>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetCharArrayRegion(array, start, std::vector<jchar>(elements, elements + length));
}

void Pascal_RemoteVM_SetShortArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jshortArray array = PascalRead<jshortArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jshort* elements = GetArray<jshort>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetShortArrayRegion(array, start, std::vector<jshort>(elements, elements + length));
}

void Pascal_RemoteVM_SetIntArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jintArray array = PascalRead<jintArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jint* elements = GetArray<jint>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetIntArrayRegion(array, start, std::vector<jint>(elements, elements + length));
}

void Pascal_RemoteVM_SetLongArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jlongArray array = PascalRead<jlongArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jlong* elements = GetArray<jlong>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetLongArrayRegion(array, start, std::vector<jlong>(elements, elements + length));
}

void Pascal_RemoteVM_SetFloatArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jfloatArray array = PascalRead<jfloatArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jfloat* elements = GetArray<jfloat>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetFloatArrayRegion(array, start, std::vector<jfloat>(elements, elements + length));
}

void Pascal_RemoteVM_SetDoubleArrayRegion(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jdoubleArray array = PascalRead<jdoubleArray>(Params[1]);
    jsize start = PascalRead<jsize>(Params[2]);

    std::size_t length = 0;
    jdouble* elements = GetArray<jdouble>(PascalRead<void*>(Params[3]), &length);
    remote_vm->SetDoubleArrayRegion(array, start, std::vector<jdouble>(elements, elements + length));
}

void Pascal_RemoteVM_MonitorEnter(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->MonitorEnter(obj));
}

void Pascal_RemoteVM_MonitorExit(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->MonitorExit(obj));
}

void Pascal_RemoteVM_GetJavaVM(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    PascalWrite(Result, remote_vm->GetJavaVM());
}

void Pascal_RemoteVM_NewDirectByteBuffer(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    void* address = PascalRead<void*>(Params[1]);
    jlong capacity = PascalRead<jlong>(Params[2]);
    PascalWrite(Result, remote_vm->NewDirectByteBuffer(address, capacity));
}

void Pascal_RemoteVM_GetDirectBufferAddress(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject buf = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->GetDirectBufferAddress(buf));
}

void Pascal_RemoteVM_GetDirectBufferCapacity(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject buf = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->GetDirectBufferCapacity(buf));
}

void Pascal_RemoteVM_GetObjectRefType(void** Params, void** Result)
{
    RemoteVM* remote_vm = PascalRead<RemoteVM*>(Params[0]);
    jobject obj = PascalRead<jobject>(Params[1]);
    PascalWrite(Result, remote_vm->GetObjectRefType(obj));
}



template<typename T>
int PascalHigh(T* Arr) noexcept
{
    return reinterpret_cast<int*>(Arr)[-1];
}

template<typename T>
int PascalLength(T* Arr) noexcept
{
    return PascalHigh<T>(Arr) + 1;
}

template<typename T>
T* AllocateArray(std::size_t size, std::size_t element_size) noexcept
{
    std::size_t new_size = (size * element_size) + sizeof(PascalArray);
    #if defined(PASCAL_CALLING_CONVENTION)
    PascalArray* ptr = static_cast<PascalArray*>(PLUGIN_MEMORY_MANAGER.AllocMem(new_size));
    #else
    PascalArray* ptr = static_cast<PascalArray*>(
            PLUGIN_MEMORY_ALLOCATORS.GetMem ? PLUGIN_MEMORY_ALLOCATORS.GetMem(new_size) :
            PLUGIN_SIMBA_METHODS.GetMem ? PLUGIN_SIMBA_METHODS.GetMem(new_size) :
            nullptr
    );
    #endif
    ptr->refCount = 0;
    ptr->length = size - 1;
    return reinterpret_cast<T*>(++ptr);
}

template<typename T>
T* AllocateString(std::size_t size, std::size_t element_size) noexcept
{
    std::size_t new_size = (size * element_size) + sizeof(PascalString) + sizeof(T);
#if defined(PASCAL_CALLING_CONVENTION)
    PascalString* ptr = static_cast<PascalString*>(PLUGIN_MEMORY_MANAGER.AllocMem(new_size));
#else
    PascalString* ptr = static_cast<PascalString*>(
            PLUGIN_MEMORY_ALLOCATORS.GetMem ? PLUGIN_MEMORY_ALLOCATORS.GetMem(new_size) :
            PLUGIN_SIMBA_METHODS.GetMem ? PLUGIN_SIMBA_METHODS.GetMem(new_size) :
            nullptr
    );
#endif

    ptr->refCount = 0;
    ptr->length = size;
    ptr->codePage = 65001; //CP_UTF8
    ptr->elementSize = sizeof(T);
    *reinterpret_cast<char*>(ptr + 1) = '\0';
    return reinterpret_cast<T*>(++ptr);
}

template<typename T>
T* GetArray(void* ptr, std::size_t* size) noexcept
{
    if (!ptr)
    {
        return nullptr;
    }

    PascalArray* mem = static_cast<PascalArray*>(ptr) - 1;
    *size = mem->length + 1;
    return reinterpret_cast<T*>(mem->data);
}

template<typename T>
std::vector<T> GetArrayAsVector(void* ptr) noexcept
{
    if (!ptr)
    {
        return {};
    }

    PascalArray* mem = static_cast<PascalArray*>(ptr) - 1;
    if (mem->length + 1 > 0)
    {
        T* values = reinterpret_cast<T*>(mem->data);
        return std::vector<T>(values, values + mem->length + 1);
    }
    return {};
}

template<typename T>
T* GetString(void* ptr, std::size_t* size) noexcept
{
    PascalString* mem = static_cast<PascalString*>(ptr) - 1;
    *size = mem->length;
    return reinterpret_cast<T*>(mem->data);
}

template<typename T>
T PascalRead(void* ptr) noexcept
{
    T result;
    std::memcpy(&result, ptr, sizeof(T));
    return result;
}

template<typename T>
void PascalWrite(void* ptr, T result) noexcept
{
    std::memcpy(ptr, &result, sizeof(T));
}

template<typename T>
void PascalProcess(EIOS* eios, void* ptr, Stream &result) noexcept
{
    T* buffer = reinterpret_cast<T*>(&eios->local_storage);
    result.read<T>(*buffer);
    PascalWrite(ptr, buffer);
}

void PascalWrite(EIOS* eios, Stream &stream, void* ptr, ReflectionType type) noexcept
{
    switch (type)
    {
        case ReflectionType::CHAR:
        {
            PascalProcess<jchar>(eios, ptr, stream);
        }
            break;

        case ReflectionType::BYTE:
        {
            PascalProcess<jbyte>(eios, ptr, stream);
        }
            break;

        case ReflectionType::BOOL:
        {
            PascalProcess<jboolean>(eios, ptr, stream);
        }
            break;

        case ReflectionType::SHORT:
        {
            PascalProcess<jshort>(eios, ptr, stream);
        }
            break;

        case ReflectionType::INT:
        {
            PascalProcess<jint>(eios, ptr, stream);
        }
            break;

        case ReflectionType::LONG:
        {
            PascalProcess<jlong>(eios, ptr, stream);
        }
            break;

        case ReflectionType::FLOAT:
        {
            PascalProcess<jfloat>(eios, ptr, stream);
        }
            break;

        case ReflectionType::DOUBLE:
        {
            PascalProcess<jdouble>(eios, ptr, stream);
        }
            break;

        case ReflectionType::STRING:
        {
            std::string string = stream.read<std::string>();
            if (!string.empty())
            {
                char* output = AllocateString<char>(string.length());
                std::memcpy(output, &string[0], string.length());
                output[string.length()] = '\0';

                PascalWrite(ptr, output);
            }
        }
            break;

        case ReflectionType::OBJECT:
        {
            PascalWrite(ptr, stream.read<jobject>());
        }
            break;

        case ReflectionType::ARRAY:
        {
            PascalWrite(ptr, stream.read<jarray>());
        }
            break;

        default:
        {
            std::terminate();
        }
            break;
    }
}