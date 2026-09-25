#include "DebugConsole.hxx"
#include <cstdio>

DebugConsole::DebugConsole() noexcept : input(nullptr), error(nullptr), output(nullptr), allocated_console(false)
{
    #if defined(_WIN32) || defined(_WIN64)
    allocated_console = AllocConsole();
    #if defined(__STDC_LIB_EXT1__) && __STDC_WANT_LIB_EXT1__ == 1
    freopen_s(&input, "CONIN$", "r", stdin);
    freopen_s(&error, "CONOUT$", "w", stderr);
    freopen_s(&output, "CONOUT$", "w", stdout);
    #else
    input = std::freopen("CONIN$", "r", stdin);
    error = std::freopen("CONOUT$", "w", stderr);
    output = std::freopen("CONOUT$", "w", stdout);
    #endif
    #else

    #endif
}

DebugConsole::DebugConsole(DebugConsole&& other) : input(other.input), error(other.error), output(other.output), allocated_console(other.allocated_console)
{
    other.input = nullptr;
    other.error = nullptr;
    other.output = nullptr;
    other.allocated_console = false;
}

DebugConsole::~DebugConsole() noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    if (allocated_console)
    {
        FreeConsole();
    }
    #endif

    std::fclose(input);
    std::fclose(error);
    std::fclose(output);
}

DebugConsole& DebugConsole::operator=(DebugConsole &&other)
{
    std::fclose(input);
    std::fclose(error);
    std::fclose(output);
    input = other.input;
    error = other.error;
    output = other.output;
    allocated_console = other.allocated_console;
    other.input = nullptr;
    other.error = nullptr;
    other.output = nullptr;
    other.allocated_console = false;
    return *this;
}