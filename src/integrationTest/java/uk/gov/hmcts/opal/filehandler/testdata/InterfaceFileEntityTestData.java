package uk.gov.hmcts.opal.filehandler.testdata;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.opal.filehandler.entity.Domain;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.PaymentType;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.entity.Type;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;

@Component
@RequiredArgsConstructor
public class InterfaceFileEntityTestData {

    private final InterfaceFilesRepository repository;
    private final Clock clock;

    public InterfaceFileEntity getTypicalInterfaceFile(String fileName) {
        return repository.save(InterfaceFileEntity.builder()
            .source(Interface.NATWEST)
            .target(Interface.OPAL)
            .type(Type.SOURCE_JSON)
            .opalDomain(Domain.FINES)
            .fileName(fileName)
            .status(Status.INGESTED)
            .createdDatetime(LocalDateTime.now(clock))
            .build());
    }

    public InterfaceFileEntity getTypicalRelatedChildInterfaceFile(
        String fileName,
        InterfaceFileEntity parent
    ) {
        InterfaceFileEntity child = getTypicalInterfaceFile(fileName);

        child.setPaymentType(PaymentType.CASH);
        child.setBusinessUnitCode(new String[] {"AB01", "CD02"});
        child.setRelatedInterfaceFile(parent);

        return child;
    }

    public InterfaceFileEntity getMaximumInterfaceFile() {
        InterfaceFileEntity interfaceFile = getTypicalInterfaceFile("ei2-transformed-file.json");

        interfaceFile.setSource(Interface.ALLPAY_DD);
        interfaceFile.setTarget(Interface.MARSTON);
        interfaceFile.setType(Type.TRANSFORMED_JSON);
        interfaceFile.setOpalDomain(Domain.FILE_HANDLER);
        interfaceFile.setFilestoreUuid(UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        interfaceFile.setChecksum("1234567890abcdef1234567890abcdef");
        interfaceFile.setStatus(Status.SUCCESS);
        interfaceFile.setCreatedDatetime(LocalDateTime.ofInstant(Instant.ofEpochMilli(1753279200000L),
            TimeZone.getDefault().toZoneId()));
        interfaceFile.setErrors("[{\"errorCode\": \"E001\", \"errorMessage\": \"Sample error message\"}]");
        interfaceFile.setBusinessUnitCode(new String[] {"AB01", "CD02"});
        interfaceFile.setPaymentType(PaymentType.CHEQUE);

        return interfaceFile;
    }

    public InterfaceFileEntity saveTypicalInterfaceFile(String fileName) {
        return repository.save(getTypicalInterfaceFile(fileName));
    }

    public InterfaceFileEntity saveAndFlushInterfaceFile(InterfaceFileEntity interfaceFile) {
        return repository.saveAndFlush(interfaceFile);
    }
}

