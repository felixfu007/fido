package com.fido.server.service;

import com.fido.server.domain.AuthChallenge;
import com.fido.server.domain.BoundDevice;
import com.fido.server.domain.FidoCredential;
import com.fido.server.domain.FidoUserRef;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.AuditOutcome;
import com.fido.server.domain.enums.CeremonyType;
import com.fido.server.domain.enums.RecordStatus;
import com.fido.server.dto.request.RegistrationOptionsRequest;
import com.fido.server.dto.request.RegistrationResultRequest;
import com.fido.server.dto.response.CredentialDescriptor;
import com.fido.server.dto.response.RegistrationOptionsResponse;
import com.fido.server.dto.response.RegistrationOptionsResponse.AuthenticatorSelection;
import com.fido.server.dto.response.RegistrationOptionsResponse.PubKeyCredParam;
import com.fido.server.dto.response.RegistrationOptionsResponse.PublicKeyCredentialCreationOptions;
import com.fido.server.dto.response.RegistrationOptionsResponse.RpEntity;
import com.fido.server.dto.response.RegistrationOptionsResponse.UserEntity;
import com.fido.server.dto.response.RegistrationResultResponse;
import com.fido.server.dto.response.RegistrationResultResponse.DeviceInfo;
import com.fido.server.exception.ApiException;
import com.fido.server.exception.ErrorCode;
import com.fido.server.repository.BoundDeviceRepository;
import com.fido.server.repository.FidoCredentialRepository;
import com.fido.server.repository.FidoUserRefRepository;
import com.fido.server.webauthn.AndroidKeyAttestationChainValidator;
import com.fido.server.webauthn.AttestationChainResult;
import com.fido.server.webauthn.AttestationObjectParser;
import com.fido.server.webauthn.AttestationStatementVerifier;
import com.fido.server.webauthn.ClientData;
import com.fido.server.webauthn.ClientDataParser;
import com.fido.server.webauthn.CoseKeyParser;
import com.fido.server.webauthn.ParsedAttestationObject;
import com.fido.server.webauthn.WebAuthnValidationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 對應 api-contract.md 第 2 節：註冊流程 API（WebAuthn Attestation Ceremony）。
 *
 * <p>已「真實」實作：challenge 產生與 60 秒時效檢核（{@link ChallengeService}）、
 * clientDataJSON 解析與 type/challenge/origin 驗證、attestationObject 頂層 CBOR 解碼與
 * authenticatorData 結構解析、attStmt 簽章驗證（{@link AttestationStatementVerifier}，預設
 * 實作見 {@link com.fido.server.webauthn.RealAttestationStatementVerifier}）、Android Key
 * Attestation 憑證鏈驗證與 securityLevel 判讀（{@link AndroidKeyAttestationChainValidator}，
 * 預設實作見 {@link com.fido.server.webauthn.RealAndroidKeyAttestationChainValidator}）、
 * 防重複註冊、user_handle 產生與 fido_user_ref upsert、資料落表
 * （fido_credentials / bound_devices）、audit_log 寫入。
 */
