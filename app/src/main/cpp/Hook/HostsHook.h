#ifndef BLACKBOX_HOSTSHOOK_H
#define BLACKBOX_HOSTSHOOK_H

// Hosts virtual: sama seperti file /etc/hosts, tapi hanya berlaku untuk proses app virtual.
// Format tiap baris: "<ip> <hostname> [hostname2 ...]"  (# = komentar)
class HostsHook {
public:
    static void setRules(const char *text);
    static void install();
};

#endif
