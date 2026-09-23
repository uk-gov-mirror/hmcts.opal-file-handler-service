package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureDisabledException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.BTEckohBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.BusinessUnitBankAccountEntity;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.support.AbstractBaisFileProcessorServiceIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraEpic;
import uk.hmcts.zephyr.automation.junit5.annotations.JiraStory;

@ActiveProfiles("integration")
public class BTEckohBaisFileProcessorServiceIntegrationTest
    extends AbstractBaisFileProcessorServiceIntegrationTest {

    private static final String BANKING_INTERFACES_ENABLED_PROPERTY =
        "launchdarkly.default-flag-values.release-1c-banking-interfaces";
    private static final String FEATURE_FLAG = "bteckoh-file-transfer-job";
    private static final String JOB_PROPERTY = "launchdarkly.default-flag-values." + FEATURE_FLAG;

    private static final String BUSINESS_UNIT_CODE = "AB01";
    private static final String BTECKOH_FILE = "a121_00350005_300000.dat";
    private static final String BTECKOH_FILE_CHECKSUM = "f3f29c87cf2058c337fa6f130dd66b28";
    private static final String BTECKOH_FILE_RESOURCE = "bais-emulator/" + BTECKOH_FILE;
    private static final String BTECKOH_FILE_CONTAINER = "/home/BTEckoh/" + BTECKOH_FILE;

    @Autowired
    private BTEckohBaisFileProcessorService service;

    @Autowired
    private BTEckohBaisFileProcessorConfiguration config;

    @Autowired
    private BusinessUnitBankAccountEntityTestData businessUnitBankAccountEntityTestData;

    @MockitoBean
    private JmsTemplate jmsTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        businessUnitBankAccountEntityTestData.clear();

        blobServiceClient.createBlobContainerIfNotExists(config.getContainerName());

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
        BANKING_INTERFACES_ENABLED_PROPERTY + "=false",
        JOB_PROPERTY + "=true"
    })
    public class BankingInterfacesDisabled {

        @Test
        @DisplayName("Feature flag 'release-1c-banking-interfaces' is false")
        @JiraStory("PO-6428")
        @JiraEpic("PO-3497")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(config));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        BANKING_INTERFACES_ENABLED_PROPERTY + "=true",
        JOB_PROPERTY + "=false"
    })
    public class JobFeatureFlagIsDisabled {

        @Test
        @DisplayName("Feature flag 'bteckoh-file-transfer-job' is false")
        @JiraStory("PO-6428")
        @JiraEpic("PO-3497")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(config));

            assertThat(exception).hasMessage(FEATURE_FLAG + " is not enabled");
        }
    }

    @Nested
    @TestPropertySource(properties = {
        BANKING_INTERFACES_ENABLED_PROPERTY + "=false",
        JOB_PROPERTY + "=false"
    })
    public class BothFeatureFlagsDisabled {

        @Test
        @DisplayName("Both feature flags are false")
        @JiraStory("PO-6428")
        @JiraEpic("PO-3497")
        void bankingInterfacesIsDisabled() {
            FeatureDisabledException exception = assertThrows(FeatureDisabledException.class, () ->
                service.run(config));

            assertThat(exception).hasMessage(FeatureFlags.RELEASE_1C_BANKING_INTERFACES + " is not enabled");
        }
    }

    @DisplayName("When a BTEckoh file is present it should be read and stored correctly")
    @Nested
    @TestPropertySource(properties = {
        BANKING_INTERFACES_ENABLED_PROPERTY + "=true",
        JOB_PROPERTY + "=true",
    })
    public class ReadAndStoreBTEckohFileCorrectly {
        @Test
        void readAndStoreBTEckohFileCorrectly() {
            uploadResourceToSftp(BTECKOH_FILE_RESOURCE, BTECKOH_FILE_CONTAINER);

            service.run(config);

            var sourceFile = assertSuccessfulInterfaceFile(
                BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, Interface.BTECKOH, Type.SOURCE, Domain.MAINTENANCE);

            var sourceJson = assertSuccessfulSourceJsonInterfaceFile(
                BTECKOH_FILE, Interface.BTECKOH, Domain.MAINTENANCE, sourceFile.getInterfaceFileId());

            verify(jmsTemplate, times(1)).convertAndSend(
                eq("banking-interfaces-preprocess-interface-file-maintenance"), anyString());

            assertArrayEquals(sourceFile.getBusinessUnitCode(), sourceJson.getBusinessUnitCode());
            assertBlobChecksum(BTECKOH_FILE, BTECKOH_FILE_CHECKSUM, config.getContainerName());
            assertNumberOfSftpFiles(config.getSftpUsername(), 0);
        }
    }

}
