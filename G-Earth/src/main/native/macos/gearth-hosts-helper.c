#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <signal.h>
#include <spawn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

#define HELPER_VERSION "1"
#define SOCKET_PATH "/var/run/com.gearth.hosts-helper.sock"
#define HOSTS_PATH "/etc/hosts"
#define HOSTS_MARKER "\t# G-Earth replacement"
#define MAX_REQUEST_SIZE 65536
#define MAX_HOSTS_SIZE (1024 * 1024)
#define MAX_MAPPINGS 64

static int server_fd = -1;

static void cleanup(int signal_number) {
    (void) signal_number;
    if (server_fd >= 0) {
        close(server_fd);
    }
    unlink(SOCKET_PATH);
    _exit(0);
}

static bool write_all(int fd, const char *data, size_t length) {
    while (length > 0) {
        ssize_t written = write(fd, data, length);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        data += written;
        length -= (size_t) written;
    }
    return true;
}

static void reply(int fd, const char *message) {
    (void) write_all(fd, message, strlen(message));
}

static bool active_console_user(uid_t peer_uid) {
    struct stat console;
    return peer_uid == 0
           || (stat("/dev/console", &console) == 0 && console.st_uid != 0 && peer_uid == console.st_uid);
}

static bool valid_loopback_ip(const char *value) {
    int octet = 0;
    int digits = 0;
    int index = 0;

    for (const unsigned char *cursor = (const unsigned char *) value; ; cursor++) {
        if (isdigit(*cursor)) {
            if (++digits > 3) {
                return false;
            }
            octet = octet * 10 + (*cursor - '0');
            if (octet > 255) {
                return false;
            }
        } else if (*cursor == '.' || *cursor == '\0') {
            if (digits == 0 || index > 3 || (index == 0 && octet != 127)) {
                return false;
            }
            index++;
            if (*cursor == '\0') {
                return index == 4;
            }
            octet = 0;
            digits = 0;
        } else {
            return false;
        }
    }
}

static bool valid_hostname(const char *value) {
    size_t length = strlen(value);
    if (length == 0 || length > 253) {
        return false;
    }
    for (size_t i = 0; i < length; i++) {
        unsigned char character = (unsigned char) value[i];
        if (!(isalnum(character) || character == '.' || character == '_' || character == '-')) {
            return false;
        }
    }
    return true;
}

static bool loopback_alias_exists(const char *address) {
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces) != 0) {
        return false;
    }

    bool found = false;
    for (struct ifaddrs *item = interfaces; item != NULL; item = item->ifa_next) {
        if (item->ifa_addr == NULL || item->ifa_addr->sa_family != AF_INET || strcmp(item->ifa_name, "lo0") != 0) {
            continue;
        }
        char candidate[INET_ADDRSTRLEN];
        struct sockaddr_in *socket_address = (struct sockaddr_in *) item->ifa_addr;
        if (inet_ntop(AF_INET, &socket_address->sin_addr, candidate, sizeof(candidate)) != NULL
                && strcmp(candidate, address) == 0) {
            found = true;
            break;
        }
    }
    freeifaddrs(interfaces);
    return found;
}

