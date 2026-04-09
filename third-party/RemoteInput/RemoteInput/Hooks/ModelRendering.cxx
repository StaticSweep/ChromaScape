//
//  ModelRendering.cpp
//  RemoteInput
//
//  Created by Brandon on 2019-12-08.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "ModelRendering.hxx"

#if defined(DEBUG)
#include "Hooks.hpp"
#include "ReflectionHook.hxx"
#include "ControlCenter.hxx"
#include <mutex>


template<typename T>
T ReadPointer(void* &ptr)
{
    T result = *static_cast<T*>(ptr);
    ptr = static_cast<T*>(ptr) + 1;
    return result;
}

std::string ReadPointer(void* &ptr)
{
    std::size_t length = *static_cast<std::size_t*>(ptr);
    ptr = static_cast<std::size_t*>(ptr) + 1;

    std::string result = std::string(reinterpret_cast<const char*>(ptr), length);
    ptr = static_cast<char*>(ptr) + (result.length() * sizeof(char));
    ptr = static_cast<char*>(ptr) + 1;
    return result;
}

template<typename T>
void WritePointer(void* &ptr, T result)
{
    *static_cast<T*>(ptr) = result;
    ptr = static_cast<T*>(ptr) + 1;
}

void WritePointer(void* &ptr, const std::string &result)
{
    *static_cast<std::size_t*>(ptr) = result.length();
    ptr = static_cast<std::size_t*>(ptr) + 1;

    memcpy(ptr, &result[0], result.length() * sizeof(char));
    ptr = static_cast<char*>(ptr) + (result.length() * sizeof(char));

    *static_cast<char*>(ptr) = '\0';
    ptr = static_cast<char*>(ptr) + 1;
}

class Reflector
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;

    Reflection* env;
    void process_reflect_array_index(Reflection* eios, jarray array, void* &arguments, void* &response, int dimensions);

public:
    Reflector::object make_safe(void* object);

    Reflector(Reflection* env);
    ~Reflector();

    Reflector(const Reflector &other) = delete;
    Reflector(Reflector &&other) = delete;
    Reflector& operator = (const Reflector &other) = delete;
    Reflector& operator = (Reflector &&other) = delete;

    Reflector::object GetObject(void* object, const Hook hook);
    void ReleaseObject(void* object);
    void ReleaseObjects(void** objects, std::size_t amount);
    bool GetBoolean(void* object, const Hook hook);
    char GetChar(void* object, const Hook hook);
    std::uint8_t GetByte(void* object, const Hook hook);
    std::int16_t GetShort(void* object, const Hook hook);
    std::int32_t GetInt(void* object, const Hook hook);
    std::int64_t GetLong(void* object, const Hook hook);
    float GetFloat(void* object, const Hook hook);
    double GetDouble(void* object, const Hook hook);
    std::string GetString(void* object, const Hook hook);
    Reflector::object GetArray(void* object, const Hook hook);
    std::int32_t GetArraySize(void* array);

    Reflector::object ArrayIndex(void* array, std::int32_t index);

    template<typename T>
    std::vector<T> GetArrayIndex(void* array, std::int32_t index, std::size_t length);

    template<typename T>
    std::vector<T> GetArrayIndex2D(void* array, std::size_t length, std::int32_t x, std::int32_t y);

    template<typename T>
    std::vector<T> GetArrayIndex3D(void* array, std::size_t length, std::int32_t x, std::int32_t y, std::int32_t z);

    template<typename T>
    std::vector<T> GetArrayIndex4D(void* array, std::size_t length, std::int32_t x, std::int32_t y, std::int32_t z, std::int32_t w);
};

Reflector* get_reflector(Reflection* reflection)
{
    for (std::size_t i = 0; i < 2048; ++i)
    {
        SINE[i] = static_cast<std::int32_t>(65536.0 * sin(static_cast<double>(i) * UNIT));
        COSINE[i] = static_cast<std::int32_t>(65536.0 * cos(static_cast<double>(i) * UNIT));
    }
    return new Reflector(reflection);
}

Reflector::Reflector(Reflection* env) : env(env)
{
}

Reflector::~Reflector()
{
}

Reflector::object Reflector::make_safe(void* object)
{
    auto deleter = [&](void* const ptr) {
        this->ReleaseObject(ptr);
    };

    return std::unique_ptr<void, decltype(deleter)>{static_cast<void*>(object), deleter};
}

Reflector::object Reflector::GetObject(void* object, const Hook hook)
{
    return make_safe(env->getField<jobject>(static_cast<jobject>(object), hook.hook()));
}

void Reflector::ReleaseObject(void* object)
{
    if (object)
    {
        env->getEnv()->DeleteGlobalRef(static_cast<jobject>(object));
    }
}

void Reflector::ReleaseObjects(void** objects, std::size_t amount)
{
    for (std::size_t i = 0; i < amount; ++i)
    {
        ReleaseObject(objects[i]);
    }
}

bool Reflector::GetBoolean(void* object, const Hook hook)
{
    return env->getPrimitive<jboolean>(static_cast<jobject>(object), hook.hook());
}

char Reflector::GetChar(void* object, const Hook hook)
{
    return env->getPrimitive<jchar>(static_cast<jobject>(object), hook.hook());
}

std::uint8_t Reflector::GetByte(void* object, const Hook hook)
{
    return env->getPrimitive<jbyte>(static_cast<jobject>(object), hook.hook());
}

std::int16_t Reflector::GetShort(void* object, const Hook hook)
{
    return env->getPrimitive<jshort>(static_cast<jobject>(object), hook.hook());
}

