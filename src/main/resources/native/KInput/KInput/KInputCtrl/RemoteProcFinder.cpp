/*
This program was modified by ThatOneGuyScripts April,15,2023
Copyright(c) 2023 ThatOneGuyScripts
Copyright (c) 2009 Christoph Husse & Copyright (c) 2012 Justin Stenning

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/
#include <windows.h>
#include <map>
#include <TlHelp32.h>
#include <string>
#include "RemoteProcFinder.h"

using namespace std;


void* GetRemoteFuncAddress(unsigned long pId, char* module, char* func) {
    /*
    Description:
        Get remote function address for the module within the remote process.
        This is done by retrieving the exports found within the specified module and
        finding a match for "func". The export name must exactly match "func".
    Parameters:
        - pId

            The Id of the remote process
        - hProcess
            The handle of the remote process as returned by a call to OpenProcess
        - module
            The name of the module that contains the function within the remote process
        - func
            The name of the function within the module to find the address for
    Example:
        INT_PTR fAddress = GetRemoteFuncAddress(pId, "kernel32.dll", "GetProcAddress");
    */

    HANDLE hProcess = OpenProcess(PROCESS_ALL_ACCESS, FALSE, pId);

    if (hProcess == NULL) {
        printf("Failed to open process with pid %lu\n", pId);
        return NULL;
    }
    HMODULE hRemote = GetRemoteModuleHandle(pId, module);
    IMAGE_DOS_HEADER DosHeader;
    IMAGE_NT_HEADERS NtHeaders;
    IMAGE_EXPORT_DIRECTORY EATDirectory;

    DWORD* AddressOfFunctions;
    DWORD* AddressOfNames;
    WORD* AddressOfOrdinals;

    unsigned int i;

    DWORD_PTR dwExportBase;
    DWORD_PTR dwExportSize;
    DWORD_PTR dwAddressOfFunction;
    DWORD_PTR dwAddressOfName;

    char pszFunctionName[256] = { 0 };
    char pszRedirectName[256] = { 0 };
    char pszModuleName[256] = { 0 };
    char pszFunctionRedi[256] = { 0 };

    int a = 0;
    int b = 0;

    WORD OrdinalValue;

    DWORD_PTR dwAddressOfRedirectedFunction;
    DWORD_PTR dwAddressOfRedirectedName;

    char pszRedirectedFunctionName[256] = { 0 };

    if (!hRemote)
        return NULL;

    // Load DOS PE header
    if (!ReadProcessMemory(hProcess, (void*)hRemote, &DosHeader, sizeof(IMAGE_DOS_HEADER), NULL) || DosHeader.e_magic != IMAGE_DOS_SIGNATURE)
        return NULL;

    // Load NT PE headers
    if (!ReadProcessMemory(hProcess, (void*)((DWORD_PTR)hRemote + DosHeader.e_lfanew), &NtHeaders, sizeof(IMAGE_NT_HEADERS), NULL) || NtHeaders.Signature != IMAGE_NT_SIGNATURE)
        return NULL;

    // Load image export directory
    if (!GetRemoteModuleExportDirectory(hProcess, hRemote, &EATDirectory, DosHeader, NtHeaders))
        return NULL;

    // Allocate room for all the function information
    AddressOfFunctions = (DWORD*)malloc(EATDirectory.NumberOfFunctions * sizeof(DWORD));
    AddressOfNames = (DWORD*)malloc(EATDirectory.NumberOfNames * sizeof(DWORD));
    AddressOfOrdinals = (WORD*)malloc(EATDirectory.NumberOfNames * sizeof(WORD));

    // Read function address locations
    if (!ReadProcessMemory(hProcess, (void*)((DWORD_PTR)hRemote + (DWORD_PTR)EATDirectory.AddressOfFunctions), AddressOfFunctions, EATDirectory.NumberOfFunctions * sizeof(DWORD), NULL)) {
        free(AddressOfFunctions);
        free(AddressOfNames);
        free(AddressOfOrdinals);
        return NULL;
    }

    // Read function name locations
    if (!ReadProcessMemory(hProcess, (void*)((DWORD_PTR)hRemote + (DWORD_PTR)EATDirectory.AddressOfNames), AddressOfNames, EATDirectory.NumberOfNames * sizeof(DWORD), NULL)) {
        free(AddressOfFunctions);
        free(AddressOfNames);
        free(AddressOfOrdinals);
        return NULL;
    }

    // Read function name ordinal locations
    if (!ReadProcessMemory(hProcess, (void*)((DWORD_PTR)hRemote + (DWORD_PTR)EATDirectory.AddressOfNameOrdinals), AddressOfOrdinals, EATDirectory.NumberOfNames * sizeof(WORD), NULL)) {
        free(AddressOfFunctions);
        free(AddressOfNames);
        free(AddressOfOrdinals);
        return NULL;
    }

    dwExportBase = ((DWORD_PTR)hRemote + NtHeaders.OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT].VirtualAddress);
    dwExportSize = (dwExportBase + NtHeaders.OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT].Size);

    // Check each name for a match
    for (i = 0; i < EATDirectory.NumberOfNames; ++i) {
        dwAddressOfFunction = (DWORD_PTR)hRemote + AddressOfFunctions[i];
        dwAddressOfName = (DWORD_PTR)hRemote + AddressOfNames[i];

        memset(&pszFunctionName, 0, 256);

        if (!ReadProcessMemory(hProcess, (void*)dwAddressOfName, pszFunctionName, 256, NULL))
            continue;

        // Skip until we find the matching function name
        if (_stricmp(pszFunctionName, func) != 0)
            continue;

        // Check if address of function is found in another module
        if (dwAddressOfFunction >= dwExportBase && dwAddressOfFunction <= dwExportSize) {
            memset(&pszRedirectName, 0, 256);

            if (!ReadProcessMemory(hProcess, (void*)dwAddressOfFunction, pszRedirectName, 256, NULL))
                continue;

            memset(&pszModuleName, 0, 256);
            memset(&pszFunctionRedi, 0, 256);

            a = 0;
            for (; pszRedirectName[a] != '.'; a++)
                pszModuleName[a] = pszRedirectName[a];
            a++;
            pszModuleName[a] = '\0';

            b = 0;
            for (; pszRedirectName[a] != '\0'; a++, b++)
                pszFunctionRedi[b] = pszRedirectName[a];
            b++;
            pszFunctionRedi[b] = '\0';

            strcat_s(pszModuleName, 256, ".dll");

            free(AddressOfFunctions);
            free(AddressOfNames);
            free(AddressOfOrdinals);

            return GetRemoteFuncAddress(pId, pszModuleName, pszFunctionRedi);
        }

        OrdinalValue = AddressOfOrdinals[i];

        if (OrdinalValue >= EATDirectory.NumberOfNames)
        {
            return NULL;
        }

        // If ordinal doesn't match index retrieve correct address
        if (OrdinalValue != i) {
            dwAddressOfRedirectedFunction = ((DWORD_PTR)hRemote + (DWORD_PTR)AddressOfFunctions[OrdinalValue]);
            dwAddressOfRedirectedName = ((DWORD_PTR)hRemote + (DWORD_PTR)AddressOfNames[OrdinalValue]);

            memset(&pszRedirectedFunctionName, 0, 256);

            free(AddressOfFunctions);
            free(AddressOfNames);
            free(AddressOfOrdinals);

            if (!ReadProcessMemory(hProcess, (void*)dwAddressOfRedirectedName, pszRedirectedFunctionName, 256, NULL))
                return NULL;
            else
                return (void*)dwAddressOfRedirectedFunction;
        }
        // Otherwise return the address
        else {
            free(AddressOfFunctions);
            free(AddressOfNames);
            free(AddressOfOrdinals);

            return (void*)dwAddressOfFunction;
        }
    }

    free(AddressOfFunctions);
    free(AddressOfNames);
    free(AddressOfOrdinals);

    return NULL;
}

