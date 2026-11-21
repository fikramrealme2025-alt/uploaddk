#include "HostsHook.h"

#include <netdb.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <strings.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <mutex>

#include "xdl.h"
#include "Dobby/dobby.h"
#include "Log.h"

namespace {

struct HostRule {
    std::string host;  // huruf kecil
    std::string ip;
};

std::vector<HostRule> gRules;
std::mutex gMutex;
bool gInstalled = false;

std::string toLower(const char *s) {
    std::string r(s ? s : "");
    for (size_t i = 0; i < r.size(); i++) {
        if (r[i] >= 'A' && r[i] <= 'Z') r[i] = (char) (r[i] - 'A' + 'a');
    }
    return r;
}

// Pencocokan glob sederhana: '*' = nol/lebih karakter, '?' = satu karakter.
bool globMatch(const char *p, const char *s) {
    const char *star = nullptr;
    const char *ss = s;
    while (*s) {
        if (*p == '*') {
            star = p++;
            ss = s;
        } else if (*p == '?' || *p == *s) {
            p++;
            s++;
        } else if (star) {
            p = star + 1;
            s = ++ss;
        } else {
            return false;
        }
    }
    while (*p == '*') p++;
    return *p == '\0';
}

// Cari IP pengganti untuk hostname; nullptr kalau tidak ada aturan.
// Hasil disalin ke buf (aman dari perubahan aturan di thread lain).
bool lookupHost(const char *node, char *buf, size_t len) {
    if (!node || !node[0]) return false;
    std::string h = toLower(node);
    // buang titik di akhir ("example.com.")
    while (!h.empty() && h[h.size() - 1] == '.') h.erase(h.size() - 1);
    std::lock_guard<std::mutex> lock(gMutex);
    for (size_t i = 0; i < gRules.size(); i++) {
        if (globMatch(gRules[i].host.c_str(), h.c_str())) {
            strncpy(buf, gRules[i].ip.c_str(), len - 1);
            buf[len - 1] = '\0';
            return true;
        }
    }
    return false;
}

// ---- getaddrinfo (libc, dipakai kode native / game Unity)
int (*orig_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **) = nullptr;

int my_getaddrinfo(const char *node, const char *service, const struct addrinfo *hints,
                   struct addrinfo **res) {
    char ip[64];
    if (lookupHost(node, ip, sizeof(ip))) {
        ALOGD("HostsHook getaddrinfo: %s -> %s", node, ip);
        return orig_getaddrinfo(ip, service, hints, res);
    }
    return orig_getaddrinfo(node, service, hints, res);
}

// ---- android_getaddrinfofornet (dipakai Java: InetAddress / libcore)
int (*orig_gai_fornet)(const char *, const char *, const struct addrinfo *, unsigned, unsigned,
                       struct addrinfo **) = nullptr;

int my_gai_fornet(const char *node, const char *service, const struct addrinfo *hints,
                  unsigned netid, unsigned mark, struct addrinfo **res) {
    char ip[64];
    if (lookupHost(node, ip, sizeof(ip))) {
        ALOGD("HostsHook fornet: %s -> %s", node, ip);
        return orig_gai_fornet(ip, service, hints, netid, mark, res);
    }
    return orig_gai_fornet(node, service, hints, netid, mark, res);
}

// ---- android_getaddrinfofornetcontext
int (*orig_gai_ctx)(const char *, const char *, const struct addrinfo *, const void *,
                    struct addrinfo **) = nullptr;

int my_gai_ctx(const char *node, const char *service, const struct addrinfo *hints,
               const void *netctx, struct addrinfo **res) {
    char ip[64];
    if (lookupHost(node, ip, sizeof(ip))) {
        ALOGD("HostsHook fornetcontext: %s -> %s", node, ip);
        return orig_gai_ctx(ip, service, hints, netctx, res);
    }
    return orig_gai_ctx(node, service, hints, netctx, res);
}

// ---- gethostbyname (API lama)
struct hostent *(*orig_gethostbyname)(const char *) = nullptr;

struct hostent *my_gethostbyname(const char *name) {
    char ip[64];
    if (lookupHost(name, ip, sizeof(ip))) {
        static thread_local struct hostent he;
        static thread_local struct in_addr addr;
        static thread_local char *addrList[2];
        static thread_local char *aliases[1];
        static thread_local char hostName[256];
        if (inet_pton(AF_INET, ip, &addr) == 1) {
            strncpy(hostName, name, sizeof(hostName) - 1);
            hostName[sizeof(hostName) - 1] = '\0';
            addrList[0] = (char *) &addr;
            addrList[1] = nullptr;
            aliases[0] = nullptr;
            he.h_name = hostName;
            he.h_aliases = aliases;
            he.h_addrtype = AF_INET;
            he.h_length = sizeof(struct in_addr);
            he.h_addr_list = addrList;
            ALOGD("HostsHook gethostbyname: %s -> %s", name, ip);
            return &he;
        }
    }
    return orig_gethostbyname(name);
}

void hookSymbol(void *handle, const char *name, void *replace, void **orig) {
    void *target = xdl_dsym(handle, name, nullptr);
    if (!target) {
        ALOGD("HostsHook: simbol %s tidak ditemukan", name);
        return;
    }
    if (DobbyHook(target, replace, orig) == 0) {
        ALOGD("HostsHook: hook %s OK", name);
    } else {
        ALOGD("HostsHook: hook %s GAGAL", name);
    }
}

} // namespace

void HostsHook::setRules(const char *text) {
    std::vector<HostRule> rules;
    if (text) {
        std::string all(text);
        size_t pos = 0;
        while (pos <= all.size()) {
            size_t nl = all.find('\n', pos);
            std::string line = all.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
            pos = (nl == std::string::npos) ? all.size() + 1 : nl + 1;

            size_t hash = line.find('#');
            if (hash != std::string::npos) line.erase(hash);

            std::vector<std::string> tok;
            std::string cur;
            for (size_t i = 0; i <= line.size(); i++) {
                char c = i < line.size() ? line[i] : ' ';
                if (c == ' ' || c == '\t' || c == '\r') {
                    if (!cur.empty()) { tok.push_back(cur); cur.clear(); }
                } else {
                    cur += c;
                }
            }
            if (tok.size() < 2) continue;
            // IP harus valid (IPv4 / IPv6)
            unsigned char tmp[16];
            if (inet_pton(AF_INET, tok[0].c_str(), tmp) != 1 &&
                inet_pton(AF_INET6, tok[0].c_str(), tmp) != 1) {
                continue;
            }
            for (size_t i = 1; i < tok.size(); i++) {
                HostRule r;
                r.host = toLower(tok[i].c_str());
                r.ip = tok[0];
                rules.push_back(r);
            }
        }
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        gRules.swap(rules);
    }
    ALOGD("HostsHook: %zu aturan dimuat", gRules.size());
}

void HostsHook::install() {
    if (gInstalled) return;
    gInstalled = true;
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGD("HostsHook: libc.so tidak bisa dibuka");
        return;
    }
    hookSymbol(handle, "getaddrinfo", (void *) my_getaddrinfo, (void **) &orig_getaddrinfo);
    hookSymbol(handle, "android_getaddrinfofornet", (void *) my_gai_fornet, (void **) &orig_gai_fornet);
    hookSymbol(handle, "android_getaddrinfofornetcontext", (void *) my_gai_ctx, (void **) &orig_gai_ctx);
    hookSymbol(handle, "gethostbyname", (void *) my_gethostbyname, (void **) &orig_gethostbyname);
    xdl_close(handle);
}