std::int32_t Reflector::GetInt(void* object, const Hook hook)
{
    return env->getPrimitive<jint>(static_cast<jobject>(object), hook.hook());
}

std::int64_t Reflector::GetLong(void* object, const Hook hook)
{
    return env->getPrimitive<jlong>(static_cast<jobject>(object), hook.hook());
}

float Reflector::GetFloat(void* object, const Hook hook)
{
    return env->getPrimitive<jfloat>(static_cast<jobject>(object), hook.hook());
}

double Reflector::GetDouble(void* object, const Hook hook)
{
    return env->getPrimitive<jdouble>(static_cast<jobject>(object), hook.hook());
}

std::string Reflector::GetString(void* object, const Hook hook)
{
    return env->getField<std::string>(static_cast<jobject>(object), hook.hook());
}

Reflector::object Reflector::GetArray(void* object, const Hook hook)
{
    return make_safe(env->getField<jobject>(static_cast<jobject>(object), hook.hook()));
}

std::int32_t Reflector::GetArraySize(void* array)
{
    return env->getEnv()->GetArrayLength(static_cast<jarray>(array));
}

Reflector::object Reflector::ArrayIndex(void* array, std::int32_t index)
{
    return make_safe(GetArrayIndex<jobject>(array, index, 1)[0]);
}

void Reflector::process_reflect_array_index(Reflection* eios, jarray array, void* &arguments, void* &response, int dimensions)
{
    auto write_result = [eios](jarray array, ReflectionArrayType type, jsize index, jsize length, void* &response) -> void {
        if (!array)
        {
            return WritePointer(response, nullptr);
        }

        //Maybe better to use GetPrimitiveArrayCritical + memcpy
        switch (type) {
            case ReflectionArrayType::CHAR:
            {
                eios->getEnv()->GetCharArrayRegion(static_cast<jcharArray>(array), index, length, static_cast<jchar*>(response));
            }
                break;

            case ReflectionArrayType::BYTE:
            {
                eios->getEnv()->GetByteArrayRegion(static_cast<jbyteArray>(array), index, length, static_cast<jbyte*>(response));
            }
                break;

            case ReflectionArrayType::BOOL:
            {
                eios->getEnv()->GetBooleanArrayRegion(static_cast<jbooleanArray>(array), index, length, static_cast<jboolean*>(response));
            }
                break;

            case ReflectionArrayType::SHORT:
            {
                eios->getEnv()->GetShortArrayRegion(static_cast<jshortArray>(array), index, length, static_cast<jshort*>(response));
            }
                break;

            case ReflectionArrayType::INT:
            {
                eios->getEnv()->GetIntArrayRegion(static_cast<jintArray>(array), index, length, static_cast<jint*>(response));
            }
                break;

            case ReflectionArrayType::LONG:
            {
                eios->getEnv()->GetLongArrayRegion(static_cast<jlongArray>(array), index, length, static_cast<jlong*>(response));
            }
                break;

            case ReflectionArrayType::FLOAT:
            {
                eios->getEnv()->GetFloatArrayRegion(static_cast<jfloatArray>(array), index, length, static_cast<jfloat*>(response));
            }
                break;

            case ReflectionArrayType::DOUBLE:
            {
                eios->getEnv()->GetDoubleArrayRegion(static_cast<jdoubleArray>(array), index, length, static_cast<jdouble*>(response));
            }
                break;

            case ReflectionArrayType::STRING:
            {
                auto get_string = [eios](jstring string){
                    if (string)
                    {
                        jsize length = eios->getEnv()->GetStringUTFLength(string);
                        const char* chars = eios->getEnv()->GetStringUTFChars(string, nullptr);
                        std::string result = std::string(chars, length);
                        eios->getEnv()->ReleaseStringUTFChars(string, chars);
                        return result;
                    }
                    return std::string();
                };

                if (length > 1)
                {
                    for (jsize i = 0; i < length; ++i)
                    {
                        auto element = eios->getEnv()->GetObjectArrayElement(static_cast<jobjectArray>(array), index + i);
                        std::string result = get_string(static_cast<jstring>(element));
                        WritePointer(response, result);
                        eios->getEnv()->DeleteLocalRef(element);
                    }
                }
                else
                {
                    auto element = eios->getEnv()->GetObjectArrayElement(static_cast<jobjectArray>(array), index);
                    std::string result = get_string(static_cast<jstring>(element));
                    WritePointer(response, result);
                    eios->getEnv()->DeleteLocalRef(element);
                }
            }
                break;

            case ReflectionArrayType::OBJECT:
            {
                if (length > 1)
                {
                    for (jsize i = 0; i < length; ++i)
                    {
                        auto result = eios->getEnv()->GetObjectArrayElement(static_cast<jobjectArray>(array), index + i);
                        WritePointer(response, result ? eios->getEnv()->NewGlobalRef(result) : nullptr);
                    }
                }
                else
                {
                    auto result = eios->getEnv()->GetObjectArrayElement(static_cast<jobjectArray>(array), index);
                    WritePointer(response, result ? eios->getEnv()->NewGlobalRef(result) : nullptr);
                }
            }
                break;
        }
    };

    if (dimensions == 1)
    {
        ReflectionArrayType type = ReadPointer<ReflectionArrayType>(arguments);
        jsize index = ReadPointer<jsize>(arguments);
        jsize length = ReadPointer<jsize>(arguments);
        write_result(array, type, index, length, response);
        return;
    }

    ReflectionArrayType type = ReadPointer<ReflectionArrayType>(arguments);
    for (int i = 0; i < dimensions - 1; ++i)
    {
        jsize index = ReadPointer<jsize>(arguments);

        if (array)
        {
            array = static_cast<jarray>(eios->getEnv()->GetObjectArrayElement(static_cast<jobjectArray>(array), index));
        }
    }

    jsize index = ReadPointer<jsize>(arguments);
    jsize length = ReadPointer<jsize>(arguments);
    write_result(array, type, index, length, response);
}

