package com.fido.server.repository;

import com.fido.server.domain.CrossDeviceSession;

import java.util.Optional;

/**
 * `cross_device_sessions` 表的 persistence 介面（db-schema.md 第 11 節 / DB19）。
 *
 * <p>主要用途：{@code com.fido.server.service.CrossDeviceLoginService} 依 {@code xdevId}
 * capability 反查 session、驅動 {@code PENDING -> SCANNED -> CONFIRMED -> CONSUMED} 狀態機。
 */
public interface CrossDeviceSessionRepository {

    Optional<CrossDeviceSession> findByXdevId(String xdevId);

    CrossDeviceSession save(CrossDeviceSession session);
}
