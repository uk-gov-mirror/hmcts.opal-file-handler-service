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
import uk.gov.hmcts.opal.filehandler.config.BarclaycardBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "launchdarkly.default-flag-values.barclaycard-file-transfer-job=true",
})
public class BarclaycardBaisFileProcessorServiceTest extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String BARCLAYCARD_FILE = "a121_00010065_317608.dat";
    private static final String BARCLAYCARD_FILE_CHECKSUM = "3e7eb40eae410fee9a8d999bdcd7c302";
    private static final String BARCLAYCARD_FILE_RESOURCE = "bais-emulator/" + BARCLAYCARD_FILE;
    private static final String BARCLAYCARD_FILE_CONTAINER = "/home/BARCLAYCARD/" + BARCLAYCARD_FILE;
    private static final String BUSINESS_UNIT_CODE = "BC12";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private BarclaycardBaisFileProcessorService service;

    @Autowired
    private BarclaycardBaisFileProcessorConfiguration configuration;

    @Autowired
    private BusinessUnitBankAccountEntityTestData businessUnitBankAccountEntityTestData;

    @MockitoBean
    private FinesInterfaceFilePreprocessQueueService finesQueueService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        businessUnitBankAccountEntityTestData.clear();
        businessUnitBankAccountEntityTestData.saveTypicalBusinessUnitBankAccount(1L, BUSINESS_UNIT_CODE);
        blobServiceClient.createBlobContainerIfNotExists(configuration.getContainerName());
    }


    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values.barclaycard-file-transfer-job=false"
    })
    public class NatWestFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'barclaycard-file-transfer-job' is false")
        void barclaycardFileTransferJobIsDisabled() {
            FeatureDisabledException exception = assertThrows(
                FeatureDisabledException.class, () -> service.run(configuration)
            );
            assertThat(exception).hasMessage("barclaycard-file-transfer-job is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.barclays-file-transfer-job=false"
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
    @DisplayName("AC2: When Barclaycard file is present it should be read and stored correctly")
    void natWestBaisFileProcessorServiceShouldRunSuccessfully() throws Exception {
        uploadResourceToSftp(BARCLAYCARD_FILE_RESOURCE, BARCLAYCARD_FILE_CONTAINER);

        service.run(configuration);

        InterfaceFileEntity sourceFile = assertSuccessfulInterfaceFile(
            BARCLAYCARD_FILE, BARCLAYCARD_FILE_CHECKSUM, Interface.BARCLAYCARD, Type.SOURCE, Domain.FINES);
        InterfaceFileEntity sourceJsonFile = assertSuccessfulSourceJsonInterfaceFile(
            BARCLAYCARD_FILE, Interface.BARCLAYCARD, Domain.FINES, sourceFile.getInterfaceFileId());
        assertBlobChecksum(BARCLAYCARD_FILE, BARCLAYCARD_FILE_CHECKSUM, configuration.getContainerName());
        assertSourceJsonContents(sourceJsonFile);
        assertNumberOfSftpFiles(configuration.getSftpUsername(), 0);
        verify(finesQueueService, times(1)).send(sourceJsonFile.getInterfaceFileId());
    }

    private void assertSourceJsonContents(InterfaceFileEntity sourceJson) throws Exception {
        BlobClient client = blobServiceClient
            .getBlobContainerClient(configuration.getContainerName())
            .getBlobClient(sourceJson.getFilestoreUuid().toString());

        JsonNode json = objectMapper.readTree(client.downloadContent().toBytes());

        assertThat(json.get("file_name").asText()).isEqualTo(BARCLAYCARD_FILE);
        assertThat(json.get("payment_type").asText()).isEqualTo("CASH");
        assertThat(json.at("/destination_details/bank_details/sort_code").asText()).isEqualTo("560033");
        assertThat(json.at("/destination_details/bank_details/account_number").asText()).isEqualTo("27048527");
        assertThat(json.get("transactions").size()).isEqualTo(2);
    }

}
