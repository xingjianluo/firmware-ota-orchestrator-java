package com.github.xingjianluo.firmwareotaorchestratorjava.error;

import com.github.xingjianluo.firmwareotaorchestratorjava.domain.RejectReason;

/** 操作被拒绝，携带可区分的拒绝原因。 */
public class OperationRejectedException extends RuntimeException {

    private final RejectReason reason;

    public OperationRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
