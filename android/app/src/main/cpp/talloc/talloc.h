#pragma once
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>
typedef void TALLOC_CTX;
#define talloc_new(ctx) malloc(1)
#define talloc(ctx, type) ((type*)calloc(1, sizeof(type)))
#define talloc_zero(ctx, type) ((type*)calloc(1, sizeof(type)))
#define talloc_array(ctx, type, n) ((type*)calloc((n), sizeof(type)))
#define talloc_zero_array(ctx, type, n) ((type*)calloc((n), sizeof(type)))
#define talloc_size(ctx, size) malloc(size)
#define talloc_zero_size(ctx, size) calloc(1, size)
#define talloc_free(ptr) (free(ptr), 0)
#define talloc_steal(ctx, ptr) (ptr)
#define talloc_get_size(ptr) (0)
#define talloc_set_destructor(ptr, fn)
#define talloc_report_full(ptr, f)
#define talloc_enable_leak_report()
#define talloc_set_name_const(ptr, name) (ptr)
#define talloc_named_const(ctx, size, name) malloc(size)
#define _talloc_zero(ctx, size, name) calloc(1, size)
#define talloc_get_name(ptr) ""
#define talloc_realloc(ctx, ptr, type, n) ((type*)realloc((ptr), (n)*sizeof(type)))
#define talloc_realloc_size(ctx, ptr, size) realloc((ptr), (size))
#define talloc_parent(ptr) (NULL)
#define talloc_reference(ctx, ptr) (ptr)
#define talloc_unlink(ctx, ptr) (0)
static inline char *talloc_strdup(const void *ctx, const char *str) {
    return str ? strdup(str) : NULL;
}
static inline char *talloc_strndup(const void *ctx, const char *str, size_t n) {
    if (!str) return NULL;
    char *r = (char*)malloc(n+1);
    if (r) { strncpy(r, str, n); r[n] = 0; }
    return r;
}
static inline char *talloc_asprintf(const void *ctx, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(NULL, 0, fmt, ap); va_end(ap);
    if (n < 0) return NULL;
    char *r = (char*)malloc(n+1);
    if (r) { va_start(ap, fmt); vsnprintf(r, n+1, fmt, ap); va_end(ap); }
    return r;
}
static inline char *talloc_asprintf_append(char *str, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(NULL, 0, fmt, ap); va_end(ap);
    size_t l = str ? strlen(str) : 0;
    char *r = (char*)realloc(str, l+n+1);
    if (r) { va_start(ap, fmt); vsnprintf(r+l, n+1, fmt, ap); va_end(ap); }
    return r;
}
#define talloc_strndup_append(str, s, n) talloc_asprintf_append(str, "%.*s", (int)(n), (s))
#define talloc_array_length(arr) (0)
#define talloc_autofree_context() (NULL)
#define talloc_reparent(old_ctx, new_ctx, ptr) (ptr)
#define talloc_vasprintf(ctx, fmt, ap) (NULL)
#define talloc_array_ptrtype(ctx, ptr, n) (__typeof__(ptr))calloc(n, sizeof(*(ptr)))
#define talloc_ptrtype(ctx, ptr) (__typeof__(ptr))malloc(sizeof(*(ptr)))
