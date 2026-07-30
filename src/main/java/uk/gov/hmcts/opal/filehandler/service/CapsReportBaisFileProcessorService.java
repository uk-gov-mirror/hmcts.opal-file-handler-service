package uk.gov.hmcts.opal.filehandler.service;

import java.io.InputStream;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;

@Slf4j
@Service
public class CapsReportBaisFileProcessorService extends AbstractBaisFileProcessorService {

    public CapsReportBaisFileProcessorService(
        Clock clock,
        FeatureFlagUtil featureFlagUtil,
        BaisSftpClient baisSftpClient,
        InterfaceFileBlobStoreService interfaceFileBlobStoreService,
        InterfaceFilesRepository interfaceFilesRepository,
        TransactionTemplate transactionTemplate,
        ObjectMapper objectMapper
    ) {
        super(clock, featureFlagUtil, baisSftpClient, interfaceFileBlobStoreService, interfaceFilesRepository,
            transactionTemplate, objectMapper);
    }

    @Override
    public void processFile(InterfaceFileEntity fileEntity, InputStream inputStream) {
        // Not Required
    }

}