template<typename T>
ReflectionArrayType GetArrayType()
{
    ReflectionArrayType type = ReflectionArrayType::OBJECT;
    if (std::is_same<T, char>::value || std::is_same<T, jchar>::value)
    {
        type = ReflectionArrayType::CHAR;
    }

    if (std::is_same<T, std::uint8_t>::value || std::is_same<T, jbyte>::value)
    {
        type = ReflectionArrayType::BYTE;
    }

    if (std::is_same<T, bool>::value || std::is_same<T, jboolean>::value)
    {
        type = ReflectionArrayType::BOOL;
    }

    if (std::is_same<T, std::int16_t>::value || std::is_same<T, jshort>::value)
    {
        type = ReflectionArrayType::SHORT;
    }

    if (std::is_same<T, std::int32_t>::value || std::is_same<T, jint>::value)
    {
        type = ReflectionArrayType::INT;
    }

    if (std::is_same<T, std::int64_t>::value || std::is_same<T, jlong>::value)
    {
        type = ReflectionArrayType::LONG;
    }

    if (std::is_same<T, float>::value || std::is_same<T, jfloat>::value)
    {
        type = ReflectionArrayType::FLOAT;
    }

    if (std::is_same<T, double>::value || std::is_same<T, jdouble>::value)
    {
        type = ReflectionArrayType::DOUBLE;
    }

    if (std::is_same<T, void*>::value || std::is_same<T, jobject>::value)
    {
        type = ReflectionArrayType::OBJECT;
    }
    return type;
}

template<typename T>
std::vector<T> Reflector::GetArrayIndex(void* array, std::int32_t index, std::size_t length)
{
    std::vector<std::uint8_t> args(1024);
    void* arguments = &args[0];

    WritePointer(arguments, GetArrayType<T>());
    WritePointer(arguments, static_cast<jsize>(index));
    WritePointer(arguments, static_cast<jsize>(length));

    std::vector<T> buffer(length);
    arguments = &args[0];
    void* response = &buffer[0];
    process_reflect_array_index(env, static_cast<jarray>(array), arguments, response, 1);
    return buffer;
}

template<typename T>
std::vector<T> Reflector::GetArrayIndex2D(void* array, std::size_t length, std::int32_t x, std::int32_t y)
{
    std::vector<std::uint8_t> args(1024);
    void* arguments = &args[0];

    WritePointer(arguments, GetArrayType<T>());
    WritePointer(arguments, static_cast<jsize>(x));
    WritePointer(arguments, static_cast<jsize>(y));
    WritePointer(arguments, static_cast<jsize>(length));

    std::vector<T> buffer(length);
    arguments = &args[0];
    void* response = &buffer[0];
    process_reflect_array_index(env, static_cast<jarray>(array), arguments, response, 2);
    return buffer;
}

template<typename T>
std::vector<T> Reflector::GetArrayIndex3D(void* array, std::size_t length, std::int32_t x, std::int32_t y, std::int32_t z)
{
    std::vector<std::uint8_t> args(1024);
    void* arguments = &args[0];

    WritePointer(arguments, GetArrayType<T>());
    WritePointer(arguments, static_cast<jsize>(x));
    WritePointer(arguments, static_cast<jsize>(y));
    WritePointer(arguments, static_cast<jsize>(z));
    WritePointer(arguments, static_cast<jsize>(length));

    std::vector<T> buffer(length);
    arguments = &args[0];
    void* response = &buffer[0];
    process_reflect_array_index(env, static_cast<jarray>(array), arguments, response, 3);
    return buffer;
}

template<typename T>
std::vector<T> Reflector::GetArrayIndex4D(void* array, std::size_t length, std::int32_t x, std::int32_t y, std::int32_t z, std::int32_t w)
{
    std::vector<std::uint8_t> args(1024);
    void* arguments = &args[0];

    WritePointer(arguments, GetArrayType<T>());
    WritePointer(arguments, static_cast<jsize>(x));
    WritePointer(arguments, static_cast<jsize>(y));
    WritePointer(arguments, static_cast<jsize>(z));
    WritePointer(arguments, static_cast<jsize>(w));
    WritePointer(arguments, static_cast<jsize>(length));

    std::vector<T> buffer(length);
    arguments = &args[0];
    void* response = &buffer[0];
    process_reflect_array_index(env, static_cast<jarray>(array), arguments, response, 4);
    return buffer;
}




// MARK: - MODELS

Point Point::rotate(std::int32_t orientation)
{
    return Point(((x * COSINE[orientation]) + (z * SINE[orientation])) >> 16,
                 y,
                 ((z * COSINE[orientation]) - (x * SINE[orientation])) >> 16
    );
}

Polygon::Polygon(std::vector<std::int32_t> xpoints, std::vector<std::int32_t> ypoints, std::int32_t npoints)
{
    if (npoints > xpoints.size() || npoints > ypoints.size())
    {
        throw "npoints > xpoints.length || npoints > ypoints.length";
    }

    if (npoints < 0)
    {
        throw "npoints < 0";
    }

    this->npoints = npoints;
    this->xpoints = xpoints;
    this->ypoints = ypoints;
}

