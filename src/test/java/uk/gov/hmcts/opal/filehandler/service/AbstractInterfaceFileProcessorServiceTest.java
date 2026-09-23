package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.opal.filehandler.config.BaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.BaisSftpFileDownloadException;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;
import uk.gov.hmcts.opal.filehandler.exception.InvalidReportFileException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;

@ExtendWith(MockitoExtension.class)
class AbstractInterfaceFileProcessorServiceTest {

    private static final String TEST_FEATURE_FLAG = "test-feature-flag";
    private static final String SFTP_USERNAME = "sftp-username";
    private static final String MATCHING_FILE = "matching-file.dat";
    private static final String IGNORED_FILE = "ignored-file.txt";
    private static final String CONTAINER = "test-container";
    private static final String CHECKSUM = "3685d7f2b30e9b34b8d3e5496fb45506";
    private static final byte[] FILE_CONTENT = {0, 1, 13, 10, (byte) 255};
    private static final UUID FILE_UUID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-30T10:15:30Z"), ZoneOffset.UTC);

    @Mock
    private FeatureFlagUtil featureFlagUtil;

    @Mock
    private BaisSftpClient baisSftpClient;

    @Mock
    private InterfaceFileBlobStoreService blobStoreService;

    @Mock
    private InterfaceFilesRepository repository;

    @Mock
    private BaisFileProcessorConfiguration config;

    @Mock
    private TransactionTemplate transactionTemplate;

    private TestProcessor service;
    private ObjectMapper objectMapper;
    private List<Status> savedStatuses;

