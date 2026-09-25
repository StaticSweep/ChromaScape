//
//  RemoteInputBootstrap_Darwin.cpp
//  RemoteInputBootstrap
//
//  Created by Brandon on 2020-03-17.
//  Copyright © 2020 XIO. All rights reserved.
//

#if defined(MACH_INJECT)
#include <cstdio>
#include <cstring>

#include <mach/mach.h>
#include <mach/thread_act.h>
#include <mach/mach_init.h>
#include <pthread.h>
#include <dlfcn.h>
#include <signal.h>
#include <sys/proc_info.h>
#include <libproc.h>
#include <sys/types.h>
#include <mach/error.h>
#include <mach/vm_types.h>
#include <stddef.h>

#define EXPORT [[gnu::visibility("default")]]

#ifdef __cplusplus
extern "C" {
#endif

void _pthread_set_self(void*);
EXPORT void LoadLibrary(ptrdiff_t offset, void *param, size_t psize, void *dummy);

#ifdef __cplusplus
}
#endif

void LoadLibrary(ptrdiff_t offset, void* param, size_t psize, void* userp)
{
    _pthread_set_self(userp);

    int policy = 0;
    pthread_attr_t attr = {};
    pthread_t thread = nullptr;
    struct sched_param sched = {};

    pthread_attr_init(&attr);
    pthread_attr_getschedpolicy(&attr, &policy);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setinheritsched(&attr, PTHREAD_EXPLICIT_SCHED);

    sched.sched_priority = sched_get_priority_max(policy);
    pthread_attr_setschedparam(&attr, &sched);

    pthread_create(&thread, &attr, [](void* param) -> void* {
        void* lib = dlopen(static_cast<char*>(param), RTLD_NOW | RTLD_NOLOAD);
        if (lib)
        {
            dlclose(lib);
            return nullptr;
        }

        lib = dlopen(static_cast<char*>(param), RTLD_NOW);
        if (!lib)
        {
            fprintf(stderr, "Could not load module: %s\n", dlerror());
        }
        return nullptr;
    }, param);

    pthread_attr_destroy(&attr);
    thread_suspend(mach_thread_self());
}
#endif