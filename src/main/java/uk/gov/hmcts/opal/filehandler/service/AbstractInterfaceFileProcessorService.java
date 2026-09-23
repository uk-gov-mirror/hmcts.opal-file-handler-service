package uk.gov.hmcts.opal.filehandler.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import uk.gov.hmcts.opal.common.launchdarkly.FeatureFlags;
import uk.gov.hmcts.opal.filehandler.config.BaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;
import uk.gov.hmcts.opal.filehandler.exception.BlobUploadException;
import uk.gov.hmcts.opal.filehandler.exception.InvalidReportFileException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.util.BaisSftpClient;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractInterfaceFileProcessorService {

    protected final Clock clock;
    private final FeatureFlagUtil featureFlagUtil;
    private final BaisSftpClient baisSftpClient;
    protected final InterfaceFileBlobStoreService interfaceFileBlobStoreService;
    protected final InterfaceFilesRepository interfaceFilesRepository;
    private final TransactionTemplate transactionTemplate;
    protected final ObjectMapper objectMapper;

    protected abstract void processFile(
        BaisFileProcessorConfiguration config,
        InterfaceFileEntity fileEntity,
        InputStream inputStream
    );

    protected void validateFile(InputStream inputStream) {
        // Processors may validate their input format before it is uploaded.
    }

    public void run(BaisFileProcessorConfiguration config) {
        featureFlagUtil.requireEnabledFeature(FeatureFlags.RELEASE_1C_BANKING_INTERFACES);
        featureFlagUtil.requireEnabledFeature(config.getFeatureFlag());

        List<String> baisFiles = selectFilesToProcess(config);

        for (String fileName : baisFiles) {
            try {
                ingestFile(config, fileName);
            } catch (IOException | RuntimeException e) {
                log.error("Failed to ingest file '{}'", fileName, e);
            }
        }
    }

    protected List<String> selectFilesToProcess(BaisFileProcessorConfiguration config) {
        log.info("Selecting files to process from BAIS for user '{}' and source '{}'",
            config.getSftpUsername(), config.getSource());
        List<String> baisFiles = baisSftpClient.listRegularFiles(config.getSftpUsername());

        if (baisFiles.isEmpty()) {
            log.info("No files found in BAIS for user '{}' when processing source '{}'", config.getSftpUsername(),
                config.getSource());
            return List.of();
        }

        Map<Boolean, List<String>> filesByMatch = baisFiles.stream()
            .collect(Collectors.partitioningBy(fileName ->
                config.getFileNameRegex().matcher(fileName).matches()));

        List<String> matchingFiles = filesByMatch.get(true);
        List<String> ignoringFiles = filesByMatch.get(false);

        if (!ignoringFiles.isEmpty()) {
            log.error("Found {} additional files in BAIS for user '{}' that did not match the regex for source '{}' "
                    + "and were ignored: {}",
                ignoringFiles.size(), config.getSftpUsername(), config.getSource(), String.join(", ", ignoringFiles));
        }

        return matchingFiles;
    }

    private void ingestFile(BaisFileProcessorConfiguration config, String fileName) throws IOException {
        final byte[] downloadedBytes;

        try (ByteArrayOutputStream downloadStream = new ByteArrayOutputStream()) {
            baisSftpClient.downloadFile(config.getSftpUsername(), fileName, downloadStream);
            downloadedBytes = downloadStream.toByteArray();

            String fileChecksum = calculateChecksum(new ByteArrayInputStream(downloadedBytes));
            Optional<InterfaceFileEntity> duplicate = interfaceFilesRepository.findByFileNameAndChecksumAndStatus(
                fileName, fileChecksum, Status.SUCCESS);

            InterfaceFileEntity entity;

            try {
                if (duplicate.isPresent()) {
                    entity = createDuplicateInterfaceFile(config, fileName, fileChecksum, duplicate.get());
                } else {
                    validateFile(new ByteArrayInputStream(downloadedBytes));
                    UUID fileStoreUuid = UUID.randomUUID();
                    interfaceFileBlobStoreService.uploadBaisFile(
                        fileStoreUuid, config.getContainerName(),
                        new ByteArrayInputStream(downloadedBytes), fileChecksum);
                    entity = createNewInterfaceFile(config, fileName, fileChecksum, fileStoreUuid);
                }
            } catch (InvalidReportFileException e) {
                entity = createFailureInterfaceFile(config, fileName, fileChecksum, e.getMessage());
            } catch (BlobChecksumValidationException e) {
                entity = createFailureInterfaceFile(config, fileName, fileChecksum, e.getMessage());
            } catch (BlobUploadException e) {
                entity = createFailureInterfaceFile(config, fileName, fileChecksum,
                    "Blob upload failed for file '%s': %s".formatted(fileName, e.getMessage()));
            }

            entity = saveInitialFile(entity);

            if (entity.getStatus().equals(Status.INGESTED)) {
                processIngestedFile(config, entity, new ByteArrayInputStream(downloadedBytes));
            }

            completeIngestion(config, fileName, entity);
        }
    }

    private InterfaceFileEntity createDuplicateInterfaceFile(
        BaisFileProcessorConfiguration config,
        String fileName,
        String fileChecksum,
        InterfaceFileEntity duplicate
    ) {
        log.error("File with name '{}' and checksum '{}' for source '{}' is a duplicate of {}",
            fileName, fileChecksum, config.getSource(), duplicate.getInterfaceFileId());

        return InterfaceFileEntity.builder()
            .type(Type.SOURCE)
            .target(config.getTarget())
            .source(config.getSource())
            .fileName(fileName)
            .checksum(fileChecksum)
            .status(Status.DUPLICATE)
            .filestoreUuid(duplicate.getFilestoreUuid())
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(Domain.MAINTENANCE)
            .errors(errorJson("File with name '%s' and checksum '%s' for source '%s' already processed skipping"
                .formatted(fileName, fileChecksum, config.getSource())))
            .build();
    }

    private InterfaceFileEntity createNewInterfaceFile(
        BaisFileProcessorConfiguration config,
        String fileName,
        String fileChecksum,
        UUID fileStoreUuid
    ) {
        return InterfaceFileEntity.builder()
            .type(Type.SOURCE)
            .target(config.getTarget())
            .source(config.getSource())
            .fileName(fileName)
            .checksum(fileChecksum)
            .status(Status.INGESTED)
            .filestoreUuid(fileStoreUuid)
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(Domain.MAINTENANCE)
            .build();
    }

    private InterfaceFileEntity createFailureInterfaceFile(
        BaisFileProcessorConfiguration config,
        String fileName,
        String fileChecksum,
        String failureMessage
    ) {
        return InterfaceFileEntity.builder()
            .type(Type.SOURCE)
            .target(config.getTarget())
            .source(config.getSource())
            .fileName(fileName)
            .checksum(fileChecksum)
            .status(Status.FAILED)
            .createdDatetime(LocalDateTime.now(clock))
            .opalDomain(Domain.MAINTENANCE)
            .errors(errorJson(failureMessage))
            .build();
    }

    private InterfaceFileEntity saveInitialFile(InterfaceFileEntity entity) {
        return transactionTemplate.execute(transactionStatus -> {
            supersedePreviousFailures(entity.getFileName(), entity.getChecksum());
            return interfaceFilesRepository.save(entity);
        });
    }

    private void processIngestedFile(
        BaisFileProcessorConfiguration config,
        InterfaceFileEntity entity,
        InputStream inputStream
    ) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> processFile(config, entity, inputStream));
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

    private void completeIngestion(BaisFileProcessorConfiguration config, String fileName, InterfaceFileEntity entity) {
        if (entity.getStatus().equals(Status.FAILED)) {
            return;
        }

        boolean deleted = baisSftpClient.deleteFile(config.getSftpUsername(), fileName);

        if (!deleted) {
            log.error("Unable to delete BAIS file '{}' for interfaceFileId={}",
                fileName, entity.getInterfaceFileId());
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

    @SuppressWarnings("java:S4790") // Used for checksum, not in a sensitive context
    protected static String calculateChecksum(InputStream stream) throws IOException {
        return DigestUtils.md5DigestAsHex(stream);
    }
}
