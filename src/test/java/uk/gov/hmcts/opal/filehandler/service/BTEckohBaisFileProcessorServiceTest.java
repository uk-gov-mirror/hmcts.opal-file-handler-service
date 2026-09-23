package uk.gov.hmcts.opal.filehandler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.opal.filehandler.config.BaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.service.extraction.BacsStandard18BaisExtractionService;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.service.queue.MaintenanceInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;

@ExtendWith(MockitoExtension.class)
class BTEckohBaisFileProcessorServiceTest {

    @Mock
    private FeatureFlagUtil featureFlagUtil;

    @Mock
    private BaisSftpClient baisSftpClient;

    @Mock
    private InterfaceFileBlobStoreService blobStoreService;

    @Mock
    private InterfaceFilesRepository repository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private BaisFileProcessorConfiguration config;

    @Mock
    private BacsStandard18BaisExtractionService extractionService;

    @Mock
    private FinesInterfaceFilePreprocessQueueService finesQueueService;

    @Mock
    private MaintenanceInterfaceFilePreprocessQueueService maintenanceQueueService;

    private BTEckohBaisFileProcessorService service;

    @BeforeEach
    void setUp() {
        service = new BTEckohBaisFileProcessorService(
            Clock.systemUTC(),
            featureFlagUtil,
            baisSftpClient,
            blobStoreService,
            repository,
            transactionTemplate,
            JsonMapper.builder().build(),
            extractionService,
            finesQueueService,
            maintenanceQueueService
        );
    }

    @Test
    void shouldProcessBacsStandard18File() {
        InterfaceFileEntity sourceFile = InterfaceFileEntity.builder()
            .interfaceFileId(100L)
            .source(Interface.BTECKOH)
            .target(Interface.OPAL)
            .type(Type.SOURCE)
            .opalDomain(Domain.MAINTENANCE)
            .fileName("a121_00350005_300000.dat")
            .status(Status.INGESTED)
            .createdDatetime(LocalDateTime.now())
            .build();
        InputStream inputStream = InputStream.nullInputStream();
        when(extractionService.extractStandardData(sourceFile, inputStream)).thenReturn(List.of());

        service.processFile(config, sourceFile, inputStream);

        assertThat(sourceFile.getStatus()).isEqualTo(Status.SUCCESS_NO_TRANSACTIONS);
        verify(extractionService).extractStandardData(sourceFile, inputStream);
        verify(repository).save(sourceFile);
    }

    @Test
    void shouldPassImplementationSpecificDependenciesToAbstractService() {
        assertThat(service.extractionService).isSameAs(extractionService);
        assertThat(service.queueService(Domain.FINES)).isSameAs(finesQueueService);
        assertThat(service.queueService(Domain.MAINTENANCE)).isSameAs(maintenanceQueueService);
    }
}
