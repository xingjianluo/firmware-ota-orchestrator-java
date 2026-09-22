package com.github.xingjianluo.firmwareotaorchestratorjava.exception;

import com.github.xingjianluo.firmwareotaorchestratorjava.state.RejectReason;

/**
 * 业务拒绝异常：携带可区分的原因码，供调用方/HTTP 层精确解释。
 */
public class RejectedException extends RuntimeException {

    private final RejectReason reason;

    public RejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
