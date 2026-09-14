#include "gearth-helper-proxy.h"

#include <CoreFoundation/CoreFoundation.h>
#include <SystemConfiguration/SystemConfiguration.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <libproc.h>
#include <stdlib.h>
#include <string.h>
#include <sys/proc_info.h>
#include <sys/stat.h>
#include <unistd.h>

#define PROXY_STATE_PATH "/var/db/com.gearth.proxy-state.plist"
#define MAX_STATE_SIZE (1024 * 1024)

static const CFStringRef bypass_domains[] = {
        CFSTR("discord.com"),
        CFSTR("discordapp.com"),
        CFSTR("canary.discord.com"),
        CFSTR("canary.discordapp.com"),
        CFSTR("github.com"),
        CFSTR("gateway.discord.gg")
};

static bool write_all(int fd, const UInt8 *bytes, size_t length) {
    while (length > 0) {
        ssize_t written = write(fd, bytes, length);
        if (written <= 0) return false;
        bytes += written;
        length -= (size_t) written;
    }
    return true;
}

static bool save_state(CFDictionaryRef state) {
    CFErrorRef error = NULL;
    CFDataRef data = CFPropertyListCreateData(NULL, state,
                                               kCFPropertyListBinaryFormat_v1_0, 0, &error);
    if (error != NULL) CFRelease(error);
    if (data == NULL) return false;

    char temporary[] = "/var/db/.com.gearth.proxy-state.XXXXXX";
    int fd = mkstemp(temporary);
    bool success = fd >= 0;
    if (success) success = fchmod(fd, 0600) == 0;
    if (success) success = write_all(fd, CFDataGetBytePtr(data), (size_t) CFDataGetLength(data));
    if (success) success = fsync(fd) == 0;
    if (fd >= 0) close(fd);
    if (success) success = rename(temporary, PROXY_STATE_PATH) == 0;
    if (!success) unlink(temporary);
    CFRelease(data);
    return success;
}

static CFDictionaryRef load_state(void) {
    int fd = open(PROXY_STATE_PATH, O_RDONLY | O_NOFOLLOW);
    if (fd < 0) return NULL;

    struct stat metadata;
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode)
            || metadata.st_uid != 0 || metadata.st_size <= 0 || metadata.st_size > MAX_STATE_SIZE) {
        close(fd);
        return NULL;
    }

    UInt8 *bytes = malloc((size_t) metadata.st_size);
    if (bytes == NULL) {
        close(fd);
        return NULL;
    }
    size_t offset = 0;
    while (offset < (size_t) metadata.st_size) {
        ssize_t count = read(fd, bytes + offset, (size_t) metadata.st_size - offset);
        if (count <= 0) break;
        offset += (size_t) count;
    }
    close(fd);

    CFDataRef data = CFDataCreate(NULL, bytes, (CFIndex) offset);
    free(bytes);
    if (data == NULL) return NULL;
    CFPropertyListRef property = CFPropertyListCreateWithData(NULL, data,
                                                              kCFPropertyListImmutable, NULL, NULL);
    CFRelease(data);
    if (property == NULL || CFGetTypeID(property) != CFDictionaryGetTypeID()) {
        if (property != NULL) CFRelease(property);
        return NULL;
    }
    return (CFDictionaryRef) property;
}

static bool commit_preferences(SCPreferencesRef preferences) {
    return SCPreferencesCommitChanges(preferences) && SCPreferencesApplyChanges(preferences);
}

