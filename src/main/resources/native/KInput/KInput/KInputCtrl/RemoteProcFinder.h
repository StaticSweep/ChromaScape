#pragma once
#include "Injector.hpp"
void* GetRemoteFuncAddress(unsigned long pId, char* module, char* func);
HMODULE GetRemoteModuleHandle(unsigned long pId, const char* module);
BOOL GetRemoteModuleExportDirectory(HANDLE hProcess, HMODULE hRemote, PIMAGE_EXPORT_DIRECTORY ExportDirectory, IMAGE_DOS_HEADER DosHeader, IMAGE_NT_HEADERS NtHeaders);