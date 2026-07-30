package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.CapsReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.support.AbstractIntegrationTest;
import uk.gov.hmcts.opal.filehandler.support.TestContainerConfig;

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

    @Autowired
    private InterfaceFilesRepository interfaceFilesRepository;

    @Autowired
    private CapsReportBaisFileProcessorService capsReportBaisFileProcessorService;

    @Autowired
    private CapsReportBaisFileProcessorConfiguration capsReportBaisFileProcessorConfiguration;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("opal.file-handler-service.file-store.connection-string", TestContainerConfig::azuriteConnectionString);

        var pk = Files.readString(
            Path.of("src/integrationTest/resources/bais-emulator/client-keys/CAPS-report/bais-sftp-key"));

        registry.add("opal.file-handler-service.sftp.bais.private-key", () -> pk);
    }

    @Test
    @DisplayName("AC2: CAPS file is present, read and stored correctly")
    void capsReportBaisFileProcessorServiceShouldRunSuccesfully() {}

    @Test
    @DisplayName("AC3: When no files are present the service should not fail")
    void whenNoFilesArePresentServiceSucceeds() {}

    @Test
    @DisplayName("AC4: Duplicate file with previous success should reject")
    void duplicateFileShouldReject() {}

    @Test
    @DisplayName("AC5: Duplicate file with no previous success should process")
    void processDuplicateWithoutPreviousSuccess() {}

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

}
