#ifndef GEARTH_HELPER_PROXY_H
#define GEARTH_HELPER_PROXY_H

#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>

bool gearth_proxy_apply(uint16_t port);
bool gearth_proxy_restore(void);
bool gearth_process_listens(pid_t pid, uint16_t port);

#endif
