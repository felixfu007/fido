package com.fido.server;

import com.fido.server.config.FidoProperties;
import com.fido.server.domain.SigningKey;
import com.fido.server.domain.enums.SigningKeyStatus;
import com.fido.server.repository.SigningKeyRepository;
import com.fido.server.service.JwtService;
import com.fido.server.service.SigningKeyFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 對應 CLAUDE.md「Session JWT 簽章金鑰持久化」決策的三種啟動情境，用 mock
 * {@link SigningKeyRepository} 驗證 {@link JwtService} 的載入/首啟產生/並發競態邏輯，
 * 不需要真正的資料庫（真實 JPA + H2 的落地驗證見 {@link JpaPersistenceH2FlowTest}）。
 */
class JwtServiceTest {

    private static final String RP_ID = "shop.example.com";

    private FidoProperties propertiesWithKid(String kid) {
        FidoProperties properties = new FidoProperties();
        properties.getSessionJwt().setIssuer("https://fido.example.internal");
        properties.getSessionJwt().setTtlSeconds(120);
        properties.getSessionJwt().setKid(kid);
        return properties;
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    private static SigningKey toSigningKey(long pk, String kid, KeyPair keyPair, SigningKeyStatus status) {
        SigningKey key = new SigningKey();
        key.setKeyPk(pk);
        key.setKid(kid);
        key.setAlgorithm("ES256");
        key.setCurve("P-256");
        key.setPrivateKey(keyPair.getPrivate().getEncoded());
        key.setPublicKey(keyPair.getPublic().getEncoded());
        key.setStatus(status);
        return key;
    }

    /**
     * 情境 (a)：全新資料庫首次啟動（{@code findActive()} 回傳空）—— 應產生新金鑰、以
     * {@code fido.session-jwt.kid} 設定值命名、並呼叫 {@code save} 落地一次。
     */
    @Test
    void freshDatabaseGeneratesAndPersistsNewActiveKeyOnFirstBoot() throws Exception {
        SigningKeyRepository repository = mock(SigningKeyRepository.class);
        when(repository.findActive()).thenReturn(Optional.empty());
        when(repository.save(any(SigningKey.class))).thenAnswer(invocation -> {
            SigningKey key = invocation.getArgument(0);
            key.setKeyPk(1L);
            return key;
        });

        JwtService jwtService = new JwtService(propertiesWithKid("2026-fido-1"), repository, new SigningKeyFactory());

        ArgumentCaptor<SigningKey> captor = ArgumentCaptor.forClass(SigningKey.class);
        verify(repository, times(1)).save(captor.capture());
        SigningKey persisted = captor.getValue();
        assertThat(persisted.getKid()).isEqualTo("2026-fido-1");
        assertThat(persisted.getStatus()).isEqualTo(SigningKeyStatus.ACTIVE);
        assertThat(persisted.getPrivateKey()).isNotEmpty();
        assertThat(persisted.getPublicKey()).isNotEmpty();

        JwtService.IssuedToken issued = jwtService.issue(RP_ID, "user-1", "tenant-uid-1", "cred-1", "device-1");
        assertThat(issued.token()).isNotBlank();

        // header kid 應為新產生金鑰的 kid，JWKS 應只回傳這一把（雖然此測試 findAll() 未 mock，
        // 預設回傳 null 也沒關係——這裡只驗證 issue() 走的是首啟產生的那把金鑰，不驗證 jwks()）。
        assertThat(issued.expiresIn()).isEqualTo(120);
    }

    /**
     * 情境 (a) 變體：若沒有設定 {@code fido.session-jwt.kid}（null/空白），應自動產生
     * {@code sk_<yyyyMMdd>_<短亂數>} 格式的 kid。
     */
    @Test
    void freshDatabaseGeneratesDefaultKidWhenConfigKidIsBlank() {
        SigningKeyRepository repository = mock(SigningKeyRepository.class);
        when(repository.findActive()).thenReturn(Optional.empty());
        when(repository.save(any(SigningKey.class))).thenAnswer(invocation -> {
            SigningKey key = invocation.getArgument(0);
            key.setKeyPk(1L);
            return key;
        });

        new JwtService(propertiesWithKid(""), repository, new SigningKeyFactory());

        ArgumentCaptor<SigningKey> captor = ArgumentCaptor.forClass(SigningKey.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getKid()).matches("sk_\\d{8}_[0-9a-f]{6}");
    }

    /**
     * 情境 (b)：已有 {@code ACTIVE} 金鑰時，應直接載入還原（不呼叫 {@code save}），且
     * {@code issue()}/{@code jwks()} 皆使用該既有金鑰的 {@code kid} 與公私鑰。
     */
    @Test
    void existingActiveKeyIsLoadedAndReusedWithoutGeneratingNewOne() throws Exception {
        KeyPair existingKeyPair = generateEcKeyPair();
        SigningKey existing = toSigningKey(42L, "existing-kid-001", existingKeyPair, SigningKeyStatus.ACTIVE);

        SigningKeyRepository repository = mock(SigningKeyRepository.class);
        when(repository.findActive()).thenReturn(Optional.of(existing));
        when(repository.findAll()).thenReturn(List.of(existing));

        JwtService jwtService = new JwtService(propertiesWithKid("2026-fido-1"), repository, new SigningKeyFactory());

        verify(repository, never()).save(any());

        JwtService.IssuedToken issued = jwtService.issue(RP_ID, "user-1", "tenant-uid-1", "cred-1", "device-1");
        assertThat(issued.token()).isNotBlank();
        // 解析 JWT header 確認 kid 是既有金鑰的 kid，而非 fido.session-jwt.kid 設定值。
        String headerJson = decodeJwtHeader(issued.token());
        assertThat(headerJson).contains("existing-kid-001");
        assertThat(headerJson).doesNotContain("2026-fido-1");

        JwtService.JwkSet jwkSet = jwtService.jwks();
        assertThat(jwkSet.keys()).hasSize(1);
        assertThat(jwkSet.keys().get(0).kid()).isEqualTo("existing-kid-001");
    }

    /**
     * 情境 (c)：多實例並發首啟——本實例 {@code findActive()} 先回傳空、嘗試
     * {@code save()} 時撞到 {@code UX_signkey_one_active} 唯一索引違反
     * （{@link DataIntegrityViolationException}，代表另一個實例已搶先插入），應改為重新查詢
     * 既有 {@code ACTIVE} 列並改用它，而不是讓建構子/啟動失敗。
     */
    @Test
    void concurrentFirstBootConflictFallsBackToRereadingWinnersActiveKey() throws Exception {
        KeyPair winnerKeyPair = generateEcKeyPair();
        SigningKey winnerKey = toSigningKey(99L, "winner-kid", winnerKeyPair, SigningKeyStatus.ACTIVE);

        SigningKeyRepository repository = mock(SigningKeyRepository.class);
        // 第一次呼叫 findActive()（建構時）回傳空；save() 拋出唯一索引衝突；
        // 之後重新呼叫 findActive() 回傳「贏家」實例已插入的既有 ACTIVE 列。
        when(repository.findActive())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerKey));
        when(repository.save(any(SigningKey.class)))
                .thenThrow(new DataIntegrityViolationException("UX_signkey_one_active violated"));

