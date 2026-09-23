package uk.gov.hmcts.opal.filehandler.service;


import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.service.extraction.BacsStandard18BaisExtractionService;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.InterfaceFileCommonDataExtract;
import uk.gov.hmcts.opal.filehandler.service.queue.FinesInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.service.queue.MaintenanceInterfaceFilePreprocessQueueService;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;

@Service
public class BTEckohBaisFileProcessorService
    extends AbstractBaisInterfaceFileProcessorWithExtractionService<InterfaceFileCommonDataExtract> {

    protected BTEckohBaisFileProcessorService(
        Clock clock,
        FeatureFlagUtil featureFlagUtil,
        BaisSftpClient baisSftpClient,
        InterfaceFileBlobStoreService interfaceFileBlobStoreService,
        InterfaceFilesRepository interfaceFilesRepository,
        TransactionTemplate transactionTemplate,
        ObjectMapper objectMapper,
        BacsStandard18BaisExtractionService extractionService,
        FinesInterfaceFilePreprocessQueueService finesQueueService,
        MaintenanceInterfaceFilePreprocessQueueService maintenanceQueueService
    ) {
        super(clock, featureFlagUtil, baisSftpClient, interfaceFileBlobStoreService, interfaceFilesRepository,
            transactionTemplate, objectMapper, extractionService, finesQueueService, maintenanceQueueService);
    }
}
