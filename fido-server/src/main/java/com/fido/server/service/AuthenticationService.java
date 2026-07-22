package com.fido.server.service;

import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.FidoUserRef;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.domain.enums.CeremonyType;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.domain.enums.RevokedReason;
import com.fido.server.dto.request.AuthenticationOptionsRequest;
import com.fido.server.dto.request.AuthenticationResultRequest;
import com.fido.server.dto.response.AuthenticationOptionsResponse;
import com.fido.server.dto.response.AuthenticationOptionsResponse.AssertionOptions;
import com.fido.server.dto.response.AuthenticationResultResponse;
import com.fido.server.dto.response.AuthenticationResultResponse.SessionInfo;
import com.fido.server.dto.response.CredentialDescriptor;
import com.fido.server.exception.ApiException;
import com.fido.server.exception.ErrorCode;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.FidoUserRefRepository;
import com.fido.server.webauthn.AssertionSignatureVerifier;
import com.fido.server.webauthn.AuthenticatorData;
import com.fido.server.webauthn.AuthenticatorDataParser;
import com.fido.server.webauthn.ClientData;
import com.fido.server.webauthn.ClientDataParser;
import com.fido.server.webauthn.WebAuthnValidationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 對應 api-contract.md 第 3 節：登入流程 API（WebAuthn Assertion Ceremony）。
 *
 * <p>已「真實」實作：challenge 60 秒時效檢核與一次性消費、clientDataJSON 解析與
 * type/challenge/origin 驗證、authenticatorData 結構解析（含 rpIdHash 與 UV flag 的真實比對，
 * 因為這兩者是純 bytes 結構讀取，不需密碼學）、assertion 簽章驗證
 * （{@link AssertionSignatureVerifier}，預設實作見
 * {@link com.fido.server.webauthn.RealAssertionSignatureVerifier}：由 COSE 公鑰還原
 * PublicKey 後驗簽）、**sign counter 檢查與自動撤銷**（核心語意）、
 * 防帳號列舉（externalUserId 帶入但查無使用者/憑證時仍回 200 + 空 allowCredentials）、
 * session JWT 簽發、audit_log 寫入。
 */
@Service
public class AuthenticationService {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final FidoUserRefRepository fidoUserRefRepository;
    private final FidoCredentialRepository fidoCredentialRepository;
    private final BoundDeviceRepository boundDeviceRepository;
    private final ChallengeService challengeService;
    private final AuditService auditService;
    private final ClientDataParser clientDataParser;
    private final OriginValidator originValidator;
    private final TransportsCodec transportsCodec;
    private final AssertionSignatureVerifier assertionSignatureVerifier;
    private final JwtService jwtService;

    public AuthenticationService(FidoUserRefRepository fidoUserRefRepository,
                                  FidoCredentialRepository fidoCredentialRepository,
                                  BoundDeviceRepository boundDeviceRepository,
                                  ChallengeService challengeService,
                                  AuditService auditService,
                                  ClientDataParser clientDataParser,
                                  OriginValidator originValidator,
                                  TransportsCodec transportsCodec,
                                  AssertionSignatureVerifier assertionSignatureVerifier,
                                  JwtService jwtService) {
        this.fidoUserRefRepository = fidoUserRefRepository;
        this.fidoCredentialRepository = fidoCredentialRepository;
        this.boundDeviceRepository = boundDeviceRepository;
        this.challengeService = challengeService;
        this.auditService = auditService;
        this.clientDataParser = clientDataParser;
        this.originValidator = originValidator;
        this.transportsCodec = transportsCodec;
        this.assertionSignatureVerifier = assertionSignatureVerifier;
        this.jwtService = jwtService;
    }

