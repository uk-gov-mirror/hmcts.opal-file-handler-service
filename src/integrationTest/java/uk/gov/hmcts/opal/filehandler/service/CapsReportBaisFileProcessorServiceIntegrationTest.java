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
import uk.gov.hmcts.opal.filehandler.config.CapsReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "launchdarkly.default-flag-values.caps-report-file-transfer-job=true",
})
@Slf4j
public class CapsReportBaisFileProcessorServiceIntegrationTest extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String CAPS_FILE = "CapFa.GB.20260701.173024.xml";
    private static final String CAPS_FILE_2 = "CapFa.GB.20260702.173024.xml";
    private static final String CAPS_FILE_CHECKSUM = "1a78ae802423eb5d7cd9b878e318517c";
    private static final String CAPS_FILE_CHECKSUM_2 = "06d22729aa16ab128fbcb1b937afe94c";
    private static final String CAPS_FILE_RESOURCE = "bais-emulator/" + CAPS_FILE;
    private static final String CAPS_FILE_RESOURCE_2 = "bais-emulator/" + CAPS_FILE_2;
    private static final String CAPS_FILE_CONTAINER = "/home/CAPS-report/" + CAPS_FILE;
    private static final String CAPS_FILE_CONTAINER_2 = "/home/CAPS-report/" + CAPS_FILE_2;

    @Autowired
    private CapsReportBaisFileProcessorService capsReportBaisFileProcessorService;

    @Autowired
    private CapsReportBaisFileProcessorConfiguration capsReportBaisFileProcessorConfiguration;

    private final Logger logger = (Logger) LoggerFactory.getLogger(AbstractInterfaceFileProcessorService.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        clearReportFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(),
            capsReportBaisFileProcessorConfiguration.getContainerName());

        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clearReportFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(),
            capsReportBaisFileProcessorConfiguration.getContainerName());
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.caps-report-file-transfer-job=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'release-1c-banking-interfaces' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values.caps-report-file-transfer-job=false"
    })
    public class CapsReportFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'caps-report-file-transfer-job' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage("caps-report-file-transfer-job is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.caps-report-file-transfer-job=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("AC1: Both feature flags are false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Test
    @DisplayName("AC2: CAPS file is present, read and stored correctly")
    void capsReportBaisFileProcessorServiceShouldRunSuccesfully() throws IOException {
        uploadResourceToSftp(CAPS_FILE_RESOURCE, CAPS_FILE_CONTAINER);
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertSuccessfulInterfaceFile(CAPS_FILE, CAPS_FILE_CHECKSUM, Interface.CAPS_REPORT, Type.SOURCE,
            Domain.MAINTENANCE);
        assertBlobChecksum(CAPS_FILE, CAPS_FILE_CHECKSUM, capsReportBaisFileProcessorConfiguration.getContainerName());
        assertNumberOfSftpFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(), 0);
        assertReportCanBeListedAndDownloaded(CAPS_FILE, CAPS_FILE_CHECKSUM, CAPS_FILE_RESOURCE);
    }

    @Test
    @DisplayName("AC3: When no files are present the service should not fail")
    void whenNoFilesArePresentServiceSucceeds() {
        assertNumberOfSftpFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(), 0);
        final var before = storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName());
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);
        assertThat(storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName())).isEqualTo(before);

        assertThat(repository.findAll()).isEmpty();
        assertThat(logAppender.list)
            .filteredOn(event -> event.getLevel() == Level.INFO)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsAnyOf(String.format("No files found in BAIS for user '%s' when processing source 'CAPS_REPORT'",
                capsReportBaisFileProcessorConfiguration.getSftpUsername()));
    }

    @Test
    @DisplayName("AC4: Duplicate file with previous success should reject")
    void duplicateFileShouldReject() {
        uploadResourceToSftp(CAPS_FILE_RESOURCE, CAPS_FILE_CONTAINER);
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);
        final InterfaceFileEntity success = assertSuccessfulInterfaceFile(
            CAPS_FILE, CAPS_FILE_CHECKSUM, Interface.CAPS_REPORT, Type.SOURCE, Domain.MAINTENANCE);
        final var before = storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName());

        uploadResourceToSftp(CAPS_FILE_RESOURCE, CAPS_FILE_CONTAINER);
        uploadResourceToSftp(CAPS_FILE_RESOURCE_2, CAPS_FILE_CONTAINER_2);
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertEntitiesWithStatus(CAPS_FILE_2, CAPS_FILE_CHECKSUM_2, Status.SUCCESS);
        assertEntitiesWithStatus(CAPS_FILE, CAPS_FILE_CHECKSUM, Status.DUPLICATE);
        assertNumberOfSftpFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(), 0);

        assertBlobChecksum(
            CAPS_FILE_2, CAPS_FILE_CHECKSUM_2, capsReportBaisFileProcessorConfiguration.getContainerName());

        var duplicate = repository.findByFileNameAndChecksumAndStatus(
            CAPS_FILE, CAPS_FILE_CHECKSUM, Status.DUPLICATE).orElseThrow();
        assertThat(duplicate.getFilestoreUuid()).isEqualTo(success.getFilestoreUuid());
        var second = repository.findByFileNameAndChecksumAndStatus(
            CAPS_FILE_2, CAPS_FILE_CHECKSUM_2, Status.SUCCESS).orElseThrow();
        var after = storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName());
        assertThat(after.remove(second.getFilestoreUuid().toString())).isNotNull();
        assertThat(after).isEqualTo(before);

        assertThat(logAppender.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(
                String.format("File with name '%s' and checksum '%s' for source 'CAPS_REPORT' is a duplicate of %s",
                    CAPS_FILE, CAPS_FILE_CHECKSUM, success.getInterfaceFileId()));
    }

    @Test
    @DisplayName("AC5: Duplicate file with no previous success should process")
    void processDuplicateWithoutPreviousSuccess() {
        createFailedInterfaceFile(CAPS_FILE, CAPS_FILE_CHECKSUM, Interface.CAPS_REPORT);
        uploadResourceToSftp(CAPS_FILE_RESOURCE, CAPS_FILE_CONTAINER);

        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertNumberOfSftpFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(), 0);
        assertEntitiesWithStatus(CAPS_FILE, CAPS_FILE_CHECKSUM, Status.FAILED_SUPERSEDED);
        assertSuccessfulInterfaceFile(CAPS_FILE, CAPS_FILE_CHECKSUM, Interface.CAPS_REPORT, Type.SOURCE,
            Domain.MAINTENANCE);
        assertBlobChecksum(CAPS_FILE, CAPS_FILE_CHECKSUM, capsReportBaisFileProcessorConfiguration.getContainerName());
    }


    @Test
    @DisplayName("PO-6382: Unsupported filename is retained without storing a report")
    void unsupportedFilenameIsIgnored() {
        final var before = storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName());
        uploadResourceToSftp(CAPS_FILE_RESOURCE, CAPS_FILE_CONTAINER + ".unsupported");

        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertThat(repository.findAll()).isEmpty();
        assertThat(storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName())).isEqualTo(before);
        assertThat(sftpClient.listRegularFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername()))
            .containsExactly(CAPS_FILE + ".unsupported");
    }

    @Test
    @DisplayName("PO-6382: Malformed report is retained and its failed retry remains traceable")
    void malformedReportRetryDoesNotUpload() throws IOException {
        final var before = storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName());
        uploadResourceToSftp("bais-emulator/malformed-report.txt", CAPS_FILE_CONTAINER);
        String checksum;
        try (var input = getClass().getClassLoader().getResourceAsStream("bais-emulator/malformed-report.txt")) {
            checksum = DigestUtils.md5DigestAsHex(input);
        }

        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        var failed = repository.findByFileNameAndChecksumAndStatus(
            CAPS_FILE, checksum, Status.FAILED).orElseThrow();
        assertThat(failed.getFilestoreUuid()).isNull();
        assertThat(failed.getErrors()).contains("CAPS report was not valid XML");
        assertThat(storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName())).isEqualTo(before);
        assertThat(sftpClient.listRegularFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername()))
            .containsExactly(CAPS_FILE);

        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findById(failed.getInterfaceFileId()).orElseThrow().getStatus())
            .isEqualTo(Status.FAILED_SUPERSEDED);
        var retry = repository.findByFileNameAndChecksumAndStatus(
            CAPS_FILE, checksum, Status.FAILED).orElseThrow();
        assertThat(retry.getInterfaceFileId()).isNotEqualTo(failed.getInterfaceFileId());
        assertThat(retry.getFilestoreUuid()).isNull();
        assertThat(retry.getErrors()).contains("CAPS report was not valid XML");
        assertThat(storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName())).isEqualTo(before);
        assertThat(sftpClient.listRegularFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername()))
            .containsExactly(CAPS_FILE);
    }

    @Test
    @DisplayName("PO-6382: Corrected report succeeds and preserves the failed attempt")
    void correctedReportCanBeIngested() throws IOException {
        uploadResourceToSftp("bais-emulator/malformed-report.txt", CAPS_FILE_CONTAINER);
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);
        assertThat(repository.findAll()).singleElement().satisfies(file ->
            assertThat(file.getStatus()).isEqualTo(Status.FAILED));
        final var failedId = repository.findAll().getFirst().getInterfaceFileId();

        uploadResourceToSftp(CAPS_FILE_RESOURCE, CAPS_FILE_CONTAINER);
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findById(failedId).orElseThrow().getStatus()).isEqualTo(Status.FAILED);
        var success = assertSuccessfulInterfaceFile(
            CAPS_FILE, CAPS_FILE_CHECKSUM, Interface.CAPS_REPORT, Type.SOURCE, Domain.MAINTENANCE);
        assertThat(storedBlobs(capsReportBaisFileProcessorConfiguration.getContainerName()))
            .containsOnlyKeys(success.getFilestoreUuid().toString());
        assertBlobChecksum(CAPS_FILE, CAPS_FILE_CHECKSUM, capsReportBaisFileProcessorConfiguration.getContainerName());
        assertReportCanBeListedAndDownloaded(CAPS_FILE, CAPS_FILE_CHECKSUM, CAPS_FILE_RESOURCE);
        assertNumberOfSftpFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername(), 0);
    }

}