static bool restore_state(CFDictionaryRef state) {
    SCPreferencesRef preferences = SCPreferencesCreate(NULL, CFSTR("G-Earth"), NULL);
    if (preferences == NULL || !SCPreferencesLock(preferences, true)) {
        if (preferences != NULL) CFRelease(preferences);
        return false;
    }

    bool success = false;
    SCNetworkSetRef set = SCNetworkSetCopyCurrent(preferences);
    CFArrayRef services = set == NULL ? NULL : SCNetworkSetCopyServices(set);
    if (services != NULL) {
        CFIndex count = CFArrayGetCount(services);
        for (CFIndex i = 0; i < count; i++) {
            SCNetworkServiceRef service = (SCNetworkServiceRef) CFArrayGetValueAtIndex(services, i);
            CFStringRef identifier = SCNetworkServiceGetServiceID(service);
            const void *saved = identifier == NULL ? NULL : CFDictionaryGetValue(state, identifier);
            if (saved == NULL) continue;

            SCNetworkProtocolRef protocol = SCNetworkServiceCopyProtocol(service, kSCNetworkProtocolTypeProxies);
            if (protocol == NULL) continue;
            const CFDictionaryRef configuration = saved == kCFNull ? NULL : (CFDictionaryRef) saved;
            if (!SCNetworkProtocolSetConfiguration(protocol, configuration)) {
                CFRelease(protocol);
                goto cleanup;
            }
            CFRelease(protocol);
        }
        success = commit_preferences(preferences);
    }

cleanup:
    if (services != NULL) CFRelease(services);
    if (set != NULL) CFRelease(set);
    SCPreferencesUnlock(preferences);
    CFRelease(preferences);
    return success;
}

bool gearth_proxy_restore(void) {
    if (access(PROXY_STATE_PATH, F_OK) != 0) return true;
    CFDictionaryRef state = load_state();
    if (state == NULL) return false;
    bool success = restore_state(state);
    CFRelease(state);
    if (success) success = unlink(PROXY_STATE_PATH) == 0;
    return success;
}

static void set_number(CFMutableDictionaryRef dictionary, CFStringRef key, int value) {
    CFNumberRef number = CFNumberCreate(NULL, kCFNumberIntType, &value);
    if (number != NULL) {
        CFDictionarySetValue(dictionary, key, number);
        CFRelease(number);
    }
}

static void add_bypass_domains(CFMutableDictionaryRef configuration) {
    CFArrayRef existing = (CFArrayRef) CFDictionaryGetValue(configuration,
                                                              kSCPropNetProxiesExceptionsList);
    CFMutableArrayRef domains;
    if (existing != NULL && CFGetTypeID(existing) == CFArrayGetTypeID()) {
        domains = CFArrayCreateMutableCopy(NULL, 0, existing);
    } else {
        domains = CFArrayCreateMutable(NULL, 0, &kCFTypeArrayCallBacks);
    }
    if (domains == NULL) return;

    CFRange range = CFRangeMake(0, CFArrayGetCount(domains));
    size_t count = sizeof(bypass_domains) / sizeof(bypass_domains[0]);
    for (size_t i = 0; i < count; i++) {
        if (!CFArrayContainsValue(domains, range, bypass_domains[i])) {
            CFArrayAppendValue(domains, bypass_domains[i]);
            range.length++;
        }
    }
    CFDictionarySetValue(configuration, kSCPropNetProxiesExceptionsList, domains);
    CFRelease(domains);
}

