package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.CapsReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.support.AbstractIntegrationTest;
import uk.gov.hmcts.opal.filehandler.support.TestContainerConfig;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;

@ActiveProfiles("integration")
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "opal.file-handler-service.file-types.caps-report.sftp-username=CAPS-report",
    "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
    "launchdarkly.default-flag-values.CAPS-Report-file-transfer-job=true",
})
@Slf4j
@Testcontainers
public class CapsReportBaisFileProcessorServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String CAPS_FILE = "CapFa.GB.20260701.173024.xml";
    private static final String CAPS_FILE_CHECKSUM = "1a78ae802423eb5d7cd9b878e318517c";
    private static final String CAPS_FILE_RESOURCE = "src/integrationTest/resources/bais-emulator/" + CAPS_FILE;
    private static final String CAPS_FILE_CONTAINER = "/home/CAPS-report/" + CAPS_FILE;

    @Autowired
    private InterfaceFilesRepository repository;

    @Autowired
    private BaisSftpClient baisSftpClient;

    @Autowired
    private CapsReportBaisFileProcessorService capsReportBaisFileProcessorService;

    @Autowired
    private CapsReportBaisFileProcessorConfiguration capsReportBaisFileProcessorConfiguration;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("opal.file-handler-service.file-store.connection-string",
            TestContainerConfig::azuriteConnectionString);

        var pk = Files.readString(
            Path.of("src/integrationTest/resources/bais-emulator/client-keys/CAPS-report/bais-sftp-key"));

        registry.add("opal.file-handler-service.sftp.bais.private-key", () -> pk);
    }

    @Nested
    @SpringBootTest(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.CAPS-Report-file-transfer-job=true"
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
    @SpringBootTest(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values.CAPS-Report-file-transfer-job=false"
    })
    public class CapsReportFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'CAPS-Report-file-transfer-job' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage("CAPS-Report-file-transfer-job is not enabled");
        }

    }

    @Test
    @DisplayName("AC2: CAPS file is present, read and stored correctly")
    void capsReportBaisFileProcessorServiceShouldRunSuccesfully() {
        uploadCapsFileToSftp();

        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        assertThat(baisSftpClient.listRegularFiles(capsReportBaisFileProcessorConfiguration.getSftpUsername()).size())
            .isEqualTo(0);

        List<InterfaceFileEntity> allEntities = repository.findAllByFileName(CAPS_FILE);
        assertThat(allEntities.size()).isEqualTo(1);

        InterfaceFileEntity entity = allEntities.getFirst();
        assertThat(entity.getFileName()).isEqualTo(CAPS_FILE);
        assertThat(entity.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(entity.getChecksum()).isEqualTo(CAPS_FILE_CHECKSUM);

    }

    @Test
    @DisplayName("AC3: When no files are present the service should not fail")
    void whenNoFilesArePresentServiceSucceeds() {
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);
    }

    @Test
    @DisplayName("AC4: Duplicate file with previous success should reject")
    void duplicateFileShouldReject() {
        uploadCapsFileToSftp();
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        uploadCapsFileToSftp();
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        List<InterfaceFileEntity> allEntities = repository.findAllByFileName(CAPS_FILE);
        assertThat(allEntities.size()).isEqualTo(2);

        InterfaceFileEntity entity = allEntities.getLast();
        assertThat(entity.getFileName()).isEqualTo(CAPS_FILE);
        assertThat(entity.getStatus()).isEqualTo(Status.DUPLICATE);
        assertThat(entity.getChecksum()).isEqualTo(CAPS_FILE_CHECKSUM);
    }

    @Test
    @DisplayName("AC5: Duplicate file with no previous success should process")
    void processDuplicateWithoutPreviousSuccess() {
        uploadCapsFileToSftp();
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        var entity = repository.findAllByFileName(CAPS_FILE)
            .getFirst();

        entity.setStatus(Status.FAILED);
        repository.save(entity);

        uploadCapsFileToSftp();
        capsReportBaisFileProcessorService.run(capsReportBaisFileProcessorConfiguration);

        List<InterfaceFileEntity> allEntities = repository.findAllByFileName(CAPS_FILE);
        assertThat(allEntities.size()).isEqualTo(2);

        InterfaceFileEntity entity2 = allEntities.getLast();
        assertThat(entity2.getFileName()).isEqualTo(CAPS_FILE);
        assertThat(entity2.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(entity2.getChecksum()).isEqualTo(CAPS_FILE_CHECKSUM);
    }

    private void uploadCapsFileToSftp() {
        TestContainerConfig.SFTP_CONTAINER.copyFileToContainer(
            MountableFile.forHostPath(CAPS_FILE_RESOURCE), CAPS_FILE_CONTAINER);
    }

}
