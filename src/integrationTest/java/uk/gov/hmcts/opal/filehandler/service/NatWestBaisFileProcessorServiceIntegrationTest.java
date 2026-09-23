package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.azure.storage.blob.BlobClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import uk.gov.hmcts.opal.filehandler.config.NatWestBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "launchdarkly.default-flag-values.natwest-file-transfer-job=true",
})
public class NatWestBaisFileProcessorServiceIntegrationTest extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String NATWEST_FILE = "Y01A.CARS.#D.SBURZ38.D080426";
    private static final String NATWEST_FILE_CHECKSUM = "3e7eb40eae410fee9a8d999bdcd7c302";
    private static final String NATWEST_FILE_RESOURCE = "bais-emulator/" + NATWEST_FILE;
    private static final String NATWEST_FILE_CONTAINER = "/home/NATWEST/" + NATWEST_FILE;
    private static final String BUSINESS_UNIT_CODE = "BC12";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private NatWestBaisFileProcessorService natWestBaisFileProcessorService;

    @Autowired
    private NatWestBaisFileProcessorConfiguration natWestBaisFileProcessorConfiguration;

    @Autowired
    private BusinessUnitBankAccountEntityTestData businessUnitBankAccountEntityTestData;

    @MockitoBean
    private FinesInterfaceFilePreprocessQueueService finesQueueService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        businessUnitBankAccountEntityTestData.clear();
        businessUnitBankAccountEntityTestData.saveTypicalBusinessUnitBankAccount(1L, BUSINESS_UNIT_CODE);
        blobServiceClient.createBlobContainerIfNotExists(natWestBaisFileProcessorConfiguration.getContainerName());
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.natwest-file-transfer-job=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'release-1c-banking-interfaces' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                natWestBaisFileProcessorService.run(natWestBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values.natwest-file-transfer-job=false"
    })
    public class NatWestFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'natwest-file-transfer-job' is false")
        void natWestFileTransferJobIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                natWestBaisFileProcessorService.run(natWestBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage("natwest-file-transfer-job is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.natwest-file-transfer-job=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("AC1: Both feature flags are false")
        void bothFeatureFlagsAreDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                natWestBaisFileProcessorService.run(natWestBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Test
    @DisplayName("AC2: NatWest file is present, read, extracted and stored correctly")
    void natWestBaisFileProcessorServiceShouldRunSuccessfully() throws Exception {
        uploadResourceToSftp(NATWEST_FILE_RESOURCE, NATWEST_FILE_CONTAINER);

        natWestBaisFileProcessorService.run(natWestBaisFileProcessorConfiguration);

        InterfaceFileEntity sourceFile = assertSuccessfulInterfaceFile(
            NATWEST_FILE, NATWEST_FILE_CHECKSUM, Interface.NATWEST, Type.SOURCE, Domain.FINES);
        InterfaceFileEntity sourceJsonFile = assertSuccessfulSourceJsonInterfaceFile(
            NATWEST_FILE, Interface.NATWEST, Domain.FINES, sourceFile.getInterfaceFileId());
        assertBlobChecksum(NATWEST_FILE, NATWEST_FILE_CHECKSUM,
            natWestBaisFileProcessorConfiguration.getContainerName());
        assertSourceJsonContents(sourceJsonFile);
        assertNumberOfSftpFiles(natWestBaisFileProcessorConfiguration.getSftpUsername(), 0);
        verify(finesQueueService, times(1)).send(sourceJsonFile.getInterfaceFileId());
    }

    private void assertSourceJsonContents(InterfaceFileEntity sourceJson) throws Exception {
        BlobClient client = blobServiceClient
            .getBlobContainerClient(natWestBaisFileProcessorConfiguration.getContainerName())
            .getBlobClient(sourceJson.getFilestoreUuid().toString());

        JsonNode json = objectMapper.readTree(client.downloadContent().toBytes());

        assertThat(json.get("file_name").asText()).isEqualTo(NATWEST_FILE);
        assertThat(json.get("payment_type").asText()).isEqualTo("CASH");
        assertThat(json.at("/destination_details/bank_details/sort_code").asText()).isEqualTo("560033");
        assertThat(json.at("/destination_details/bank_details/account_number").asText()).isEqualTo("27048527");
        assertThat(json.get("transactions").size()).isEqualTo(2);
    }

}
