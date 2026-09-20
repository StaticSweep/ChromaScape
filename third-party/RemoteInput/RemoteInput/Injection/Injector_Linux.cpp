//
// Created by Brandon on 2020-05-02.
//

#include "Injector.hxx"

#if defined(__linux__) && (defined(__x86_64__) || defined(__i386__))
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/reg.h>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/user.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <elf.h>
#include <sys/uio.h>
#include <sys/syscall.h>

#include <cstring>
#include <fstream>
#endif

//SYSCall calling conventions: man7.org/linux/man-pages/man2/syscall.2.html

#if defined(__linux__) && (defined(__x86_64__) || defined(__i386__))
auto find_library = [](std::int32_t pid, const char* library_name) -> std::uintptr_t {
    char file_map_name[256] = {0};
    snprintf(file_map_name, sizeof(file_map_name) - 1, "/proc/%d/maps", pid);

    std::fstream file = std::fstream(file_map_name, std::ios::in | std::ios::binary);
    if (file)
    {
        std::string line;
        while(std::getline(file, line))
        {
            if (line.find(library_name) != std::string::npos)
            {
                void* start_address = nullptr;
                void* end_address = nullptr;
                sscanf(line.c_str(), "%p-%p", &start_address, &end_address);
                return reinterpret_cast<std::intptr_t>(start_address);
            }
        }
    }
    return reinterpret_cast<std::uintptr_t>(nullptr);
};

auto find_symbol = [](std::int32_t pid, const char* library_name, void* local_symbol) -> std::uintptr_t {
    std::intptr_t remote_library_address = find_library(pid, library_name);
    std::intptr_t local_library_address = [local_symbol]() -> std::intptr_t {
        Dl_info info = {0};
        dladdr(local_symbol, &info);
        return reinterpret_cast<std::intptr_t>(info.dli_fbase);
    }();

    if (remote_library_address && local_library_address)
    {
        return reinterpret_cast<std::uintptr_t>(local_symbol) + (remote_library_address - local_library_address);
    }
    return reinterpret_cast<std::uintptr_t>(nullptr);
};

auto read_vm_memory = [](std::int32_t pid, void* dest, void* src, std::size_t size) {
    iovec local_vec = {0};
    local_vec.iov_base = dest;
    local_vec.iov_len = size;

    iovec remote_vec = {0};
    remote_vec.iov_base = src;
    remote_vec.iov_len = size;

    process_vm_readv(pid, &local_vec, 1, &remote_vec, 1, 0);
};

auto write_vm_memory = [](std::int32_t pid, void* dest, void* src, std::size_t size) {
    iovec local_vec = {0};
    local_vec.iov_base = src;
    local_vec.iov_len = size;

    iovec remote_vec = {0};
    remote_vec.iov_base = dest;
    remote_vec.iov_len = size;

    process_vm_writev(pid, &local_vec, 1, &remote_vec, 1, 0);
};

auto get_signal = [](int status, int expected, const char* message) -> int {
    if (WIFSTOPPED(status))
    {
        if (WSTOPSIG(status) == expected)
        {
            return 0;
        }

        if (WSTOPSIG(status) == 11)
        {
            printf("PROCESS DIED: %s\n", message);
            return -1;
        }

        printf("STOPPED FOR SOME OTHER SIGNAL: %s\n", message);
        return -1;
    }
    return 0;
};
#endif

#if defined(__linux__) && defined(__x86_64__)
auto remote_alloc = [](std::int32_t pid, std::size_t size) -> void* {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long rip = ptrace(PTRACE_PEEKDATA, pid, registers.rip, nullptr);

    registers.rdi = reinterpret_cast<std::uintptr_t>(nullptr); //address
    registers.rsi = size; //length
    registers.rdx = PROT_READ | PROT_WRITE | PROT_EXEC; //prot
    registers.r10 = MAP_PRIVATE | MAP_ANONYMOUS; //flags
    registers.r8 = -1; //fd
    registers.r9 = 0; //offset
    registers.rax = SYS_mmap; //__NR_mmap or filippo.io/linux-syscall-table/
    //registers.rip = sys_call_address;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.rip, 0x050F); //syscall instruction
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Alloc Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.rip, rip);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.rip, rip);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
    return reinterpret_cast<void*>(registers.rax);
};

auto remote_free = [](std::int32_t pid, void* memory, std::size_t size) -> int {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long rip = ptrace(PTRACE_PEEKDATA, pid, registers.rip, nullptr);

    registers.rdi = reinterpret_cast<std::uintptr_t>(memory); //address
    registers.rsi = size; //length
    registers.rdx = 0;
    registers.r10 = 0;
    registers.r8 = 0;
    registers.r9 = 0;
    registers.rax = SYS_munmap; //__NR_munmap or filippo.io/linux-syscall-table/
    //registers.rip = sys_call_address;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.rip, 0x050F); //syscall instruction
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Free Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.rip, rip);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return -1;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.rip, rip);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
    return static_cast<int>(registers.rax);
};

