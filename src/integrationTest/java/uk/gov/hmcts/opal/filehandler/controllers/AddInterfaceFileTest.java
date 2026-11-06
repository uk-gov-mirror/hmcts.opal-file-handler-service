package uk.gov.hmcts.opal.filehandler.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.hmcts.opal.common.dto.ToJsonString.toJsonString;

import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.opal.filehandler.IntegrationSecurityConfiguration;
import uk.gov.hmcts.opal.filehandler.authorisation.FileHandlerPermission;
import uk.gov.hmcts.opal.filehandler.config.VariantBankingFileProcessorConfig;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.support.AbstractControllerIntegrationTest;
import uk.gov.hmcts.opal.filehandler.support.UtilBlobStoreService;
import uk.gov.hmcts.opal.generated.model.AddInterfaceFileRequestMetadata;
import uk.gov.hmcts.opal.generated.model.DomainEnumTypes;
import uk.gov.hmcts.opal.generated.model.InterfaceFileEnumInterfaceFile;
import uk.gov.hmcts.opal.generated.model.InterfaceFileObjectInterfaceFile;
import uk.gov.hmcts.opal.generated.model.InterfaceFileTypeEnumInterfaceFile;
import uk.gov.hmcts.opal.generated.model.PaymentTypeEnumTypes;
import uk.gov.hmcts.opal.generated.model.StatusEnumInterfaceFile;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraDefect;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraEpic;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraStory;

@Slf4j
@DisplayName("Interface Files Controller Integration Tests - Add Interface File")
@ActiveProfiles(profiles = {"integration"})
@Import(IntegrationSecurityConfiguration.class)
public class AddInterfaceFileTest extends AbstractControllerIntegrationTest {

    private static final String URI = "/interface-files";

    private static UtilBlobStoreService utilBlobStoreService;
    @Autowired
    private InterfaceFilesRepository repository;

    private static final String bteckohResourcePath = "azure/data/bteckoh-report/2498-MCPLDB-MOJ-Payments-Report-"
        + "Daily-2026-07-06-06-00-18.xlsx";

    private static byte[] fileContents;
    private static final UUID uuid = UUID.randomUUID();

    private InterfaceFileObjectInterfaceFile buildExpectedResponse(InterfaceFileEntity interfaceFileEntity) {
        return InterfaceFileObjectInterfaceFile.builder()
            .interfaceFileId(interfaceFileEntity.getInterfaceFileId())
            .source(InterfaceFileEnumInterfaceFile.valueOf(interfaceFileEntity.getSource().name()))
            .target(InterfaceFileEnumInterfaceFile.valueOf(interfaceFileEntity.getTarget().name()))
            .type(InterfaceFileTypeEnumInterfaceFile.valueOf(interfaceFileEntity.getType().name()))
            .domain(DomainEnumTypes.valueOf(interfaceFileEntity.getOpalDomain().name()))
            .fileName(interfaceFileEntity.getFileName())
            .filestoreUuid(interfaceFileEntity.getFilestoreUuid())
            .status(StatusEnumInterfaceFile.valueOf(interfaceFileEntity.getStatus().name()))
            .createdDatetime(interfaceFileEntity.getCreatedDatetime())
            .checksum(interfaceFileEntity.getChecksum())
            .errors(interfaceFileEntity.getErrors())
            .build();
    }

    private AddInterfaceFileRequestMetadata buildMetaData(InterfaceFileEnumInterfaceFile source, Long relatedId) {
        return AddInterfaceFileRequestMetadata.builder()
            .fileName("file-name.dat")
            .businessUnitId((short) 10)
            .paymentType(PaymentTypeEnumTypes.CASH)
            .type(InterfaceFileTypeEnumInterfaceFile.SOURCE)
            .domain(DomainEnumTypes.FINES)
            .shouldPreProcessFile(false)
            .source(source)
            .target(InterfaceFileEnumInterfaceFile.OPAL)
            .relatedInterfaceFileId(relatedId)
            .build();
    }

