//
// Created by brandon on 04/11/24.
//

#ifndef INJECTOR_HXX
#define INJECTOR_HXX

#include <cstdint>
#include <string>

class Injector
{
private:
    std::int32_t pid;
    void* injector;
    void* handle;

public:
    Injector(std::int32_t pid);
    ~Injector();

    bool Inject(std::string module_path) noexcept;
    bool UnInject() noexcept;

    std::int32_t get_pid() const noexcept;
    bool is_injected() const noexcept;
};



#endif //INJECTOR_HXX
