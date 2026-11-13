package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.common.exceptions.standard.InternalServerErrorException;
import uk.gov.hmcts.opal.common.spring.security.OpalJwtAuthenticationToken;
import uk.gov.hmcts.opal.common.util.SecurityUtil;
import uk.gov.hmcts.opal.filehandler.config.BaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.PaymentType;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.service.extraction.ExtractionService;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.InterfaceFileCommonDataExtract;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;
import uk.gov.hmcts.opal.filehandler.util.StreamUtil;

@ExtendWith(MockitoExtension.class)
class AbstractInterfaceFileProcessorWithExtractionServiceServiceTest {

    private static final String CONTAINER = "natwest-report";
    private static final String SOURCE_JSON_NAME = "typical.dat";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-30T10:15:30Z"), ZoneOffset.UTC);
    private static final UUID FILE_UUID = UUID.randomUUID();

    @Mock
    private FeatureFlagUtil featureFlagUtil;

    @Mock
    private BaisSftpClient baisSftpClient;

    @Mock
    private InterfaceFileBlobStoreService blobStoreService;

    @Mock
    private InterfaceFilesRepository repository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private BaisFileProcessorConfiguration config;

    @Mock
    private ExtractionService<InterfaceFileCommonDataExtract> extractionService;

    private TestProcessor service;
    private InterfaceFileEntity sourceFile;

    private static final long CREATOR_ID = -1L;

    MockedStatic<SecurityUtil> mockedSecurityUtil;
    OpalJwtAuthenticationToken mockedToken = mock(OpalJwtAuthenticationToken.class);

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        sourceFile = sourceFile();
        service = new TestProcessor(
            featureFlagUtil, baisSftpClient, blobStoreService, repository, transactionTemplate, objectMapper,
            extractionService, config);

        lenient().when(config.getSource()).thenReturn(Interface.NATWEST);
        lenient().when(config.getTarget()).thenReturn(Interface.OPAL);
        lenient().when(config.getContainerName()).thenReturn(CONTAINER);
        lenient().when(repository.save(any(InterfaceFileEntity.class))).thenAnswer(invocation -> {
            InterfaceFileEntity entity = invocation.getArgument(0);
            if (entity.getType() == Type.SOURCE_JSON) {
                entity.setInterfaceFileId(200L);
            }
            return entity;
        });