    private InterfaceFileEntity buildEntity(
        String filename, String checksum, Status status, String errors, Long relatedId, Long creatorId) {
        InterfaceFileEntity.InterfaceFileEntityBuilder builder = InterfaceFileEntity.builder()
            .checksum(checksum)
            .fileName(filename)
            .filestoreUuid(uuid)
            .source(Interface.BTECKOH_REPORT)
            .type(Type.SOURCE)
            .target(Interface.OPAL)
            .opalDomain(Domain.FINES)
            .status(status)
            .errors(errors)
            .createdDatetime(LocalDateTime.now())
            .createdBy(creatorId);
        if (relatedId != null) {
            builder.relatedInterfaceFile(InterfaceFileEntity.builder().interfaceFileId(relatedId).build());
        }
        return builder.build();
    }

    private void insertInterfaceFileEntity(InterfaceFileEntity entity) {
        repository.save(entity);
    }

    private void assertExistsInDatabase(Long id) {
        InterfaceFileEntity saved = repository.findById(id).orElseThrow();
        assertEquals(500000000L, saved.getCreatedBy(),
            "The uploaded interface file should be attributed to the authenticated user");
    }

    private void assertDatabaseUnchanged() {
        assertTrue(repository.findAll().isEmpty());
    }

    private void assertResponse(InterfaceFileObjectInterfaceFile expected, InterfaceFileObjectInterfaceFile response) {
        assertAll(
            () -> assertEquals(expected.getChecksum(), response.getChecksum()),
            () -> assertEquals(expected.getFileName(), response.getFileName()),
            () -> assertEquals(expected.getStatus(), response.getStatus()),
            () -> assertEquals(expected.getDomain(), response.getDomain()),
            () -> assertEquals(expected.getErrors(), response.getErrors())
        );
    }

    private void assertDuplicateExistsInDatabase(String filename, String checksum) {
        assertTrue(repository.findByFileNameAndChecksumAndStatus(filename, checksum, Status.DUPLICATE).isPresent());
    }

    private void assertAddedToBlobStorage(String container, String uuid) {
        assertTrue(utilBlobStoreService.getBlobExists(container, uuid));
    }

    @TestPropertySource(properties = {
        "launchdarkly.enabled=false",
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true"
    })
    @Nested
    @DisplayName("Feature Flag On")
    class FeatureOn {

        @BeforeAll
        static void setupData() throws IOException {
            utilBlobStoreService = new UtilBlobStoreService();
            utilBlobStoreService.createContainerIfNotExists("bteckoh-report");

            URL url = Resources.getResource(bteckohResourcePath);
            fileContents = Resources.toByteArray(url);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("Correctly adds a new interface file on the db and blob store")
        void addNewInterfaceFileToDBAndBlobStore(boolean isSystemUser) {
            InterfaceFileObjectInterfaceFile expectedResponse = buildExpectedResponse(
                buildEntity("some-file-name",
                    "d553f8f289bd08e5c513de5c000c0374",
                    Status.SUCCESS,
                    null, null, 1L)
            );

            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null));

            InterfaceFileObjectInterfaceFile response = setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody(
                    "file", "some-file-name", "application/json", fileContents)
                .includeMultipartBody(
                    "metadata", "metadata.json", "application/json", metadata.getBytes())
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 2, FileHandlerPermission.CREATE_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertSuccess(HttpStatus.CREATED)
                .getResponseBodyAsObject(InterfaceFileObjectInterfaceFile.class);

            assertResponse(expectedResponse, response);
            assertExistsInDatabase(response.getInterfaceFileId());
            assertAddedToBlobStorage("bteckoh-report", response.getFilestoreUuid().toString());
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("Fails when user has no permissions")
        void failsWhenUserHasNoPermission(boolean isSystemUser) {
            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null));

            setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody(
                    "file", "some-file-name", "application/json", fileContents)
                .includeMultipartBody(
                    "metadata", "metadata.json", "application/json", metadata.getBytes())
                .includeContentDigest()
                .clearPermissions()
                .systemUser(isSystemUser)
                .execute()
                .assertForbidden();

