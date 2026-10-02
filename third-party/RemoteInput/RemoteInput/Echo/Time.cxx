//
//  Time.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "Time.hxx"
#if defined(_MSC_VER)
#include <windows.h>
#include <winsock.h>
#endif

void timeval_to_timespec(struct timeval* tv, struct timespec* ts) noexcept
{
    ts->tv_sec = tv->tv_sec;
    ts->tv_nsec = tv->tv_usec * 1000;
}

void timespec_to_timeval(struct timespec* ts, struct timeval* tv) noexcept
{
    tv->tv_sec = ts->tv_sec;
    tv->tv_usec = static_cast<int>(ts->tv_nsec / 1000);
}

struct timespec add_timespec(struct timespec* a, struct timespec* b) noexcept
{
    struct timespec result = {a->tv_sec + b->tv_sec, b->tv_nsec + b->tv_nsec};
    if(result.tv_nsec >= 1000000000)
    {
        result.tv_nsec -= 1000000000;
        ++result.tv_sec;
    }
    return result;
}

struct timespec sub_timespec(struct timespec* a, struct timespec* b) noexcept
{
    struct timespec result = {a->tv_sec - b->tv_sec, 0};
    if (b->tv_nsec > a->tv_nsec)
    {
        result.tv_nsec = a->tv_nsec + 1000000000 - b->tv_nsec;
        --result.tv_sec;
        return result;
    }
    result.tv_nsec = a->tv_nsec - b->tv_nsec;
    return result;
}

struct timeval filetime_to_timeval(uint64_t filetime) noexcept
{
    uint64_t time = filetime - 116444736000000000ULL;
    return {static_cast<long>(time / 10000000ULL), static_cast<int>(static_cast<long>(time % 10000000ULL) / 10)};
}

uint64_t timeval_to_filetime(struct timeval* tp) noexcept
{
    uint64_t time = tp->tv_sec * 10000000ULL + tp->tv_usec * 10;
    return time + 116444736000000000ULL;
}


uint64_t get_file_time() noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    static void (__stdcall *PreciseSystemFileTime)(FILETIME* file_time) = [] {
        void (__stdcall *PreciseSystemFileTime)(FILETIME* file_time) = nullptr;
        HMODULE kernel = GetModuleHandleW(L"Kernel32.dll");
        PreciseSystemFileTime = reinterpret_cast<void (__stdcall *)(FILETIME*)>(GetProcAddress(kernel, "GetSystemTimePreciseAsFileTime"));
        if (!PreciseSystemFileTime)
        {
            PreciseSystemFileTime = reinterpret_cast<void (__stdcall *)(FILETIME*)>(GetProcAddress(kernel, "GetSystemTimeAsFileTime"));
        }
        return PreciseSystemFileTime;
    }();

    FILETIME file_time;
    PreciseSystemFileTime(&file_time);
    return (static_cast<uint64_t>(file_time.dwHighDateTime) << 32) | static_cast<uint64_t>(file_time.dwLowDateTime);
    #elif __linux__
    struct timespec ts;
    #ifdef __FreeBSD__
    clock_gettime(CLOCK_REALTIME_PRECISE, &ts);
    #else
    clock_gettime(CLOCK_REALTIME, &ts);
    #endif
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000 + static_cast<uint64_t>(ts.tv_nsec);
    #else
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<uint64_t>(tv.tv_sec) * 1000000 + static_cast<uint64_t>(tv.tv_usec);
    #endif // defined
}


uint64_t get_adjusted_file_time() noexcept
{
    uint64_t time = get_file_time();
    static uint64_t timestamp = []{
        #if defined(_WIN32) || defined(_WIN64)
        LARGE_INTEGER li;
        QueryPerformanceCounter(&li);
        return static_cast<uint64_t>(li.QuadPart);
        #else
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<uint64_t>(ts.tv_sec) * 1000000000 + static_cast<uint64_t>(ts.tv_nsec);
        #endif
    }();

    static uint64_t frequency = []{
        #if defined(_WIN32) || defined(_WIN64)
        LARGE_INTEGER li;
        QueryPerformanceFrequency(&li);
        return static_cast<uint64_t>(li.QuadPart);
        #else
        struct timespec ts;
        clock_getres(CLOCK_MONOTONIC, &ts);
        return 1000000000 / static_cast<uint64_t>(ts.tv_nsec);
        #endif
    }();

    #if defined(_WIN32) || defined(_WIN64)
    LARGE_INTEGER li;
    QueryPerformanceCounter(&li);
    uint64_t delta = li.QuadPart - timestamp;
    #else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t delta = (static_cast<uint64_t>(ts.tv_sec) * 1000000000 + static_cast<uint64_t>(ts.tv_nsec)) - timestamp;
    #endif
    uint64_t delta_us = delta * 10000000 / frequency;
    return delta_us + time;
}