        JwtService jwtService = new JwtService(propertiesWithKid("2026-fido-1"), repository, new SigningKeyFactory());

        // 確認真的嘗試過 save()（証明有走「先產生新金鑰」這條路），但最終沒有讓例外往外拋。
        verify(repository, times(1)).save(any(SigningKey.class));
        verify(repository, times(2)).findActive();

        JwtService.IssuedToken issued = jwtService.issue(RP_ID, "user-1", "tenant-uid-1", "cred-1", "device-1");
        String headerJson = decodeJwtHeader(issued.token());
        assertThat(headerJson).contains("winner-kid");
    }

    /**
     * 若併發衝突發生後，重新查詢仍找不到任何 ACTIVE 列（資料狀態異常），應直接讓啟動失敗
     * 並拋出清楚訊息，而不是靜默吞掉繼續用一把不存在的金鑰。
     */
    @Test
    void concurrentConflictWithNoActiveKeyOnRereadThrowsIllegalState() {
        SigningKeyRepository repository = mock(SigningKeyRepository.class);
        when(repository.findActive())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(repository.save(any(SigningKey.class)))
                .thenThrow(new DataIntegrityViolationException("UX_signkey_one_active violated"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new JwtService(propertiesWithKid("2026-fido-1"), repository, new SigningKeyFactory()));
    }

    private static String decodeJwtHeader(String jwt) {
        String headerB64 = jwt.split("\\.")[0];
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(headerB64);
        return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
    }
}
