package uk.gov.hmcts.opal.filehandler.service;

import static java.lang.String.format;

import com.azure.core.util.BinaryData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.core.TypedPropertyPath;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.common.exceptions.bespoke.BusinessRuleViolationException;
import uk.gov.hmcts.common.exceptions.standard.BadRequestException;
import uk.gov.hmcts.common.exceptions.standard.InternalServerErrorException;
import uk.gov.hmcts.opal.common.user.authorisation.model.UserStateV2;
import uk.gov.hmcts.opal.common.util.SecurityUtil;
import uk.gov.hmcts.opal.filehandler.authorisation.FileHandlerPermission;
import uk.gov.hmcts.opal.filehandler.config.FileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.PaymentType;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.InterfaceFileNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.InvalidInterfaceFileStatusException;
import uk.gov.hmcts.opal.filehandler.mapper.InterfaceFileMapper;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.repository.specs.InterfaceFileSpecsFactory;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;
import uk.gov.hmcts.opal.filehandler.service.pdpl.InterfaceFilesPdplLoggingService;
import uk.gov.hmcts.opal.filehandler.service.request.SearchInterfaceFilesDto;
import uk.gov.hmcts.opal.filehandler.util.PermissionUtil;
import uk.gov.hmcts.opal.generated.model.AddInterfaceFileRequestMetadata;
import uk.gov.hmcts.opal.generated.model.DeleteInterfaceFilesResultTestSupport;
import uk.gov.hmcts.opal.generated.model.DomainEnumTypes;
import uk.gov.hmcts.opal.generated.model.InterfaceFileObjectInterfaceFile;
import uk.gov.hmcts.opal.generated.model.InterfaceFileTypeEnumInterfaceFile;

@Service
@Slf4j
public class InterfaceFilesService {

    private final InterfaceFilesRepository repository;
    private final InterfaceFileSpecsFactory specsFactory;
    private final InterfaceFileMapper mapper;
    private final InterfaceFileBlobStoreService blobStoreService;
    private final InterfaceFilesPdplLoggingService loggingService;
    private final Map<String, FileProcessorConfiguration> configs;
    private final Map<Class<? extends InterfaceFileProcessorService>, InterfaceFileProcessorService> processorServices;

    public InterfaceFilesService(InterfaceFilesRepository repository,
        InterfaceFileSpecsFactory specsFactory,
        InterfaceFileMapper mapper,
        InterfaceFileBlobStoreService blobStoreService,
        InterfaceFilesPdplLoggingService loggingService,
        Map<String, FileProcessorConfiguration> configs,
        List<InterfaceFileProcessorService> processorServicesList) {
        this.repository = repository;
        this.specsFactory = specsFactory;
        this.mapper = mapper;
        this.blobStoreService = blobStoreService;
        this.loggingService = loggingService;
        this.configs = configs;

        this.processorServices = processorServicesList.stream()
            .collect(Collectors.toMap(
                InterfaceFilesService::getProcessorClass,
                Function.identity()
            ));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends InterfaceFileProcessorService> getProcessorClass(
        InterfaceFileProcessorService processor) {

        return (Class<? extends InterfaceFileProcessorService>)
            ClassUtils.getUserClass(processor);
    }


    private FileProcessorConfiguration getConfig(Interface interfaceType) {
        return configs.get(interfaceType.getConfigComponentName());
    }


    @Transactional(readOnly = true)
    public List<InterfaceFileObjectInterfaceFile> searchInterfaceFiles(SearchInterfaceFilesDto request) {
        PermissionUtil.checkPermissionDomainAgnostic(FileHandlerPermission.VIEW_INTERFACE_FILES);

        Specification<InterfaceFileEntity> specs = specsFactory.createSearchSpecs(request, permittedDomains());

        Sort sort = Sort.by(Direction.ASC, TypedPropertyPath.of(InterfaceFileEntity::getCreatedDatetime));
        List<InterfaceFileEntity> interfacesFiles = repository.findAll(specs, sort);

        return mapper.toInterfaceFileObjects(interfacesFiles);
    }


    public InputStream getInterfaceFilesContent(Long id) {
        InterfaceFileEntity entity = getInterfaceFileEntity(id);
        checkAccessPermission(entity);

        if (entity.getStatus() != Status.SUCCESS) {
            throw new InvalidInterfaceFileStatusException(
                format("Interface file with id %d could not be retrieved as it has an invalid status of:"
                        + " \"%s\" only files with status: \"SUCCESS\" can be returned.",
                    id, entity.getStatus()));
        }

        FileProcessorConfiguration config = getConfig(getInterfaceType(
            entity.getSource(), entity.getTarget()
        ));
        String containerName = config.getContainerName();

        BinaryData file = blobStoreService.fetchInterfaceFile(id, entity.getFilestoreUuid(), containerName);

        UserStateV2 userState = SecurityUtil.getOpalJwtAuthenticationTokenForCurrentUser().getUserState();
        if (!userState.isSystemUser()) {
            loggingService.logPdpl(id, userState);
        }

        return file.toStream();
    }

    public InterfaceFileObjectInterfaceFile getInterfaceFile(Long id) {
        InterfaceFileEntity entity = getInterfaceFileEntity(id);
        checkAccessPermission(entity);

        return mapper.toInterfaceFileObject(entity);
    }

    private void checkAccessPermission(InterfaceFileEntity entity) {
        if (entity.getOpalDomain() != null) {
            PermissionUtil.checkPermissionInDomain(FileHandlerPermission.VIEW_INTERFACE_FILES,
                entity.getOpalDomain().toCommonDomain());
        } else {
            PermissionUtil.checkPermissionDomainAgnostic(FileHandlerPermission.VIEW_INTERFACE_FILES);
        }
    }

    public InterfaceFileEntity getInterfaceFileEntity(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new InterfaceFileNotFoundException(id));
    }

