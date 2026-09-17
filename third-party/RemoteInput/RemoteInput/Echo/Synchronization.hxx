//
// Created by Brandon on 2023-12-30.
//

#ifndef REMOTEINPUT_SYNCHRONIZATION_HXX
#define REMOTEINPUT_SYNCHRONIZATION_HXX

#include <chrono>
#include <memory>
#include <new>

#include "MemoryMap.hxx"

namespace synchronization
{
    // MARK: - Locks
    template<typename T>
    concept lockable = requires(T t)
    {
        t.lock();
        t.unlock();
    };

    template<typename T>
    concept try_lockable = lockable<T> && requires(T t)
    {
        t.try_lock();
    };

    template<typename T>
    concept timed_lockable = try_lockable<T> && requires(T t)
    {
        t.try_lock_for(std::chrono::milliseconds(0));
        t.try_lock_until(std::chrono::high_resolution_clock::now());
    };

    // MARK: - Semaphores
    template<typename T>
    concept releasable = requires(T t)
    {
        t.release(1);
    };

    template<typename T>
    concept binary_releasable = requires(T t)
    {
        t.release();
    };

    template<typename T>
    concept acquirable = (releasable<T> || binary_releasable<T>) && requires(T t)
    {
        t.acquire();
    };

    template<typename T>
    concept try_acquirable = acquirable<T> && requires(T t)
    {
        t.try_acquire();
    };

    template<typename T>
    concept timed_acquirable = try_acquirable<T> && requires(T t)
    {
        t.try_acquire_for(std::chrono::milliseconds(0));
        t.try_acquire_until(std::chrono::high_resolution_clock::now());
    };
};

namespace synchronization
{
    template<typename T>
    class SyncPrimitive
    {
    private:
        T* primitive;
        std::int32_t* ref;
        std::unique_ptr<MemoryMap> mem_map;

    public:
        template<typename... Args>
        SyncPrimitive(std::string_view name, Args&&... args) requires lockable<T> || acquirable<T>
                : primitive(nullptr), ref(nullptr), mem_map(std::make_unique<MemoryMap>(name, sizeof(T) + sizeof(std::int32_t), MemoryMap::destroy_mode::unmap))
        {
            bool created = false;
            if (!mem_map->open(MemoryMap::open_mode::read | MemoryMap::open_mode::write))
            {
                if (!mem_map->open(MemoryMap::open_mode::read | MemoryMap::open_mode::write | MemoryMap::open_mode::create))
                {
                    perror(nullptr);
                    throw std::runtime_error("Cannot create or open memory map");
                }

                created = true;
            }

            if (!mem_map->map())
            {
                throw std::runtime_error("Cannot map memory");
            }

            T* primitive = static_cast<T*>(mem_map->data());
            std::int32_t* ref = reinterpret_cast<std::int32_t*>(primitive + 1);

            if (created)
            {
                std::construct_at(primitive, std::forward<Args>(args)...);
                std::construct_at(ref, 0);
            }

            *ref += 1;
            this->primitive = primitive;
            this->ref = ref;
        }

        ~SyncPrimitive()
        {
            if (primitive && mem_map->is_open() && mem_map->is_mapped())
            {
                *ref -= 1;
                if (*ref == 0)
                {
                    std::destroy_at(ref);
                    std::destroy_at(primitive);
                    mem_map->close();
                }
                mem_map->unmap();
            }

            primitive = nullptr;
            ref = nullptr;
        }

        SyncPrimitive(const SyncPrimitive &other) = delete;
        SyncPrimitive& operator = (const SyncPrimitive &other) = delete;

        // MARK: - Lock

        void lock() const noexcept requires lockable<T>
        {
            primitive->lock();
        }

        void unlock() const noexcept requires lockable<T>
        {
            primitive->unlock();
        }

        bool try_lock() const noexcept requires try_lockable<T>
        {
            return primitive->try_lock();
        }

        template<typename Rep, typename Period>
        bool try_lock_for(const std::chrono::duration<Rep, Period>& relative_time) const noexcept requires timed_lockable<T>
        {
            return primitive->try_lock_for(relative_time);
        }

        template<typename Duration>
        bool try_lock_until(const std::chrono::time_point<std::chrono::high_resolution_clock, Duration>& absolute_time) const noexcept requires timed_lockable<T>
        {
            return primitive->try_lock_until(absolute_time);
        }

        template<typename Clock, typename Duration>
        bool try_lock_until(const std::chrono::time_point<Clock, Duration>& absolute_time) const noexcept requires timed_lockable<T>
        {
            return primitive->try_lock_until(absolute_time);
        }

        // MARK: - Semaphore

        void acquire() const noexcept requires acquirable<T>
        {
            primitive->acquire();
        }

        void release() const noexcept requires acquirable<T>
        {
            primitive->release();
        }

        bool try_acquire() const noexcept requires try_acquirable<T>
        {
            return primitive->try_acquire();
        }

        template<typename Rep, typename Period>
        bool try_acquire_for(const std::chrono::duration<Rep, Period>& relative_time) const noexcept requires timed_acquirable<T>
        {
            return primitive->try_acquire_for(relative_time);
        }

        template<typename Duration>
        bool try_acquire_until(const std::chrono::time_point<std::chrono::high_resolution_clock, Duration>& absolute_time) const noexcept requires timed_acquirable<T>
        {
            return primitive->try_acquire_until(absolute_time);
        }

        template<typename Clock, typename Duration>
        bool try_acquire_until(const std::chrono::time_point<Clock, Duration>& absolute_time) const noexcept requires timed_acquirable<T>
        {
            return primitive->try_acquire_until(absolute_time);
        }
    };
}

#endif //REMOTEINPUT_SYNCHRONIZATION_HXX
