package uk.gov.hmcts.opal.filehandler.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.support.AbstractIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.InterfaceFileEntityTestData;

class InterfaceFilesRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InterfaceFilesRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InterfaceFileEntityTestData interfaceFileEntityTestData;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @Transactional
    void shouldPersistAndLoadRelatedInterfaceFileRelationship() {
        InterfaceFileEntity parent = interfaceFileEntityTestData.saveTypicalInterfaceFile(
            "parent-source-file.dat"
        );

        InterfaceFileEntity child = interfaceFileEntityTestData.getTypicalRelatedChildInterfaceFile(
            "child-transformed-file.json",
            parent
        );

        interfaceFileEntityTestData.saveAndFlushInterfaceFile(child);

        entityManager.clear();

        long childId = child.getInterfaceFileId();
        long parentId = parent.getInterfaceFileId();

        InterfaceFileEntity loadedChild = repository.findById(childId).orElseThrow();

        assertThat(loadedChild.getRelatedInterfaceFile()).isNotNull();
        assertThat(loadedChild.getRelatedInterfaceFile().getInterfaceFileId()).isEqualTo(parentId);
        assertThat(loadedChild.getRelatedInterfaceFile().getFileName()).isEqualTo("parent-source-file.dat");
    }

    @Test
    void shouldPersistAndLoadEi2Columns() {
        InterfaceFileEntity interfaceFile = interfaceFileEntityTestData.getMaximumInterfaceFile();
        long parentId = interfaceFile.getInterfaceFileId();

        interfaceFileEntityTestData.saveAndFlushInterfaceFile(interfaceFile);
        entityManager.clear();

        InterfaceFileEntity loaded = repository.findById(parentId).orElseThrow();

        assertThat(loaded.getInterfaceFileId()).isEqualTo(interfaceFile.getInterfaceFileId());
        assertThat(loaded.getSource()).isEqualTo(interfaceFile.getSource());
        assertThat(loaded.getTarget()).isEqualTo(interfaceFile.getTarget());
        assertThat(loaded.getType()).isEqualTo(interfaceFile.getType());
        assertThat(loaded.getOpalDomain()).isEqualTo(interfaceFile.getOpalDomain());
        assertThat(loaded.getFileName()).isEqualTo(interfaceFile.getFileName());
        assertThat(loaded.getFilestoreUuid()).isEqualTo(interfaceFile.getFilestoreUuid());
        assertThat(loaded.getChecksum()).isEqualTo(interfaceFile.getChecksum());
        assertThat(loaded.getStatus()).isEqualTo(interfaceFile.getStatus());
        assertThat(loaded.getCreatedDatetime()).isEqualTo(interfaceFile.getCreatedDatetime());
        assertThat(loaded.getErrors()).isEqualTo(interfaceFile.getErrors());
        assertThat(loaded.getBusinessUnitCode()).containsExactly(interfaceFile.getBusinessUnitCode());
        assertThat(loaded.getPaymentType()).isEqualTo(interfaceFile.getPaymentType());
        assertThat(loaded.getRelatedInterfaceFile()).isNull();
    }

}



