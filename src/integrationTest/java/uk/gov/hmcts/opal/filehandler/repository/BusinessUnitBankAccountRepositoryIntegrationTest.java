package uk.gov.hmcts.opal.filehandler.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.hmcts.opal.filehandler.entity.BusinessUnitBankAccountEntity;
import uk.gov.hmcts.opal.filehandler.support.AbstractIntegrationTest;
import uk.gov.hmcts.opal.filehandler.testdata.BusinessUnitBankAccountEntityTestData;

class BusinessUnitBankAccountRepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final long TYPICAL_ID = 920001L;
    private static final long MAX_ID = 920002L;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private BusinessUnitBankAccountRepository repository;

    @Autowired
    private BusinessUnitBankAccountEntityTestData testData;

    @BeforeEach
    void setUp() {
        testData.clear();
    }

    @Test
    void shouldPersistAndLoadTypicalBusinessUnitBankAccount() {
        BusinessUnitBankAccountEntity original = testData.saveTypicalBusinessUnitBankAccount(TYPICAL_ID, "AB01");

        entityManager.clear();

        BusinessUnitBankAccountEntity fetched = repository.findById(TYPICAL_ID).orElseThrow();

        assertThat(fetched.getId()).isEqualTo(original.getId());
        assertThat(fetched.getBusinessUnitCode()).isEqualTo(original.getBusinessUnitCode());
        assertThat(fetched.getDomain()).isEqualTo(original.getDomain());
        assertThat(fetched.getBankSortCode()).isEqualTo(original.getBankSortCode());
        assertThat(fetched.getBankAccountNumber()).isEqualTo(original.getBankAccountNumber());
        assertThat(fetched.getDwpCourtCode()).isEqualTo(original.getDwpCourtCode());
    }

    @Test
    void shouldPersistAndLoadMaximumBusinessUnitBankAccount() {
        BusinessUnitBankAccountEntity original = testData.getMaximumBusinessUnitBankAccount(MAX_ID);

        testData.saveAndFlushBusinessUnitBankAccount(original);
        entityManager.clear();

        BusinessUnitBankAccountEntity fetched = repository.findById(MAX_ID).orElseThrow();

        assertThat(fetched.getId()).isEqualTo(original.getId());
        assertThat(fetched.getBusinessUnitCode()).isEqualTo(original.getBusinessUnitCode());
        assertThat(fetched.getDomain()).isEqualTo(original.getDomain());
        assertThat(fetched.getBankSortCode()).isEqualTo(original.getBankSortCode());
        assertThat(fetched.getBankAccountNumber()).isEqualTo(original.getBankAccountNumber());
        assertThat(fetched.getDwpCourtCode()).isEqualTo(original.getDwpCourtCode());
    }

    @Nested
    class FindBySortCodeAndAccountNumber {
        @Test
        void correctlyFetchesBySortCodeAndAccountNumber() {
            BusinessUnitBankAccountEntity original = testData.saveTypicalBusinessUnitBankAccount(TYPICAL_ID, "AB01");

            entityManager.clear();

            String sortCode = original.getBankSortCode();
            String accountNumber = original.getBankAccountNumber();

            BusinessUnitBankAccountEntity fetched = repository
                .findByBankSortCodeAndBankAccountNumber(sortCode, accountNumber).orElseThrow();

            assertThat(fetched.getId()).isEqualTo(original.getId());
            assertThat(fetched.getBusinessUnitCode()).isEqualTo(original.getBusinessUnitCode());
            assertThat(fetched.getDomain()).isEqualTo(original.getDomain());
            assertThat(fetched.getBankSortCode()).isEqualTo(original.getBankSortCode());
            assertThat(fetched.getBankAccountNumber()).isEqualTo(original.getBankAccountNumber());
            assertThat(fetched.getDwpCourtCode()).isEqualTo(original.getDwpCourtCode());
        }
    }
}