// MARK: - Client

std::uint8_t Client::getTileSettings(Reflector* eios, std::int32_t x, std::int32_t y, std::int32_t z)
{
    static std::once_flag flag;
    static std::vector<std::vector<std::vector<std::uint8_t>>> result;

    std::call_once(flag, [&]{
        auto settings = eios->GetArray(nullptr, HOOK_CLIENT_TILESETTINGS);
        std::size_t size = eios->GetArraySize(settings.get());
        result.reserve(size);

        auto jx = eios->GetArrayIndex<jobject>(settings.get(), 0, size);
        for (auto& x : jx)
        {
            std::vector<std::vector<std::uint8_t>> inner;
            auto jy = eios->GetArrayIndex<jobject>(x, 0, eios->GetArraySize(x));
            for (auto& y : jy)
            {
                inner.push_back(eios->GetArrayIndex<std::uint8_t>(y, 0, eios->GetArraySize(y)));
            }
            result.push_back(inner);
            eios->ReleaseObjects((void**)&jy[0], jy.size());
        }
        eios->ReleaseObjects((void**)&jx[0], jx.size());
    });
    return result[x][y][z];
}

std::int32_t Client::getTileHeights(Reflector* eios, std::int32_t x, std::int32_t y, std::int32_t z)
{
    static std::once_flag flag;
    static std::vector<std::vector<std::vector<std::int32_t>>> result;

    std::call_once(flag, [&]{
        auto settings = eios->GetArray(nullptr, HOOK_CLIENT_TILEHEIGHTS);
        std::size_t size = eios->GetArraySize(settings.get());
        result.reserve(size);

        auto jx = eios->GetArrayIndex<jobject>(settings.get(), 0, size);
        for (auto& x : jx)
        {
            std::vector<std::vector<std::int32_t>> inner;
            auto jy = eios->GetArrayIndex<jobject>(x, 0, eios->GetArraySize(x));
            for (auto& y : jy)
            {
                inner.push_back(eios->GetArrayIndex<std::int32_t>(y, 0, eios->GetArraySize(y)));
            }
            result.push_back(inner);
            eios->ReleaseObjects((void**)&jy[0], jy.size());
        }
        eios->ReleaseObjects((void**)&jx[0], jx.size());
    });
    return result[x][y][z];
}

std::int32_t Client::plane(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_PLANE) * static_cast<std::int32_t>(HOOK_CLIENT_PLANE.multiplier);
}

std::int32_t Client::viewPortWidth(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_VIEWPORTWIDTH) * static_cast<std::int32_t>(HOOK_CLIENT_VIEWPORTWIDTH.multiplier);
}

std::int32_t Client::viewPortHeight(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_VIEWPORTHEIGHT) * static_cast<std::int32_t>(HOOK_CLIENT_VIEWPORTHEIGHT.multiplier);
}

std::int32_t Client::viewPortScale(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_VIEWPORTSCALE) * static_cast<std::int32_t>(HOOK_CLIENT_VIEWPORTSCALE.multiplier);
}

// MARK: - Camera

std::int32_t Camera::getX(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_CAMERAX) * static_cast<std::int32_t>(HOOK_CLIENT_CAMERAX.multiplier);
}

std::int32_t Camera::getY(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_CAMERAY) * static_cast<std::int32_t>(HOOK_CLIENT_CAMERAY.multiplier);
}

std::int32_t Camera::getZ(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_CAMERAZ) * static_cast<std::int32_t>(HOOK_CLIENT_CAMERAZ.multiplier);
}

std::int32_t Camera::pitch(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_CAMERAPITCH) * static_cast<std::int32_t>(HOOK_CLIENT_CAMERAPITCH.multiplier);
}

std::int32_t Camera::yaw(Reflector* eios)
{
    return eios->GetInt(nullptr, HOOK_CLIENT_CAMERAYAW) * static_cast<std::int32_t>(HOOK_CLIENT_CAMERAYAW.multiplier);
}

// MARK: - Projector

std::int32_t Projector::getTileHeight(Reflector* eios, std::int32_t x, std::int32_t y, std::int32_t plane)
{
    std::int32_t xSize = x >> 7;
    std::int32_t ySize = y >> 7;

    if (xSize >= 0 && ySize >= 0 && xSize < 104 && ySize < 104)
    {
        if (plane < 3 && (Client::getTileSettings(eios, 1, xSize, ySize) & 2) == 2)
        {
            ++plane;
        }

        std::int32_t var1 = x & 127;
        std::int32_t var2 = y & 127;

        std::int32_t var3 = ((var1 * Client::getTileHeights(eios, plane, xSize + 1, ySize)) + ((128 - var1) * Client::getTileHeights(eios, plane, xSize, ySize))) >> 7;

        std::int32_t var4 = ((Client::getTileHeights(eios, plane, xSize, ySize + 1) * (128 - var1)) + (var1 * Client::getTileHeights(eios, plane, xSize + 1, ySize + 1))) >> 7;

        return (((128 - var2) * var3) + (var2 * var4)) >> 7;
    }

    return 0;
}

