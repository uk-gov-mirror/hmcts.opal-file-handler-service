package uk.gov.hmcts.opal.filehandler.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.opal.common.exception.DownstreamServiceUnavailableException;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.common.util.SecurityUtil;
import uk.gov.hmcts.opal.filehandler.config.FileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;
import uk.gov.hmcts.opal.filehandler.util.StreamUtil;

@Slf4j
@RequiredArgsConstructor
public class InterfaceFileProcessorService {

    protected final Clock clock;
    private final FeatureFlagUtil featureFlagUtil;
    protected final InterfaceFileBlobStoreService interfaceFileBlobStoreService;
    protected final InterfaceFilesRepository interfaceFilesRepository;
    private final TransactionTemplate transactionTemplate;
    protected final ObjectMapper objectMapper;

    protected final FileIngestionService<String> fileIngestionService;
    protected final FileProcessorConfiguration config;

    protected void processFile(
        InterfaceFileEntity fileEntity,
        InputStream inputStream,
        long creatorId
    ) {
        // Processors may implement their own processing logic for ingested files.
        // This method is called after the file has been successfully ingested and stored in the blob store.
    }

    protected void validateFile(InputStream inputStream) {
        // Processors may validate their input format before it is uploaded.
    }

    public void run() {
        featureFlagUtil.requireEnabledFeature(FeatureFlags.RELEASE_1C_BANKING_INTERFACES);
        featureFlagUtil.requireEnabledFeature(config.getFeatureFlag());

        List<String> baisFiles = selectFilesToProcess();

        for (String fileName : baisFiles) {
            try {
                ingestFile(fileName, getCurrentUserId());
            } catch (IOException | RuntimeException e) {
                log.error("Failed to ingest file '{}'", fileName, e);
            }
        }
    }

    protected List<String> selectFilesToProcess() {
        return fileIngestionService.getFiles();
    }

    private void ingestFile(String fileName, Long creatorId) throws IOException {
        final byte[] downloadedBytes;

        try (ByteArrayOutputStream downloadStream = fileIngestionService.getFile(fileName)) {
            downloadedBytes = downloadStream.toByteArray();
        }

        InterfaceFileEntity entity = ingestFile(
            fileName, downloadedBytes,
            config.getSource(),
            config.getTarget(),
            Type.SOURCE,
            Domain.MAINTENANCE,//TODO check
            config.getContainerName(),
            creatorId
        );
        if (entity.getStatus().equals(Status.INGESTED)) {
            processIngestedFile(entity, new ByteArrayInputStream(downloadedBytes), creatorId);
        }

        completeIngestion(fileName, entity);
    }

    public InterfaceFileEntity ingestFile(
        String fileName, byte[] sourceFileData,
        Interface source,
        Interface target,
        Type type,
        Domain domain,
        String containerName,
        Long creatorId
    ) throws IOException {
        String fileChecksum = StreamUtil.calculateChecksum(new ByteArrayInputStream(sourceFileData));
        InterfaceFileEntity entity = InterfaceFileEntity.builder()
            .type(type)
            .source(source)
            .target(target)
            .fileName(fileName)
            .checksum(fileChecksum)
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(domain)
            .createdBy(creatorId)
            .build();

        Optional<InterfaceFileEntity> duplicateOpt =
            getDuplicateFile(type, fileName, fileChecksum);
        try {
            if (duplicateOpt.isPresent()) {
                InterfaceFileEntity duplicate = duplicateOpt.get();
                String errorMessage = "File with name '%s' and checksum '%s' for source '%s' is a duplicate of %s"
                    .formatted(fileName, fileChecksum, source, duplicate.getInterfaceFileId());
                log.error(errorMessage);
                entity.setErrors(
                    errorJson(errorMessage));
                entity.setStatus(Status.DUPLICATE);
                entity.setFilestoreUuid(duplicate.getFilestoreUuid());
            } else {
                validateFile(new ByteArrayInputStream(sourceFileData));
                UUID fileStoreUuid = UUID.randomUUID();
                interfaceFileBlobStoreService.uploadBaisFile(
                    fileStoreUuid, containerName,
                    new ByteArrayInputStream(sourceFileData), fileChecksum);
                entity.setFilestoreUuid(fileStoreUuid);
                entity.setStatus(Status.INGESTED);
            }
        } catch (Exception e) {
            String errorMessage;
            if (e instanceof BlobUploadException) {
                errorMessage = "Blob upload failed for file '%s': %s".formatted(fileName, e.getMessage());
            } else {
                errorMessage = e.getMessage();
            }
            log.error(errorMessage, fileChecksum, e);
            entity.setErrors(errorJson(errorMessage));
            entity.setStatus(Status.FAILED);

            if (e instanceof BlobUploadException) {
                saveInitialFile(entity);
                throw new DownstreamServiceUnavailableException(errorMessage, e);
            }
        }
        return saveInitialFile(entity);
    }

    protected Optional<InterfaceFileEntity> getDuplicateFile(
        Type type,
        String fileName,
        String fileChecksum) {
        return interfaceFilesRepository
            .findByTypeAndFileNameAndChecksumAndStatusIn(
                type, fileName, fileChecksum, List.of(Status.SUCCESS, Status.SUCCESS_NO_TRANSACTIONS));
    }

    private InterfaceFileEntity saveInitialFile(InterfaceFileEntity entity) {
        return transactionTemplate.execute(transactionStatus -> {
            supersedePreviousFailures(entity.getFileName(), entity.getChecksum());
            return interfaceFilesRepository.save(entity);
        });
    }

    private void processIngestedFile(
        InterfaceFileEntity entity,
        InputStream inputStream,
        long creatorId
    ) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                processFile(entity, inputStream, creatorId);
            });
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                entity.setStatus(Status.FAILED);
                entity.setErrors(errorJson("File '%s' could not be processed: %s"
                    .formatted(entity.getFileName(), e.getMessage())));
                interfaceFilesRepository.save(entity);
            });
            log.error("Error processing interfaceFileId={} for file {}",
                entity.getInterfaceFileId(), entity.getFileName(), e);
        }
    }

    public void completeIngestion(String fileName, InterfaceFileEntity entity) {
        completeIngestion(fileName, entity, true);
    }

    public void completeIngestion(String fileName, InterfaceFileEntity entity,
        boolean deleteIngestedFileFromSourceLocation) {
        if (entity.getStatus().equals(Status.FAILED)) {
            return;
        }

        if (deleteIngestedFileFromSourceLocation) {
            boolean deleted = fileIngestionService.deleteFile(fileName);
            if (!deleted) {
                log.error("Unable to delete BAIS file '{}' for interfaceFileId={}",
                    fileName, entity.getInterfaceFileId());
            }
        }

        if (entity.getStatus().equals(Status.INGESTED)) {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                entity.setStatus(Status.SUCCESS);
                interfaceFilesRepository.save(entity);
            });
        }
    }

    private void supersedePreviousFailures(String fileName, String fileChecksum) {
        List<InterfaceFileEntity> previousFailures = interfaceFilesRepository
            .findAllByFileNameAndChecksumAndStatus(fileName, fileChecksum, Status.FAILED);

        previousFailures.forEach(previousFailure -> previousFailure.setStatus(Status.FAILED_SUPERSEDED));
    }

    protected String errorJson(String message) {
        return objectMapper.createObjectNode().put("message", message).toString();
    }

    protected long getCurrentUserId() {
        return SecurityUtil.getOpalJwtAuthenticationTokenForCurrentUser().getUserId();
    }
}