    private final Logger logger = (Logger) LoggerFactory.getLogger(AbstractInterfaceFileProcessorService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        savedStatuses = new ArrayList<>();

        logAppender.start();
        logger.addAppender(logAppender);

        service = new TestProcessor(
            featureFlagUtil,
            baisSftpClient,
            blobStoreService,
            repository,
            transactionTemplate,
            objectMapper
        );

        lenient().when(config.getFeatureFlag()).thenReturn(TEST_FEATURE_FLAG);
        lenient().when(config.getSftpUsername()).thenReturn(SFTP_USERNAME);
        lenient().when(config.getSource()).thenReturn(Interface.CAPS_REPORT);
        lenient().when(config.getTarget()).thenReturn(Interface.OPAL);
        lenient().when(config.getContainerName()).thenReturn(CONTAINER);
        lenient().when(config.getFileNameRegex()).thenReturn(Pattern.compile("matching-.*\\.dat"));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    class SelectFilesToProcess {

        @Test
        void shouldReturnEmptyListWhenNoFilesExistInBais() {
            service.useDefaultFileSelection();
            when(baisSftpClient.listRegularFiles(SFTP_USERNAME)).thenReturn(List.of());

            assertThat(service.selectFilesToProcess(config)).isEmpty();

            assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsAnyOf(
                    "No files found in BAIS for user 'sftp-username' when processing source 'CAPS_REPORT'");
        }

        @Test
        void shouldReturnMatchingFilesAndLogIgnoredFiles() {
            service.useDefaultFileSelection();
            when(baisSftpClient.listRegularFiles(SFTP_USERNAME)).thenReturn(List.of(IGNORED_FILE, MATCHING_FILE));

            List<String> selectedFiles = service.selectFilesToProcess(config);

            assertThat(selectedFiles).containsExactly(MATCHING_FILE);
            assertThat(errorLogs()).contains(
                "Found 1 additional files in BAIS for user 'sftp-username' that did not match the regex for source "
                    + "'CAPS_REPORT' and were ignored: ignored-file.txt");
        }

        @Test
        void shouldReturnAllFilesWhenEveryFileMatches() {
            String secondMatchingFile = "matching-second.dat";
            service.useDefaultFileSelection();
            when(baisSftpClient.listRegularFiles(SFTP_USERNAME))
                .thenReturn(List.of(MATCHING_FILE, secondMatchingFile));

            List<String> selectedFiles = service.selectFilesToProcess(config);

            assertThat(selectedFiles).containsExactly(MATCHING_FILE, secondMatchingFile);
            assertThat(errorLogs()).isEmpty();
        }
    }

    @Nested
    class Run {

        @BeforeEach
        void setUp() {
            executeTransactionsImmediately();
            configureSuccessfulRun();
        }

        @Test
        void shouldProcessFilesReturnedBySelectFilesToProcess() {
            String selectedFile = "selected-by-override.txt";
            service.stubFilesToProcess(selectedFile);

            service.run(config);

            verify(baisSftpClient).downloadFile(eq(SFTP_USERNAME), eq(selectedFile), any());
        }

        @Test
        void shouldStoreAndDeleteDuplicateWithoutProcessing() {
            InterfaceFileEntity duplicate = InterfaceFileEntity.builder()
                .interfaceFileId(123L)
                .filestoreUuid(FILE_UUID)
                .source(Interface.CAPS_REPORT)
                .target(Interface.OPAL)
                .type(Type.SOURCE)
                .fileName(MATCHING_FILE)
                .checksum(CHECKSUM)
                .status(Status.SUCCESS)
                .createdDatetime(LocalDateTime.now(CLOCK))
                .opalDomain(Domain.MAINTENANCE)
                .build();

            when(repository.findByFileNameAndChecksumAndStatus(
                MATCHING_FILE, CHECKSUM, Status.SUCCESS)).thenReturn(Optional.of(duplicate));

            service.run(config);

            assertThat(service.processCount).isZero();
            assertThat(savedStatuses).containsExactly(Status.DUPLICATE);
            assertThat(service.lastSavedEntity.getFilestoreUuid()).isEqualTo(FILE_UUID);
            verify(blobStoreService, never()).uploadBaisFile(any(), any(), any(), any());
            assertThat(errorLogs()).contains(
                "File with name 'matching-file.dat' and checksum '3685d7f2b30e9b34b8d3e5496fb45506' for source "
                    + "'CAPS_REPORT' is a duplicate of 123");
            assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
                .isEqualTo("File with name 'matching-file.dat' and checksum '3685d7f2b30e9b34b8d3e5496fb45506' "
                    + "for source 'CAPS_REPORT' already processed skipping");
            verify(baisSftpClient).deleteFile(SFTP_USERNAME, MATCHING_FILE);
        }

        @Test
        void shouldRecordUploadFailureAndSupersedePreviousFailures() {
            InterfaceFileEntity firstFailure = failedEntity(10L);
            InterfaceFileEntity secondFailure = failedEntity(11L);

            when(repository.findAllByFileNameAndChecksumAndStatus(
                MATCHING_FILE, CHECKSUM, Status.FAILED)).thenReturn(List.of(firstFailure, secondFailure));

            doThrow(new BlobUploadException(
                UUID.randomUUID(), "test-container", new RuntimeException("storage said \"no\"\nretry later")))
                .when(blobStoreService)
                .uploadBaisFile(any(UUID.class), eq("test-container"), any(InputStream.class), eq(CHECKSUM));

            service.run(config);

            assertThat(savedStatuses).containsExactly(Status.FAILED);
            assertThat(service.lastSavedEntity.getFilestoreUuid()).isNull();
            assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
                .isEqualTo("Blob upload failed for file 'matching-file.dat': storage said \"no\"\nretry later");
            assertThat(firstFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
            assertThat(secondFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
            verify(baisSftpClient, never()).deleteFile(any(), any());
        }

        @Test
        void shouldRejectInvalidContentBeforeUploadAndKeepTheSourceFile() {
            service.validationFailure = new InvalidReportFileException("Invalid report", new IOException());
            InterfaceFileEntity previousFailure = failedEntity(10L);
            when(repository.findAllByFileNameAndChecksumAndStatus(
                MATCHING_FILE, CHECKSUM, Status.FAILED)).thenAnswer(invocation ->
                    service.lastSavedEntity == null ? List.of(previousFailure) : List.of(service.lastSavedEntity));

            service.run(config);

            assertThat(savedStatuses).containsExactly(Status.FAILED);
            assertThat(service.lastSavedEntity.getStatus()).isEqualTo(Status.FAILED);
            assertThat(service.lastSavedEntity.getFilestoreUuid()).isNull();
            assertThat(service.lastSavedEntity.getErrors()).contains("Invalid report");
            assertThat(previousFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
            assertThat(service.processCount).isZero();
            verify(blobStoreService, never()).uploadBaisFile(any(), any(), any(), any());
            verify(baisSftpClient, never()).deleteFile(any(), any());
        }

        @Test
        void shouldRecordProcessingFailureAndNotDeleteRemoteFile() {
            InterfaceFileEntity previousFailure = failedEntity(10L);
            when(repository.findAllByFileNameAndChecksumAndStatus(
                MATCHING_FILE, CHECKSUM, Status.FAILED)).thenReturn(List.of(previousFailure));
            service.processingFailure = new IllegalStateException("invalid \"record\"");

            service.run(config);

            assertThat(savedStatuses).containsExactly(Status.INGESTED, Status.FAILED);
            assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
                .isEqualTo("File 'matching-file.dat' could not be processed: invalid \"record\"");
            assertThat(previousFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
            verify(baisSftpClient, never()).deleteFile(any(), any());
            verify(transactionTemplate, times(2)).executeWithoutResult(any());
        }

        @Test
        void shouldSupersedePreviousFailuresAfterSuccessfulProcessing() {
            InterfaceFileEntity firstFailure = failedEntity(10L);
            InterfaceFileEntity secondFailure = failedEntity(11L);
            when(repository.findAllByFileNameAndChecksumAndStatus(
                MATCHING_FILE, CHECKSUM, Status.FAILED)).thenReturn(List.of(firstFailure, secondFailure));

            service.run(config);

            assertThat(service.lastProcessConfig).isSameAs(config);
            assertThat(firstFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
            assertThat(secondFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
        }

        @Test
        void shouldContinueWithNextSelectedFileAfterFailure() {
            String firstFile = "matching-first.dat";
            String secondFile = "matching-second.dat";
            service.stubFilesToProcess(firstFile, secondFile);
            doThrow(new BaisSftpFileDownloadException("first download failed"))
                .when(baisSftpClient).downloadFile(eq(SFTP_USERNAME), eq(firstFile), any());

            service.run(config);

            verify(baisSftpClient).downloadFile(eq(SFTP_USERNAME), eq(secondFile), any());
            assertThat(service.processCount).isOne();
        }

        @Test
        void uploadedFileHasChecksumFailureResultsInFailedEntity() {
            when(repository.findByFileNameAndChecksumAndStatus(
                MATCHING_FILE, CHECKSUM, Status.SUCCESS)).thenReturn(Optional.empty());

            doThrow(new BlobChecksumValidationException(
                FILE_UUID, CHECKSUM, "00000000000000000000000000000000"))
                .when(blobStoreService)
                .uploadBaisFile(any(UUID.class), eq("test-container"), any(InputStream.class), eq(CHECKSUM));

            service.run(config);

            assertThat(service.lastSavedEntity.getStatus()).isEqualTo(Status.FAILED);
            assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
                .isEqualTo("Blob checksum validation failed for filestore UUID '" + FILE_UUID + "': "
                    + "expected '3685d7f2b30e9b34b8d3e5496fb45506' but was '00000000000000000000000000000000'");
            assertThat(service.processCount).isZero();
            verify(baisSftpClient, never()).deleteFile(any(), any());
        }
    }

    private void configureSuccessfulRun() {
        service.stubFilesToProcess(MATCHING_FILE);
        lenient().doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(2);
            outputStream.write(FILE_CONTENT);
            return null;
        }).when(baisSftpClient).downloadFile(eq(SFTP_USERNAME), any(), any());
        lenient().when(repository.findByFileNameAndChecksumAndStatus(
            any(), eq(CHECKSUM), eq(Status.SUCCESS))).thenReturn(Optional.empty());
        lenient().when(repository.findAllByFileNameAndChecksumAndStatus(
            any(), eq(CHECKSUM), eq(Status.FAILED))).thenReturn(List.of());
        lenient().when(baisSftpClient.deleteFile(eq(SFTP_USERNAME), any())).thenReturn(true);
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            InterfaceFileEntity entity = invocation.getArgument(0);
            if (entity.getInterfaceFileId() == null) {
                entity.setInterfaceFileId(1L);
            }
            savedStatuses.add(entity.getStatus());
            service.lastSavedEntity = entity;
            return entity;
        });
    }

    private void executeTransactionsImmediately() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private InterfaceFileEntity failedEntity(long id) {
        return InterfaceFileEntity.builder()
            .interfaceFileId(id)
            .source(Interface.CAPS_REPORT)
            .target(Interface.OPAL)
            .type(Type.SOURCE)
            .fileName(MATCHING_FILE)
            .checksum(CHECKSUM)
            .status(Status.FAILED)
            .createdDatetime(LocalDateTime.now(CLOCK))
            .opalDomain(Domain.MAINTENANCE)
            .build();
    }

    private List<String> errorLogs() {
        return logAppender.list.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static class TestProcessor extends AbstractInterfaceFileProcessorService {

        private int processCount;
        private RuntimeException processingFailure;
        private InvalidReportFileException validationFailure;
        private InterfaceFileEntity lastSavedEntity;
        private BaisFileProcessorConfiguration lastProcessConfig;
        private List<String> filesToProcess;

        TestProcessor(
            FeatureFlagUtil featureFlagUtil,
            BaisSftpClient baisSftpClient,
            InterfaceFileBlobStoreService blobStoreService,
            InterfaceFilesRepository repository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
        ) {
            super(CLOCK, featureFlagUtil, baisSftpClient, blobStoreService, repository,
                transactionTemplate, objectMapper);
        }

        void stubFilesToProcess(String... fileNames) {
            filesToProcess = List.of(fileNames);
        }

        void useDefaultFileSelection() {
            filesToProcess = null;
        }

        @Override
        protected List<String> selectFilesToProcess(BaisFileProcessorConfiguration config) {
            return filesToProcess == null ? super.selectFilesToProcess(config) : filesToProcess;
        }

        @Override
        protected void validateFile(InputStream inputStream) {
            if (validationFailure != null) {
                throw validationFailure;
            }
        }

        @Override
        protected void processFile(
            BaisFileProcessorConfiguration config,
            InterfaceFileEntity fileEntity,
            InputStream inputStream
        ) {
            processCount++;
            lastProcessConfig = config;

            if (processingFailure != null) {
                throw processingFailure;
            }
        }
    }
}