Point Projector::toScreen(Reflector* eios, Point p, std::int32_t localX, std::int32_t localY, std::int32_t plane)
{
    std::int32_t cameraX = Camera::getX(eios);
    std::int32_t cameraY = Camera::getY(eios);
    std::int32_t cameraZ = Camera::getZ(eios);
    std::int32_t cameraPitch = Camera::pitch(eios);
    std::int32_t cameraYaw = Camera::yaw(eios);
    std::int32_t clientPlane = Client::plane(eios);

    std::int32_t viewPortWidth = Client::viewPortWidth(eios);
    std::int32_t viewPortHeight = Client::viewPortHeight(eios);
    std::int32_t viewPortScale = Client::viewPortScale(eios);

    return Projector::toScreen(eios, p, localX, localY, plane, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, clientPlane, viewPortWidth, viewPortHeight, viewPortScale);
}

Point Projector::toScreen(Reflector* eios, Point p, std::int32_t localX, std::int32_t localY, std::int32_t plane,
                      std::int32_t cameraX, std::int32_t cameraY, std::int32_t cameraZ,
                      std::int32_t cameraPitch, std::int32_t cameraYaw,
                      std::int32_t clientPlane,
                      std::int32_t viewPortWidth, std::int32_t viewPortHeight,
                      std::int32_t viewPortScale)
{
    std::int32_t x = localX - p.x;
    std::int32_t y = localY - p.z;

    if (x >= 128 && y >= 128 && x <= 13056 && y <= 13056)
    {
        std::int32_t z = getTileHeight(eios, x, y, clientPlane) - plane;
        x -= cameraX;
        y -= cameraY;
        z -= cameraZ;

        int sinCurveY = SINE[cameraPitch];
        int cosCurveY = COSINE[cameraPitch];
        int sinCurveX = SINE[cameraYaw];
        int cosCurveX = COSINE[cameraYaw];
        int calculation = ((sinCurveX * y) + (cosCurveX * x)) >> 16;
        y = ((y * cosCurveX) - (x * sinCurveX)) >> 16;
        x = calculation;
        calculation = ((cosCurveY * z) - (sinCurveY * y)) >> 16;
        y = ((sinCurveY * z) + (cosCurveY * y)) >> 16;
        z = calculation;

        if (y >= 50)
        {
            int screenX = (x * viewPortScale / y) + (viewPortWidth / 2);
            int screenY = (z * viewPortScale / y) + (viewPortHeight / 2);

            if (screenX < 0 || screenX > viewPortWidth || screenY < 0 || screenY > viewPortHeight)
            {
                return Point(-1, -1, 0);
            }
            else
            {
                return Point(screenX, screenY, 0);
            }
        }
    }

    return Point(-1, -1, 0);
}

// MARK: - Model
Model::Model(Reflector* eios, object&& ref) : eios(eios), ref(std::move(ref))
{
    if (this->ref)
    {
        this->vertices = this->getVertices();
        this->triangles = this->getTriangles();
    }
}

Model::Model(Model&& other) : ref(std::move(other.ref)), eios(other.eios), vertices(std::move(other.vertices)), triangles(std::move(other.triangles))
{
}

Model& Model::operator = (Model&& other)
{
    ref = std::move(other.ref);
    eios = other.eios;
    vertices = std::move(other.vertices);
    triangles = std::move(other.triangles);
    return *this;
}

Model::operator bool() const
{
    return ref.get() != nullptr;
}

void Model::scale(std::int32_t x, std::int32_t y, std::int32_t z)
{
    for (std::size_t i = 0; i < this->vertices.size(); ++i)
    {
        Point p = this->vertices[i];
        int px = p.x * x / 128;
        int py = p.y * x / 128;
        int pz = p.z * x / 128;
        this->vertices[i] = Point(px, py, pz);
    }
}

std::vector<Point> Model::getVertices()
{
    std::vector<Point> result;
    auto verticesX = eios->GetArray(ref.get(), HOOK_MODEL_VERTICESX);
    auto verticesY = eios->GetArray(ref.get(), HOOK_MODEL_VERTICESY);
    auto verticesZ = eios->GetArray(ref.get(), HOOK_MODEL_VERTICESZ);

    std::size_t size = eios->GetArraySize(verticesX.get());
    result.resize(size);

    auto x = eios->GetArrayIndex<std::int32_t>(verticesX.get(), 0, size);
    auto y = eios->GetArrayIndex<std::int32_t>(verticesY.get(), 0, size);
    auto z = eios->GetArrayIndex<std::int32_t>(verticesZ.get(), 0, size);

    for (std::size_t i = 0; i < size; ++i)
    {
        result[i] = Point(x[i], y[i], z[i]);
    }
    return result;
}

std::vector<Triangle> Model::getTriangles()
{
    std::vector<Triangle> result;
    auto indicesX = eios->GetArray(ref.get(), HOOK_MODEL_INDICESX);
    auto indicesY = eios->GetArray(ref.get(), HOOK_MODEL_INDICESY);
    auto indicesZ = eios->GetArray(ref.get(), HOOK_MODEL_INDICESZ);

    std::int32_t size = eios->GetArraySize(indicesX.get());
    result.resize(size);

    auto x = eios->GetArrayIndex<std::int32_t>(indicesX.get(), 0, size);
    auto y = eios->GetArrayIndex<std::int32_t>(indicesY.get(), 0, size);
    auto z = eios->GetArrayIndex<std::int32_t>(indicesZ.get(), 0, size);

    for (std::int32_t i = 0; i < size; ++i)
    {
        Point a = this->vertices[x[i]];
        Point b = this->vertices[y[i]];
        Point c = this->vertices[z[i]];
        result[i] = Triangle(a, b, c);
    }
    return result;
}

