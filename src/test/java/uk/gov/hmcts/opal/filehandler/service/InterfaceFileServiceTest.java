package uk.gov.hmcts.opal.filehandler.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.opal.common.spring.security.OpalJwtAuthenticationToken;
import uk.gov.hmcts.opal.common.util.SecurityUtil;
import uk.gov.hmcts.opal.filehandler.config.BTEckohReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.config.BaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.config.CapsReportBaisFileProcessorConfiguration;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.exception.BlobNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.InterfaceFileNotFoundException;
import uk.gov.hmcts.opal.filehandler.exception.InvalidInterfaceFileStatusException;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.blobstore.InterfaceFileBlobStoreService;

@ExtendWith(MockitoExtension.class)
class InterfaceFileServiceTest {

    @Mock
    private InterfaceFilesRepository repository;

    @Mock
    private InterfaceFileBlobStoreService blobStoreService;

    @Mock
    private BTEckohReportBaisFileProcessorConfiguration bteckohConfig;

    @Mock
    private CapsReportBaisFileProcessorConfiguration capsConfig;

    @Mock
    private OpalJwtAuthenticationToken authToken;

    @Mock
    private Map<String, BaisFileProcessorConfiguration> configs;

    @InjectMocks
    private InterfaceFilesService interfaceFileService;

    private UUID uuid;

    private BinaryData mockData;
    private MockedStatic<SecurityUtil> securityUtil;

    @BeforeEach
    void setup() {
        uuid = UUID.randomUUID();
        mockData = mock(BinaryData.class);
        securityUtil = mockStatic(SecurityUtil.class);
    }

    void withPermissions() {
        securityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser).thenReturn(authToken);
        // when(authToken.hasPermission(FileHandlerPermission.ViewInterfacesFile)).thenReturn(true);
    }

    void withoutPermissions() {
        securityUtil.when(SecurityUtil::getOpalJwtAuthenticationTokenForCurrentUser).thenReturn(authToken);
        // when(authToken.hasPermission(FileHandlerPermission.ViewInterfacesFile)).thenReturn(false);
    }

    @AfterEach
    void teardown() {
        securityUtil.close();
    }

    @Test
    void getInterfaceFileContent_bteckohSourceReturnsData() {
        withPermissions();
        when(configs.get(eq("BTEckohReportBaisFileProcessorConfig"))).thenReturn(bteckohConfig);
        when(bteckohConfig.getContainerName()).thenReturn("bteckoh-report");

        when(repository.findById(eq(1L))).thenReturn(
            Optional.of(buildEntity(1L, uuid, Interface.BTECKOH_REPORT, Status.SUCCESS))
        );
        when(blobStoreService.fetchInterfaceFile(eq(1L), eq(uuid), eq("bteckoh-report"))).thenReturn(mockData);

        InputStream response = interfaceFileService.getInterfaceFilesContent(1L);

        verify(repository).findById(eq(1L));
        verify(blobStoreService).fetchInterfaceFile(eq(1L), eq(uuid), eq("bteckoh-report"));
        verify(bteckohConfig).getContainerName();
    }

    @Test
    void getInterfaceFileContent_capsSourceReturnsData() {
        withPermissions();
        when(configs.get(eq("capsReportBaisFileProcessorConfig"))).thenReturn(capsConfig);
        when(capsConfig.getContainerName()).thenReturn("caps-report");

        when(repository.findById(eq(1L))).thenReturn(
            Optional.of(buildEntity(1L, uuid, Interface.CAPS_REPORT, Status.SUCCESS))
        );
        when(blobStoreService.fetchInterfaceFile(eq(1L), eq(uuid), eq("caps-report"))).thenReturn(mockData);

        InputStream response = interfaceFileService.getInterfaceFilesContent(1L);

        verify(repository).findById(eq(1L));
        verify(blobStoreService).fetchInterfaceFile(eq(1L), eq(uuid), eq("caps-report"));
        verify(capsConfig).getContainerName();
    }

    /*
    TODO: This test is commented out due to the AC for permissions check being removed from PO-3948.
    It will be re-added as part of PO-8686
    @Test
    void getInterfaceFileContent_missingPermissionsThrowsError() {
        withoutPermissions();

        Exception e = assertThrows(
            PermissionNotAllowedException.class,
            () -> interfaceFileService.getInterfaceFilesContent(1L)
        );
        assertEquals("[ViewInterfacesFile] permission(s) are not enabled for the user.", e.getMessage());
    }
    */

    @Test
    void getInterfaceFileContent_EntityNotFoundThrowsError() {
        withPermissions();

        when(repository.findById(eq(1L))).thenReturn(
            Optional.ofNullable(null)
        );

        Exception e = assertThrows(
            InterfaceFileNotFoundException.class,
            () -> interfaceFileService.getInterfaceFilesContent(1L)
        );
        assertEquals("404 NOT_FOUND \"Interface file with id 1 could not be located.\"", e.getMessage());

        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(blobStoreService);
    }


    @Test
    void getInterfaceFileContent_invalidStatusThrowsError() {
        withPermissions();

        when(repository.findById(eq(1L))).thenReturn(
            Optional.of(buildEntity(1L, uuid, Interface.BTECKOH_REPORT, Status.FAILED))
        );

        Exception e = assertThrows(
            InvalidInterfaceFileStatusException.class,
            () -> interfaceFileService.getInterfaceFilesContent(1L)
        );
        assertEquals(
            "422 UNPROCESSABLE_CONTENT \"Interface file with id 1 could not be retrieved as it has "
            + "an invalid status of: \"FAILED\" only files with status: \"SUCCESS\" can be returned.\"",
            e.getMessage()
        );

        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(blobStoreService);
    }

    @Test
    void getInterfaceFileContent_missingBlobThrowsError() {
        withPermissions();
        when(configs.get(eq("BTEckohReportBaisFileProcessorConfig"))).thenReturn(bteckohConfig);
        when(bteckohConfig.getContainerName()).thenReturn("bteckoh-report");

        when(repository.findById(eq(1L))).thenReturn(
            Optional.of(buildEntity(1L, uuid, Interface.BTECKOH_REPORT, Status.SUCCESS))
        );

        when(blobStoreService.fetchInterfaceFile(eq(1L), eq(uuid), eq("bteckoh-report")))
            .thenThrow(BlobNotFoundException.class);

        Exception e = assertThrows(
            BlobNotFoundException.class,
            () -> interfaceFileService.getInterfaceFilesContent(1L)
        );
        assertEquals("null", e.getMessage());

        verify(repository).findById(1L);
        verifyNoMoreInteractions(repository);
        verify(bteckohConfig).getContainerName();

    }

    private InterfaceFileEntity buildEntity(long id, UUID fsuuid, Interface source, Status status) {
        return InterfaceFileEntity.builder()
            .interfaceFileId(id)
            .filestoreUuid(fsuuid)
            .source(source)
            .target(Interface.BTECKOH_REPORT)
            .type(Type.SOURCE)
            .opalDomain(Domain.FILE_HANDLER)
            .fileName("fileName")
            .status(status)
            .createdDatetime(LocalDateTime.now())
            .build();
    }
}
