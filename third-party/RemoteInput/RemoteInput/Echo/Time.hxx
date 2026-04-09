//
//  Time.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef TIME_HXX_INCLUDED
#define TIME_HXX_INCLUDED

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#else
#include <sys/time.h>
#endif

#include <ctime>
#include <cstdint>


void timeval_to_timespec(struct timeval* tv, struct timespec* ts) noexcept;
void timespec_to_timeval(struct timespec* ts, struct timeval* tv) noexcept;
struct timespec add_timespec(struct timespec* a, struct timespec* b) noexcept;
struct timespec sub_timespec(struct timespec* a, struct timespec* b) noexcept;

struct timeval filetime_to_timeval(uint64_t filetime) noexcept;
uint64_t timeval_to_filetime(struct timeval* tp) noexcept;

uint64_t get_file_time() noexcept;
uint64_t get_adjusted_file_time() noexcept;
inline uint64_t time_since_epoch(uint64_t time) noexcept {return time - 116444736000000000ULL;}

#endif // TIME_HXX_INCLUDED
