package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.azure.storage.blob.BlobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.CderBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "launchdarkly.default-flag-values[bailiffs.cder-file-transfer-job]=true"
})
public class CderBaisFileProcessorServiceIntegrationTest extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String CDER_FILE = "0000031712_dat_0000098475_20260408_103500.txt";
    private static final String CDER_FILE_CHECKSUM = "74efc9e50988e6694fa6dd55a8e739f0";
    private static final String CDER_FILE_RESOURCE = "bais-emulator/" + CDER_FILE;
    private static final String CDER_FILE_CONTAINER = "/home/CDER/" + CDER_FILE;
    private static final String DWP_CODE = "0000031714";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CderBaisFileProcessorService service;

    @Autowired
    private CderBaisFileProcessorConfiguration configuration;

    @Autowired
    private BusinessUnitBankAccountEntityTestData businessUnitBankAccountEntityTestData;

    @MockitoBean
    private FinesInterfaceFilePreprocessQueueService finesQueueService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        businessUnitBankAccountEntityTestData.clear();
        businessUnitBankAccountEntityTestData.saveTypicalBusinessUnitBankAccount(1L, "BC12", DWP_CODE);
        blobServiceClient.createBlobContainerIfNotExists(configuration.getContainerName());
    }


    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values[bailiffs.cder-file-transfer-job]=false"
    })
    public class CderFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'bailiffs.cder-file-transfer-job' is false")
        void cderFileTransferJobIsDisabled() {
            FeatureDisabledException exception = assertThrows(
                FeatureDisabledException.class, () -> service.run(configuration)
            );
            assertThat(exception).hasMessage("bailiffs.cder-file-transfer-job is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values[bailiffs.cder-file-transfer-job]=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'release-1c-banking-interfaces' is false")
        void bankingInterfacesDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(configuration)
            );

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values[bailiffs.cder-file-transfer-job]=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("AC1: Both feature flags are false")
        void bothFeatureFlagsAreDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(configuration)
            );

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }
    }

    @Test
    @DisplayName("AC2: When CDER file is present it should be read and stored correctly")
    void cderFileProcessorServiceShouldRunSuccessfully() throws Exception {
        uploadResourceToSftp(CDER_FILE_RESOURCE, CDER_FILE_CONTAINER);

        service.run(configuration);

        InterfaceFileEntity sourceFile = assertSuccessfulInterfaceFile(
            CDER_FILE, CDER_FILE_CHECKSUM, Interface.CDER, Type.SOURCE, Domain.FINES);
        InterfaceFileEntity sourceJsonFile = assertSuccessfulSourceJsonInterfaceFile(
            CDER_FILE, Interface.CDER, Domain.FINES, sourceFile.getInterfaceFileId());
        assertBlobChecksum(CDER_FILE, CDER_FILE_CHECKSUM, configuration.getContainerName());
        assertSourceJsonContents(sourceJsonFile);
        assertNumberOfSftpFiles(configuration.getSftpUsername(), 0);
        verify(finesQueueService, times(1)).send(sourceJsonFile.getInterfaceFileId());
    }


    private void assertSourceJsonContents(InterfaceFileEntity sourceJson) throws Exception {
        BlobClient client = blobServiceClient
            .getBlobContainerClient(configuration.getContainerName())
            .getBlobClient(sourceJson.getFilestoreUuid().toString());

        JsonNode json = objectMapper.readTree(client.downloadContent().toBytes());

        assertThat(json.get("file_name").asText()).isEqualTo(CDER_FILE);
        assertThat(json.get("payment_type").asText()).isEqualTo("CASH");
        assertThat(json.at("/destination_details/bank_details/sort_code").asText()).isEqualTo("560033");
        assertThat(json.at("/destination_details/bank_details/account_number").asText()).isEqualTo("27048527");
        assertThat(json.get("transactions").size()).isEqualTo(2);
    }

}
