#ifndef DEBUGCONSOLE_HXX_INCLUDED
#define DEBUGCONSOLE_HXX_INCLUDED

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#endif

#include <cstdio>

class DebugConsole final
{
private:
    FILE* input;
    FILE* error;
    FILE* output;
    bool allocated_console;

public:
    DebugConsole() noexcept;
    DebugConsole(DebugConsole&& other);
    DebugConsole(const DebugConsole& other) = delete;
    ~DebugConsole() noexcept;

    DebugConsole& operator = (const DebugConsole& other) = delete;
    DebugConsole& operator = (DebugConsole&& other);
};

#endif // DEBUGCONSOLE_HXX_INCLUDED
