package uk.gov.hmcts.opal.filehandler.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.opal.filehandler.entity.BusinessUnitBankAccountEntity;

@Repository
public interface BusinessUnitBankAccountRepository extends JpaRepository<BusinessUnitBankAccountEntity, Long> {

    Optional<BusinessUnitBankAccountEntity> findByBankSortCodeAndBankAccountNumber(
        String bankSortCode, String bankAccountNumber);
}

