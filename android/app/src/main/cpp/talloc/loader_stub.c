#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Stub loader binary symbols */
char _binary_loader_exe_start[1] = {0};
char _binary_loader_exe_end[1] = {0};

/* Stub for pokedata workaround */
uintptr_t offset_to_pokedata_workaround = 0;

void *loader_malloc(size_t size) {
    return malloc(size);
}

void loader_free(void *ptr) {
    free(ptr);
}