std::vector<Triangle> Model::getRotatedTriangles(std::int32_t orientation)
{
    for (auto triangle : this->triangles)
    {
        triangle.a = triangle.a.rotate(orientation);
        triangle.b = triangle.b.rotate(orientation);
        triangle.c = triangle.c.rotate(orientation);
    }

    return triangles;
}

// MARK: - Node
Node::Node(Reflector* eios, object&& ref) : eios(eios), ref(std::move(ref)) {}
Node::Node(Node&& other) : ref(std::move(other.ref)), eios(other.eios) {}
Node& Node::operator = (Node&& other)
{
    ref = std::move(other.ref);
    eios = other.eios;
    return *this;
}

Node::operator bool() const
{
    return (bool)ref;
}

std::int64_t Node::uid()
{
    return eios->GetLong(ref.get(), HOOK_NODE_UID) * HOOK_NODE_UID.multiplier;
}

Node Node::next()
{
    auto node = eios->GetObject(ref.get(), HOOK_NODE_NEXT);
    return Node(eios, std::move(node));
}

// MARK: - HashTable

HashTable::HashTable(Reflector* eios, object&& ref) : eios(eios), ref(std::move(ref)) {}

HashTable::operator bool() const
{
    return (bool)ref;
}

std::int32_t HashTable::size()
{
    return eios->GetInt(ref.get(), HOOK_HASHTABLE_CAPACITY) * static_cast<std::int32_t>(HOOK_HASHTABLE_CAPACITY.multiplier);
}

std::vector<Node> HashTable::buckets()
{
    std::vector<Node> result;
    auto buckets = eios->GetArray(ref.get(), HOOK_HASHTABLE_CACHE_BUCKETS);
    std::int32_t size = eios->GetArraySize(buckets.get());
    result.resize(size);

    auto buckets_array = eios->GetArrayIndex<jobject>(buckets.get(), 0, size);

    for (std::int32_t i = 0; i < size; ++i)
    {
        result[i] = Node(eios, eios->make_safe(buckets_array[i]));
    }

    return result;
}

// MARK: - Cache

Cache::Cache(Reflector* eios, object&& ref) : eios(eios), ref(std::move(ref)) {}
Cache::Cache(Cache&& other) : ref(std::move(other.ref)), eios(other.eios) {}

Cache& Cache::operator = (Cache&& other)
{
    ref = std::move(other.ref);
    eios = other.eios;
    return *this;
}

Cache::operator bool() const
{
    return (bool)ref;
}

HashTable Cache::hash_table()
{
    auto hashtable = eios->GetObject(ref.get(), HOOK_CACHE_HASHTABLE);
    return HashTable(eios, std::move(hashtable));
}

// MARK: PlayerDefinition

PlayerDefinition::PlayerDefinition(Reflector* eios, object&& ref) : eios(eios), ref(std::move(ref)) {}
PlayerDefinition::PlayerDefinition(PlayerDefinition&& other) : ref(std::move(other.ref)), eios(other.eios) {}

PlayerDefinition& PlayerDefinition::operator = (PlayerDefinition&& other)
{
    ref = std::move(other.ref);
    eios = other.eios;
    return *this;
}

PlayerDefinition::operator bool() const
{
    return (bool)ref;
}

std::int32_t PlayerDefinition::id()
{
    return eios->GetInt(ref.get(), HOOK_PLAYERDEFINITION_ID) * static_cast<std::int32_t>(HOOK_PLAYERDEFINITION_ID.multiplier);
}

std::int64_t PlayerDefinition::model_id()
{
    return eios->GetLong(ref.get(), HOOK_PLAYERDEFINITION_MODELID) * HOOK_PLAYERDEFINITION_MODELID.multiplier;
}

std::int64_t PlayerDefinition::animated_model_id()
{
    return eios->GetLong(ref.get(), HOOK_PLAYERDEFINITION_ANIMATEDMODELID) * HOOK_PLAYERDEFINITION_ANIMATEDMODELID.multiplier;
}

bool PlayerDefinition::is_female()
{
    return eios->GetBoolean(ref.get(), HOOK_PLAYERDEFINITION_ISFEMALE);
}

Cache PlayerDefinition::model_cache()
{
    auto cache = eios->GetObject(nullptr, HOOK_PLAYERDEFINITION_MODELCACHE);
    return Cache(eios, std::move(cache));
}

// MARK: - Player

Player::Player(Reflector* eios, object&& ref) : eios(eios), ref(std::move(ref)) {}
Player::Player(Player&& other) : ref(std::move(other.ref)), eios(other.eios) {}

Player& Player::operator = (Player&& other)
{
    ref = std::move(other.ref);
    eios = other.eios;
    return *this;
}

Player::operator bool() const
{
    return (bool)ref;
}

Player Player::localPlayer(Reflector* eios)
{
    auto player = eios->GetObject(nullptr, HOOK_CLIENT_LOCALPLAYER);
    return Player(eios, std::move(player));
}

std::vector<Player> Player::getAllPlayers(Reflector* eios)
{
    std::vector<Player> result;
    auto players = eios->GetArray(nullptr, HOOK_CLIENT_LOCALPLAYERS);
    std::int32_t player_count = eios->GetArraySize(players.get());

    auto array = eios->GetArrayIndex<jobject>(players.get(), 0, player_count);

    for (std::int32_t i = 0; i < player_count; ++i)
    {
        if (array[i])
        {
            result.push_back(Player(eios, eios->make_safe(array[i])));
        }
    }
    return result;
}

