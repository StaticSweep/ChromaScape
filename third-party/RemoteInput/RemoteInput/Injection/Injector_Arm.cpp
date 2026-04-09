//
// Created by Brandon on 2020-05-02.
//

#include "Injector.hxx"

#if !defined(__APPLE__)
#if defined(__aarch64__) || defined(__arm__)
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
//#include <sys/reg.h>
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

#if defined(__arm__)
#define CPSR_THUMB_MODE_BIT (1u << 5)
#endif

#if defined(__aarch64__) || defined(__arm__)
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
                return reinterpret_cast<std::uintptr_t>(start_address);
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

#if defined(__aarch64__)
auto remote_alloc = [](std::int32_t pid, std::size_t size) -> void* {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    iovec regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &regs_vec);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long pc = ptrace(PTRACE_PEEKDATA, pid, registers.pc, nullptr); //pc -- program counter

    //x0, x1, x2, x3, x4, x5
    registers.regs[0] = reinterpret_cast<std::uintptr_t>(nullptr); //addr
    registers.regs[1] = size; //length
    registers.regs[2] = PROT_READ | PROT_WRITE | PROT_EXEC; //prot
    registers.regs[3] = MAP_PRIVATE | MAP_ANONYMOUS; //flags
    registers.regs[4] = -1; //fd
    registers.regs[5] = 0; //offset

    //syscall x8
    registers.regs[8] = SYS_mmap;

    regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &regs_vec);
    ptrace(PTRACE_POKEDATA, pid, registers.pc, 0xD4000001); //syscall instruction -- svc #0
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Alloc Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.pc, pc);
        ptrace(PTRACE_SETREGSET, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &regs_vec);

    regs_vec = { .iov_base = &registers_backup, .iov_len = sizeof(registers_backup) };
    ptrace(PTRACE_POKEDATA, pid, registers_backup.pc, pc);
    ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &regs_vec);

    return reinterpret_cast<void*>(registers.regs[0]);
};

auto remote_free = [](std::int32_t pid, void* memory, std::size_t size) -> int {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    iovec regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &regs_vec);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long pc = ptrace(PTRACE_PEEKDATA, pid, registers.pc, nullptr); //pc -- program counter

    //x0, x1, x2, x3, x4, x5
    registers.regs[0] = reinterpret_cast<std::intptr_t>(memory); //address
    registers.regs[1] = size; //length
    registers.regs[2] = 0;
    registers.regs[3] = 0;
    registers.regs[4] = 0;
    registers.regs[5] = 0;

    //syscall x8
    registers.regs[8] = SYS_munmap;

    regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &regs_vec);
    ptrace(PTRACE_POKEDATA, pid, registers.pc, 0xD4000001); //syscall instruction -- svc #0
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Free Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.pc, pc);
        ptrace(PTRACE_SETREGSET, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return -1;
    }

    regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &regs_vec);

    regs_vec = { .iov_base = &registers_backup, .iov_len = sizeof(registers_backup) };
    ptrace(PTRACE_POKEDATA, pid, registers_backup.pc, pc);
    ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &regs_vec);

    return static_cast<int>(registers.regs[0]);
};

auto remote_dlopen = [](std::int32_t pid, std::uintptr_t dlopen_address, std::uintptr_t remote_path, int flags) -> void* {
    struct user_regs_struct registers = {0};
    struct user_regs_struct registers_backup = {0};
    iovec regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &regs_vec);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long pc = ptrace(PTRACE_PEEKDATA, pid, registers.pc, nullptr); //pc -- program counter

    //x0, x1, x2, x3
    registers.regs[0] = remote_path;
    registers.regs[1] = flags;
    registers.regs[2] = 0;
    registers.regs[3] = 0;
    registers.regs[4] = 0;
    registers.regs[5] = 0;
    registers.regs[6] = dlopen_address;

    regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &regs_vec);
    ptrace(PTRACE_POKEDATA, pid, registers.pc,  0xD4200000D63F00C0); //blr x6 (0xD63F00C0),  0xD420000 (brk #0)
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "remote dlopen") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.pc, pc);
        ptrace(PTRACE_SETREGSET, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    regs_vec = { .iov_base = &registers, .iov_len = sizeof(registers) };
    ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &regs_vec);

    regs_vec = { .iov_base = &registers_backup, .iov_len = sizeof(registers_backup) };
    ptrace(PTRACE_POKEDATA, pid, registers_backup.pc, pc);
    ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &regs_vec);

    return reinterpret_cast<void*>(registers.regs[0]);
};
#endif