    public AuthenticationOptionsResponse createOptions(Tenant tenant, AuthenticationOptionsRequest request) {
        Long userRefId = null;
        List<CredentialDescriptor> allowCredentials = List.of();

        if (request.externalUserId() != null && !request.externalUserId().isBlank()) {
            Optional<FidoUserRef> userRefOpt = fidoUserRefRepository
                    .findByTenantIdAndExternalUserId(tenant.getTenantId(), request.externalUserId());
            if (userRefOpt.isPresent()) {
                FidoUserRef userRef = userRefOpt.get();
                userRefId = userRef.getUserRefId();
                allowCredentials = fidoCredentialRepository
                        .findByUserRefIdAndStatus(userRef.getUserRefId(), RecordStatus.ACTIVE).stream()
                        .map(c -> CredentialDescriptor.publicKey(B64URL.encodeToString(c.getCredentialId()),
                                transportsCodec.decode(c.getTransports())))
                        .toList();
            }
            // 使用者不存在或無 active 憑證：不丟 404，allowCredentials 維持空陣列
            // （api-contract.md D7 防帳號列舉，讓 ceremony 自然於前端失敗）。
        }

        AuthChallenge challenge = challengeService.create(tenant, userRefId, CeremonyType.AUTHENTICATION);

        AssertionOptions options = new AssertionOptions(
                B64URL.encodeToString(challenge.getChallenge()), 60000L, tenant.getRpId(), "required", allowCredentials);

        auditService.record(tenant.getTenantId(), userRefId, null, "AUTH_OPTIONS_ISSUED",
                AuditOutcome.SUCCESS, Map.of("ceremonyId", challenge.getCeremonyId()));

        return new AuthenticationOptionsResponse(challenge.getCeremonyId(), options);
    }

    public AuthenticationResultResponse verifyResult(Tenant tenant, AuthenticationResultRequest request) {
        AuthChallenge challenge = challengeService.requireValid(request.ceremonyId(), tenant, CeremonyType.AUTHENTICATION);

        byte[] credentialIdBytes = decodeB64Url(request.credential().id(), "credential.id");
        byte[] credentialIdSha256 = sha256(credentialIdBytes);
        FidoCredential credential = fidoCredentialRepository
                .findByTenantIdAndCredentialIdSha256(tenant.getTenantId(), credentialIdSha256)
                .orElseThrow(() -> {
                    auditService.record(tenant.getTenantId(), null, null, "AUTH_FAIL", AuditOutcome.FAILURE,
                            Map.of("reason", "unknown credential"));
                    return new ApiException(ErrorCode.ASSERTION_INVALID, "Unknown credential.");
                });

        FidoUserRef userRef = fidoUserRefRepository.findById(credential.getUserRefId())
                .orElseThrow(() -> new ApiException(ErrorCode.ASSERTION_INVALID, "Unknown credential."));

        if (challenge.getUserRefId() != null && !challenge.getUserRefId().equals(userRef.getUserRefId())) {
            recordFailure(tenant, userRef, "credential does not match ceremony's allowCredentials scope");
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "Credential does not match this ceremony.");
        }

        if (credential.getStatus() != RecordStatus.ACTIVE) {
            recordFailure(tenant, userRef, "credential revoked");
            throw new ApiException(ErrorCode.CREDENTIAL_REVOKED, "This credential has been revoked.");
        }