std::string Player::name()
{
    auto name_info = eios->GetObject(ref.get(), HOOK_PLAYER_NAME);
    if (name_info)
    {
        auto name = eios->GetString(name_info.get(), HOOK_NAMEINFO_NAME);
        return name;
    }
    return std::string();
}

std::int32_t Player::orientation()
{
    return eios->GetInt(ref.get(), HOOK_ENTITY_ORIENTATION) * static_cast<std::int32_t>(HOOK_ENTITY_ORIENTATION.multiplier);
}

std::int32_t Player::getX()
{
    return eios->GetInt(ref.get(), HOOK_ENTITY_LOCALX) * static_cast<std::int32_t>(HOOK_ENTITY_LOCALX.multiplier);
}

std::int32_t Player::getY()
{
    return eios->GetInt(ref.get(), HOOK_ENTITY_LOCALY) * static_cast<std::int32_t>(HOOK_ENTITY_LOCALY.multiplier);
}

PlayerDefinition Player::definition()
{
    auto definition = eios->GetObject(ref.get(), HOOK_PLAYER_DEFINITION);
    return PlayerDefinition(eios, std::move(definition));
}

Model Player::model()
{
    PlayerDefinition def = this->definition();
    if (!def)
    {
        return Model(eios, nullptr);
    }

    std::int64_t modelId = def.animated_model_id();

    Cache cache = def.model_cache();
    if (!cache)
    {
        return Model(eios, nullptr);
    }

    HashTable hashTable = cache.hash_table();
    if (!hashTable)
    {
        return Model(eios, nullptr);
    }

    std::vector<Node> buckets = hashTable.buckets();
    std::vector<void*> objectsToRelease;
    objectsToRelease.reserve(buckets.size());

    if (!buckets.empty())
    {
        std::int64_t index = (modelId & (hashTable.size() - 1));
        Node& head = buckets[index];
        Node current = head.next();

        while (head.uid() != current.uid())
        {
            std::int64_t uid = current.uid();
            if (uid == modelId)
            {
                eios->ReleaseObjects(&objectsToRelease[0], objectsToRelease.size());
                return Model(eios, current.getRef());
            }

            if (uid == -1)
            {
                break;
            }

            Node next = current.next();
            objectsToRelease.push_back(current.release());
            current = std::move(next);
        }
    }

    eios->ReleaseObjects(&objectsToRelease[0], objectsToRelease.size());
    return Model(eios, nullptr);
}

void draw_player(std::function<void(Polygon* p, std::int32_t width, std::int32_t height)> renderer)
{
    extern std::unique_ptr<ControlCenter> control_center;
    Reflector* eios = nullptr; //get_reflector(control_center->get_reflector());

    static bool isAttached = false;
    if (!isAttached)
    {
        //control_center->get_reflector()->AttachAsDaemon();
    }

    static int amount = 1;
    static double time = 0.0f;
    auto start = std::chrono::high_resolution_clock::now();

    std::vector<Player> players;
    players.push_back(Player::localPlayer(eios));

    std::int32_t cameraX = Camera::getX(eios);
    std::int32_t cameraY = Camera::getY(eios);
    std::int32_t cameraZ = Camera::getZ(eios);
    std::int32_t cameraPitch = Camera::pitch(eios);
    std::int32_t cameraYaw = Camera::yaw(eios);
    std::int32_t clientPlane = Client::plane(eios);

    std::int32_t viewPortWidth = Client::viewPortWidth(eios);
    std::int32_t viewPortHeight = Client::viewPortHeight(eios);
    std::int32_t viewPortScale = Client::viewPortScale(eios);

    for (auto& player : players)
    {
        if (player && !player.name().empty())
        {
            std::int32_t width = control_center->get_width();
            std::int32_t height = control_center->get_height();

            std::int32_t playerOrientation = player.orientation();
            std::int32_t orientation = ((playerOrientation & 0x3FFF) + 1024) % 2048;


            Model model = player.model();
            auto triangles = model.getRotatedTriangles(orientation);

            int localX = player.getX();
            int localY = player.getY();

            std::vector<Polygon> polygons;
            for (std::size_t i = 0; i < triangles.size(); ++i)
            {
                Point vx = triangles[i].a;
                Point vy = triangles[i].b;
                Point vz = triangles[i].c;

                Point a = Projector::toScreen(eios, vx, localX, localY, -vx.y,
                                              cameraX, cameraY, cameraZ, cameraPitch,
                                              cameraYaw, clientPlane,
                                              viewPortWidth, viewPortHeight,
                                              viewPortScale);

                Point b = Projector::toScreen(eios, vy, localX, localY, -vy.y,
                                              cameraX, cameraY, cameraZ, cameraPitch,
                                              cameraYaw, clientPlane,
                                              viewPortWidth, viewPortHeight,
                                              viewPortScale);

                Point c = Projector::toScreen(eios, vz, localX, localY, -vz.y,
                                              cameraX, cameraY, cameraZ, cameraPitch,
                                              cameraYaw, clientPlane,
                                              viewPortWidth, viewPortHeight,
                                              viewPortScale);
                polygons.push_back(Polygon({a.x, b.x, c.x}, {a.y, b.y, c.y}, 3));
            }

            for (Polygon &p : polygons)
            {
                renderer(&p, width, height);
            }
        }

        auto end = std::chrono::high_resolution_clock::now();
        time += std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

        printf("TIME: %.2f\n", time / (double)amount);
        ++amount;
    }

    players.clear();
}