static bool run_tool(const char *path, char *const arguments[]) {
    pid_t child = 0;
    int status = posix_spawn(&child, path, NULL, NULL, arguments, environ);
    if (status != 0) {
        return false;
    }
    pid_t waited;
    do {
        waited = waitpid(child, &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited < 0) return false;
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static bool ensure_loopback_alias(const char *address) {
    if (strcmp(address, "127.0.0.1") == 0 || loopback_alias_exists(address)) {
        return true;
    }
    char *arguments[] = {"ifconfig", "lo0", "alias", (char *) address, NULL};
    return run_tool("/sbin/ifconfig", arguments);
}

static bool marked_line(const char *line, size_t length) {
    size_t marker_length = strlen(HOSTS_MARKER);
    if (length > 0 && line[length - 1] == '\r') {
        length--;
    }
    return length >= marker_length
           && memcmp(line + length - marker_length, HOSTS_MARKER, marker_length) == 0;
}

static bool read_hosts(int fd, char **contents, size_t *length, struct stat *metadata) {
    if (fstat(fd, metadata) != 0 || !S_ISREG(metadata->st_mode)
            || metadata->st_size < 0 || metadata->st_size > MAX_HOSTS_SIZE) {
        return false;
    }
    *length = (size_t) metadata->st_size;
    *contents = calloc(*length + 1, 1);
    if (*contents == NULL) {
        return false;
    }

    size_t offset = 0;
    while (offset < *length) {
        ssize_t received = read(fd, *contents + offset, *length - offset);
        if (received < 0) {
            if (errno == EINTR) {
                continue;
            }
            free(*contents);
            return false;
        }
        if (received == 0) {
            break;
        }
        offset += (size_t) received;
    }
    *length = offset;
    return true;
}

static bool replace_hosts(char *const addresses[], char *const hostnames[], size_t mapping_count) {
    int source = open(HOSTS_PATH, O_RDONLY | O_NOFOLLOW);
    if (source < 0 || flock(source, LOCK_EX) != 0) {
        if (source >= 0) close(source);
        return false;
    }

    char *original = NULL;
    size_t original_length = 0;
    struct stat metadata;
    if (!read_hosts(source, &original, &original_length, &metadata)) {
        close(source);
        return false;
    }

    size_t capacity = original_length + 1;
    for (size_t i = 0; i < mapping_count; i++) {
        capacity += strlen(addresses[i]) + strlen(hostnames[i]) + strlen(HOSTS_MARKER) + 3;
    }
    char *replacement = calloc(capacity, 1);
    if (replacement == NULL) {
        free(original);
        close(source);
        return false;
    }

    size_t used = 0;
    for (size_t i = 0; i < mapping_count; i++) {
        int added = snprintf(replacement + used, capacity - used, "%s %s%s\n",
                             addresses[i], hostnames[i], HOSTS_MARKER);
        if (added < 0 || (size_t) added >= capacity - used) {
            free(replacement);
            free(original);
            close(source);
            return false;
        }
        used += (size_t) added;
    }

    size_t offset = 0;
    while (offset < original_length) {
        char *newline = memchr(original + offset, '\n', original_length - offset);
        size_t line_length = newline == NULL
                             ? original_length - offset
                             : (size_t) (newline - (original + offset));
        size_t copy_length = line_length + (newline == NULL ? 0 : 1);
        if (!marked_line(original + offset, line_length)) {
            memcpy(replacement + used, original + offset, copy_length);
            used += copy_length;
        }
        offset += copy_length;
    }
    if (used > 0 && replacement[used - 1] != '\n') {
        replacement[used++] = '\n';
    }

    char temporary[] = "/etc/.gearth-hosts.XXXXXX";
    int destination = mkstemp(temporary);
    bool success = destination >= 0;
    if (success) success = fchmod(destination, metadata.st_mode & 07777) == 0;
    if (success) success = fchown(destination, metadata.st_uid, metadata.st_gid) == 0;
    if (success) success = write_all(destination, replacement, used);
    if (success) success = fsync(destination) == 0;
    if (destination >= 0) close(destination);
    if (success) success = rename(temporary, HOSTS_PATH) == 0;
    if (!success) unlink(temporary);

    free(replacement);
    free(original);
    flock(source, LOCK_UN);
    close(source);

    if (success) {
        char *arguments[] = {"dscacheutil", "-flushcache", NULL};
        success = run_tool("/usr/bin/dscacheutil", arguments);
    }
    return success;
}

static bool parse_mappings(char *body, char *addresses[], char *hostnames[], size_t *mapping_count) {
    char *save = NULL;
    char *line = strtok_r(body, "\n", &save);
    while (line != NULL) {
        if (*line != '\0') {
            if (*mapping_count >= MAX_MAPPINGS) {
                return false;
            }
            char *space = strchr(line, ' ');
            if (space == NULL || strchr(space + 1, ' ') != NULL) {
                return false;
            }
            *space = '\0';
            char *hostname = space + 1;
            size_t hostname_length = strlen(hostname);
            if (hostname_length > 0 && hostname[hostname_length - 1] == '\r') {
                hostname[--hostname_length] = '\0';
            }
            if (!valid_loopback_ip(line) || !valid_hostname(hostname)) {
                return false;
            }
            addresses[*mapping_count] = line;
            hostnames[*mapping_count] = hostname;
            (*mapping_count)++;
        }
        line = strtok_r(NULL, "\n", &save);
    }
    return *mapping_count > 0;
}

static void handle_client(int client) {
    uid_t peer_uid;
    gid_t peer_gid;
    if (getpeereid(client, &peer_uid, &peer_gid) != 0 || !active_console_user(peer_uid)) {
        reply(client, "ERR unauthorized\n");
        return;
    }

    struct timeval timeout = {.tv_sec = 5, .tv_usec = 0};
    (void) setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    char request[MAX_REQUEST_SIZE + 1];
    size_t length = 0;
    while (length < MAX_REQUEST_SIZE) {
        ssize_t received = read(client, request + length, MAX_REQUEST_SIZE - length);
        if (received < 0) {
            if (errno == EINTR) continue;
            reply(client, "ERR read\n");
            return;
        }
        if (received == 0) break;
        length += (size_t) received;
    }
    if (length == 0 || length == MAX_REQUEST_SIZE) {
        reply(client, "ERR request-size\n");
        return;
    }
    request[length] = '\0';

    if (strcmp(request, "PING\n") == 0 || strcmp(request, "PING") == 0) {
        reply(client, "OK " HELPER_VERSION "\n");
        return;
    }
    if (strcmp(request, "REMOVE\n") == 0 || strcmp(request, "REMOVE") == 0) {
        if (replace_hosts(NULL, NULL, 0)) reply(client, "OK\n");
        else reply(client, "ERR hosts\n");
        return;
    }
    if (strncmp(request, "APPLY\n", 6) != 0) {
        reply(client, "ERR command\n");
        return;
    }

    char *addresses[MAX_MAPPINGS] = {0};
    char *hostnames[MAX_MAPPINGS] = {0};
    size_t mapping_count = 0;
    if (!parse_mappings(request + 6, addresses, hostnames, &mapping_count)) {
        reply(client, "ERR mappings\n");
        return;
    }
    for (size_t i = 0; i < mapping_count; i++) {
        if (!ensure_loopback_alias(addresses[i])) {
            reply(client, "ERR loopback\n");
            return;
        }
    }
    if (replace_hosts(addresses, hostnames, mapping_count)) reply(client, "OK\n");
    else reply(client, "ERR hosts\n");
}

int main(void) {
    if (geteuid() != 0) {
        return EXIT_FAILURE;
    }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, cleanup);
    signal(SIGINT, cleanup);

    server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) return EXIT_FAILURE;

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(SOCKET_PATH) >= sizeof(address.sun_path)) return EXIT_FAILURE;
    strcpy(address.sun_path, SOCKET_PATH);
    unlink(SOCKET_PATH);
    if (bind(server_fd, (struct sockaddr *) &address, sizeof(address)) != 0
            || chmod(SOCKET_PATH, 0666) != 0
            || listen(server_fd, 8) != 0) {
        cleanup(0);
    }

    while (true) {
        int client = accept(server_fd, NULL, NULL);
        if (client < 0) {
            if (errno == EINTR) continue;
            cleanup(0);
        }
        handle_client(client);
        close(client);
    }
}
