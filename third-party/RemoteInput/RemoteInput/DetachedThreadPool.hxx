#ifndef DETACHEDTHREADPOOL_HXX_INCLUDED
#define DETACHEDTHREADPOOL_HXX_INCLUDED

#include <thread>
#include <future>
#include <mutex>
#include <atomic>
#include <queue>
#include <functional>
#include <condition_variable>
#include <stdexcept>
#include <vector>

class DetachedThreadPool final
{
private:
    std::shared_ptr<std::mutex> mutex;
    std::shared_ptr<std::condition_variable> condition;
    std::shared_ptr<std::queue<std::function<void(std::atomic_bool& stopped)>>> tasks;
    std::vector<std::thread> threads;
    std::shared_ptr<std::atomic_bool> stop;
    std::size_t max_threads;
    void create_threads() noexcept;

public:
    DetachedThreadPool() noexcept;
    DetachedThreadPool(std::size_t max_threads) noexcept;
    DetachedThreadPool(const DetachedThreadPool&) = delete;
    DetachedThreadPool(DetachedThreadPool&& other) noexcept;
    ~DetachedThreadPool() noexcept;


    DetachedThreadPool& operator = (const DetachedThreadPool&) = delete;
    DetachedThreadPool& operator = (DetachedThreadPool&&) = delete;

    void terminate() noexcept;

    void add_task(std::function<void(std::atomic_bool&)> &&task);

    template<typename Task, typename... Args>
    auto enqueue(Task &&task, Args&&... args) noexcept -> std::future<std::invoke_result_t<Task, Args...>>;
};

template<typename Task, typename... Args>
auto DetachedThreadPool::enqueue(Task &&task, Args&&... args) noexcept -> std::future<std::invoke_result_t<Task, Args...>>
{
    auto packaged_task = std::make_shared<std::packaged_task<std::invoke_result_t<Task, Args...>(Args&&...)>>(
        std::bind(std::forward<Task>(task), std::forward<Args>(args)...)
    );

    std::future<std::invoke_result_t<Task, Args...>> result = packaged_task->get_future();
    std::unique_lock<std::mutex> lock(*mutex);
    if (*stop)
    {
        throw std::runtime_error("DetachedThreadPool is currently shutting down. Cannot enqueue more tasks.");
    }

    tasks->emplace([packaged_task](std::atomic_bool &stopped) {
        (*packaged_task)(stopped);
    }, std::ref(stop));

    condition->notify_one();
    return result;
}

#endif // DETACHEDTHREADPOOL_HXX_INCLUDED