auto remote_dlopen = [](std::int32_t pid, std::uintptr_t dlopen_address, std::uintptr_t remote_path, int flags) -> void* {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long rip = ptrace(PTRACE_PEEKDATA, pid, registers.rip, nullptr);

    registers.rdi = remote_path;
    registers.rsi = flags;
    registers.rax = dlopen_address;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.rip, 0xCCD0FF); //call rax, int3  -- 0xFF, 0xD0, 0xCC
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.rip, rip);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        //ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.rip, rip);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
    //ptrace(PTRACE_CONT, pid, nullptr, nullptr);

    return reinterpret_cast<void*>(registers.rax);
};
#endif

#if defined(__linux__) && defined(__i386__)
auto remote_alloc = [](std::int32_t pid, std::size_t size) -> void* {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long eip = ptrace(PTRACE_PEEKDATA, pid, registers.eip, nullptr);

    registers.ebx = reinterpret_cast<std::uintptr_t>(nullptr); //address
    registers.ecx = size; //length
    registers.edx = PROT_READ | PROT_WRITE | PROT_EXEC; //prot
    registers.esi = MAP_PRIVATE | MAP_ANONYMOUS; //flags
    registers.edi = -1; //fd
    registers.ebp = 0; //offset
    registers.eax = SYS_mmap; //__NR_mmap or filippo.io/linux-syscall-table/
    //registers.eip = sys_call_address;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.eip, 0x80CD); //syscall instruction -- int $0x80 -- interrupt 80
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Alloc Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.eip, eip);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.eip, eip);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
    return reinterpret_cast<void*>(registers.eax);
};

auto remote_free = [](std::int32_t pid, void* memory, std::size_t size) -> int {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long eip = ptrace(PTRACE_PEEKDATA, pid, registers.eip, nullptr);

    registers.ebx = reinterpret_cast<std::intptr_t>(memory); //address
    registers.ebc = size; //length
    registers.edx = 0;
    registers.esi = 0;
    registers.edi = 0;
    registers.ebp = 0;
    registers.eax = SYS_munmap; //__NR_munmap or filippo.io/linux-syscall-table/
    //registers.eip = sys_call_address;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.eip, 0x80CD); //syscall instruction -- int $0x80 -- interrupt 80
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Free Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.eip, eip);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return -1;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.eip, eip);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
    return static_cast<int>(registers.eip);
};

auto remote_dlopen = [](std::int32_t pid, std::uintptr_t dlopen_address, std::uintptr_t remote_path, int flags) void* {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));


    std::uint64_t stack_size = 16 * 512;
    void* remote_stack = remote_alloc(pid, stack_size);
    remote_stack += (stack_size / 2);
    remote_stack -= 8;

    static std::uint8_t assembly[] = {
        0x6A, 0x00, 0x00, 0x00, 0x00,   //push 0x00000000  //flags
        0x68, 0xA7, 0xBE, 0xAD, 0xDE,   //push 0xDEADBEE7  //path
        0xFF, 0xD0,                     //call eax         //call dlopen
        0x83, 0xC4, 0x08                //add esp, 0x08    //cleanup cdecl stack
    };

    memcpy(&assembly[1], &flags, sizeof(flags));
    memcpy(&assembly[2], &remote_path, sizeof(remote_path));
    write_vm_memory(pid, remote_stack, &assembly[0], sizeof(assembly));

    registers.eax = dlopen_address;
    registers.eip = remote_stack;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.eip, 0xCCD0FF); //call eax, int3
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.eax, eax);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        //ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.eax, eax);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
    //ptrace(PTRACE_CONT, pid, nullptr, nullptr);

    remote_stack += 8;
    remote_stack -= (stack_size / 2);
    remote_free(pid, remote_stack, stack_size);

    return reinterpret_cast<void*>(registers.eax);
};
#endif

#if defined(__linux__) && (defined(__x86_64__) || defined(__i386__))
bool Injector::Inject(std::string module_path, std::int32_t pid, void* bootstrap) noexcept
{
    //Determine if the module is already injected
    if (find_library(pid, basename(module_path.c_str())))
    {
        return false;
    }

    //Get ASLR address of dlopen
    std::uintptr_t dlopen_address = find_symbol(pid, "libc.so", reinterpret_cast<void*>(dlopen));
    if (!dlopen_address)
    {
        dlopen_address = find_symbol(pid, "libdl.so", reinterpret_cast<void*>(dlopen));
    }
    
    if (!dlopen_address)
    {
        return false;
    }

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) < 0)
    {
        return false;
    }

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 19, "Injector Attach") < 0)
    {
        return false;
    }

    //Allocate path
    void* remote_path = remote_alloc(pid, module_path.length() + 1);
    if (remote_path)
    {
        //Write the remote path..
        write_vm_memory(pid, remote_path, &module_path[0], module_path.length() + 1);

        //Load the module
        void* result = remote_dlopen(pid, dlopen_address, reinterpret_cast<std::uintptr_t>(remote_path), RTLD_LAZY);

        //Free path
        remote_free(pid, remote_path, module_path.length() + 1);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);

        if (!result || result == reinterpret_cast<void*>(dlopen_address))
        {
            ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
            return false;
        }

        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return true;
    }

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
    return false;
}
#endif