bool gearth_proxy_apply(uint16_t port) {
    if (port == 0 || !gearth_proxy_restore()) return false;

    SCPreferencesRef preferences = SCPreferencesCreate(NULL, CFSTR("G-Earth"), NULL);
    if (preferences == NULL || !SCPreferencesLock(preferences, true)) {
        if (preferences != NULL) CFRelease(preferences);
        return false;
    }

    bool success = false;
    CFMutableDictionaryRef state = CFDictionaryCreateMutable(NULL, 0,
                                                              &kCFTypeDictionaryKeyCallBacks,
                                                              &kCFTypeDictionaryValueCallBacks);
    SCNetworkSetRef set = SCNetworkSetCopyCurrent(preferences);
    CFArrayRef services = set == NULL ? NULL : SCNetworkSetCopyServices(set);
    if (state == NULL || services == NULL) goto cleanup;

    CFIndex count = CFArrayGetCount(services);
    for (CFIndex i = 0; i < count; i++) {
        SCNetworkServiceRef service = (SCNetworkServiceRef) CFArrayGetValueAtIndex(services, i);
        if (!SCNetworkServiceGetEnabled(service)) continue;

        CFStringRef identifier = SCNetworkServiceGetServiceID(service);
        SCNetworkProtocolRef protocol = SCNetworkServiceCopyProtocol(service, kSCNetworkProtocolTypeProxies);
        if (identifier == NULL || protocol == NULL) {
            if (protocol != NULL) CFRelease(protocol);
            continue;
        }

        CFDictionaryRef current = SCNetworkProtocolGetConfiguration(protocol);
        const void *saved = current == NULL ? (const void *) kCFNull : (const void *) current;
        CFDictionarySetValue(state, identifier, saved);
        CFMutableDictionaryRef updated = current == NULL
                                         ? CFDictionaryCreateMutable(NULL, 0,
                                                                    &kCFTypeDictionaryKeyCallBacks,
                                                                    &kCFTypeDictionaryValueCallBacks)
                                         : CFDictionaryCreateMutableCopy(NULL, 0, current);
        if (updated == NULL) {
            CFRelease(protocol);
            goto cleanup;
        }

        set_number(updated, kSCPropNetProxiesHTTPEnable, 1);
        set_number(updated, kSCPropNetProxiesHTTPPort, port);
        CFDictionarySetValue(updated, kSCPropNetProxiesHTTPProxy, CFSTR("127.0.0.1"));
        set_number(updated, kSCPropNetProxiesHTTPSEnable, 1);
        set_number(updated, kSCPropNetProxiesHTTPSPort, port);
        CFDictionarySetValue(updated, kSCPropNetProxiesHTTPSProxy, CFSTR("127.0.0.1"));
        add_bypass_domains(updated);

        bool configured = SCNetworkProtocolSetConfiguration(protocol, updated);
        CFRelease(updated);
        CFRelease(protocol);
        if (!configured) goto cleanup;
    }

    if (CFDictionaryGetCount(state) == 0 || !save_state(state)) goto cleanup;
    success = commit_preferences(preferences);

cleanup:
    if (services != NULL) CFRelease(services);
    if (set != NULL) CFRelease(set);
    if (state != NULL) CFRelease(state);
    SCPreferencesUnlock(preferences);
    CFRelease(preferences);
    if (!success) {
        CFDictionaryRef saved = load_state();
        if (saved != NULL) {
            bool restored = restore_state(saved);
            CFRelease(saved);
            if (restored) unlink(PROXY_STATE_PATH);
        }
    }
    return success;
}

bool gearth_process_listens(pid_t pid, uint16_t port) {
    int size = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, NULL, 0);
    if (size <= 0) return false;
    struct proc_fdinfo *descriptors = malloc((size_t) size);
    if (descriptors == NULL) return false;

    int used = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, descriptors, size);
    bool found = false;
    for (int i = 0; i < used / (int) sizeof(*descriptors); i++) {
        if (descriptors[i].proc_fdtype != PROX_FDTYPE_SOCKET) continue;
        struct socket_fdinfo socket_info;
        int result = proc_pidfdinfo(pid, descriptors[i].proc_fd, PROC_PIDFDSOCKETINFO,
                                    &socket_info, (int) sizeof(socket_info));
        if (result != (int) sizeof(socket_info) || socket_info.psi.soi_kind != SOCKINFO_TCP) continue;

        struct tcp_sockinfo *tcp = &socket_info.psi.soi_proto.pri_tcp;
        struct in_sockinfo *address = &tcp->tcpsi_ini;
        if (tcp->tcpsi_state == TSI_S_LISTEN
                && ntohs((uint16_t) address->insi_lport) == port
                && (address->insi_vflag & INI_IPV4) != 0
                && address->insi_laddr.ina_46.i46a_addr4.s_addr == htonl(INADDR_LOOPBACK)) {
            found = true;
            break;
        }
    }
    free(descriptors);
    return found;
}