HMODULE GetRemoteModuleHandle(unsigned long pId, const char* module)
{
    /*
    Description:
        Get Remote Module Handle retrieves a handle to the specified module within the provided process Id
    Parameters:
        - pId
            The Id of the target process to find the module within
        - module
            The name of the module
    Example:
        GetRemoteModuleHandle(PID, "kernel32.dll");
    */
    MODULEENTRY32 modEntry;
    HANDLE tlh = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, pId);
    wchar_t moduleChar[256] = { 0 };

    modEntry.dwSize = sizeof(MODULEENTRY32);
    Module32First(tlh, &modEntry);
    do
    {
        size_t i;
        mbstowcs_s(&i, moduleChar, 256, modEntry.szModule, 256);
        if (!_wcsicmp(moduleChar, wstring(module, module + strlen(module)).c_str()))
        {
            CloseHandle(tlh);
            return modEntry.hModule;
        }
        modEntry.dwSize = sizeof(MODULEENTRY32);
    } while (Module32Next(tlh, &modEntry));

    CloseHandle(tlh);
    return NULL;
}

BOOL GetRemoteModuleExportDirectory(HANDLE hProcess, HMODULE hRemote, PIMAGE_EXPORT_DIRECTORY ExportDirectory, IMAGE_DOS_HEADER DosHeader, IMAGE_NT_HEADERS NtHeaders) {
    /*
    Description:

        Retrieves the export dictionary for the provided module.
    Parameters:
        - hProcess
            The handle to the remote process to read from
        - hRemote
            The handle to the remote module to retrieve the export dictionary for
        - ExportDictionary
            Will contain the resulting export dictionary
        - DosHeader
            The preloaded DOS PE Header
            e.g.: ReadProcessMemory(hProcess, (void*)hRemote, &DosHeader, sizeof(IMAGE_DOS_HEADER), NULL)

        - NtHeaders
            The preloaded NT PE headers
            e.g.: dwNTHeaders = (PDWORD)((DWORD)hRemote + DosHeader.e_lfanew);
                  ReadProcessMemory(hProcess, dwNTHeaders, &NtHeaders, sizeof(IMAGE_NT_HEADERS), NULL)
    */
    PUCHAR ucAllocatedPEHeader;
    PIMAGE_SECTION_HEADER pImageSectionHeader;
    int i;
    DWORD dwEATAddress;

    if (!ExportDirectory)
        return FALSE;

    ucAllocatedPEHeader = (PUCHAR)malloc(1000 * sizeof(UCHAR));

    memset(ExportDirectory, 0, sizeof(IMAGE_EXPORT_DIRECTORY));


    if (!ReadProcessMemory(hProcess, (void*)hRemote, ucAllocatedPEHeader, (SIZE_T)1000, NULL))
        return FALSE;

    pImageSectionHeader = (PIMAGE_SECTION_HEADER)(ucAllocatedPEHeader + DosHeader.e_lfanew + sizeof(IMAGE_NT_HEADERS));

    for (i = 0; i < NtHeaders.FileHeader.NumberOfSections; i++, pImageSectionHeader++) {
        if (!pImageSectionHeader)
            continue;

        if (_stricmp((char*)pImageSectionHeader->Name, ".edata") == 0) {
            if (!ReadProcessMemory(hProcess, reinterpret_cast<void*>(pImageSectionHeader->VirtualAddress), ExportDirectory, sizeof(IMAGE_EXPORT_DIRECTORY), NULL))
                continue;


            free(ucAllocatedPEHeader);
            return TRUE;
        }

    }

    dwEATAddress = NtHeaders.OptionalHeader.DataDirectory[0].VirtualAddress;
    if (!dwEATAddress)
        return FALSE;

    if (!ReadProcessMemory(hProcess, (void*)((DWORD_PTR)hRemote + dwEATAddress), ExportDirectory, sizeof(IMAGE_EXPORT_DIRECTORY), NULL))
        return FALSE;

    free(ucAllocatedPEHeader);
    return TRUE;
}
