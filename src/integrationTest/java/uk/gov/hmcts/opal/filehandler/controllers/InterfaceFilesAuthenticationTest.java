package uk.gov.hmcts.opal.filehandler.controllers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.common.io.Resources;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import uk.gov.hmcts.opal.filehandler.authorisation.FileHandlerPermission;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.support.AbstractControllerIntegrationTest;
import uk.gov.hmcts.opal.filehandler.support.UserStateStub;
import uk.gov.hmcts.opal.filehandler.support.UtilBlobStoreService;
import uk.gov.hmcts.opal.generated.model.AddInterfaceFileRequestMetadata;
import uk.gov.hmcts.opal.generated.model.DomainEnumTypes;
import uk.gov.hmcts.opal.generated.model.InterfaceFileEnumInterfaceFile;
import uk.gov.hmcts.opal.generated.model.InterfaceFileTypeEnumInterfaceFile;
import uk.gov.hmcts.opal.generated.model.PaymentTypeEnumTypes;

@ActiveProfiles(value = "authentication-integration", inheritProfiles = false)
@TestPropertySource(properties = {
    "launchdarkly.enabled=false",
    "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
    "opal.common.system-users.users.opal-system-user.client-id=test-system-client",
    "opal.common.system-users.users.opal-system-user.client-secret=test-secret"
})
class InterfaceFilesAuthenticationTest extends AbstractControllerIntegrationTest {

    private static final String BASE_URL = "http://localhost:4553";
    private static final String ISSUER = BASE_URL + "/issuer";
    private static final byte[] FILE_CONTENT = "test interface file".getBytes(StandardCharsets.UTF_8);
    private static final String UPLOAD_FILE_NAME = "authentication-upload.xlsx";
    private static final String UPLOAD_RESOURCE = "azure/data/bteckoh-report/2498-MCPLDB-MOJ-Payments-Report-"
        + "Daily-2026-07-06-06-00-18.xlsx";
    private static final RSAKey SIGNING_KEY = createSigningKey();

    @Autowired
    private InterfaceFilesRepository repository;

