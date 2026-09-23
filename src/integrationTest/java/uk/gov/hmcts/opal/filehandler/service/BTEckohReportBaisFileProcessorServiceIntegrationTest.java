package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import org.springframework.util.DigestUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.BTEckohReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "launchdarkly.default-flag-values.bteckoh-report-file-transfer-job=true",
})
@Slf4j
public class BTEckohReportBaisFileProcessorServiceIntegrationTest
    extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String BTECKOH_FILE = "2498-MCPLDB-MOJ-Payments-Report-Daily-2026-07-06-06-00-18.xlsx";
    private static final String BTECKOH_FILE_2 = "2498-MCPLDB-MOJ-Payments-Report-Daily-2026-07-07-06-00-18.xlsx";
    private static final String BTECKOH_FILE_CHECKSUM = "d553f8f289bd08e5c513de5c000c0374";
    private static final String BTECKOH_FILE_CHECKSUM_2 = "30276e2620c23e98d2c227efca8b220c";
    private static final String BTECKOH_FILE_RESOURCE = "bais-emulator/" + BTECKOH_FILE;
    private static final String BTECKOH_FILE_RESOURCE_2 = "bais-emulator/" + BTECKOH_FILE_2;
    private static final String BTECKOH_FILE_CONTAINER = "/home/BTEckoh-report/" + BTECKOH_FILE;
    private static final String BTECKOH_FILE_CONTAINER_2 = "/home/BTEckoh-report/" + BTECKOH_FILE_2;

    @Autowired
    private BTEckohReportBaisFileProcessorService service;

    @Autowired
    private BTEckohReportBaisFileProcessorConfiguration config;

    private final Logger logger = (Logger) LoggerFactory.getLogger(AbstractInterfaceFileProcessorService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        clearReportFiles(config.getSftpUsername(), config.getContainerName());

        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clearReportFiles(config.getSftpUsername(), config.getContainerName());
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.bteckoh-report-file-transfer-job=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'release-1c-banking-interfaces' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(config));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values.bteckoh-report-file-transfer-job=false"
    })
    public class BTEckohReportFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'bteckoh-report-file-transfer-job' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(config));

            assertThat(exception).hasMessage("bteckoh-report-file-transfer-job is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.bteckoh-report-file-transfer-job=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("AC1: Both feature flags are false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(config));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Test
    @DisplayName("AC2: BTEckoh-Report file is present, read and stored correctly")
    void btEckohReportBaisFileProcessorServiceShouldRunSuccesfully() throws IOException {
        uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER);
        service.run(config);

        assertSuccessfulInterfaceFile(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Interface.BTECKOH_REPORT, Type.SOURCE,
            Domain.MAINTENANCE);
        assertBlobChecksum(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, config.getContainerName());
        assertNumberOfSftpFiles(config.getSftpUsername(), 0);
        assertReportCanBeListedAndDownloaded(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, BTECKOH_FILE_RESOURCE);
    }

    @Test
    @DisplayName("AC3: When no files are present the service should not fail")
    void whenNoFilesArePresentServiceSucceeds() {
        assertNumberOfSftpFiles(config.getSftpUsername(), 0);
        final var before = storedBlobs(config.getContainerName());
        service.run(config);
        assertThat(storedBlobs(config.getContainerName())).isEqualTo(before);

        assertThat(repository.findAll()).isEmpty();
        assertThat(logAppender.list)
            .filteredOn(event -> event.getLevel() == Level.INFO)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsAnyOf(
                String.format("No files found in BAIS for user '%s' when processing source 'BTECKOH_REPORT'",
                    config.getSftpUsername()));
    }

    @Test
    @DisplayName("AC4: Duplicate file with previous success should reject")
    void duplicateFileShouldReject() {
        uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER);
        service.run(config);
        final InterfaceFileEntity success = assertSuccessfulInterfaceFile(
            BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Interface.BTECKOH_REPORT, Type.SOURCE, Domain.MAINTENANCE);
        final var before = storedBlobs(config.getContainerName());
        uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER);
        uploadResourceToSftp(BTECKOH_FILE_RESOURCE_2, BTECKOH_FILE_CONTAINER_2);

        service.run(config);

        assertNumberOfEntitiesWithStatus(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Status.SUCCESS, 1);
        assertNumberOfEntitiesWithStatus(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Status.DUPLICATE, 1);
        assertNumberOfEntitiesWithStatus(BTECKOH_FILE_2, BTECKOH_FILE_CHECKSUM_2, Status.SUCCESS, 1);
        assertNumberOfSftpFiles(config.getSftpUsername(), 0);

        assertBlobChecksum(BTECKOH_FILE_2, BTECKOH_FILE_CHECKSUM_2, config.getContainerName());

        var duplicate = repository.findByFileNameAndChecksumAndStatus(
            BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Status.DUPLICATE).orElseThrow();
        assertThat(duplicate.getFilestoreUuid()).isEqualTo(success.getFilestoreUuid());
        var second = repository.findByFileNameAndChecksumAndStatus(
            BTECKOH_FILE_2, BTECKOH_FILE_CHECKSUM_2, Status.SUCCESS).orElseThrow();
        var after = storedBlobs(config.getContainerName());
        assertThat(after.remove(second.getFilestoreUuid().toString())).isNotNull();
        assertThat(after).isEqualTo(before);

        assertThat(logAppender.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(
                String.format("File with name '%s' and checksum '%s' for source 'BTECKOH_REPORT' is a duplicate of %s",
                    BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, success.getInterfaceFileId()));
    }


    @Test
    @DisplayName("AC5: Duplicate file with no previous success should process")
    void processDuplicateWithoutPreviousSuccess() {
        createFailedInterfaceFile(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Interface.BTECKOH_REPORT);
        uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER);

        service.run(config);

        assertNumberOfSftpFiles(config.getSftpUsername(), 0);
        assertEntitiesWithStatus(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Status.FAILED_SUPERSEDED);
        assertSuccessfulInterfaceFile(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Interface.BTECKOH_REPORT, Type.SOURCE,
            Domain.MAINTENANCE);
        assertBlobChecksum(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, config.getContainerName());
    }


    @Test
    @DisplayName("PO-6382: Unsupported filename is retained without storing a report")
    void unsupportedFilenameIsIgnored() {
        final var before = storedBlobs(config.getContainerName());
        uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER + ".unsupported");

        service.run(config);

        assertThat(repository.findAll()).isEmpty();
        assertThat(storedBlobs(config.getContainerName())).isEqualTo(before);
        assertThat(sftpClient.listRegularFiles(config.getSftpUsername()))
            .containsExactly(BTECKOH_FILE + ".unsupported");
    }

    @Test
    @DisplayName("PO-6382: Malformed report is retained and its failed retry remains traceable")
    void malformedReportRetryDoesNotUpload() throws IOException {
        final var before = storedBlobs(config.getContainerName());
        uploadResourceToSftp("bais-emulator/malformed-report.txt", BTECKOH_FILE_CONTAINER);
        String checksum;
        try (var input = getClass().getClassLoader().getResourceAsStream("bais-emulator/malformed-report.txt")) {
            checksum = DigestUtils.md5DigestAsHex(input);
        }

        service.run(config);

        var failed = repository.findByFileNameAndChecksumAndStatus(
            BTECKOH_FILE, checksum, Status.FAILED).orElseThrow();
        assertThat(failed.getFilestoreUuid()).isNull();
        assertThat(failed.getErrors()).contains("BTEckoh report was not a valid XLSX workbook");
        assertThat(storedBlobs(config.getContainerName())).isEqualTo(before);
        assertThat(sftpClient.listRegularFiles(config.getSftpUsername())).containsExactly(BTECKOH_FILE);

        service.run(config);

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findById(failed.getInterfaceFileId()).orElseThrow().getStatus())
            .isEqualTo(Status.FAILED_SUPERSEDED);
        var retry = repository.findByFileNameAndChecksumAndStatus(
            BTECKOH_FILE, checksum, Status.FAILED).orElseThrow();
        assertThat(retry.getInterfaceFileId()).isNotEqualTo(failed.getInterfaceFileId());
        assertThat(retry.getFilestoreUuid()).isNull();
        assertThat(retry.getErrors()).contains("BTEckoh report was not a valid XLSX workbook");
        assertThat(storedBlobs(config.getContainerName())).isEqualTo(before);
        assertThat(sftpClient.listRegularFiles(config.getSftpUsername())).containsExactly(BTECKOH_FILE);
    }

    @Test
    @DisplayName("PO-6382: Corrected report succeeds and preserves the failed attempt")
    void correctedReportCanBeIngested() throws IOException {
        uploadResourceToSftp("bais-emulator/malformed-report.txt", BTECKOH_FILE_CONTAINER);
        service.run(config);
        assertThat(repository.findAll()).singleElement().satisfies(file ->
            assertThat(file.getStatus()).isEqualTo(Status.FAILED));
        final var failedId = repository.findAll().getFirst().getInterfaceFileId();

        uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER);
        service.run(config);

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findById(failedId).orElseThrow().getStatus()).isEqualTo(Status.FAILED);
        var success = assertSuccessfulInterfaceFile(
            BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Interface.BTECKOH_REPORT, Type.SOURCE, Domain.MAINTENANCE);
        assertThat(storedBlobs(config.getContainerName()))
            .containsOnlyKeys(success.getFilestoreUuid().toString());
        assertBlobChecksum(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, config.getContainerName());
        assertReportCanBeListedAndDownloaded(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, BTECKOH_FILE_RESOURCE);
        assertNumberOfSftpFiles(config.getSftpUsername(), 0);
    }

}
