//
//  MemoryMap.hxx
//  Echo
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef MEMORYMAP_HXX_INCLUDED
#define MEMORYMAP_HXX_INCLUDED

#if defined(_WIN32) || defined(_WIN64)
#if !defined(WIN32_LEAN_AND_MEAN)
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>
#else
#include <sys/types.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#endif

#include <cstdint>
#include <string>
#include <string_view>

class MemoryMap
{
public:
    /********************************************//**
     * @brief A set of flags that determine how the file is memory
     *
     * @param read open_mode - Determine if the memory map should be opened for reading
     * @param write open_mode - Determine if the memory map should be opened for writing
     * @param create open_mode - Determine if the memory map should be created if it doesn't already exist
     *
     ***********************************************/
    enum class open_mode: std::uint32_t
    {
        read = 0x01,
        write = 0x02,
        create = 0x04
    };

    /********************************************//**
     * @brief A set of flags that determine how the file is closed or unmapped on destruction of this class
     *
     * @param none destroy_mode - Leave the memory map open and mapped
     * @param unmap destroy_mode - Unmaps the memory, but leaves the file open
     * @param close destroy_mode - Closes the file, but leaves the memory mapped
     * @param unmap_and_close destroy_mode - Unmaps the memory, and closes the file
     *
     ***********************************************/
    enum class destroy_mode: std::uint32_t
    {
        none = 0x00,
        unmap = 0x01,
        close = 0x02,
        unmap_and_close = 0x04
    };

    explicit MemoryMap(std::string_view path, std::size_t size, destroy_mode destroy_option = destroy_mode::unmap_and_close) noexcept;
    ~MemoryMap() noexcept;

    bool open(open_mode mode = open_mode::read | open_mode::write | open_mode::create) noexcept;
    bool open_file(open_mode mode = open_mode::read | open_mode::write | open_mode::create) noexcept;

    bool map() noexcept;
    bool unmap() noexcept;

    bool close() noexcept;

    bool is_open() const noexcept;
    bool is_mapped() const noexcept;

    std::size_t size() const noexcept;
    void* data() const noexcept;
    void flush() const noexcept;

    static std::size_t granularity() noexcept;

    friend inline open_mode operator | (open_mode a, open_mode b)
    {
        return static_cast<open_mode>(
                static_cast<typename std::underlying_type<open_mode>::type>(a) |
                static_cast<typename std::underlying_type<open_mode>::type>(b));
    }

private:
    #if defined(_WIN32) || defined(_WIN64)
    HANDLE hFile;
    HANDLE hMap;
    #else
    int hFile;
    #endif

    bool physical;
    std::string path;
    void* pData;
    std::size_t pSize;
    open_mode mode;
    destroy_mode destruct_mode;
};

#endif /* MEMORYMAP_HXX_INCLUDED */
