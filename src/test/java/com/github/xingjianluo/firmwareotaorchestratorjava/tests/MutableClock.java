package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/** 可手动推进的时钟，用于离线判定与心跳时间戳的确定性测试。 */
public class MutableClock extends Clock {

    private final AtomicLong millis;

    public MutableClock(long initialMillis) {
        this.millis = new AtomicLong(initialMillis);
    }

    public void advance(long deltaMillis) {
        millis.addAndGet(deltaMillis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
        return millis.get();
    }
}
