package uk.gov.hmcts.opal.filehandler.service.extraction;

import jakarta.persistence.EntityNotFoundException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.opal.filehandler.entity.BusinessUnitBankAccountEntity;
import uk.gov.hmcts.opal.filehandler.entity.Interface;
import uk.gov.hmcts.opal.filehandler.entity.InterfaceFileEntity;
import uk.gov.hmcts.opal.filehandler.entity.PaymentType;
import uk.gov.hmcts.opal.filehandler.entity.Status;
import uk.gov.hmcts.opal.filehandler.repository.BusinessUnitBankAccountRepository;
import uk.gov.hmcts.opal.filehandler.repository.InterfaceFilesRepository;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.BankDetails;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.DestinationDetails;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.InterfaceFileCommonDataExtract;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.OriginatorDetails;
import uk.gov.hmcts.opal.filehandler.service.extraction.model.Transaction;
import uk.gov.hmcts.opal.filehandler.utils.StreamUtil;

@Service
@RequiredArgsConstructor
public class BacsStandard18BaisExtractionService implements ExtractionService<InterfaceFileCommonDataExtract> {

    private static final Set<String> REQUIRED_HEADER_PREFIXES = Set.of("VOL1", "HDR1", "UHL1");
    private static final Set<String> CONTROL_ROW_PREFIXES = Set.of(
        "VOL1", "HDR1", "HDR2", "UHL1", "EOF1", "EOF2", "UTL1", "CTL "
    );
    private static final DateTimeFormatter OUTPUT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InterfaceFilesRepository interfaceFilesRepository;
    private final BusinessUnitBankAccountRepository businessUnitBankAccountRepository;

    @Override
    public List<InterfaceFileCommonDataExtract> extractStandardData(
        InterfaceFileEntity sourceInterfaceFile,
        InputStream fileContents
    ) {
        validateInputs(sourceInterfaceFile, fileContents);
        List<String> lines = StreamUtil.readLines(fileContents);
        validateHeaders(lines);

        List<ParsedTransaction> transactions = lines.stream()
            .filter(line -> !line.isBlank())
            .filter(line -> !isControlRow(line))
            .filter(line -> !isTotalRow(line))
            .map(this::parseTransaction)
            .toList();

        if (transactions.isEmpty()) {
            sourceInterfaceFile.setStatus(Status.SUCCESS_NO_TRANSACTIONS);
            interfaceFilesRepository.save(sourceInterfaceFile);
            return List.of();
        }

        ParsedTransaction firstTransaction = transactions.getFirst();
        BankDetails destinationBankDetails = firstTransaction.destinationBankDetails();
        validateConsistentDestinationDetails(transactions, firstTransaction);
        applyAllpayDdSourceUpdate(sourceInterfaceFile, transactions.getFirst().transaction());

        InterfaceFileCommonDataExtract extract = InterfaceFileCommonDataExtract.builder()
            .fileName(sourceInterfaceFile.getFileName())
            .destinationDetails(DestinationDetails.builder().bankDetails(destinationBankDetails).build())
            .paymentType(paymentTypeFor(firstTransaction.transaction().getTransactionCode()))
            .transactions(transactions.stream().map(ParsedTransaction::transaction).toList())
            .build();

        return List.of(extract);
    }

    @Override
    public BusinessUnitBankAccountEntity getBusinessUnitBankAccount(InterfaceFileCommonDataExtract extractedData) {
        BankDetails bank = extractedData.getDestinationDetails().getBankDetails();
        return businessUnitBankAccountRepository.findByBankSortCodeAndBankAccountNumber(
            bank.getSortCode(),bank.getAccountNumber())
            .orElseThrow(() -> new EntityNotFoundException(
                String.format("Business unit bank account with sort code '%s' and account number '%s' could not be "
                    + "located for file_name '%s'",
                    bank.getSortCode(), bank.getAccountNumber(), extractedData.getFileName())
            ));
    }

    void validateInputs(InterfaceFileEntity sourceInterfaceFile, InputStream fileContents) {
        if (sourceInterfaceFile == null) {
            throw new IllegalArgumentException("Source interface file is required");
        }
        if (sourceInterfaceFile.getFileName() == null || sourceInterfaceFile.getFileName().isBlank()) {
            throw new IllegalArgumentException("Source interface file name is required");
        }
        if (fileContents == null) {
            throw new IllegalArgumentException("BACS Standard 18 file contents are required");
        }
    }

    void validateHeaders(List<String> lines) {
        Set<String> presentHeaders = lines.stream()
            .filter(line -> line.length() >= 4)
            .map(line -> line.substring(0, 4))
            .filter(REQUIRED_HEADER_PREFIXES::contains)
            .collect(java.util.stream.Collectors.toSet());

        if (!presentHeaders.containsAll(REQUIRED_HEADER_PREFIXES)) {
            throw new IllegalArgumentException("BACS Standard 18 file did not contain the expected headers");
        }
    }

    boolean isControlRow(String line) {
        return line.length() >= 4 && CONTROL_ROW_PREFIXES.contains(line.substring(0, 4));
    }

    boolean isTotalRow(String line) {
        return Transaction.isTotalCode(line.substring(15, 17));
    }

