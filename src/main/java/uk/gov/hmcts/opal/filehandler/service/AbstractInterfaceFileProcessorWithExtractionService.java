package uk.gov.hmcts.opal.filehandler.service;

import static uk.gov.hmcts.opal.filehandler.repository.specs.InterfaceFileSpecsFactory.sourceFilesWithJsonFailuresWithinRetryLimit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.common.exceptions.standard.InternalServerErrorException;
import uk.gov.hmcts.opal.filehandler.config.FileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.BlobChecksumValidationException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.service.extraction.ExtractionService;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.InterfaceFileCommonDataExtract;
import uk.gov.hmcts.opal.filehandler.util.FeatureFlagUtil;
import uk.gov.hmcts.opal.filehandler.util.StreamUtil;

@Slf4j
public abstract class AbstractInterfaceFileProcessorWithExtractionService<T extends InterfaceFileCommonDataExtract>
    extends InterfaceFileProcessorService {

    @Value("${opal.file-handler-service.extraction-service.max-retries:5}")
    protected int maxRetries;

    protected final ExtractionService<T> extractionService;

    protected AbstractInterfaceFileProcessorWithExtractionService(
        Clock clock,
        FeatureFlagUtil featureFlagUtil,
        InterfaceFileBlobStoreService interfaceFileBlobStoreService,
        InterfaceFilesRepository interfaceFilesRepository,
        TransactionTemplate transactionTemplate,
        ObjectMapper objectMapper,
        ExtractionService<T> extractionService,
        FileIngestionService<String> ingestionService,
        FileProcessorConfiguration config
    ) {
        super(clock, featureFlagUtil, interfaceFileBlobStoreService, interfaceFilesRepository,
            transactionTemplate, objectMapper, ingestionService, config);
        this.extractionService = extractionService;
    }

    @Override
    protected List<String> selectFilesToProcess() {
        List<InterfaceFileEntity> sourceFilesToRetry = interfaceFilesRepository.findAll(
            sourceFilesWithJsonFailuresWithinRetryLimit(config.getSource(), maxRetries));

        for (InterfaceFileEntity sourceFile : sourceFilesToRetry) {
            try (InputStream sourceStream = interfaceFileBlobStoreService.fetchInterfaceFile(
                sourceFile.getInterfaceFileId(),
                sourceFile.getFilestoreUuid(),
                config.getContainerName()).toStream()) {

                processFile(sourceFile, sourceStream, getCurrentUserId());
            } catch (IOException e) {
                log.error("Failed to process source file {} with interfaceFileId {}",
                    sourceFile.getFileName(), sourceFile.getInterfaceFileId(), e);
                throw new InternalServerErrorException(
                    "Internal Server Error",
                    "Failed to process source file " + sourceFile.getFileName()
                        + " with interfaceFileId " + sourceFile.getInterfaceFileId(), e);
            }
        }

        return super.selectFilesToProcess();
    }

    @Override
    protected void processFile(
        InterfaceFileEntity sourceInterfaceFile,
        InputStream inputStream,
        long creatorId
    ) {
        List<T> extracts = extractionService.extractStandardData(sourceInterfaceFile, inputStream);

        if (extracts.isEmpty()) {
            sourceInterfaceFile.setStatus(Status.SUCCESS_NO_TRANSACTIONS);
            interfaceFilesRepository.save(sourceInterfaceFile);
            return;
        }

        for (T extract : extracts) {
            if (preProcessExtract(sourceInterfaceFile, extract)) {
                InterfaceFileEntity sourceJson = createAndUploadSourceJson(
                    sourceInterfaceFile, extract, creatorId);
                if (sourceJson != null) {
                    postProcessExtract(sourceJson, extract);
                }
            }
        }

        interfaceFilesRepository.save(sourceInterfaceFile);
    }


    InterfaceFileEntity createAndUploadSourceJson(
        InterfaceFileEntity sourceInterfaceFile, T extract, long creatorId) {
        byte[] jsonBytes = objectMapper.writeValueAsBytes(extract);
        String checksum = calculateExtractChecksum(jsonBytes);

        if (alreadyProcessedSuccessfully(sourceInterfaceFile, extract, checksum)) {
            return null;
        }

        supersedeFailedSourceJson(sourceInterfaceFile, extract, checksum);

        InterfaceFileEntity sourceJson = createSourceJson(sourceInterfaceFile, extract,
            getBusinessUnitsFromExtract(extract),
            getDomainFromExtract(extract),
            checksum, creatorId);

        uploadSourceJson(sourceJson, jsonBytes);
        return interfaceFilesRepository.save(sourceJson);
    }


    protected boolean alreadyProcessedSuccessfully(
        InterfaceFileEntity sourceInterfaceFile,
        T extract,
        String checksum
    ) {
        boolean duplicateExists = interfaceFilesRepository
            .findByRelatedInterfaceFileInterfaceFileIdAndTypeAndFileNameAndChecksumAndStatusIn(
                sourceInterfaceFile.getInterfaceFileId(),
                Type.SOURCE_JSON,
                extract.getFileName(),
                checksum,
                List.of(Status.SUCCESS, Status.SUCCESS_NO_TRANSACTIONS)
            )
            .isPresent();

        if (duplicateExists) {
            log.warn("SOURCE_JSON object with name '{}' and checksum '{}' for associated SOURCE '{}' already "
                    + "processed skipping",
                extract.getFileName(), checksum, sourceInterfaceFile.getInterfaceFileId());
        }

        return duplicateExists;
    }

    void supersedeFailedSourceJson(
        InterfaceFileEntity sourceInterfaceFile,
        T extract,
        String checksum
    ) {
        List<InterfaceFileEntity> failedSourceJsonFiles = interfaceFilesRepository
            .findAllByRelatedInterfaceFileInterfaceFileIdAndTypeAndFileNameAndChecksumAndStatus(
                sourceInterfaceFile.getInterfaceFileId(),
                Type.SOURCE_JSON,
                extract.getFileName(),
                checksum,
                Status.FAILED);

        failedSourceJsonFiles.forEach(sourceJson -> sourceJson.setStatus(Status.FAILED_SUPERSEDED));

        if (!failedSourceJsonFiles.isEmpty()) {
            interfaceFilesRepository.saveAll(failedSourceJsonFiles);
        }
    }


    String calculateExtractChecksum(byte[] jsonBytes) {
        try {
            return StreamUtil.calculateChecksum(new ByteArrayInputStream(jsonBytes));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to calculate SOURCE_JSON checksum", e);
        }
    }

    InterfaceFileEntity createSourceJson(
        InterfaceFileEntity sourceInterfaceFile,
        T extract,
        Short[] businessUnitIds,
        Domain domain,
        String checksum,
        long creatorId
    ) {
        return InterfaceFileEntity.builder()
            .source(config.getSource())
            .target(config.getTarget())
            .type(Type.SOURCE_JSON)
            .opalDomain(domain)
            .fileName(extract.getFileName())
            .checksum(checksum)
            .status(Status.SUCCESS)
            .createdDatetime(LocalDateTime.now(clock))
            .relatedInterfaceFile(sourceInterfaceFile)
            .businessUnitId(businessUnitIds)
            .paymentType(extract.getPaymentType())
            .createdBy(creatorId)
            .build();
    }

    void uploadSourceJson(
        InterfaceFileEntity sourceJson,
        byte[] jsonBytes
    ) {
        UUID filestoreUuid = UUID.randomUUID();

        try {
            interfaceFileBlobStoreService.uploadBaisFile(
                filestoreUuid,
                config.getContainerName(),
                new ByteArrayInputStream(jsonBytes),
                sourceJson.getChecksum()
            );
            sourceJson.setFilestoreUuid(filestoreUuid);
        } catch (BlobChecksumValidationException e) {
            markSourceJsonFailed(sourceJson, "Blob checksum validation failed: %s".formatted(e.getMessage()), e);
        } catch (RuntimeException e) {
            markSourceJsonFailed(sourceJson, "Blob upload failed: %s".formatted(e.getMessage()), e);
        }
    }

    void markSourceJsonFailed(InterfaceFileEntity sourceJson, String message, RuntimeException exception) {
        sourceJson.setStatus(Status.FAILED);
        sourceJson.setErrors(errorJson(message));
        log.error("Error processing SOURCE_JSON interfaceFileId={} fileName={}",
            sourceJson.getInterfaceFileId(), sourceJson.getFileName(), exception);
    }

    protected abstract Short[] getBusinessUnitsFromExtract(T extract);

    protected abstract Domain getDomainFromExtract(T extract);

    protected abstract void postProcessExtract(
        InterfaceFileEntity sourceInterfaceFile,
        T extract
    );

    /**
     * Decides whether an extract should continue into SOURCE_JSON creation.
     *
     * @return true if the extract should be processed, false if it should be skipped
     */
    public abstract boolean preProcessExtract(
        InterfaceFileEntity sourceInterfaceFile,
        T extract
    );
}
