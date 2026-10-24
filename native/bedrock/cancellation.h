#pragma once
#include "recovery_scheduling.h"
#ifdef _OPENMP
#include <omp.h>
#endif

void startRecoveryCancellation();
void stopRecoveryCancellation();

class RecoveryCancellationSession {
    RecoveryThreadPriority priority;
public:
    RecoveryCancellationSession() {
#ifdef _OPENMP
        omp_set_num_threads(recoveryWorkerCount());
#endif
        startRecoveryCancellation();
    }
    ~RecoveryCancellationSession() { stopRecoveryCancellation(); }
    RecoveryCancellationSession(const RecoveryCancellationSession&) = delete;
    RecoveryCancellationSession& operator=(const RecoveryCancellationSession&) = delete;
};
