package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.JacobsBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "opal.file-handler-service.file-types.bailiffs.jacobs.sftp-username=Jacobs",
    "launchdarkly.default-flag-values[bailiffs.jacobs-file-transfer-job]=true"
})
public class JacobsBaisFileProcessorServiceIntegrationTest
    extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String JACOBS_FILE = "0000031712_dat_0000098475_20260408_103500.txt";
    private static final String JACOBS_FILE_CHECKSUM = "74efc9e50988e6694fa6dd55a8e739f0";
    private static final String JACOBS_FILE_RESOURCE = "bais-emulator/" + JACOBS_FILE;
    private static final String JACOBS_FILE_CONTAINER = "/home/Jacobs/" + JACOBS_FILE;
    private static final String DWP_CODE = "0000031714";

    @Autowired
    private JacobsBaisFileProcessorService service;
    
    @Autowired
    private JacobsBaisFileProcessorConfiguration configuration;

    @Autowired
    private BusinessUnitBankAccountEntityTestData buBankAccountTestData;

    @MockitoBean
    private FinesInterfaceFilePreprocessQueueService queue;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        blobServiceClient.createBlobContainerIfNotExists(configuration.getContainerName());

        buBankAccountTestData.clear();
        buBankAccountTestData.saveTypicalBusinessUnitBankAccount(1L, "JA01", DWP_CODE);
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values[bailiffs.jacobs-file-transfer-job]=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'release-1c-banking-interfaces' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(configuration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values[bailiffs.jacobs-file-transfer-job]=false"
    })
    public class JacobsFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'bailiffs.jacobs-file-transfer-job' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(configuration));

            assertThat(exception).hasMessage("bailiffs.jacobs-file-transfer-job is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values[bailiffs.jacobs-file-transfer-job]=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("AC1: Both feature flags are false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(configuration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }
    }

    @Test
    @DisplayName("AC2: When Jacobs file is present it should be read and stored correctly")
    void whenJacobsFileIsPresentReadAndStoreCorrectly() {
        uploadResourceToSftp(JACOBS_FILE_RESOURCE, JACOBS_FILE_CONTAINER);

        service.run(configuration);

        var sourceFile = assertSuccessfulInterfaceFile(
            JACOBS_FILE, JACOBS_FILE_CHECKSUM, Interface.JACOBS, Type.SOURCE, Domain.FINES);

        var sourceJson = assertSuccessfulSourceJsonInterfaceFile(
            JACOBS_FILE, Interface.JACOBS, Domain.FINES, sourceFile.getInterfaceFileId());

        verify(queue).send(sourceJson.getInterfaceFileId());

        assertArrayEquals(sourceFile.getBusinessUnitCode(), sourceJson.getBusinessUnitCode());
        assertBlobChecksum(JACOBS_FILE, JACOBS_FILE_CHECKSUM, configuration.getContainerName());
        assertNumberOfSftpFiles(configuration.getSftpUsername(), 0);
    }
}