    private InterfaceFileEntity file;
    private String humanToken;
    private String systemToken;

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("user.service.url", () -> BASE_URL);
        registry.add("spring.security.oauth2.client.registration.internal-azure-ad.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.client.provider.internal-azure-ad-provider.jwk-set-uri",
            () -> BASE_URL + "/jwks");
    }

    @BeforeEach
    void setUp() {
        humanToken = signedToken("human-subject", false);
        systemToken = signedToken("system-subject", true);
        stubFor(get(urlEqualTo("/jwks"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString())));

        UserStateStub humanState = new UserStateStub(humanToken);
        UserStateStub systemState = new UserStateStub(systemToken);

        systemState.isSystemUser(true);

        stubUserState(humanToken, humanState);
        stubUserState(systemToken, systemState);

        file = interfaceFileEntityTestData.getTypicalInterfaceFile("authentication-test-file.txt");
        file.setOpalDomain(Domain.FILE_HANDLER);
        file.setStatus(Status.SUCCESS);
        file.setFilestoreUuid(UUID.randomUUID());
        file = interfaceFileEntityTestData.saveAndFlushInterfaceFile(file);

        UtilBlobStoreService blobStoreService = new UtilBlobStoreService();
        blobStoreService.storeBlob(FILE_CONTENT, "natwest", file.getFilestoreUuid().toString());
        blobStoreService.createContainerIfNotExists("bteckoh-report");
    }


    @Test
    void humanCanListInterfaceFiles() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/interface-files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.number_of_results").value(1))
            .andExpect(jsonPath("$.interface_files[0].interface_file_id").value(file.getInterfaceFileId()));
    }

    @Test
    void humanCanGetInterfaceFile() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(interfaceFileUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interface_file_id").value(file.getInterfaceFileId()));
    }

    @Test
    void humanCanGetInterfaceFileContent() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(contentUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
            .andExpect(status().isOk())
            .andExpect(content().bytes(FILE_CONTENT));
    }

    @Test
    void humanCanAddInterfaceFile() throws Exception {
        mockMvc.perform(uploadRequest()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.file_name").value(UPLOAD_FILE_NAME))
            .andExpect(jsonPath("$.domain").value("FINES"))
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertEquals(2, repository.count());
    }

    @Test
    void systemUserCanListInterfaceFiles() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/interface-files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.number_of_results").value(1))
            .andExpect(jsonPath("$.interface_files[0].interface_file_id").value(file.getInterfaceFileId()));
    }

    @Test
    void systemUserCanGetInterfaceFile() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(interfaceFileUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interface_file_id").value(file.getInterfaceFileId()));
    }

    @Test
    void systemUserCanGetInterfaceFileContent() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(contentUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemToken))
            .andExpect(status().isOk())
            .andExpect(content().bytes(FILE_CONTENT));
    }

    @Test
    void systemUserCanAddInterfaceFile() throws Exception {
        mockMvc.perform(uploadRequest()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemToken))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.file_name").value(UPLOAD_FILE_NAME))
            .andExpect(jsonPath("$.domain").value("FINES"))
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertEquals(2, repository.count());
    }

    @Test
    void anonymousUserCannotListInterfaceFiles() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/interface-files"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUserCannotGetInterfaceFile() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(interfaceFileUri()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUserCannotGetInterfaceFileContent() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(contentUri()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUserCannotAddInterfaceFile() throws Exception {
        mockMvc.perform(uploadRequest())
            .andExpect(status().isUnauthorized());

        assertEquals(1, repository.count());
    }

    @Test
    void humanWithoutViewPermissionCannotListInterfaceFiles() throws Exception {
        UserStateStub state = new UserStateStub(humanToken);
        state.setupWithNoPermissions();
        stubUserState(humanToken, state);

        mockMvc.perform(MockMvcRequestBuilders.get("/interface-files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void systemUserWithoutViewPermissionCannotListInterfaceFiles() throws Exception {
        UserStateStub state = new UserStateStub(systemToken);
        state.setupWithNoPermissions();
        state.isSystemUser(true);
        stubUserState(systemToken, state);

        mockMvc.perform(MockMvcRequestBuilders.get("/interface-files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void humanUserWithoutCreatePermissionCannotAddInterfaceFile() throws Exception {
        stubWithoutCreatePermission(humanToken, false);

        mockMvc.perform(uploadRequest()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + humanToken))
            .andExpect(status().isForbidden());

        assertEquals(1, repository.count());
    }

    @Test
    void systemUserWithoutCreatePermissionCannotAddInterfaceFile() throws Exception {
        stubWithoutCreatePermission(systemToken, true);
        mockMvc.perform(uploadRequest()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + systemToken))
            .andExpect(status().isForbidden());

        assertEquals(1, repository.count());
    }

    private MockMultipartHttpServletRequestBuilder uploadRequest() throws IOException {

        AddInterfaceFileRequestMetadata metadata =
            AddInterfaceFileRequestMetadata.builder()
                .fileName("authentication-upload.xlsx")
                .businessUnitId((short) 10)
                .paymentType(PaymentTypeEnumTypes.CASH)
                .type(InterfaceFileTypeEnumInterfaceFile.SOURCE)
                .domain(DomainEnumTypes.FINES)
                .shouldPreProcessFile(false)
                .source(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT)
                .target(InterfaceFileEnumInterfaceFile.OPAL)
                .build();

        String metadataJson = objectMapper.writeValueAsString(metadata);

        return MockMvcRequestBuilders.multipart("/interface-files")
            .file(new MockMultipartFile(
                "file",
                UPLOAD_FILE_NAME,
                "application/octet-stream",
                Resources.toByteArray(Resources.getResource(UPLOAD_RESOURCE))))
            .file(new MockMultipartFile(
                "metadata",
                "metadata.json",
                "application/json",
                metadataJson.getBytes(StandardCharsets.UTF_8)));
    }

    private void stubWithoutCreatePermission(String token, boolean systemUser) {
        UserStateStub state = new UserStateStub(token);
        state.getUserState()
            .getDomainBusinessUnitUsers(uk.gov.hmcts.opal.common.user.authorisation.model.Domain.FINES)
            .getBusinessUnitUsers()
            .forEach(businessUnitUser -> businessUnitUser.getPermissions().removeIf(permission ->
                permission.getDescription().equals(FileHandlerPermission.CREATE_INTERFACE_FILES.getDescription())));
        state.isSystemUser(systemUser);
        stubUserState(token, state);
    }

    private String interfaceFileUri() {
        return "/interface-files/" + file.getInterfaceFileId();
    }

    private String contentUri() {
        return interfaceFileUri() + "/content";
    }

    private void stubUserState(String token, UserStateStub state) {
        stubFor(get(urlEqualTo("/v2/users/0/state"))
            .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + token))
            .willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(state.getUserStateAsJson())));
    }

    private static String signedToken(String subject, boolean systemUser) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)));

        if (systemUser) {
            claims.claim("appid", "test-system-client");
        }

        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(SIGNING_KEY.getKeyID()).build(), claims.build());

        try {
            jwt.sign(new RSASSASigner(SIGNING_KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test token", e);
        }

        return jwt.serialize();
    }

    private static RSAKey createSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("test-key")
                .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA signing unavailable", e);
        }
    }

}