//void DrawLine(CGLContextObj ctx, jint x1, jint y1, jint x2, jint y2)
//{
//	CGLContextObj CGL_MACRO_CONTEXT = ctx;
//
//    glBegin(GL_LINES);
//    if (y1 == y2) {
//        GLfloat fx1 = (GLfloat)x1;
//        GLfloat fx2 = (GLfloat)x2;
//        GLfloat fy  = ((GLfloat)y1) + 0.2f;
//
//        if (x1 > x2) {
//            GLfloat t = fx1; fx1 = fx2; fx2 = t;
//        }
//
//        glVertex2f(fx1+0.2f, fy);
//        glVertex2f(fx2+1.2f, fy);
//    } else if (x1 == x2) {
//        GLfloat fx  = ((GLfloat)x1) + 0.2f;
//        GLfloat fy1 = (GLfloat)y1;
//        GLfloat fy2 = (GLfloat)y2;
//
//        if (y1 > y2) {
//            GLfloat t = fy1; fy1 = fy2; fy2 = t;
//        }
//
//        glVertex2f(fx, fy1+0.2f);
//        glVertex2f(fx, fy2+1.2f);
//    } else {
//        GLfloat fx1 = (GLfloat)x1;
//        GLfloat fy1 = (GLfloat)y1;
//        GLfloat fx2 = (GLfloat)x2;
//        GLfloat fy2 = (GLfloat)y2;
//
//        if (x1 < x2) {
//            fx1 += 0.2f;
//            fx2 += 1.0f;
//        } else {
//            fx1 += 0.8f;
//            fx2 -= 0.2f;
//        }
//
//        if (y1 < y2) {
//            fy1 += 0.2f;
//            fy2 += 1.0f;
//        } else {
//            fy1 += 0.8f;
//            fy2 -= 0.2f;
//        }
//
//        glVertex2f(fx1, fy1);
//        glVertex2f(fx2, fy2);
//    }
//	glEnd();
//}
//
//void DrawPoly(CGLContextObj ctx,
//			  jint nPoints, jint isClosed,
//			  jint transX, jint transY,
//			  jint *xPoints, jint *yPoints)
//{
//	CGLContextObj CGL_MACRO_CONTEXT = ctx;
//    jboolean isEmpty = JNI_TRUE;
//    jint mx, my;
//    jint i;
//
//    if (xPoints == NULL || yPoints == NULL)
//	{
//        return;
//    }
//
//    mx = xPoints[0];
//    my = yPoints[0];
//
//	glBegin(GL_LINE_STRIP);
//    for (i = 0; i < nPoints; i++) {
//        jint x = xPoints[i];
//        jint y = yPoints[i];
//
//        isEmpty = isEmpty && (x == mx && y == my);
//        glVertex2f((GLfloat)(x + transX) + 0.5f,
//				   (GLfloat)(y + transY) + 0.5f);
//    }
//
//    if (isClosed && !isEmpty &&
//        (xPoints[nPoints-1] != mx ||
//         yPoints[nPoints-1] != my))
//    {
//        glVertex2f((GLfloat)(mx + transX) + 0.5f,
//				   (GLfloat)(my + transY) + 0.5f);
//		glEnd();
//    } else if (!isClosed || isEmpty) {
//		glBegin(GL_LINES);
//        mx = xPoints[nPoints-1] + transX;
//        my = yPoints[nPoints-1] + transY;
//        glVertex2i(mx, my);
//        glVertex2i(mx+1, my+1);
//    } else {
//		glEnd();
//    }
//}
//
//void DrawPolyLines(CGLContextObj ctx, std::int32_t npoints, std::int32_t* xpoints, std::int32_t* ypoints)
//{
//	std::int32_t size = npoints - 2;
//	for (int i = 0; i <= size; ++i)
//	{
//		DrawLine(ctx, xpoints[i], ypoints[i], xpoints[i + 1], ypoints[i + 1]);
//	}
//
//	DrawLine(ctx, xpoints[size + 1], ypoints[size + 1], xpoints[0], ypoints[0]);
//}
//
//
//
//
//void DrawLine2(int x, int y, int x2, int y2, std::function<void(int x, int y)> plot)
//{
//	bool yLonger=false;
//	int incrementVal;
//	int shortLen=y2-y;
//	int longLen=x2-x;
//
//	if (abs(shortLen)>abs(longLen))
//	{
//		int swap=shortLen;
//		shortLen=longLen;
//		longLen=swap;
//		yLonger=true;
//	}
//
//	if (longLen<0) incrementVal=-1;
//	else incrementVal=1;
//
//	double multDiff;
//	if (longLen==0.0) multDiff=(double)shortLen;
//	else multDiff=(double)shortLen/(double)longLen;
//	if (yLonger)
//	{
//		for (int i=0;i!=longLen;i+=incrementVal)
//		{
//			plot(x+(int)((double)i*multDiff),y+i);
//		}
//	}
//	else
//	{
//		for (int i=0;i!=longLen;i+=incrementVal)
//		{
//			plot(x+i,y+(int)((double)i*multDiff));
//		}
//	}
//}
//
//void DrawPolyLines2(std::int32_t npoints, std::int32_t* xpoints, std::int32_t* ypoints, std::function<void(int x, int y)> plot)
//{
//	std::int32_t size = npoints - 2;
//	for (int i = 0; i <= size; ++i)
//	{
//		DrawLine2(xpoints[i], ypoints[i], xpoints[i + 1], ypoints[i + 1], plot);
//	}
//
//	DrawLine2(xpoints[size + 1], ypoints[size + 1], xpoints[0], ypoints[0], plot);
//}
#endif