    ParsedTransaction parseTransaction(String line) {
        validateTransactionLength(line);
        String destinationSortCode = numericIdentifier(line, 0, 6, "destination sort code");
        String destinationAccountNumber = numericIdentifier(line, 6, 14, "destination account number");
        String accountType = numericIdentifier(line, 14, 15, "destination account type");
        String transactionCode = numericIdentifier(line, 15, 17, "transaction code");
        String originatorSortCode = numericIdentifier(line, 17, 23, "originator sort code");
        String originatorAccountNumber = numericIdentifier(line, 23, 31, "originator account number");
        long amount = parseAmount(paddedNumericText(line, 35, 46, "amount"));
        String originatorName = text(line, 46, 64);
        String accountReference = requiredText(line, 64, 82, "originator account reference");
        String destinationName = text(line, 82, 100);

        if (!"0".equals(accountType)) {
            throw new IllegalArgumentException(
                "BACS Standard 18 destination account type was " + accountType + " but expected 0"
            );
        }

        Transaction transaction = Transaction.builder()
            .transactionCode(transactionCode)
            .originatorDetails(OriginatorDetails.builder()
                .name(originatorName)
                .accountReference(accountReference)
                .bankDetails(BankDetails.builder()
                    .sortCode(originatorSortCode)
                    .accountNumber(originatorAccountNumber)
                    .build())
                .build())
            .amount(amount)
            .dateEntryApplied(line.length() == 106 ? parseDateEntryApplied(line.substring(100, 106)) : null)
            .build();

        BankDetails destinationBankDetails = BankDetails.builder()
            .sortCode(destinationSortCode)
            .accountNumber(destinationAccountNumber)
            .name(destinationName)
            .type(accountType)
            .build();

        return new ParsedTransaction(transaction, destinationBankDetails);
    }

    void validateTransactionLength(String line) {
        if (line.length() != 100 && line.length() != 106) {
            throw new IllegalArgumentException(
                "BACS Standard 18 transaction line was " + line.length() + " characters but expected 100 or 106"
            );
        }
    }

    String numericIdentifier(String line, int startInclusive, int endExclusive, String fieldName) {
        String value = line.substring(startInclusive, endExclusive);
        if (value.isBlank() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                "BACS Standard 18 " + fieldName + " at index " + startInclusive + " to " + endExclusive
                    + " must be numeric"
            );
        }
        return value;
    }

    String paddedNumericText(String line, int startInclusive, int endExclusive, String fieldName) {
        String value = text(line, startInclusive, endExclusive);
        if (value.isBlank() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                "BACS Standard 18 " + fieldName + " at index " + startInclusive + " to " + endExclusive
                    + " must be numeric"
            );
        }
        return value;
    }

    String requiredText(String line, int startInclusive, int endExclusive, String fieldName) {
        String value = text(line, startInclusive, endExclusive);
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                "BACS Standard 18 " + fieldName + " is required at index " + startInclusive + " to " + endExclusive
            );
        }
        return value;
    }

    String text(String line, int startInclusive, int endExclusive) {
        return line.substring(startInclusive, endExclusive).trim();
    }

    long parseAmount(String amount) {
        try {
            return Long.parseLong(amount);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("BACS Standard 18 amount is too large", ex);
        }
    }

    String parseDateEntryApplied(String rawDate) {
        if (rawDate.length() != 6 || rawDate.charAt(0) != ' ') {
            throw new IllegalArgumentException("BACS Standard 18 transaction date must be blank followed by YYDDD");
        }
        String yearAndDay = rawDate.substring(1);
        if (!yearAndDay.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("BACS Standard 18 transaction date ordinal must be numeric");
        }
        int year = 2000 + Integer.parseInt(yearAndDay.substring(0, 2));
        int dayOfYear = Integer.parseInt(yearAndDay.substring(2));
        try {
            return LocalDate.ofYearDay(year, dayOfYear).format(OUTPUT_DATE_FORMATTER);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("BACS Standard 18 transaction date ordinal is invalid", ex);
        }
    }

    PaymentType paymentTypeFor(String transactionCode) {
        return "11".equals(transactionCode)
            ? PaymentType.CHEQUE
            : PaymentType.CASH;
    }

    void applyAllpayDdSourceUpdate(InterfaceFileEntity sourceInterfaceFile, Transaction firstTransaction) {
        String firstOriginatorName = firstTransaction.getOriginatorDetails().getName();
        if (sourceInterfaceFile.getSource() == Interface.ALLPAY && "ALLPAY DD".equals(firstOriginatorName)) {
            sourceInterfaceFile.setSource(Interface.ALLPAY_DD);
            interfaceFilesRepository.save(sourceInterfaceFile);
        }
    }

    void validateConsistentDestinationDetails(
        List<ParsedTransaction> transactions,
        ParsedTransaction expected
    ) {
        BankDetails expectedDestination = expected.destinationBankDetails();
        String expectedTransactionCode = expected.transaction().getTransactionCode();
        boolean allMatch = transactions.stream()
            .allMatch(transaction -> hasExpectedDestinationDetails(transaction, expectedDestination)
                && expectedTransactionCode.equals(transaction.transaction().getTransactionCode()));

        if (!allMatch) {
            throw new IllegalArgumentException(
                "BACS Standard 18 transaction rows contained mixed destination bank details or mixed transaction codes"
            );
        }
    }

    boolean hasExpectedDestinationDetails(ParsedTransaction transaction, BankDetails expected) {
        BankDetails destination = transaction.destinationBankDetails();
        return expected.getSortCode().equals(destination.getSortCode())
            && expected.getAccountNumber().equals(destination.getAccountNumber());
    }

    record ParsedTransaction(Transaction transaction, BankDetails destinationBankDetails) {

    }
}
