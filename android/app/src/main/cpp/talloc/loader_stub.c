#include <stdlib.h>
#include <string.h>

void *loader_malloc(size_t size) {
    return malloc(size);
}

void loader_free(void *ptr) {
    free(ptr);
}
