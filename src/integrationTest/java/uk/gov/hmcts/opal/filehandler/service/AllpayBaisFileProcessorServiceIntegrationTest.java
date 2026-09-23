package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.AllpayBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.BusinessUnitBankAccountEntity;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.service.queue.MaintenanceInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;

@ActiveProfiles("integration")
@TestPropertySource(properties = {
    "opal.file-handler-service.file-types.allpay.sftp-username=AllPay",
    "launchdarkly.default-flag-values.allpay-file-transfer-job=true"
})
public class AllpayBaisFileProcessorServiceIntegrationTest extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String BUSINESS_UNIT_CODE = "AB01";
    private static final String ALLPAY_FILE = "a121_00350005_300000";
    private static final String ALLPAY_FILE_CHECKSUM = "f3f29c87cf2058c337fa6f130dd66b28";
    private static final String ALLPAY_FILE_RESOURCE = "bais-emulator/" + ALLPAY_FILE;
    private static final String ALLPAY_FILE_CONTAINER = "/home/AllPay/" + ALLPAY_FILE;

    @Autowired
    private AllpayBaisFileProcessorService allpayBaisFileProcessorService;

    @Autowired
    private AllpayBaisFileProcessorConfiguration allpayBaisFileProcessorConfiguration;

    @Autowired
    private BusinessUnitBankAccountEntityTestData businessUnitBankAccountEntityTestData;

    //TODO - Remove mocked bean and replace with test container service bus for integration testing
    @MockitoBean
    private MaintenanceInterfaceFilePreprocessQueueService maintenanceQueueService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        blobServiceClient.createBlobContainerIfNotExists(allpayBaisFileProcessorConfiguration.getContainerName());

        businessUnitBankAccountEntityTestData.clear();

        BusinessUnitBankAccountEntity bu = BusinessUnitBankAccountEntity.builder()
            .id(1L)
            .businessUnitCode(BUSINESS_UNIT_CODE)
            .domain(Domain.MAINTENANCE)
            .bankSortCode("010101")
            .bankAccountNumber("12341234")
            .build();
        businessUnitBankAccountEntityTestData.saveAndFlushBusinessUnitBankAccount(bu);
    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.allpay-file-transfer-job=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'release-1c-banking-interfaces' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                allpayBaisFileProcessorService.run(allpayBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=true",
        "launchdarkly.default-flag-values.allpay-file-transfer-job=false"
    })
    public class AllpayFileTransferJobDisabled {

        @Test
        @DisplayName("AC1: Feature flag 'allpay-file-transfer-job' is false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                allpayBaisFileProcessorService.run(allpayBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage("allpay-file-transfer-job is not enabled");
        }

    }

    @Nested
    @TestPropertySource(properties = {
        "launchdarkly.default-flag-values.release-1c-banking-interfaces=false",
        "launchdarkly.default-flag-values.allpay-file-transfer-job=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("AC1: Both feature flags are false")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                allpayBaisFileProcessorService.run(allpayBaisFileProcessorConfiguration));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }

    }

    @Test
    @DisplayName("AC2: An Allpay DAT file is stored and transformed to SOURCE_JSON")
    void whenAllpayDatFileIsPresentReadStoreAndTransformCorrectly() {
        String file = ALLPAY_FILE + ".dat";

        uploadResourceToSftp(ALLPAY_FILE_RESOURCE + ".dat", ALLPAY_FILE_CONTAINER + ".dat");
        allpayBaisFileProcessorService.run(allpayBaisFileProcessorConfiguration);

        InterfaceFileEntity sourceFile = assertSuccessfulInterfaceFile(
            file, ALLPAY_FILE_CHECKSUM, Interface.ALLPAY, Type.SOURCE, Domain.MAINTENANCE);
        InterfaceFileEntity sourceJsonFile = assertSuccessfulSourceJsonInterfaceFile(
            file, Interface.ALLPAY, Domain.MAINTENANCE, sourceFile.getInterfaceFileId());
        verify(maintenanceQueueService).send(sourceJsonFile.getInterfaceFileId());
        assertBlobChecksum(file, ALLPAY_FILE_CHECKSUM, allpayBaisFileProcessorConfiguration.getContainerName());
        assertNumberOfSftpFiles(allpayBaisFileProcessorConfiguration.getSftpUsername(), 0);
    }

    @ParameterizedTest
    @DisplayName("AC2: A non-DAT Allpay file is stored without being transformed to SOURCE_JSON")
    @ValueSource(strings = {".crf", ".dir", ".err", ".sta"})
    void whenNonDatAllpayFileIsPresentReadAndStoreWithoutTransforming(String fileEnding) {
        String resource = ALLPAY_FILE_RESOURCE + fileEnding;
        String container = ALLPAY_FILE_CONTAINER + fileEnding;

        uploadResourceToSftp(resource, container);
        allpayBaisFileProcessorService.run(allpayBaisFileProcessorConfiguration);

        String file = ALLPAY_FILE + fileEnding;

        var source = assertSuccessfulInterfaceFile(
            file, ALLPAY_FILE_CHECKSUM, Interface.ALLPAY, Type.SOURCE, Domain.MAINTENANCE);
        assertThat(repository.findAll())
            .filteredOn(interfaceFile -> interfaceFile.getType() == Type.SOURCE_JSON)
            .filteredOn(interfaceFile -> file.equals(interfaceFile.getFileName()))
            .isEmpty();
        verify(maintenanceQueueService, never()).send(source.getInterfaceFileId());
        assertBlobChecksum(file, ALLPAY_FILE_CHECKSUM, allpayBaisFileProcessorConfiguration.getContainerName());
        assertNumberOfSftpFiles(allpayBaisFileProcessorConfiguration.getSftpUsername(), 0);
    }

}
