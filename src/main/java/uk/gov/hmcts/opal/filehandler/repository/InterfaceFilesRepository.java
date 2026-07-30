package uk.gov.hmcts.opal.filehandler.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.Status;

@Repository
public interface InterfaceFilesRepository extends JpaRepository<InterfaceFileEntity, Long>,
    JpaSpecificationExecutor<InterfaceFileEntity> {

    Optional<InterfaceFileEntity> findByFileNameAndChecksumAndStatus(String fileName, String checksum, Status status);

    List<InterfaceFileEntity> findAllByFileNameAndChecksumAndStatus(
        String fileName,
        String checksum,
        Status status
    );

    List<InterfaceFileEntity> findAllByFileName(String fileName);

}