    private Set<uk.gov.hmcts.opal.filehandler.entity.Domain> permittedDomains() {
        return SecurityUtil.getOpalJwtAuthenticationTokenForCurrentUser()
            .getUserState()
            .getDomains()
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue().getBusinessUnitUsers().stream()
                .flatMap(user -> user.getPermissions().stream())
                .anyMatch(permission -> permission.getDescription()
                    .equalsIgnoreCase(FileHandlerPermission.VIEW_INTERFACE_FILES.getDescription())))
            .flatMap(entry -> Arrays.stream(uk.gov.hmcts.opal.filehandler.entity.Domain.values())
                .filter(domain -> domain.toCommonDomain() == entry.getKey()))
            .collect(Collectors.toSet());
    }

    private InterfaceFileProcessorService getProcessorService(Interface sourceType) {
        return processorServices.get(sourceType.getProcessorServiceClass());
    }

    @Transactional
    public InterfaceFileObjectInterfaceFile addInterfaceFile(MultipartFile file,
        AddInterfaceFileRequestMetadata metadata) {
        boolean shouldPreProcess = Boolean.TRUE.equals(metadata.getShouldPreProcessFile());
        if (shouldPreProcess && !InterfaceFileTypeEnumInterfaceFile.SOURCE.equals(metadata.getType())) {
            throw new BusinessRuleViolationException(
                "Invalid type",
                "Type must be SOURCE when should_pre_process_file is true");
        }
        DomainEnumTypes metadataDomain = metadata.getDomain();
        Domain fromMetadata = Domain.valueOf(metadataDomain);

        if (metadataDomain != null && fromMetadata != null) {
            PermissionUtil.checkPermissionInDomain(
                FileHandlerPermission.CREATE_INTERFACE_FILES, fromMetadata.toCommonDomain());
        } else {
            PermissionUtil.checkPermission(FileHandlerPermission.CREATE_INTERFACE_FILES);
        }

        try {
            //Ensure the related interface file exists if provided
            InterfaceFileEntity relatedInterfaceFile = null;
            if (metadata.getRelatedInterfaceFileId() != null) {
                relatedInterfaceFile = getInterfaceFileEntity(metadata.getRelatedInterfaceFileId());
            }
            Interface interfaceType = getInterfaceType(
                Interface.valueOf(metadata.getSource()),
                Interface.valueOf(metadata.getTarget())
            );
            InterfaceFileProcessorService processorService = getProcessorService(interfaceType);
            FileProcessorConfiguration config = getConfig(interfaceType);

            InterfaceFileEntity entity = processorService.ingestFile(
                file.getOriginalFilename(),
                file.getBytes(),
                Interface.valueOf(metadata.getSource()),
                Interface.valueOf(metadata.getTarget()),
                Type.valueOf(metadata.getType()),
                Domain.valueOf(metadata.getDomain()),
                config.getContainerName(),
                SecurityUtil.getOpalJwtAuthenticationTokenForCurrentUser().getUserId()
            );
            entity.setRelatedInterfaceFile(relatedInterfaceFile);
            entity.setPaymentType(PaymentType.valueOf(metadata.getPaymentType()));

            entity.setBusinessUnitId(metadata.getBusinessUnitId() == null
                ? null : new Short[] {metadata.getBusinessUnitId()});

            entity = repository.save(entity);

            if (shouldPreProcess) {
                processorService.processFile(
                    entity,
                    new ByteArrayInputStream(file.getBytes()),
                    SecurityUtil.getOpalJwtAuthenticationTokenForCurrentUser().getUserId()
                );
            }
            processorService.completeIngestion(metadata.getFileName(), entity, false);
            return mapper.toInterfaceFileObject(getInterfaceFileEntity(entity.getInterfaceFileId()));
        } catch (IOException e) {
            throw new InternalServerErrorException(
                "Internal Server Error",
                "Failed to read file content for interface file creation ", e);
        }
    }

    @Transactional
    public DeleteInterfaceFilesResultTestSupport deleteInterfaceFiles(List<Long> ids) {
        ArrayList<Long> successIds = new ArrayList<>();
        ArrayList<Long> failedIds = new ArrayList<>();

        List<InterfaceFileEntity> interfaceFiles = getAndValidateInterfaceFilesForDeletion(ids);

        for (InterfaceFileEntity interfaceFile : interfaceFiles) {
            Long id = interfaceFile.getInterfaceFileId();
            try {
                // First delete other files in the group, eg: FAILED, FAILED_SUPERSEDED and DUPLICATE that will not be
                // related to this interface file via the related_interface_file_id foreign key
                log.info("Deleting other interface files with same name and checksum as interface file id: {}", id);
                List<InterfaceFileEntity> filesWithSameNameAndChecksum = repository.findByTypeAndChecksumAndFileName(
                    Type.SOURCE, interfaceFile.getChecksum(), interfaceFile.getFileName());
                // This query will return the original file, which we are not deleting yet.
                filesWithSameNameAndChecksum.remove(interfaceFile);
                filesWithSameNameAndChecksum.stream().forEach(this::deleteInterfaceFile);

                // Now delete the whole tree of related files, ie those related via the related_interface_file_id
                // foreign key. This will include the SOURCE_JSON and TRANSFORMED_JSON files. We start at the bottom of
                // the tree and work up.
                log.info("Processing delete for interface file id: {} and all related files...", id);
                processDelete(interfaceFile);
                successIds.add(id);
            } catch (Exception e) {
                log.warn("Did not complete all deletes related to interface file id: {}", id);
                failedIds.add(id);
            }
        }

        log.info("Successfully processed {} out of {} requested)", successIds.size(), ids.size());

        return DeleteInterfaceFilesResultTestSupport.builder()
            .successfullyDeletedIds(successIds)
            .failedIds(failedIds)
            .build();
    }

    private List<InterfaceFileEntity> getAndValidateInterfaceFilesForDeletion(List<Long> ids) {
        ids = ids.stream()
            .distinct()
            .collect(Collectors.toList());

        List<InterfaceFileEntity> files = new ArrayList<>();
        Set<Long> notFoundsIds = new TreeSet<>();
        Set<Long> invalidIds = new TreeSet<>();
        for (Long id: ids) {
            repository.findById(id)
                .ifPresentOrElse(files::add, () -> notFoundsIds.add(id));
        }
        for (InterfaceFileEntity file: files) {
            if (file.getType() != Type.SOURCE) {
                invalidIds.add(file.getInterfaceFileId());
            }
        }

        if (notFoundsIds.isEmpty() && invalidIds.isEmpty()) {
            return files;
        } else {
            StringBuilder msgBuilder = new StringBuilder();
            if (!notFoundsIds.isEmpty()) {
                msgBuilder.append(format("The following interface files do not exist: %s", notFoundsIds));
            }
            if (!invalidIds.isEmpty()) {
                if (!notFoundsIds.isEmpty()) {
                    msgBuilder.append(System.lineSeparator());
                }
                msgBuilder.append(format("The following interface files are not of type SOURCE: %s", invalidIds));
            }

            throw new BadRequestException("Request to delete interface files failed validation", msgBuilder.toString());
        }
    }

    private void processDelete(InterfaceFileEntity interfaceFile) {
        // We can't just do a cascade delete because we need to ensure the blobs
        // are deleted from the blob store before we delete the row from the db.
        if (interfaceFile.hasChildren()) {
            interfaceFile.getChildRelatedInterfaceFiles().stream()
                .collect(Collectors.toUnmodifiableSet()) // copy because we are going to remove items from the original
                .forEach(this::processDelete);
        } else {
            DeleteInterfaceFileResult result = deleteInterfaceFile(interfaceFile);
            // We are at the bottom of the tree, so if no other children
            // exist at this level then we can delete the parent
            if (result.hadParentWithoutRemainingChildren()) {
                processDelete(result.parent());
            }
        }
    }

    private DeleteInterfaceFileResult deleteInterfaceFile(InterfaceFileEntity interfaceFile) {
        if (interfaceFile.getFilestoreUuid() != null) {
            deleteBlobIfNotShared(interfaceFile);
        }

        log.info("Deleting interface file id: {} type: {} status: {} from db", interfaceFile.getInterfaceFileId(),
            interfaceFile.getType(), interfaceFile.getStatus());

        InterfaceFileEntity parent = null;
        if (interfaceFile.hasParent()) {
            parent = interfaceFile.getRelatedInterfaceFile();
            parent.getChildRelatedInterfaceFiles().remove(interfaceFile);
        }
        repository.delete(interfaceFile);
        log.info("Successfully deleted interface file id: {} from db", interfaceFile.getInterfaceFileId());
        return new DeleteInterfaceFileResult(parent);
    }

    private void deleteBlobIfNotShared(InterfaceFileEntity interfaceFile) {
        final Long id = interfaceFile.getInterfaceFileId();
        final UUID filestoreUUID = interfaceFile.getFilestoreUuid();
        final String containerName = getConfig(interfaceFile.getSource()).getContainerName();

        if (repository.existsByFilestoreUuidAndSourceAndInterfaceFileIdNot(filestoreUUID,
            interfaceFile.getSource(), id)) {
            log.info("Skipping deleting interface file id: {} type: {} status: {} from blob store as it is referenced "
                    + "by other interface file rows", id, interfaceFile.getType(), interfaceFile.getStatus());
            return;
        }

        log.info("Deleting interface file id: {} type: {} status: {} from blob store", id,
            interfaceFile.getType(), interfaceFile.getStatus());

        try {
            blobStoreService.deleteBlob(filestoreUUID, containerName);
            log.info(
                "Successfully deleted Interface File id: {} filestoreUuid: {} container: {} from blob store",
                id, filestoreUUID, containerName);
        } catch (Exception e) {
            log.warn("Failed to delete Interface File id: {} filestoreUuid: {} container: {} from blob store,"
                + "and will now skip deleting from db", id, filestoreUUID, containerName);
            throw e; // this exception will halt the tree of deletes for whatever the parent was
        }
    }

    private record DeleteInterfaceFileResult(InterfaceFileEntity parent) {
        boolean hadParentWithoutRemainingChildren() {
            return parent != null && !parent.hasChildren();
        }
    }

    private Interface getInterfaceType(Interface source, Interface target) {
        return Interface.OPAL.equals(source) ? target : source;
    }
}