        ClientData clientData;
        byte[] clientDataJsonBytes;
        try {
            clientDataJsonBytes = clientDataParser.decodeRawJson(request.credential().response().clientDataJSON());
            clientData = clientDataParser.parse(request.credential().response().clientDataJSON());
        } catch (WebAuthnValidationException e) {
            recordFailure(tenant, userRef, "clientDataJSON parse error: " + e.getMessage());
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "Invalid clientDataJSON: " + e.getMessage());
        }
        if (!"webauthn.get".equals(clientData.type())) {
            recordFailure(tenant, userRef, "unexpected clientDataJSON.type=" + clientData.type());
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "clientDataJSON.type must be webauthn.get.");
        }
        if (!challengeMatches(clientData.challengeBase64Url(), challenge.getChallenge())) {
            recordFailure(tenant, userRef, "challenge mismatch");
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "clientDataJSON.challenge does not match issued challenge.");
        }
        if (!originValidator.isAllowed(clientData.origin(), tenant.getExpectedOrigin())) {
            recordFailure(tenant, userRef, "origin mismatch: " + clientData.origin());
            throw new ApiException(ErrorCode.RP_ID_MISMATCH, "Origin does not match tenant's expected origin.");
        }

        byte[] authDataRaw = decodeB64Url(request.credential().response().authenticatorData(), "authenticatorData");
        AuthenticatorData authData;
        try {
            authData = AuthenticatorDataParser.parse(authDataRaw);
        } catch (WebAuthnValidationException e) {
            recordFailure(tenant, userRef, "authenticatorData parse error: " + e.getMessage());
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "Invalid authenticatorData: " + e.getMessage());
        }

        // rpIdHash 是 authenticatorData 固定版面的前 32 bytes（非 CBOR），可直接以
        // SHA-256(tenant.rpId) 真實比對，不需 attestation 憑證鏈或簽章驗證。
        byte[] expectedRpIdHash = sha256(tenant.getRpId().getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(authData.rpIdHash(), expectedRpIdHash)) {
            recordFailure(tenant, userRef, "authenticatorData rpIdHash mismatch");
            throw new ApiException(ErrorCode.RP_ID_MISMATCH, "authenticatorData rpIdHash does not match tenant RP ID.");
        }
        if (!authData.userVerified()) {
            recordFailure(tenant, userRef, "user verification flag not set");
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "User verification is required but was not performed.");
        }

        byte[] signature = decodeB64Url(request.credential().response().signature(), "signature");
        boolean sigValid = assertionSignatureVerifier.verify(
                credential.getPublicKey(), authDataRaw, clientDataJsonBytes, signature, credential.getCoseAlg());
        if (!sigValid) {
            recordFailure(tenant, userRef, "assertion signature invalid");
            throw new ApiException(ErrorCode.ASSERTION_INVALID, "Assertion signature verification failed.");
        }

        BoundDevice device = boundDeviceRepository.findByCredentialPk(credential.getCredentialPk())
                .orElseThrow(() -> new ApiException(ErrorCode.ASSERTION_INVALID, "Bound device not found for credential."));

        long newCount = authData.signCount();
        long storedCount = credential.getSignCount();
        Instant now = Instant.now();

        if (newCount > storedCount || (newCount == 0 && storedCount == 0)) {
            if (newCount > storedCount) {
                credential.setSignCount(newCount);
            }
            credential.setLastUsedAt(now);
            fidoCredentialRepository.save(credential);
            device.setLastUsedAt(now);
            boundDeviceRepository.save(device);
        } else {
            // sign counter 倒退：立即自動撤銷 credential 與 device（CLAUDE.md「簽章異常自動撤銷」落地）。
            credential.setStatus(RecordStatus.REVOKED);
            credential.setRevokedAt(now);
            credential.setRevokedReason(RevokedReason.COUNTER_REGRESSION);
            fidoCredentialRepository.save(credential);
            device.setStatus(RecordStatus.REVOKED);
            device.setRevokedAt(now);
            device.setRevokedReason(RevokedReason.COUNTER_REGRESSION);
            boundDeviceRepository.save(device);

            auditService.record(tenant.getTenantId(), userRef.getUserRefId(), device.getDevicePk(),
                    "AUTO_REVOKE_COUNTER_REGRESSION", AuditOutcome.FAILURE,
                    Map.of("storedCount", storedCount, "newCount", newCount));

            throw new ApiException(ErrorCode.SIGN_COUNTER_REGRESSION,
                    "Sign counter regression detected; credential has been automatically revoked.");
        }

        JwtService.IssuedToken issued = jwtService.issue(
                tenant.getRpId(), userRef.getExternalUserId(), tenant.getTenantUid().toString(),
                B64URL.encodeToString(credential.getCredentialId()), device.getDeviceId().toString());

        auditService.record(tenant.getTenantId(), userRef.getUserRefId(), device.getDevicePk(), "AUTH_SUCCESS",
                AuditOutcome.SUCCESS, Map.of("jti", issued.jti()));

        challengeService.consume(challenge);

        SessionInfo session = new SessionInfo(issued.token(), "Bearer", issued.expiresIn());
        return new AuthenticationResultResponse(true, userRef.getExternalUserId(),
                B64URL.encodeToString(credential.getCredentialId()), device.getDeviceId().toString(), session);
    }

    private void recordFailure(Tenant tenant, FidoUserRef userRef, String reason) {
        auditService.record(tenant.getTenantId(), userRef == null ? null : userRef.getUserRefId(), null,
                "AUTH_FAIL", AuditOutcome.FAILURE, Map.of("reason", reason));
    }

    private boolean challengeMatches(String clientChallengeB64Url, byte[] storedChallenge) {
        try {
            byte[] clientChallenge = B64URL_DEC.decode(clientChallengeB64Url);
            return MessageDigest.isEqual(clientChallenge, storedChallenge);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] decodeB64Url(String value, String fieldName) {
        try {
            return B64URL_DEC.decode(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, fieldName + " is not valid base64url.");
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
