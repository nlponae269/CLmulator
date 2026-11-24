/*
 * CLmulator test-build su: minimal, libc-free, i386.
 * Grants root to any caller (this is a TEST firmware, not for real devices).
 *   su                -> root shell (/system/bin/sh)
 *   su -c "command"   -> /system/bin/sh -c "command"
 *   su [user] [-c ...] (user ignored, always root)
 */
static long sys1(long n, long a) { long r; __asm__ volatile("int $0x80":"=a"(r):"a"(n),"b"(a):"memory"); return r; }
static long sys3(long n, long a, long b, long c) { long r; __asm__ volatile("int $0x80":"=a"(r):"a"(n),"b"(a),"c"(b),"d"(c):"memory"); return r; }
static int eq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static long len(const char *s) { long n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { sys3(4, 2, (long)s, len(s)); }

int su_main(int argc, char **argv, char **envp)
{
    static char *nargv[64];
    static char sh[] = "/system/bin/sh";
    static char dashc[] = "-c";
    int i, n = 0;
    sys3(206, 0, 0, 0);            /* setgroups32(0, NULL) */
    sys1(214, 0);                  /* setgid32(0) */
    if (sys1(213, 0) != 0) {       /* setuid32(0) */
        out("su: permission denied (no setuid?)\n");
        return 1;
    }
    nargv[n++] = sh;
    for (i = 1; i < argc && n < 62; i++) {
        if (eq(argv[i], "-c") || eq(argv[i], "--command")) {
            if (i + 1 < argc) { nargv[n++] = dashc; nargv[n++] = argv[i + 1]; }
            break;
        }
        if (eq(argv[i], "-v") || eq(argv[i], "--version")) { out("clm-su 1.0\n"); return 0; }
        /* user name / "-" / "-l": ignored, always root */
    }
    nargv[n] = 0;
    sys3(11, (long)sh, (long)nargv, (long)envp);   /* execve */
    out("su: cannot exec /system/bin/sh\n");
    return 127;
}

__asm__(
    ".globl _start\n"
    "_start:\n"
    "  xorl %ebp,%ebp\n"
    "  movl (%esp),%eax\n"          /* argc */
    "  leal 4(%esp),%ebx\n"         /* argv */
    "  leal 8(%esp,%eax,4),%ecx\n"  /* envp */
    "  andl $-16,%esp\n"
    "  subl $4,%esp\n"
    "  pushl %ecx\n"
    "  pushl %ebx\n"
    "  pushl %eax\n"
    "  call su_main\n"
    "  movl %eax,%ebx\n"
    "  movl $1,%eax\n"
    "  int $0x80\n");
