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
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;

@ExtendWith(MockitoExtension.class)
class AbstractBaisFileProcessorServiceTest {

    private static final String TEST_FEATURE_FLAG = "test-feature-flag";
    private static final String SFTP_USERNAME = "sftp-username";
    private static final String MATCHING_FILE = "matching-file.dat";
    private static final String IGNORED_FILE = "ignored-file.txt";
    private static final String CHECKSUM = "3685d7f2b30e9b34b8d3e5496fb45506";
    private static final byte[] FILE_CONTENT = {0, 1, 13, 10, (byte) 255};

    @Mock
    private FeatureFlagUtil featureFlagUtil;

    @Mock
    private BaisSftpClient baisSftpClient;

    @Mock
    private InterfaceFileBlobStoreService interfaceFileBlobStoreService;

    @Mock
    private InterfaceFilesRepository interfaceFilesRepository;

    @Mock
    private BaisFileProcessorConfiguration baisFileProcessorConfiguration;

    @Mock
    private TransactionTemplate transactionTemplate;

    private TestBaisFileProcessorService service;
    private Clock clock;
    private ObjectMapper objectMapper;
    private List<Status> savedStatuses;

    private final Logger logger = (Logger) LoggerFactory.getLogger(AbstractBaisFileProcessorService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        objectMapper = JsonMapper.builder().build();
        savedStatuses = new ArrayList<>();

        logAppender.start();
        logger.addAppender(logAppender);

        service = new TestBaisFileProcessorService(
            Clock.systemUTC(),
            featureFlagUtil,
            baisSftpClient,
            interfaceFileBlobStoreService,
            interfaceFilesRepository,
            transactionTemplate,
            objectMapper
        );

        executeTransactionsImmediately();
        configureSuccessfulRun();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void whenNoFilesExistInBaisShouldLogAndExit() {
        when(baisSftpClient.listRegularFiles(SFTP_USERNAME)).thenReturn(List.of());

        service.run(baisFileProcessorConfiguration);

        assertThat(logAppender.list)
            .filteredOn(event -> event.getLevel() == Level.INFO)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("No files found in BAIS for user 'sftp-username' when processing source 'CAPS_REPORT'");

        verify(baisSftpClient, never()).downloadFile(any(), any(), any());
    }

    @Test
    void ignoresNonMatchingFilesAndProcessesMatchingFiles() {
        when(baisSftpClient.listRegularFiles(SFTP_USERNAME)).thenReturn(List.of(IGNORED_FILE, MATCHING_FILE));

        service.run(baisFileProcessorConfiguration);

        verify(baisSftpClient).downloadFile(eq(SFTP_USERNAME), eq(MATCHING_FILE), any());
        verify(baisSftpClient, never()).downloadFile(eq(SFTP_USERNAME), eq(IGNORED_FILE), any());
        assertThat(errorLogs()).contains(
            "Found 1 additional files in BAIS for user 'sftp-username' that did not match the regex for source "
                + "'CAPS_REPORT' and were ignored: ignored-file.txt"
        );
    }

    @Test
    void duplicateIsStoredReportedAndDeletedWithoutProcessing() {
        InterfaceFileEntity duplicate = InterfaceFileEntity.builder()
            .interfaceFileId(123L)
            .source(Interface.CAPS_REPORT)
            .target(Interface.OPAL)
            .type(uk.gov.hmcts.opal.filehandler.entity.Type.SOURCE)
            .fileName(MATCHING_FILE)
            .checksum(CHECKSUM)
            .status(Status.SUCCESS)
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(Domain.MAINTENANCE)
            .build();

        when(interfaceFilesRepository.findByFileNameAndChecksumAndStatus(
            MATCHING_FILE, CHECKSUM, Status.SUCCESS)).thenReturn(Optional.of(duplicate));

        service.run(baisFileProcessorConfiguration);

        assertThat(service.processCount).isZero();
        assertThat(savedStatuses).containsExactly(Status.DUPLICATE);
        assertThat(errorLogs()).contains(
            "File with name 'matching-file.dat' and checksum '3685d7f2b30e9b34b8d3e5496fb45506' for source "
                + "'CAPS_REPORT' is a duplicate of 123"
        );
        assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
            .isEqualTo("File with name 'matching-file.dat' and checksum '3685d7f2b30e9b34b8d3e5496fb45506' "
                + "for source 'CAPS_REPORT' already processed skipping");
        verify(baisSftpClient).deleteFile(SFTP_USERNAME, MATCHING_FILE);
    }

    @Test
    void uploadFailureCreatesFailedFileAndSupersedesEveryPreviousFailure() {
        InterfaceFileEntity firstFailure = failedEntity(10L);
        InterfaceFileEntity secondFailure = failedEntity(11L);

        when(interfaceFilesRepository.findAllByFileNameAndChecksumAndStatus(
            MATCHING_FILE, CHECKSUM, Status.FAILED)).thenReturn(List.of(firstFailure, secondFailure));

        doThrow(new BlobUploadException(
            UUID.randomUUID(), "test-container", new RuntimeException("storage said \"no\"\nretry later")))
            .when(interfaceFileBlobStoreService)
            .uploadBaisFile(any(UUID.class), eq("test-container"), any(InputStream.class), eq(CHECKSUM));

        service.run(baisFileProcessorConfiguration);

        assertThat(savedStatuses).containsExactly(Status.FAILED);
        assertThat(service.lastSavedEntity.getFilestoreUuid()).isNull();
        assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
            .isEqualTo("Blob upload failed for file 'matching-file.dat': storage said \"no\"\nretry later");
        assertThat(firstFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
        assertThat(secondFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
        verify(baisSftpClient, never()).deleteFile(any(), any());
    }

    @Test
    void uploadFailureIsRetainedWhenDuplicateExists() {
        InterfaceFileEntity duplicate = InterfaceFileEntity.builder()
            .interfaceFileId(123L)
            .source(Interface.CAPS_REPORT)
            .target(Interface.OPAL)
            .type(Type.SOURCE)
            .fileName(MATCHING_FILE)
            .checksum(CHECKSUM)
            .status(Status.SUCCESS)
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(Domain.MAINTENANCE)
            .build();

        when(interfaceFilesRepository.findByFileNameAndChecksumAndStatus(
            MATCHING_FILE, CHECKSUM, Status.SUCCESS)).thenReturn(Optional.of(duplicate));
        doThrow(new BlobUploadException(
            UUID.randomUUID(), "test-container", new RuntimeException("storage unavailable")))
            .when(interfaceFileBlobStoreService)
            .uploadBaisFile(any(UUID.class), eq("test-container"), any(InputStream.class), eq(CHECKSUM));

        service.run(baisFileProcessorConfiguration);

        assertThat(savedStatuses).containsExactly(Status.FAILED);
        assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
            .isEqualTo("Blob upload failed for file 'matching-file.dat': storage unavailable");
        assertThat(service.processCount).isZero();
        verify(baisSftpClient, never()).deleteFile(any(), any());
    }

    @Test
    void processingFailureRollsBackThenPersistsFailureAndSupersedesPreviousFailures() {
        InterfaceFileEntity previousFailure = failedEntity(10L);
        when(interfaceFilesRepository.findAllByFileNameAndChecksumAndStatus(
            MATCHING_FILE, CHECKSUM, Status.FAILED)).thenReturn(List.of(previousFailure));
        service.processingFailure = new IllegalStateException("invalid \"record\"");

        service.run(baisFileProcessorConfiguration);

        assertThat(savedStatuses).containsExactly(Status.INGESTED, Status.FAILED);
        assertThat(objectMapper.readTree(service.lastSavedEntity.getErrors()).get("message").asString())
            .isEqualTo("File 'matching-file.dat' could not be processed: invalid \"record\"");
        assertThat(previousFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
        verify(baisSftpClient, never()).deleteFile(any(), any());
        verify(transactionTemplate, times(2)).executeWithoutResult(any());
    }

    @Test
    void successfulProcessingSupersedesEveryPreviousFailure() {
        InterfaceFileEntity firstFailure = failedEntity(10L);
        InterfaceFileEntity secondFailure = failedEntity(11L);
        when(interfaceFilesRepository.findAllByFileNameAndChecksumAndStatus(
            MATCHING_FILE, CHECKSUM, Status.FAILED)).thenReturn(List.of(firstFailure, secondFailure));

        service.run(baisFileProcessorConfiguration);

        assertThat(firstFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
        assertThat(secondFailure.getStatus()).isEqualTo(Status.FAILED_SUPERSEDED);
    }

    @Test
    void failureOnOneFileDoesNotPreventNextMatchingFileFromBeingProcessed() {
        String firstFile = "matching-first.dat";
        String secondFile = "matching-second.dat";
        when(baisSftpClient.listRegularFiles(SFTP_USERNAME)).thenReturn(List.of(firstFile, secondFile));
        doThrow(new BaisSftpFileDownloadException("first download failed"))
            .when(baisSftpClient).downloadFile(eq(SFTP_USERNAME), eq(firstFile), any());

        service.run(baisFileProcessorConfiguration);

        verify(baisSftpClient).downloadFile(eq(SFTP_USERNAME), eq(secondFile), any());
        assertThat(service.processCount).isOne();
    }

    private void configureSuccessfulRun() {
        lenient().when(baisFileProcessorConfiguration.getFeatureFlag()).thenReturn(TEST_FEATURE_FLAG);
        lenient().when(baisFileProcessorConfiguration.getSftpUsername()).thenReturn(SFTP_USERNAME);
        lenient().when(baisFileProcessorConfiguration.getSource()).thenReturn(Interface.CAPS_REPORT);
        lenient().when(baisFileProcessorConfiguration.getTarget()).thenReturn(Interface.OPAL);
        lenient().when(baisFileProcessorConfiguration.getContainerName()).thenReturn("test-container");
        lenient().when(baisFileProcessorConfiguration.getFileNameRegex())
            .thenReturn(Pattern.compile("matching-.*\\.dat"));
        lenient().when(baisSftpClient.listRegularFiles(SFTP_USERNAME)).thenReturn(List.of(MATCHING_FILE));
        lenient().doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(2);
            outputStream.write(FILE_CONTENT);
            return null;
        }).when(baisSftpClient).downloadFile(eq(SFTP_USERNAME), any(), any());
        lenient().when(interfaceFilesRepository.findByFileNameAndChecksumAndStatus(
            any(), eq(CHECKSUM), eq(Status.SUCCESS))).thenReturn(Optional.empty());
        lenient().when(interfaceFilesRepository.findAllByFileNameAndChecksumAndStatus(
            any(), eq(CHECKSUM), eq(Status.FAILED))).thenReturn(List.of());
        lenient().when(baisSftpClient.deleteFile(eq(SFTP_USERNAME), any())).thenReturn(true);
        lenient().when(interfaceFilesRepository.save(any())).thenAnswer(invocation -> {
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
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(Domain.MAINTENANCE)
            .build();
    }

    private List<String> errorLogs() {
        return logAppender.list.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static class TestBaisFileProcessorService extends AbstractBaisFileProcessorService {

        private int processCount;
        private RuntimeException processingFailure;
        private InterfaceFileEntity lastSavedEntity;

        TestBaisFileProcessorService(Clock clock,
            FeatureFlagUtil featureFlagUtil,
            BaisSftpClient baisSftpClient,
            InterfaceFileBlobStoreService interfaceFileBlobStoreService,
            InterfaceFilesRepository interfaceFilesRepository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
        ) {
            super(clock, featureFlagUtil, baisSftpClient, interfaceFileBlobStoreService, interfaceFilesRepository,
                transactionTemplate, objectMapper);
        }

        @Override
        protected void processFile(InterfaceFileEntity fileEntity, InputStream inputStream) {
            processCount++;

            if (processingFailure != null) {
                throw processingFailure;
            }
        }
    }
}