        mockedSecurityUtil = mockStatic(SecurityUtil.class);
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtil.close();
    }

    @Nested
    class ProcessFile {

        @Test
        void noExtractsMarksSourceFileAsSuccessNoTransactions() {
            InputStream inputStream = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));

            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            when(extractionService.extractStandardData(sourceFile, inputStream)).thenReturn(List.of());

            service.processFile(sourceFile, inputStream, CREATOR_ID);

            assertThat(sourceFile.getStatus()).isEqualTo(Status.SUCCESS_NO_TRANSACTIONS);
            verify(repository).save(sourceFile);
            verify(blobStoreService, never()).uploadBaisFile(any(), any(), any(), any());
            assertThat(service.postProcessedFiles).isEmpty();
        }

        @Test
        void passesSavedSourceJsonToPostProcessHook() {
            TestProcessor processor = spy(service);
            InputStream inputStream = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
            InterfaceFileCommonDataExtract extract = extract();
            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);

            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            when(extractionService.extractStandardData(sourceFile, inputStream)).thenReturn(List.of(extract));
            doReturn(sourceJson).when(processor).createAndUploadSourceJson(sourceFile, extract, CREATOR_ID);

            processor.processFile(sourceFile, inputStream, CREATOR_ID);

            assertThat(processor.postProcessedFiles).containsExactly(sourceJson);
            verify(processor).createAndUploadSourceJson(sourceFile, extract, CREATOR_ID);
            verify(repository).save(sourceFile);
        }

        @Test
        void skipsJsonWorkWhenPreProcessRejectsExtract() {
            TestProcessor processor = spy(service);
            InputStream inputStream = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
            InterfaceFileCommonDataExtract extract = extract();
            processor.preProcessResult = false;

            when(extractionService.extractStandardData(sourceFile, inputStream)).thenReturn(List.of(extract));

            processor.processFile(sourceFile, inputStream, CREATOR_ID);

            assertThat(processor.postProcessedFiles).isEmpty();
            verify(processor, never()).createAndUploadSourceJson(any(), any(), anyLong());
            verify(blobStoreService, never()).uploadBaisFile(any(), any(), any(), any());
            verify(repository).save(sourceFile);
        }

        @Test
        void skipsPostProcessWhenCreateAndUploadReturnsNull() {
            TestProcessor processor = spy(service);
            InputStream inputStream = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
            InterfaceFileCommonDataExtract extract = extract();

            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            when(extractionService.extractStandardData(sourceFile, inputStream)).thenReturn(List.of(extract));
            doReturn(null).when(processor).createAndUploadSourceJson(sourceFile, extract, CREATOR_ID);

            processor.processFile(sourceFile, inputStream, CREATOR_ID);

            assertThat(processor.postProcessedFiles).isEmpty();
            verify(processor).createAndUploadSourceJson(sourceFile, extract, CREATOR_ID);
            verify(blobStoreService, never()).uploadBaisFile(any(), any(), any(), any());
            verify(repository).save(sourceFile);
        }

        @Test
        void processesEachAcceptedExtractAndSavesSourceFileOnce() {
            TestProcessor processor = spy(service);
            InputStream inputStream = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
            InterfaceFileCommonDataExtract first = extract();
            InterfaceFileCommonDataExtract second = InterfaceFileCommonDataExtract.builder()
                .fileName("second.dat")
                .paymentType(PaymentType.CHEQUE)
                .build();
            InterfaceFileEntity firstSourceJson = sourceJsonFile(200L, Status.SUCCESS);
            InterfaceFileEntity secondSourceJson = sourceJsonFile(201L, Status.SUCCESS);

            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            when(extractionService.extractStandardData(sourceFile, inputStream)).thenReturn(List.of(first, second));
            doReturn(firstSourceJson).when(processor).createAndUploadSourceJson(sourceFile, first, CREATOR_ID);
            doReturn(secondSourceJson).when(processor)
                .createAndUploadSourceJson(sourceFile, second, CREATOR_ID);

            processor.processFile(sourceFile, inputStream, CREATOR_ID);

            assertThat(processor.postProcessedFiles).containsExactly(firstSourceJson, secondSourceJson);
            verify(processor).createAndUploadSourceJson(sourceFile, first, CREATOR_ID);
            verify(processor).createAndUploadSourceJson(sourceFile, second, CREATOR_ID);
            verify(repository).save(sourceFile);
        }
    }

    @Nested
    class CreateAndUploadSourceJson {

        @Test
        void returnsNullWhenSuccessfulDuplicateAlreadyExists() {
            TestProcessor processor = spy(service);
            InterfaceFileCommonDataExtract extract = extract();

            doReturn("checksum").when(processor).calculateExtractChecksum(any());
            doReturn(true).when(processor).alreadyProcessedSuccessfully(sourceFile, extract, "checksum");

            InterfaceFileEntity sourceJson = processor.createAndUploadSourceJson(
                sourceFile, extract, CREATOR_ID);

            assertThat(sourceJson).isNull();
            verify(processor, never()).supersedeFailedSourceJson(any(), any(), any());
            verify(processor, never()).createSourceJson(any(), any(), any(), any(), any(), anyLong());
            verify(processor, never()).uploadSourceJson(any(), any());
            verify(repository, never()).save(any());
        }

        @Test
        void createsUploadsAndSavesSourceJsonWhenDuplicateDoesNotExist() {
            TestProcessor processor = spy(service);
            InterfaceFileCommonDataExtract extract = extract();
            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);
            Short[] businessUnits = new Short[] {(short) 12};

            doReturn("checksum").when(processor).calculateExtractChecksum(any());
            doReturn(false).when(processor).alreadyProcessedSuccessfully(sourceFile, extract, "checksum");
            doReturn(businessUnits).when(processor).getBusinessUnitsFromExtract(extract);
            doReturn(Domain.FINES).when(processor).getDomainFromExtract(extract);
            doReturn(sourceJson).when(processor)
                .createSourceJson(sourceFile, extract, businessUnits, Domain.FINES, "checksum", CREATOR_ID);

            InterfaceFileEntity result = processor.createAndUploadSourceJson(sourceFile, extract, CREATOR_ID);

            assertThat(result).isSameAs(sourceJson);
            verify(processor).calculateExtractChecksum(any());
            verify(processor).alreadyProcessedSuccessfully(sourceFile, extract, "checksum");
            verify(processor).supersedeFailedSourceJson(sourceFile, extract, "checksum");
            verify(processor)
                .createSourceJson(sourceFile, extract, businessUnits, Domain.FINES, "checksum", CREATOR_ID);
            verify(processor).uploadSourceJson(eq(sourceJson), any());
            verify(repository).save(sourceJson);
        }

        @Test
        void savesSourceJsonAfterUploadMutatesStatus() {
            TestProcessor processor = spy(service);
            InterfaceFileCommonDataExtract extract = extract();
            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);
            Short[] businessUnits = new Short[] {(short) 12};

            doReturn("checksum").when(processor).calculateExtractChecksum(any());
            doReturn(false).when(processor).alreadyProcessedSuccessfully(sourceFile, extract, "checksum");
            doReturn(businessUnits).when(processor).getBusinessUnitsFromExtract(extract);
            doReturn(Domain.FINES).when(processor).getDomainFromExtract(extract);
            doReturn(sourceJson).when(processor)
                .createSourceJson(sourceFile, extract, businessUnits, Domain.FINES, "checksum", CREATOR_ID);
            doAnswer(invocation -> {
                sourceJson.setStatus(Status.FAILED);
                sourceJson.setErrors("{\"message\":\"upload failed\"}");
                return null;
            }).when(processor).uploadSourceJson(eq(sourceJson), any());

            InterfaceFileEntity result = processor.createAndUploadSourceJson(sourceFile, extract, CREATOR_ID);

            assertThat(result).isSameAs(sourceJson);
            assertThat(result.getStatus()).isEqualTo(Status.FAILED);
            assertThat(result.getErrors()).isEqualTo("{\"message\":\"upload failed\"}");
            verify(processor).uploadSourceJson(eq(sourceJson), any());
            verify(repository).save(sourceJson);
        }
    }

    @Nested
    class AlreadyProcessedSuccessfully {

        @Test
        void returnsTrueWhenSuccessfulDuplicateExists() {
            InterfaceFileCommonDataExtract extract = extract();

            when(repository.findByRelatedInterfaceFileInterfaceFileIdAndTypeAndFileNameAndChecksumAndStatusIn(
                100L, Type.SOURCE_JSON, SOURCE_JSON_NAME, "checksum",
                List.of(Status.SUCCESS, Status.SUCCESS_NO_TRANSACTIONS)))
                .thenReturn(Optional.of(sourceJsonFile(200L, Status.SUCCESS)));

            assertThat(service.alreadyProcessedSuccessfully(sourceFile, extract, "checksum")).isTrue();
        }

        @Test
        void returnsFalseWhenSuccessfulDuplicateDoesNotExist() {
            assertThat(service.alreadyProcessedSuccessfully(sourceFile, extract(), "checksum")).isFalse();
        }
    }

    @Nested
    class SupersedeFailedSourceJson {

        @Test
        void marksFailedDuplicatesAsSupersededAndSavesThem() {
            InterfaceFileEntity failed = sourceJsonFile(200L, Status.FAILED);

            when(repository.findAllByRelatedInterfaceFileInterfaceFileIdAndTypeAndFileNameAndChecksumAndStatus(
                100L, Type.SOURCE_JSON, SOURCE_JSON_NAME, "checksum", Status.FAILED))
                .thenReturn(List.of(failed));

            service.supersedeFailedSourceJson(sourceFile, extract(), "checksum");

            assertThat(failed.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
            verify(repository).saveAll(List.of(failed));
        }

        @Test
        void doesNotSaveWhenNoFailedDuplicatesExist() {
            service.supersedeFailedSourceJson(sourceFile, extract(), "checksum");

            verify(repository, never()).saveAll(any());
        }
    }

    @Nested
    class CalculateExtractChecksum {

        @Test
        void calculatesChecksumFromBytes() {
            assertThat(service.calculateExtractChecksum("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        }

        @Test
        void wrapsChecksumReadFailure() {
            try (MockedStatic<StreamUtil> checksum = mockStatic(StreamUtil.class)) {

                checksum.when(() -> StreamUtil.calculateChecksum(any(InputStream.class)))
                    .thenThrow(new IOException("read failed"));
                Throwable thrown =
                    catchThrowable(() -> service.calculateExtractChecksum("abc".getBytes(StandardCharsets.UTF_8)));

                assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to calculate SOURCE_JSON checksum")
                    .hasCauseInstanceOf(IOException.class);
            }
        }
    }

    @Nested
    class CreateSourceJson {

        @Test
        void mapsEntityFields() {
            InterfaceFileCommonDataExtract extract = extract();

            InterfaceFileEntity sourceJson = service.createSourceJson(
                sourceFile, extract, new Short[] {(short) 12, (short) 34},
                Domain.FINES, "checksum", CREATOR_ID);

            assertThat(sourceJson.getSource()).isEqualTo(Interface.NATWEST);
            assertThat(sourceJson.getTarget()).isEqualTo(Interface.OPAL);
            assertThat(sourceJson.getType()).isEqualTo(Type.SOURCE_JSON);
            assertThat(sourceJson.getOpalDomain()).isEqualTo(Domain.FINES);
            assertThat(sourceJson.getFileName()).isEqualTo(SOURCE_JSON_NAME);
            assertThat(sourceJson.getChecksum()).isEqualTo("checksum");
            assertThat(sourceJson.getStatus()).isEqualTo(Status.SUCCESS);
            assertThat(sourceJson.getCreatedDatetime()).isEqualTo(LocalDateTime.now(CLOCK));
            assertThat(sourceJson.getRelatedInterfaceFile()).isSameAs(sourceFile);
            assertThat(sourceJson.getBusinessUnitId()).containsExactly((short) 12, (short) 34);
            assertThat(sourceJson.getPaymentType()).isEqualTo(PaymentType.CASH);
        }
    }

    @Nested
    class UploadSourceJson {

        @Test
        void populatesFilestoreUuidAndUploadsJsonBytes() throws IOException {
            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);
            byte[] jsonBytes = "{}".getBytes(StandardCharsets.UTF_8);
            ArgumentCaptor<InputStream> inputStreamCaptor = ArgumentCaptor.forClass(InputStream.class);

            service.uploadSourceJson(sourceJson, jsonBytes);

            assertThat(sourceJson.getFilestoreUuid()).isNotNull();
            verify(blobStoreService).uploadBaisFile(any(UUID.class), eq(CONTAINER), inputStreamCaptor.capture(),
                eq(sourceJson.getChecksum()));
            assertThat(inputStreamCaptor.getValue().readAllBytes()).isEqualTo(jsonBytes);
        }

        @Test
        void marksFailedWhenBlobChecksumValidationFails() {
            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);
            BlobChecksumValidationException exception = new BlobChecksumValidationException(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "expected", "actual");

            doThrow(exception).when(blobStoreService)
                .uploadBaisFile(any(UUID.class), eq(CONTAINER), any(InputStream.class), eq(sourceJson.getChecksum()));

            service.uploadSourceJson(sourceJson, "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(sourceJson.getStatus()).isEqualTo(Status.FAILED);
            assertThat(sourceJson.getErrors())
                .isEqualTo(
                    "{\"message\":\"Blob checksum validation failed: Blob checksum validation failed for filestore "
                        + "UUID '11111111-1111-1111-1111-111111111111': expected 'expected' but was 'actual'\"}");
            assertThat(sourceJson.getFilestoreUuid()).isNull();
        }

        @Test
        void marksFailedWhenUploadThrowsGenericRuntimeException() {
            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);

            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            doThrow(new RuntimeException("blob unavailable")).when(blobStoreService)
                .uploadBaisFile(any(UUID.class), eq(CONTAINER), any(InputStream.class), eq(sourceJson.getChecksum()));

            service.uploadSourceJson(sourceJson, "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(sourceJson.getStatus()).isEqualTo(Status.FAILED);
            assertThat(sourceJson.getErrors()).isEqualTo("{\"message\":\"Blob upload failed: blob unavailable\"}");
            assertThat(sourceJson.getFilestoreUuid()).isNull();
        }
    }

    @Nested
    class MarkSourceJsonFailed {

        @Test
        void setsFailureStatusAndErrors() {
            InterfaceFileEntity sourceJson = sourceJsonFile(200L, Status.SUCCESS);

            service.markSourceJsonFailed(sourceJson, "failed message", new RuntimeException("failure"));

            assertThat(sourceJson.getStatus()).isEqualTo(Status.FAILED);
            assertThat(sourceJson.getErrors()).contains("failed message");
        }
    }

    @Nested
    class SelectFilesToProcess {

        @Test
        void wrapsRetrySourceStreamCloseFailure() throws IOException {
            IOException exception = new IOException("Failed to close source stream");
            InputStream sourceStream = mock(InputStream.class);
            BinaryData sourceData = mock(BinaryData.class);
            when(sourceData.toStream()).thenReturn(sourceStream);
            doThrow(exception).when(sourceStream).close();

            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(CREATOR_ID);
            when(repository.findAll(ArgumentMatchers.<Specification<InterfaceFileEntity>>any()))
                .thenReturn(List.of(sourceFile));
            when(blobStoreService.fetchInterfaceFile(sourceFile.getInterfaceFileId(), sourceFile.getFilestoreUuid(),
                CONTAINER)).thenReturn(sourceData);

            Throwable thrown = catchThrowable(service::selectFilesToProcess);

            assertThat(thrown)
                .isInstanceOf(InternalServerErrorException.class)
                .hasMessage("Failed to process source file source.dat with interfaceFileId 100")
                .hasCause(exception);
        }

        @Test
        void retriesFailedSourceJsonFilesBeforeSelectingNewBaisFiles() {
            mockedSecurityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser)
                .thenReturn(mockedToken);
            when(mockedToken.getUserId()).thenReturn(-1L);

            when(repository.findAll(ArgumentMatchers.<Specification<InterfaceFileEntity>>any()))
                .thenReturn(List.of(sourceFile));
            when(blobStoreService.fetchInterfaceFile(anyLong(), any(UUID.class), anyString()))
                .thenReturn(BinaryData.fromBytes("source".getBytes(StandardCharsets.UTF_8)));
            when(extractionService.extractStandardData(eq(sourceFile), any(InputStream.class))).thenReturn(List.of());

            service.selectFilesToProcess();

            verify(blobStoreService).fetchInterfaceFile(sourceFile.getInterfaceFileId(), sourceFile.getFilestoreUuid(),
                CONTAINER);
            assertThat(sourceFile.getStatus()).isEqualTo(Status.SUCCESS_NO_TRANSACTIONS);
            verify(repository).save(sourceFile);
        }

        @Test
        void doesNotFetchRetrySourceFilesWhenSpecificationFindsNone() {
            service.selectFilesToProcess();

            verify(blobStoreService, never()).fetchInterfaceFile(anyLong(), any(UUID.class), anyString());
        }

        @Test
        void usesConfiguredMaxRetriesInRetrySpecificationQuery() {
            service.maxRetries = 2;

            service.selectFilesToProcess();

            verify(repository).findAll(ArgumentMatchers.<Specification<InterfaceFileEntity>>any());
            verify(blobStoreService, never()).fetchInterfaceFile(anyLong(), any(UUID.class), anyString());
        }
    }

    private InterfaceFileEntity sourceFile() {
        return InterfaceFileEntity.builder()
            .interfaceFileId(100L)
            .source(Interface.NATWEST)
            .target(Interface.OPAL)
            .type(Type.SOURCE)
            .opalDomain(Domain.FILE_HANDLER)
            .fileName("source.dat")
            .checksum("source-checksum")
            .status(Status.INGESTED)
            .filestoreUuid(FILE_UUID)
            .createdDatetime(LocalDateTime.now(CLOCK))
            .createdBy(CREATOR_ID)
            .build();
    }

    private InterfaceFileEntity sourceJsonFile(long id, Status status) {
        return InterfaceFileEntity.builder()
            .interfaceFileId(id)
            .source(Interface.NATWEST)
            .target(Interface.OPAL)
            .type(Type.SOURCE_JSON)
            .opalDomain(Domain.FINES)
            .fileName(SOURCE_JSON_NAME)
            .checksum("json-checksum")
            .status(status)
            .createdDatetime(LocalDateTime.now(CLOCK))
            .relatedInterfaceFile(sourceFile)
            .createdBy(CREATOR_ID)
            .build();
    }

    private InterfaceFileCommonDataExtract extract() {
        return InterfaceFileCommonDataExtract.builder()
            .fileName(SOURCE_JSON_NAME)
            .paymentType(PaymentType.CASH)
            .build();
    }

    private static class TestProcessor
        extends AbstractInterfaceFileProcessorWithExtractionService<InterfaceFileCommonDataExtract> {

        private final List<InterfaceFileEntity> postProcessedFiles = new ArrayList<>();
        private boolean preProcessResult = true;

        TestProcessor(
            FeatureFlagUtil featureFlagUtil,
            BaisSftpClient baisSftpClient,
            InterfaceFileBlobStoreService blobStoreService,
            InterfaceFilesRepository repository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            ExtractionService<InterfaceFileCommonDataExtract> extractionService,
            BaisFileProcessorConfiguration config
        ) {
            super(CLOCK, featureFlagUtil, blobStoreService, repository, transactionTemplate,
                objectMapper, extractionService, new SftpFileIngestionService(config, baisSftpClient), config);

            maxRetries = 5;
        }

        @Override
        protected void postProcessExtract(
            InterfaceFileEntity sourceFile,
            InterfaceFileCommonDataExtract extract
        ) {
            postProcessedFiles.add(sourceFile);
        }

        @Override
        protected Short[] getBusinessUnitsFromExtract(
            InterfaceFileCommonDataExtract extract
        ) {
            return new Short[] {12};
        }

        @Override
        protected Domain getDomainFromExtract(
            InterfaceFileCommonDataExtract extract
        ) {
            return Domain.FINES;
        }

        @Override
        public boolean preProcessExtract(
            InterfaceFileEntity sourceFile,
            InterfaceFileCommonDataExtract extract
        ) {
            return preProcessResult;
        }
    }
}
