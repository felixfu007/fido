package com.fido.server.repository.inmemory;

import com.fido.server.domain.CrossDeviceSession;
import com.fido.server.domain.enums.CrossDeviceSessionStatus;
import com.fido.server.repository.CrossDeviceSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory 骨架實作，見 {@link InMemoryTenantRepository} 說明。 */
@Repository
@ConditionalOnProperty(prefix = "fido.persistence", name = "mode", havingValue = "memory")
public class InMemoryCrossDeviceSessionRepository implements CrossDeviceSessionRepository {

    private final Map<String, CrossDeviceSession> byXdevId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    @Override
    public Optional<CrossDeviceSession> findByXdevId(String xdevId) {
        return Optional.ofNullable(byXdevId.get(xdevId));
    }

    @Override
    public synchronized CrossDeviceSession save(CrossDeviceSession session) {
        if (session.getXdevPk() == null) {
            session.setXdevPk(idSeq.getAndIncrement());
        }
        byXdevId.put(session.getXdevId(), session);
        return session;
    }

    /**
     * 見 {@link CrossDeviceSessionRepository#consumeConfirmedJwt} 的完整語意說明。in-memory 模式
     * 下用 {@code synchronized}（與 {@link #save} 共用同一個物件監視鎖）達成「檢查目前狀態是否仍為
     * CONFIRMED、若是才原子轉為 CONSUMED 並清空 issued_jwt」的等價原子操作——不能只是分成
     * 「先讀再寫」兩步驟（那樣兩個執行緒都可能在檢查通過後才互相搶著寫，各自都以為自己是贏家），
     * 這裡整段檢查+變更都在同一個 synchronized 區塊內完成，同一時間只有一個執行緒能通過。
     */
    @Override
    public synchronized Optional<String> consumeConfirmedJwt(String xdevId, Instant consumedAt) {
        CrossDeviceSession session = byXdevId.get(xdevId);
        if (session == null || session.getStatus() != CrossDeviceSessionStatus.CONFIRMED) {
            return Optional.empty();
        }
        String issuedJwt = session.getIssuedJwt();
        session.setStatus(CrossDeviceSessionStatus.CONSUMED);
        session.setConsumedAt(consumedAt);
        session.setIssuedJwt(null);
        session.setUpdatedAt(consumedAt);
        return Optional.ofNullable(issuedJwt);
    }
}
