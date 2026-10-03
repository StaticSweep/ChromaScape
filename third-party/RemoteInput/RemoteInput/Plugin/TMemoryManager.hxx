#ifndef TMEMORYMANAGER_HXX_INCLUDED
#define TMEMORYMANAGER_HXX_INCLUDED

#if !defined(_MSC_VER)  && !defined(__APPLE__)
//#if __has_attribute(regparm)
#define PASCAL_CALLING_CONVENTION __attribute__((regparm(3)))
//#endif
#endif

#if defined(PASCAL_CALLING_CONVENTION)
typedef struct
{
    std::uint32_t TotalAddressSpace;
    std::uint32_t TotalUncommited;
    std::uint32_t TotalCommited;
    std::uint32_t TotalAllocated;
    std::uint32_t TotalFree;
    std::uint32_t FreeSmall;
    std::uint32_t FreeBig;
    std::uint32_t Unused;
    std::uint32_t Overhead;
    std::uint32_t HeapErrorCode;
} THeapStatus;

typedef struct
{
    std::uintptr_t MaxHeapSize;
    std::uintptr_t MaxHeapUsed;
    std::uintptr_t CurrHeapSize;
    std::uintptr_t CurrHeapUsed;
    std::uintptr_t CurrHeapFree;
} TFPCHeapStatus;

typedef struct
{
    bool NeedLock;
    void* (*GetMem)(std::intptr_t size) PASCAL_CALLING_CONVENTION;
    std::intptr_t (*FreeMem)(void* &ptr) PASCAL_CALLING_CONVENTION;
    std::intptr_t (*FreeMemSize)(void* &ptr, std::intptr_t size) PASCAL_CALLING_CONVENTION;
    void* (*AllocMem)(std::intptr_t size) PASCAL_CALLING_CONVENTION;
    void* (*ReAllocMem)(void* &ptr, std::intptr_t size) PASCAL_CALLING_CONVENTION;
    std::intptr_t (*MemSize)(void* ptr) PASCAL_CALLING_CONVENTION;

    void (*InitThread)() PASCAL_CALLING_CONVENTION;
    void (*DoneThread)() PASCAL_CALLING_CONVENTION;
    void (*RelocateHeap)() PASCAL_CALLING_CONVENTION;
    THeapStatus (*GetHeapStatus)() PASCAL_CALLING_CONVENTION;
    TFPCHeapStatus (*GetFPCHeapStatus)() PASCAL_CALLING_CONVENTION;
} TMemoryManager;

typedef struct
{
    bool NeedLock;
    void* (*GetMem)(std::intptr_t size) PASCAL_CALLING_CONVENTION;
    std::intptr_t (*FreeMem)(void* &ptr) PASCAL_CALLING_CONVENTION;
    std::intptr_t (*FreeMemSize)(void* &ptr, std::intptr_t size) PASCAL_CALLING_CONVENTION;
    void* (*AllocMem)(std::intptr_t size) PASCAL_CALLING_CONVENTION;
    void* (*ReAllocMem)(void* &ptr, std::intptr_t size) PASCAL_CALLING_CONVENTION;
    std::intptr_t (*MemSize)(void* ptr) PASCAL_CALLING_CONVENTION;

    void (*InitThread)() PASCAL_CALLING_CONVENTION;
    void (*DoneThread)() PASCAL_CALLING_CONVENTION;
    void (*RelocateHeap)() PASCAL_CALLING_CONVENTION;
    THeapStatus (*GetHeapStatus)() PASCAL_CALLING_CONVENTION;
    TFPCHeapStatus (*GetFPCHeapStatus)() PASCAL_CALLING_CONVENTION;
} __attribute__((__packed__)) TCMemoryManager;
#endif

#if defined(_MSC_VER)
#define STRUCT_PACK( __Declaration__ ) __pragma( pack(push, 1) ) __Declaration__ __pragma( pack(pop))
#else
#define STRUCT_PACK( __Declaration__ ) __Declaration__ __attribute__((__packed__))
#endif

STRUCT_PACK(typedef struct
{
    void (*Sync)(void(*synchronize_method)(void*), void* data);
}) TSimbaMethods;

STRUCT_PACK(typedef struct
{
    void* (*GetMem)(std::size_t size);
    void (*FreeMem)(void* ptr);
}) TSimbaMemoryAllocators;

STRUCT_PACK(typedef struct
{
    std::int32_t SimbaMinor;
    std::int32_t SimbaMajor;
    const char* FileName;
    void* Compiler;
}) TSimbaInfomation;

STRUCT_PACK(typedef struct
{
    void (*RunOnMainThread)(void(*TMainThreadMethod)(void*), void* data);
    void* (*GetMem)(std::size_t size);
    void (*FreeMem)(void* ptr);
    void* (*AllocMem)(std::size_t size);
    void* (*ReAllocMem)(void** ptr, std::size_t size);
    std::size_t (*MemSize)(void* ptr);

    void(*RaiseException)(const char* message);

    void* (*GetTypeInfo)(void* Compiler, const char* Type);
    void* (*GetTypeInfoSize)(void* TypeInfo);
    std::size_t (*GetTypeInfoFieldOffset)(void* TypeInfo, const char* FieldName);

    void* (*AllocateRawArray)(std::size_t element_size, std::size_t length);
    void (*ReAllocateRawArray)(void** array, std::size_t element_size, std::size_t new_length);

    void* (*AllocateArray)(void* TypeInfo, std::size_t length);
    void* (*AllocateString)(const char* data);
    void* (*AllocateUnicodeString)(const wchar_t* data);
    
    void (*SetArrayLength)(void* TypeInfo, void** var, std::size_t new_len);
    std::size_t (*GetArrayLength)(void* array);

    void* (*ExternalImage_Create)(bool AutoResize);
    void (*ExternalImage_SetMemory)(void* img, void* bgra_data, std::int32_t width, std::int32_t height);
    void (*ExternalImage_Resize)(void* img, std::int32_t new_width, std::int32_t new_height);
    void (*ExternalImage_SetUserData)(void* img, void* userdata);
    void* (*ExternalImage_GetUserData)(void* img);
}) TSimbaMethodsExtended;

#endif // TMEMORYMANAGER_HXX_INCLUDED