@Service
public class RegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final FidoUserRefRepository fidoUserRefRepository;
    private final FidoCredentialRepository fidoCredentialRepository;
    private final BoundDeviceRepository boundDeviceRepository;
    private final ChallengeService challengeService;
    private final AuditService auditService;
    private final ClientDataParser clientDataParser;
    private final AttestationObjectParser attestationObjectParser;
    private final CoseKeyParser coseKeyParser;
    private final AttestationStatementVerifier attestationStatementVerifier;
    private final AndroidKeyAttestationChainValidator chainValidator;
    private final OriginValidator originValidator;
    private final TransportsCodec transportsCodec;

    public RegistrationService(FidoUserRefRepository fidoUserRefRepository,
                                FidoCredentialRepository fidoCredentialRepository,
                                BoundDeviceRepository boundDeviceRepository,
                                ChallengeService challengeService,
                                AuditService auditService,
                                ClientDataParser clientDataParser,
                                AttestationObjectParser attestationObjectParser,
                                CoseKeyParser coseKeyParser,
                                AttestationStatementVerifier attestationStatementVerifier,
                                AndroidKeyAttestationChainValidator chainValidator,
                                OriginValidator originValidator,
                                TransportsCodec transportsCodec) {
        this.fidoUserRefRepository = fidoUserRefRepository;
        this.fidoCredentialRepository = fidoCredentialRepository;
        this.boundDeviceRepository = boundDeviceRepository;
        this.challengeService = challengeService;
        this.auditService = auditService;
        this.clientDataParser = clientDataParser;
        this.attestationObjectParser = attestationObjectParser;
        this.coseKeyParser = coseKeyParser;
        this.attestationStatementVerifier = attestationStatementVerifier;
        this.chainValidator = chainValidator;
        this.originValidator = originValidator;
        this.transportsCodec = transportsCodec;
    }

    public RegistrationOptionsResponse createOptions(Tenant tenant, RegistrationOptionsRequest request) {
        FidoUserRef userRef = fidoUserRefRepository
                .findByTenantIdAndExternalUserId(tenant.getTenantId(), request.externalUserId())
                .orElseGet(() -> createUserRef(tenant, request));

        AuthChallenge challenge = challengeService.create(tenant, userRef.getUserRefId(), CeremonyType.REGISTRATION);

        List<CredentialDescriptor> excludeCredentials = fidoCredentialRepository
                .findByUserRefIdAndStatus(userRef.getUserRefId(), RecordStatus.ACTIVE).stream()
                .map(c -> CredentialDescriptor.publicKey(B64URL.encodeToString(c.getCredentialId()),
                        transportsCodec.decode(c.getTransports())))
                .toList();

        RpEntity rp = new RpEntity(tenant.getRpId(), tenant.getName());
        UserEntity user = new UserEntity(B64URL.encodeToString(userRef.getUserHandle()),
                request.externalUserId(), request.displayName());
        List<PubKeyCredParam> pubKeyCredParams = List.of(
                new PubKeyCredParam("public-key", -7),   // ES256
                new PubKeyCredParam("public-key", -257)  // RS256
        );
        AuthenticatorSelection selection = new AuthenticatorSelection("platform", "required", true, "required");

        PublicKeyCredentialCreationOptions publicKey = new PublicKeyCredentialCreationOptions(
                rp, user, B64URL.encodeToString(challenge.getChallenge()), pubKeyCredParams,
                60000L, "direct", selection, excludeCredentials);

        auditService.record(tenant.getTenantId(), userRef.getUserRefId(), null, "REG_OPTIONS_ISSUED",
                AuditOutcome.SUCCESS, Map.of("ceremonyId", challenge.getCeremonyId()));

        return new RegistrationOptionsResponse(challenge.getCeremonyId(), publicKey);
    }

    public RegistrationResultResponse verifyResult(Tenant tenant, RegistrationResultRequest request) {
        AuthChallenge challenge = challengeService.requireValid(request.ceremonyId(), tenant, CeremonyType.REGISTRATION);

        FidoUserRef userRef = fidoUserRefRepository.findById(challenge.getUserRefId())
                .orElseThrow(() -> new ApiException(ErrorCode.CHALLENGE_NOT_FOUND, "Ceremony not found."));
        if (!userRef.getExternalUserId().equals(request.externalUserId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "externalUserId does not match this ceremony.");
        }

        ClientData clientData;
        byte[] clientDataJsonBytes;
        try {
            clientDataJsonBytes = clientDataParser.decodeRawJson(request.credential().response().clientDataJSON());
            clientData = clientDataParser.parse(request.credential().response().clientDataJSON());
        } catch (WebAuthnValidationException e) {
            recordFailure(tenant, userRef, "REG_FAIL", "clientDataJSON parse error: " + e.getMessage());
            throw new ApiException(ErrorCode.ATTESTATION_INVALID, "Invalid clientDataJSON: " + e.getMessage());
        }

        if (!"webauthn.create".equals(clientData.type())) {
            recordFailure(tenant, userRef, "REG_FAIL", "unexpected clientDataJSON.type=" + clientData.type());
            throw new ApiException(ErrorCode.ATTESTATION_INVALID, "clientDataJSON.type must be webauthn.create.");
        }
        if (!challengeMatches(clientData.challengeBase64Url(), challenge.getChallenge())) {
            recordFailure(tenant, userRef, "REG_FAIL", "challenge mismatch");
            throw new ApiException(ErrorCode.ATTESTATION_INVALID, "clientDataJSON.challenge does not match issued challenge.");
        }
        OriginValidator.OriginCheckResult originCheck = originValidator.check(clientData.origin(), tenant);
        if (!originCheck.allowed()) {
            recordFailure(tenant, userRef, "REG_FAIL", "origin not allowed: " + clientData.origin());
            throw new ApiException(ErrorCode.ORIGIN_NOT_ALLOWED,
                    "Origin is not in tenant's allowed origin list.");
        }

        ParsedAttestationObject parsed;
        try {
            parsed = attestationObjectParser.parse(request.credential().response().attestationObject());
        } catch (WebAuthnValidationException e) {
            recordFailure(tenant, userRef, "REG_FAIL", "attestationObject parse error: " + e.getMessage());
            throw new ApiException(ErrorCode.ATTESTATION_INVALID, "Invalid attestationObject: " + e.getMessage());
        }
        if (!parsed.authenticatorData().attestedCredentialDataIncluded()
                || parsed.authenticatorData().credentialPublicKeyCose() == null) {
            recordFailure(tenant, userRef, "REG_FAIL", "attestedCredentialData missing");
            throw new ApiException(ErrorCode.ATTESTATION_INVALID, "attestationObject missing attestedCredentialData.");
        }

        byte[] clientDataHash = sha256(clientDataJsonBytes);
        boolean stmtValid = attestationStatementVerifier.verify(parsed, clientDataHash);
        if (!stmtValid) {
            recordFailure(tenant, userRef, "REG_FAIL", "attestation statement signature invalid");
            throw new ApiException(ErrorCode.ATTESTATION_INVALID, "Attestation statement signature verification failed.");
        }

        AttestationChainResult chainResult = chainValidator.validate(parsed, challenge.getChallenge());
        if (!chainResult.chainValid()) {
            recordFailure(tenant, userRef, "REG_FAIL",
                    "attestation chain invalid: " + chainResult.detectedLevelRaw());
            throw new ApiException(ErrorCode.ATTESTATION_CHAIN_INVALID, "Android Key Attestation certificate chain is invalid.");
        }
        if (chainResult.securityLevel() == null) {
            recordFailure(tenant, userRef, "REG_FAIL", "hardware security not met: " + chainResult.detectedLevelRaw());
            throw new ApiException(ErrorCode.HARDWARE_SECURITY_NOT_MET,
                    "Registration requires TEE or StrongBox hardware-backed key.",
                    Map.of("detectedLevel", String.valueOf(chainResult.detectedLevelRaw())));
        }

        byte[] credentialIdBytes = decodeB64Url(request.credential().id(), "credential.id");
        byte[] credentialIdSha256 = sha256(credentialIdBytes);
        Optional<FidoCredential> existing = fidoCredentialRepository
                .findByTenantIdAndCredentialIdSha256(tenant.getTenantId(), credentialIdSha256);
        if (existing.isPresent()) {
            recordFailure(tenant, userRef, "REG_FAIL", "credential already exists");
            throw new ApiException(ErrorCode.CREDENTIAL_ALREADY_EXISTS, "This credential has already been registered.");
        }

        int coseAlg = coseKeyParser.extractAlg(parsed.authenticatorData().credentialPublicKeyCose());
        List<String> transports = request.credential().response().transports();

        FidoCredential credential = new FidoCredential();
        credential.setUserRefId(userRef.getUserRefId());
        credential.setTenantId(tenant.getTenantId());
        credential.setCredentialId(credentialIdBytes);
        credential.setCredentialIdSha256(credentialIdSha256);
        credential.setPublicKey(parsed.authenticatorData().credentialPublicKeyCose());
        credential.setCoseAlg(coseAlg);
        credential.setSignCount(0L);
        credential.setAaguid(parsed.authenticatorData().aaguid());
        credential.setTransports(transportsCodec.encode(transports));
        credential.setAttestationFormat(parsed.fmt());
        credential.setStatus(RecordStatus.ACTIVE);
        credential = fidoCredentialRepository.save(credential);

        BoundDevice device = new BoundDevice();
        device.setCredentialPk(credential.getCredentialPk());
        device.setUserRefId(userRef.getUserRefId());
        device.setTenantId(tenant.getTenantId());
        device.setDeviceName(request.deviceLabel() != null ? request.deviceLabel() : "FIDO Device");
        // model / osVersion：api-contract.md 2.2 request 目前未定義對應欄位（回應範例雖含
        // model/osVersion，但無輸入來源）。此為規格缺口，暫存 null，待 systems-analyst 補完
        // 合約（例如由前端 User-Agent / WebAuthn extension 帶入）後再串接。
        device.setModel(null);
        device.setOsVersion(null);
        device.setSecurityLevel(chainResult.securityLevel());
        device.setAttestationSummary(buildAttestationSummary(chainResult));
        device.setStatus(RecordStatus.ACTIVE);
        device = boundDeviceRepository.save(device);

        challengeService.consume(challenge);

        auditService.record(tenant.getTenantId(), userRef.getUserRefId(), device.getDevicePk(), "REG_SUCCESS",
                AuditOutcome.SUCCESS, Map.of("deviceId", device.getDeviceId().toString(),
                        "securityLevel", chainResult.securityLevel().name(),
                        "originType", originCheck.originType().name()));

        DeviceInfo deviceInfo = new DeviceInfo(
                device.getDeviceName(),
                credential.getAaguid() != null ? aaguidToUuidString(credential.getAaguid()) : null,
                device.getSecurityLevel().name(),
                DateTimeFormatter.ISO_INSTANT.format(device.getCreatedAt()));

        return new RegistrationResultResponse(
                B64URL.encodeToString(credential.getCredentialId()),
                device.getDeviceId().toString(),
                deviceInfo,
                credential.getSignCount());
    }

    private FidoUserRef createUserRef(Tenant tenant, RegistrationOptionsRequest request) {
        byte[] userHandle = new byte[32];
        RANDOM.nextBytes(userHandle);
        FidoUserRef userRef = new FidoUserRef();
        userRef.setTenantId(tenant.getTenantId());
        userRef.setExternalUserId(request.externalUserId());
        userRef.setUserHandle(userHandle);
        userRef.setDisplayName(request.displayName());
        return fidoUserRefRepository.save(userRef);
    }

    private void recordFailure(Tenant tenant, FidoUserRef userRef, String eventType, String reason) {
        auditService.record(tenant.getTenantId(), userRef == null ? null : userRef.getUserRefId(), null,
                eventType, AuditOutcome.FAILURE, Map.of("reason", reason));
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

    private static String aaguidToUuidString(byte[] aaguid) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(aaguid);
        long high = bb.getLong();
        long low = bb.getLong();
        return new java.util.UUID(high, low).toString();
    }

    private static String buildAttestationSummary(AttestationChainResult chainResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chainValid", chainResult.chainValid());
        summary.put("securityLevel", chainResult.detectedLevelRaw());
        return summary.toString();
    }
}