#if defined(__arm__)
auto remote_alloc = [](std::int32_t pid, std::size_t size) -> void* {
    struct user_regs registers = {0};
    struct user_regs registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long pc = ptrace(PTRACE_PEEKDATA, pid, registers.uregs[15], nullptr); //pc -- program counter

    //r0, r1, r2, r3, r4, r5
    registers.uregs[0] = reinterpret_cast<std::uintptr_t>(nullptr); //addr
    registers.uregs[1] = size; //length
    registers.uregs[2] = PROT_READ | PROT_WRITE | PROT_EXEC; //prot
    registers.uregs[3] = MAP_PRIVATE | MAP_ANONYMOUS; //flags
    registers.uregs[4] = -1; //fd
    registers.uregs[5] = 0; //offset

    //syscall r7
    registers.uregs[7] = SYS_mmap2;
    registers.uregs[16] &= ~CPSR_THUMB_MODE_BIT;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.uregs[15], 0xEF000000); //syscall instruction -- swi 0x0 -- software interrupt
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Alloc Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.uregs[15], pc);
        ptrace(PTRACE_SETREGSET, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.uregs[15], pc);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);

    return reinterpret_cast<void*>(registers.uregs[0]);
};

auto remote_free = [](std::int32_t pid, void* memory, std::size_t size) -> int {
    struct user_regs registers = {0};
    struct user_regs registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long pc = ptrace(PTRACE_PEEKDATA, pid, registers.uregs[15], nullptr); //pc -- program counter

    //r0, r1, r2, r3, r4, r5
    registers.uregs[0] = reinterpret_cast<std::uintptr_t>(memory); //address
    registers.uregs[1] = size; //length
    registers.uregs[2] = 0;
    registers.uregs[3] = 0;
    registers.uregs[4] = 0;
    registers.uregs[5] = 0;

    //syscall r7
    registers.uregs[7] = SYS_munmap;
    registers.uregs[16] &= ~CPSR_THUMB_MODE_BIT;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.uregs[15], 0xEF000000); //syscall instruction -- swi 0x0 -- software interrupt
    ptrace(PTRACE_SINGLESTEP, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "Free Mem") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.uregs[15], pc);
        ptrace(PTRACE_SETREGSET, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return -1;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.uregs[15], pc);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);

    return static_cast<int>(registers.uregs[0]);
};

auto remote_dlopen = [](std::int32_t pid, std::uintptr_t dlopen_address, std::uintptr_t remote_path, int flags) -> void* {
    struct user_regs registers = {0};
    struct user_regs registers_backup = {0};
    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    memcpy(&registers_backup, &registers, sizeof(registers));

    long pc = ptrace(PTRACE_PEEKDATA, pid, registers.uregs[15], nullptr); //pc -- program counter

    //r0, r1, r2, r3, r4, r5
    registers.uregs[0] = remote_path;
    registers.uregs[1] = flags;
    registers.uregs[2] = 0;
    registers.uregs[3] = 0;
    registers.uregs[4] = 0;
    registers.uregs[5] = 0;
    registers.uregs[6] = dlopen_address;

    registers.uregs[15] = dlopen_address;
    registers.uregs[16] &= ~CPSR_THUMB_MODE_BIT;

    ptrace(PTRACE_SETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers.uregs[15],  0xE7F001F0D63F00C0); //blx r6 (0xD63F00C0),  0xE1200070 (bkpt #0), 0xE7F001F0 (undefined instruction breakpoint)
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);

    int status = 0;
    if (waitpid(pid, &status, 0) < 1 || get_signal(status, 5, "remote dlopen") < 0)
    {
        ptrace(PTRACE_POKEDATA, pid, registers_backup.uregs[15], pc);
        ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);
        ptrace(PTRACE_CONT, pid, nullptr, nullptr);
        return nullptr;
    }

    ptrace(PTRACE_GETREGS, pid, nullptr, &registers);
    ptrace(PTRACE_POKEDATA, pid, registers_backup.uregs[15], pc);
    ptrace(PTRACE_SETREGS, pid, nullptr, &registers_backup);

    return reinterpret_cast<void*>(registers.uregs[0]);
};
#endif


#if defined(__aarch64__) || defined(__arm__)
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
#endif