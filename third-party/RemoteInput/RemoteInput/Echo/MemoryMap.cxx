//
// Created by Brandon on 2023-12-27.
//

#include "MemoryMap.hxx"

inline MemoryMap::open_mode operator & (MemoryMap::open_mode a, MemoryMap::open_mode b)
{
    return static_cast<MemoryMap::open_mode>(
            static_cast<typename std::underlying_type<MemoryMap::open_mode>::type>(a) &
            static_cast<typename std::underlying_type<MemoryMap::open_mode>::type>(b));
}

inline bool operator ! (MemoryMap::open_mode mode)
{
    return !static_cast<typename std::underlying_type<MemoryMap::open_mode>::type>(mode);
}

#if defined(_WIN32) || defined(_WIN64)
MemoryMap::MemoryMap(std::string_view path, std::size_t size, destroy_mode destroy_option) noexcept : hFile(nullptr), hMap(nullptr), physical(false), path(path), pData(nullptr), pSize(size), mode(open_mode::read | open_mode::write | open_mode::create), destruct_mode(destroy_option) {}
#else
MemoryMap::MemoryMap(std::string_view path, std::size_t size, destroy_mode destroy_option) noexcept : hFile(-1), physical(false), path(path), pData(MAP_FAILED), pSize(size), mode(open_mode::read | open_mode::write | open_mode::create), destruct_mode(destroy_option) {}
#endif

MemoryMap::~MemoryMap() noexcept
{
    switch (destruct_mode)
    {
        case destroy_mode::none: break;
        case destroy_mode::unmap: unmap(); break;
        case destroy_mode::close: close(); break;
        case destroy_mode::unmap_and_close: unmap(); close(); break;
        default: unmap(); close(); break;
    }
}

bool MemoryMap::open(open_mode mode) noexcept
{
    if (is_open() || is_mapped())
    {
        return true;
    }

    this->mode = mode;
    this->physical = false;
    bool read_only = !(mode & open_mode::write);
    bool open_only = !(mode & open_mode::create);

    #if defined(_WIN32) || defined(_WIN64)
    DWORD dwAccess = read_only ? PAGE_READONLY : PAGE_READWRITE;

    if (open_only)
    {
        hMap = OpenFileMappingA(FILE_MAP_ALL_ACCESS, false, path.c_str());
        return hMap != nullptr;
    }

    hMap = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, dwAccess, 0, pSize, path.c_str());
    return hMap != nullptr;
    #else
    bool created = false;
    int dwFlags = read_only ? O_RDONLY : O_RDWR;

    if (open_only)
    {
        hFile = shm_open(path.c_str(), dwFlags, S_IRWXU | S_IRWXO);
    }
    else
    {
        created = true;
        hFile = shm_open(path.c_str(), dwFlags | O_CREAT | O_EXCL, S_IRWXU | S_IRWXO);
        if (hFile == -1 && errno == EEXIST)
        {
            created = false;
            hFile = shm_open(path.c_str(), dwFlags, S_IRWXU | S_IRWXO);
        }
    }

    if (hFile != -1)
    {
        if (pSize == 0)
        {
            struct stat info = {0};
            if (fstat(hFile, &info) != -1)
            {
                pSize = info.st_size;
            }
        }

        if (!read_only && created)
        {
            if (ftruncate(hFile, pSize) != -1)
            {
                struct stat info = {0};
                return fstat(hFile, &info) != -1 && info.st_size >= static_cast<std::int64_t>(pSize);
            }
            return false;
        }
        return true;
    }
    return false;
    #endif
}

