package com.fido.server.repository;

import com.fido.server.domain.SigningKey;

import java.util.List;
import java.util.Optional;

/**
 * `signing_keys` 表的 persistence 介面（db-schema.md 第 10 節 / DB18）。
 *
 * <p>主要用途：{@code com.fido.server.service.JwtService} 啟動時載入/建立唯一 {@code ACTIVE}
 * 金鑰、{@code jwks()} 回傳所有 {@code ACTIVE}+{@code RETIRED} 金鑰；
 * {@code com.fido.server.admin.AdminCliRunner} 的 {@code rotate-signing-key} 指令手動輪替。
 *
 * <p>{@link #save} 對「新插入一把 {@code status=ACTIVE} 的列」在真實資料庫（JPA 實作）上，
 * 若同時已存在另一把 {@code ACTIVE} 列，會因 filtered unique index
 * {@code UX_signkey_one_active} 違反而拋出
 * {@link org.springframework.dao.DataIntegrityViolationException}——這是刻意設計的多實例
 * 首次啟動並發競態防護，呼叫端（{@code JwtService}）需捕捉此例外並重新呼叫 {@link #findActive()}
 * 改用既有的 ACTIVE 列。
 */
public interface SigningKeyRepository {

    /**
     * 回傳目前唯一的 {@code ACTIVE} 金鑰（若存在）。正常情況下最多一筆。
     */
    Optional<SigningKey> findActive();

    /**
     * 回傳所有金鑰（{@code ACTIVE}+{@code RETIRED}），供 JWKS 端點發布公鑰使用。
     */
    List<SigningKey> findAll();

    SigningKey save(SigningKey signingKey);
}
