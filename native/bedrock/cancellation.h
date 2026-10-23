#pragma once

void startRecoveryCancellation();
void stopRecoveryCancellation();

class RecoveryCancellationSession {
public:
    RecoveryCancellationSession() { startRecoveryCancellation(); }
    ~RecoveryCancellationSession() { stopRecoveryCancellation(); }
    RecoveryCancellationSession(const RecoveryCancellationSession&) = delete;
    RecoveryCancellationSession& operator=(const RecoveryCancellationSession&) = delete;
};