bool MemoryMap::open_file(open_mode mode) noexcept
{
    this->mode = mode;
    this->physical = true;
    bool read_only = !(mode & open_mode::write);
    bool open_only = !(mode & open_mode::create);

    #if defined(_WIN32) || defined(_WIN64)
    DWORD dwAccess = read_only ? GENERIC_READ : (GENERIC_READ | GENERIC_WRITE);
    DWORD dwCreation = open_only ? OPEN_EXISTING : OPEN_ALWAYS;
    DWORD dwAttributes = FILE_ATTRIBUTE_TEMPORARY & (read_only ? FILE_ATTRIBUTE_READONLY : 0);

    hFile = CreateFileA(path.c_str(), dwAccess, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, dwCreation, dwAttributes, nullptr);

    if (hFile != INVALID_HANDLE_VALUE)
    {
        std::uint32_t high = (static_cast<std::uint64_t>(pSize) >> 32);
        std::uint32_t low = (static_cast<std::uint64_t>(pSize) & 0xFFFFFFFF);
        if ((SetFilePointer(hFile, static_cast<long>(low), reinterpret_cast<long*>(&high), FILE_BEGIN) != INVALID_SET_FILE_POINTER))
        {
            if (!read_only && SetEndOfFile(hFile))
            {
                low = GetFileSize(hFile, reinterpret_cast<LPDWORD>(&high));
                if (low != INVALID_FILE_SIZE && ((static_cast<std::uint64_t>(high) << 32) | low) == pSize)
                {
                    DWORD dwAccess = read_only ? PAGE_READONLY : PAGE_READWRITE;

                    if (open_only)
                    {
                        hMap = OpenFileMappingA(FILE_MAP_ALL_ACCESS, false, path.c_str());
                        return hMap != nullptr;
                    }

                    hMap = CreateFileMappingA(hFile, nullptr, dwAccess, 0, pSize, path.c_str());
                    return hMap != nullptr;
                }
                return false;
            }

            low = GetFileSize(hFile, reinterpret_cast<LPDWORD>(&high));
            pSize = low != INVALID_FILE_SIZE ? (static_cast<std::uint64_t>(high) << 32) | low : 0;
            if (low != INVALID_FILE_SIZE)
            {
                DWORD dwAccess = read_only ? PAGE_READONLY : PAGE_READWRITE;

                if (open_only)
                {
                    hMap = OpenFileMappingA(FILE_MAP_ALL_ACCESS, false, path.c_str());
                    return hMap != nullptr;
                }

                hMap = CreateFileMappingA(hFile, nullptr, dwAccess, 0, pSize, path.c_str());
                return hMap != nullptr;
            }
        }
    }
    return false;
    #else
    int dwFlags = read_only ? O_RDONLY : O_RDWR;

    if (open_only)
    {
        hFile = ::open(path.c_str(), dwFlags, S_IRWXU | S_IRWXO);
    }
    else
    {
        hFile = ::open(path.c_str(), dwFlags | O_CREAT | O_EXCL | O_TRUNC, S_IRWXU | S_IRWXO);
        if (hFile == -1 && errno == EEXIST)
        {
            hFile = ::open(path.c_str(), dwFlags, S_IRWXU | S_IRWXO);
        }
    }

    if (hFile != -1)
    {
        if (!read_only && ftruncate(hFile, pSize) != -1)
        {
            struct stat info = {0};
            return fstat(hFile, &info) != -1 && info.st_size >= static_cast<std::int64_t>(pSize);
        }

        pSize = 0;
        struct stat info = {0};
        if (fstat(hFile, &info) != -1)
        {
            pSize = info.st_size;
            return true;
        }
    }
    return false;
    #endif
}

bool MemoryMap::map() noexcept
{
    if (!is_open())
    {
        return false;
    }

    if (is_mapped())
    {
        return true;
    }

    bool read_only = !(mode & open_mode::write);
    #if defined(_WIN32) || defined(_WIN64)
    if (pData)
    {
        return true;
    }

    DWORD dwAccess = read_only ? FILE_MAP_READ : FILE_MAP_READ | FILE_MAP_WRITE;
    pData = MapViewOfFile(hMap, dwAccess, 0, 0, pSize);
    return pData != nullptr;
    #else
    if (pData != MAP_FAILED)
    {
        return true;
    }

    int dwAccess = read_only ? PROT_READ : (PROT_READ | PROT_WRITE);
    pData = mmap(nullptr, pSize, dwAccess, MAP_SHARED, hFile, 0);
    return pData != MAP_FAILED && pData != nullptr;
    #endif
}

bool MemoryMap::unmap() noexcept
{
    bool result = true;
    if (is_mapped())
    {
        #if defined(_WIN32) || defined(_WIN64)
        result = UnmapViewOfFile(pData);
        pData = nullptr;
        #else
        result = !munmap(pData, pSize);
        pData = nullptr;
        #endif
    }
    return result;
}

bool MemoryMap::close() noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    bool result = CloseHandle(hMap);
    hMap = nullptr;

    if (hFile != INVALID_HANDLE_VALUE)
    {
        result = CloseHandle(hFile) && result;
        hFile = INVALID_HANDLE_VALUE;
    }
    return result;
    #else
    bool result = false;
    if (physical)
    {
        result = ::close(hFile) != -1 && result;
        hFile = -1;
    }
    else
    {
        result = !shm_unlink(path.c_str()) && result;
    }
    return result;
    #endif
}

bool MemoryMap::is_open() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    return ((hMap != nullptr) && (hMap != INVALID_HANDLE_VALUE)) || ((hFile != nullptr) && (hFile != INVALID_HANDLE_VALUE));
    #else
    return hFile != -1;
    #endif
}

bool MemoryMap::is_mapped() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    return pData != nullptr;
    #else
    return pData != MAP_FAILED && pData != nullptr;
    #endif
}

std::size_t MemoryMap::size() const noexcept
{
    return pSize;
}

void* MemoryMap::data() const noexcept
{
    return pData;
}

void MemoryMap::flush() const noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    FlushViewOfFile(pData, pSize);
    #else
    msync(pData, pSize, MS_SYNC);
    #endif
}

std::size_t MemoryMap::granularity() noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    SYSTEM_INFO info = {0};
    GetSystemInfo(&info);
    return info.dwAllocationGranularity;
    #else
    return sysconf(_SC_PAGESIZE);
    #endif
}