            assertDatabaseUnchanged();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("Correctly sets related interface file")
        void relatedFileIsSetCorrectly(boolean isSystemUser) {
            InterfaceFileEntity originalEntity = buildEntity("some-file-name",
                "d553f8f289bd08e5c513de5c000c0374", Status.SUCCESS, null, null, 1L);
            insertInterfaceFileEntity(originalEntity);
            InterfaceFileObjectInterfaceFile expectedResponse = buildExpectedResponse(
                buildEntity("some-other-file-name",
                    "d553f8f289bd08e5c513de5c000c0374", Status.SUCCESS,
                    null, originalEntity.getInterfaceFileId(), 1L)
            );
            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT,
                originalEntity.getInterfaceFileId()));

            InterfaceFileObjectInterfaceFile response = setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", "some-other-file-name", "application/json", fileContents)
                .includeMultipartBody("metadata", "metadata.json", "application/json", metadata.getBytes())
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 2, FileHandlerPermission.CREATE_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertSuccess(HttpStatus.CREATED)
                .getResponseBodyAsObject(InterfaceFileObjectInterfaceFile.class);

            assertResponse(expectedResponse, response);
            assertExistsInDatabase(response.getInterfaceFileId());
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("Correctly adds a duplicate file")
        void rejectsDuplicateCorrectly(boolean isSystemUser) {
            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null));

            InterfaceFileEntity originalEntity = buildEntity(
                "some-file-name", "d553f8f289bd08e5c513de5c000c0374", Status.SUCCESS, null, null, 1L);
            insertInterfaceFileEntity(originalEntity);
            utilBlobStoreService.storeBlob(fileContents, "bteckoh-report", uuid.toString());
            final String originalVersion = utilBlobStoreService.getBlobVersion(
                "bteckoh-report", originalEntity.getFilestoreUuid().toString());

            InterfaceFileObjectInterfaceFile expectedResponse = buildExpectedResponse(
                buildEntity("some-file-name", "d553f8f289bd08e5c513de5c000c0374", Status.DUPLICATE,
                    "{\"message\":\"File with name 'some-file-name' and checksum 'd553f8f289bd08e5c513de5c000c0374'"
                        + " for source 'BTECKOH_REPORT' is a duplicate of " + originalEntity.getInterfaceFileId()
                        + "\"}", null, 1L)
            );

            InterfaceFileObjectInterfaceFile response = setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", "some-file-name", "application/json", fileContents)
                .includeMultipartBody("metadata", "metadata.json", "application/json", metadata.getBytes())
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 2, FileHandlerPermission.CREATE_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertSuccess(HttpStatus.CREATED)
                .getResponseBodyAsObject(InterfaceFileObjectInterfaceFile.class);

            assertResponse(expectedResponse, response);
            assertDuplicateExistsInDatabase("some-file-name", "d553f8f289bd08e5c513de5c000c0374");
            assertEquals(uuid, response.getFilestoreUuid());
            assertAddedToBlobStorage("bteckoh-report", uuid.toString());
            assertEquals(
                originalVersion,
                utilBlobStoreService.getBlobVersion("bteckoh-report", originalEntity.getFilestoreUuid().toString())
            );
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("Accepts the same non-variant filename when its checksum differs")
        void differentChecksumIsNotDuplicate(boolean isSystemUser) throws IOException {
            byte[] originalContent = Resources.toByteArray(Resources.getResource(
                "bais-emulator/2498-MCPLDB-MOJ-Payments-Report-Daily-2026-07-07-06-00-18.xlsx"));
            String originalChecksum = org.springframework.util.DigestUtils.md5DigestAsHex(originalContent);
            String incomingChecksum = org.springframework.util.DigestUtils.md5DigestAsHex(fileContents);
            assertNotEquals(originalChecksum, incomingChecksum);
            InterfaceFileEntity original = buildEntity("some-file-name", originalChecksum,
                Status.SUCCESS, null, null, 1L);
            repository.saveAndFlush(original);
            utilBlobStoreService.storeBlob(originalContent, "bteckoh-report", uuid.toString());
            final String originalVersion = utilBlobStoreService.getBlobVersion("bteckoh-report", uuid.toString());

            InterfaceFileObjectInterfaceFile response = uploadContent(fileContents,
                buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null), isSystemUser);

            assertEquals(StatusEnumInterfaceFile.SUCCESS, response.getStatus());
            assertEquals(incomingChecksum, response.getChecksum());
            assertNull(response.getErrors());
            assertNotEquals(original.getInterfaceFileId(), response.getInterfaceFileId());
            assertNotEquals(original.getFilestoreUuid(), response.getFilestoreUuid());
            assertEquals(2, repository.count());
            InterfaceFileEntity stored = repository.findById(response.getInterfaceFileId()).orElseThrow();
            assertEquals(Status.SUCCESS, stored.getStatus());
            assertEquals(incomingChecksum, stored.getChecksum());
            assertEquals(Status.SUCCESS, repository.findById(original.getInterfaceFileId()).orElseThrow().getStatus());
            assertAddedToBlobStorage("bteckoh-report", response.getFilestoreUuid().toString());
            assertEquals(originalVersion, utilBlobStoreService.getBlobVersion("bteckoh-report", uuid.toString()));
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("AC4 - Repeating an INGESTED file creates a new record and blob")
        void ingestedFileIsNotDuplicate(boolean isSystemUser) {
            InterfaceFileEntity original = buildEntity("some-file-name",
                "d553f8f289bd08e5c513de5c000c0374", Status.INGESTED, null, null, 1L);
            insertInterfaceFileEntity(original);
            utilBlobStoreService.storeBlob(fileContents, "bteckoh-report", uuid.toString());

            InterfaceFileObjectInterfaceFile response = uploadContent(fileContents,
                buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null), isSystemUser);

            assertEquals(StatusEnumInterfaceFile.SUCCESS, response.getStatus());
            assertNotEquals(original.getInterfaceFileId(), response.getInterfaceFileId());
            assertNotEquals(uuid, response.getFilestoreUuid());
            assertEquals(2, repository.count());
            assertEquals(Status.INGESTED, repository.findById(original.getInterfaceFileId()).orElseThrow().getStatus());
            assertAddedToBlobStorage("bteckoh-report", response.getFilestoreUuid().toString());
            assertAddedToBlobStorage("bteckoh-report", uuid.toString());
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("AC2 - Invalid BTEckoh JSON is recorded as FAILED without a blob reference")
        void invalidContentIsRecordedAsFailed(boolean isSystemUser) {
            InterfaceFileObjectInterfaceFile response = uploadContent(
                "{}".getBytes(StandardCharsets.UTF_8),
                buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null), isSystemUser);

            assertEquals(StatusEnumInterfaceFile.FAILED, response.getStatus());
            assertNull(response.getFilestoreUuid());
            assertTrue(response.getErrors() != null
                && response.getErrors().contains("BTEckoh report was not a valid XLSX workbook"));
            assertEquals(1, repository.count());
            InterfaceFileEntity stored = repository.findById(response.getInterfaceFileId()).orElseThrow();
            assertEquals(Status.FAILED, stored.getStatus());
            assertNull(stored.getFilestoreUuid());
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("AC2 - Different metadata and multipart filenames are accepted")
        void differentMetadataFilenameIsAccepted(boolean isSystemUser) {
            AddInterfaceFileRequestMetadata metadata = buildMetaData(
                InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null);
            metadata.setFileName("different-metadata-name.xlsx");
            InterfaceFileObjectInterfaceFile response = uploadContent(fileContents, metadata, isSystemUser);

            assertEquals(StatusEnumInterfaceFile.SUCCESS, response.getStatus());
            assertEquals("some-file-name", response.getFileName());
            assertEquals(response.getFileName(), repository.findById(response.getInterfaceFileId())
                .orElseThrow().getFileName());
            assertAddedToBlobStorage("bteckoh-report", response.getFilestoreUuid().toString());
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("AC2 - Reject a multipart upload without the required file")
        @JiraDefect("PO-10908")
        @Disabled("PO-10908: missing multipart parts return 500; re-enable when fixed to return 400")
        void rejectsMissingFilePart(boolean isSystemUser) {
            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null));
            setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("metadata", "metadata.json", "application/json",
                    metadata.getBytes(StandardCharsets.UTF_8))
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 1, FileHandlerPermission.VIEW_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertStatus(HttpStatus.BAD_REQUEST);
            assertDatabaseUnchanged();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("AC2 - Reject a multipart upload without the required metadata")
        @JiraDefect("PO-10908")
        @Disabled("PO-10908: missing multipart parts return 500; re-enable when fixed to return 400")
        void rejectsMissingMetadataPart(boolean isSystemUser) {
            setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", "some-file-name", "application/octet-stream", fileContents)
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 1, FileHandlerPermission.VIEW_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertStatus(HttpStatus.BAD_REQUEST);
            assertDatabaseUnchanged();
        }

        private InterfaceFileObjectInterfaceFile uploadContent(
            byte[] content, AddInterfaceFileRequestMetadata metadata, boolean isSystemUser) {
            return setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", "some-file-name", "application/octet-stream", content)
                .includeMultipartBody("metadata", "metadata.json", "application/json",
                    toJsonString(metadata).getBytes(StandardCharsets.UTF_8))
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 2, FileHandlerPermission.CREATE_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertSuccess(HttpStatus.CREATED)
                .getResponseBodyAsObject(InterfaceFileObjectInterfaceFile.class);
        }

    }

    @Nested
    @DisplayName("VARIANT_BANKING uploads")
    @TestPropertySource(properties = {
        "launchdarkly.enabled=false",
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true"
    })
    class VariantBanking {

        private static final String FILE_NAME = "a121_003500VB123_13.dat";

        @Autowired
        private VariantBankingFileProcessorConfig config;

        @Autowired
        private InterfaceFileBlobStoreService blobStoreService;

        private byte[] content;
        private String checksum;
        private UtilBlobStoreService variantBlobStore;

        @BeforeEach
        void setUpVariantBanking() throws IOException {
            content = Resources.toByteArray(Resources.getResource("bais-emulator/a121_00350005_300000.dat"));
            checksum = org.springframework.util.DigestUtils.md5DigestAsHex(content);
            variantBlobStore = new UtilBlobStoreService();
            variantBlobStore.createContainerIfNotExists(config.getContainerName());
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("Stores a variant banking upload and its metadata in the database and configured container")
        void storesUploadedFile(boolean isSystemUser) {
            InterfaceFileObjectInterfaceFile response = uploadVariantBanking(isSystemUser);

            assertEquals(StatusEnumInterfaceFile.SUCCESS, response.getStatus());
            assertEquals(checksum, response.getChecksum());
            assertEquals(FILE_NAME, response.getFileName());
            assertEquals("VARIANT_BANKING", response.getSource().getValue());
            assertEquals(InterfaceFileEnumInterfaceFile.OPAL, response.getTarget());
            assertEquals(InterfaceFileTypeEnumInterfaceFile.SOURCE, response.getType());
            assertEquals(DomainEnumTypes.FINES, response.getDomain());
            assertNull(response.getErrors());
            assertEquals(1, repository.count());

            InterfaceFileEntity stored = repository.findById(response.getInterfaceFileId()).orElseThrow();
            assertEquals(Interface.VARIANT_BANKING, stored.getSource());
            assertEquals(Status.SUCCESS, stored.getStatus());
            assertEquals(response.getFilestoreUuid(), stored.getFilestoreUuid());
            assertThat(stored.getBusinessUnitId()).containsExactly((short) 10);
            assertExistsInDatabase(stored.getInterfaceFileId());
            assertThat(blobStoreService.fetchInterfaceFile(stored.getInterfaceFileId(), stored.getFilestoreUuid(),
                config.getContainerName()).toBytes()).containsExactly(content);
        }

        @ParameterizedTest
        @CsvSource({"false, false", "false, true", "true, false", "true, true"})
        @DisplayName("Marks the same variant banking filename as duplicate regardless of checksum")
        void recordsDuplicate(boolean isSystemUser, boolean sameChecksum) {
            byte[] originalContent = sameChecksum ? content : "previous variant banking content"
                .getBytes(StandardCharsets.UTF_8);
            String originalChecksum = org.springframework.util.DigestUtils.md5DigestAsHex(originalContent);
            if (!sameChecksum) {
                assertNotEquals(originalChecksum, checksum);
            }
            InterfaceFileEntity original = buildEntity(FILE_NAME, originalChecksum, Status.SUCCESS, null, null, 1L);
            original.setSource(Interface.VARIANT_BANKING);
            repository.saveAndFlush(original);
            variantBlobStore.storeBlob(originalContent, config.getContainerName(),
                original.getFilestoreUuid().toString());

            InterfaceFileObjectInterfaceFile response = uploadVariantBanking(isSystemUser);

            assertEquals(StatusEnumInterfaceFile.DUPLICATE, response.getStatus());
            assertNotEquals(original.getInterfaceFileId(), response.getInterfaceFileId());
            assertEquals(original.getFilestoreUuid(), response.getFilestoreUuid());
            assertEquals(2, repository.count());
            InterfaceFileEntity duplicate = repository.findById(response.getInterfaceFileId()).orElseThrow();
            assertEquals(Interface.VARIANT_BANKING, duplicate.getSource());
            assertEquals(Status.DUPLICATE, duplicate.getStatus());
            assertEquals(checksum, duplicate.getChecksum());
            assertEquals(checksum, response.getChecksum());
            assertEquals("File with name '%s' and checksum '%s' for source 'VARIANT_BANKING' is a duplicate of %s"
                    .formatted(FILE_NAME, checksum, original.getInterfaceFileId()),
                objectMapper.readTree(duplicate.getErrors()).get("message").asString());
            assertThat(blobStoreService.fetchInterfaceFile(original.getInterfaceFileId(), original.getFilestoreUuid(),
                config.getContainerName()).toBytes()).containsExactly(originalContent);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("Rejects a variant banking upload when the user lacks permission")
        void rejectsWithoutPermission(boolean isSystemUser) {
            setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", FILE_NAME, "application/octet-stream", content)
                .includeMultipartBody("metadata", "metadata.json", "application/json", metadata())
                .includeContentDigest()
                .clearPermissions()
                .systemUser(isSystemUser)
                .execute()
                .assertForbidden();

            assertDatabaseUnchanged();
        }

        private InterfaceFileObjectInterfaceFile uploadVariantBanking(boolean isSystemUser) {
            return setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", FILE_NAME, "application/octet-stream", content)
                .includeMultipartBody("metadata", "metadata.json", "application/json", metadata())
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 2, FileHandlerPermission.CREATE_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertSuccess(HttpStatus.CREATED)
                .getResponseBodyAsObject(InterfaceFileObjectInterfaceFile.class);
        }

        private byte[] metadata() {
            return """
                {
                  "file_name": "%s",
                  "business_unit_id": 10,
                  "payment_type": "CASH",
                  "type": "SOURCE",
                  "domain": "FINES",
                  "should_pre_process_file": false,
                  "source": "VARIANT_BANKING",
                  "target": "OPAL"
                }
                """.formatted(FILE_NAME).getBytes(StandardCharsets.UTF_8);
        }
    }

    @TestPropertySource(properties = {
        "launchdarkly.enabled=false",
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false"
    })
    @Nested
    class FeatureOff {

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("API should return 404 when feature flag is off")
        void addInterfaceFile_shouldReturn404_whenFeatureFlagIsOff(boolean isSystemUser) throws Exception {
            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null));
            setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", "some-file-name", "application/json", fileContents)
                .includeMultipartBody("metadata", "metadata.json", "application/json", metadata.getBytes())
                .addPermission((short) 1, FileHandlerPermission.VIEW_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertFeatureFlagDisabledResponse();
        }
    }

    @TestPropertySource(properties = {
        "launchdarkly.enabled=false",
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
    })
    @Nested
    class BlobStorageFailure {

        @BeforeAll
        static void setupData() throws IOException {
            URL url = Resources.getResource(bteckohResourcePath);
            fileContents = Resources.toByteArray(url);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @JiraStory("PO-6453")
        @JiraEpic("PO-3497")
        @DisplayName("Correctly handles a blob store upload failure")
        void blobstoreUploadfailure(boolean isSystemUser) {
            String metadata = toJsonString(buildMetaData(InterfaceFileEnumInterfaceFile.BTECKOH_REPORT, null));
            String problemDetail = "Blob upload failed for file 'some-file-name': Status code 404, "
                + "\"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Error>\n  <Code>ContainerNotFound"
                + "</Code>\n  <Message>The specified container does not exist.\n";

            utilBlobStoreService = new UtilBlobStoreService();
            utilBlobStoreService.deleteContainer("bteckoh-report");

            setupFileUploadApiTest(HttpMethod.POST, URI)
                .includeMultipartBody("file", "some-file-name", "application/json", fileContents)
                .includeMultipartBody("metadata", "metadata.json", "application/json", metadata.getBytes())
                .includeContentDigest()
                .clearPermissions()
                .addPermission((short) 2, FileHandlerPermission.CREATE_INTERFACE_FILES)
                .systemUser(isSystemUser)
                .execute()
                .assertProblemDetails(HttpStatus.SERVICE_UNAVAILABLE, problemDetail, "Service Unavailable", false);

            assertDatabaseUnchanged();

        }
    }
}
