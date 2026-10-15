/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.loanschedule.domain;

import static java.math.BigDecimal.ZERO;
import static java.util.Collections.EMPTY_LIST;
import static org.apache.fineract.organisation.monetary.domain.MonetaryCurrency.fromApplicationCurrency;
import static org.apache.fineract.organisation.workingdays.domain.RepaymentRescheduleType.MOVE_TO_NEXT_WORKING_DAY;
import static org.apache.fineract.portfolio.calendar.service.CalendarUtils.FLOATING_TIMEZONE_PROPERTY_KEY;
import static org.apache.fineract.portfolio.common.domain.DayOfWeekType.INVALID;
import static org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.DAYS;
import static org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.WEEKS;
import static org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.WHOLE_TERM;
import static org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType.CUMULATIVE;
import static org.apache.fineract.portfolio.loanproduct.domain.AmortizationMethod.EQUAL_INSTALLMENTS;
import static org.apache.fineract.portfolio.loanproduct.domain.InterestCalculationPeriodMethod.SAME_AS_REPAYMENT_PERIOD;
import static org.apache.fineract.portfolio.loanproduct.domain.InterestMethod.FLAT;
import static org.apache.fineract.portfolio.loanproduct.domain.LoanPreClosureInterestCalculationStrategy.NONE;
import static org.apache.fineract.portfolio.loanproduct.domain.RepaymentStartDateType.DISBURSEMENT_DATE;
import static org.apache.fineract.util.TimeZoneConstants.EUROPE_BERLIN_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.junit.context.WithTenantContext;
import org.apache.fineract.junit.context.WithTenantContextExtension;
import org.apache.fineract.junit.system.WithSystemProperty;
import org.apache.fineract.junit.system.WithSystemPropertyExtension;
import org.apache.fineract.junit.timezone.WithSystemTimeZone;
import org.apache.fineract.junit.timezone.WithSystemTimeZoneExtension;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Nia down-payment products: a loan of 100 with a 20% down payment and 20% flat interest for the whole term must have
 * the configured number of regular repayments, sharing the remaining 80 of principal, and 20 of interest.
 */
@ExtendWith({ WithSystemTimeZoneExtension.class, WithTenantContextExtension.class, WithSystemPropertyExtension.class })
public class DownPaymentSpreadRemainingPrincipalTest {

    private static final String MONDAY_TO_SATURDAY = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA";
    // Monday 12 October 2026
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private final CumulativeFlatInterestLoanScheduleGenerator generator = new CumulativeFlatInterestLoanScheduleGenerator(
            new DefaultScheduledDateGenerator(), new DefaultPaymentPeriodsInOneYearCalculator());

    @BeforeEach
    public void setUp() {
        ConfigurationDomainService cds = Mockito.mock(ConfigurationDomainService.class);
        given(cds.getRoundingMode()).willReturn(6); // default

        MoneyHelper moneyHelper = new MoneyHelper();
        ReflectionTestUtils.setField(moneyHelper, "configurationDomainService", cds);
        moneyHelper.initialize();
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, MONDAY)));
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void settingOffKeepsTheShortStockSchedule() {
        // 2H8W today: installments are sized for 100, so the 80 left runs out after 6 of the 8 repayments
        Schedule schedule = generate(WEEKS, 1, 8, BigDecimal.valueOf(20), 1, false, false);

        assertThat(schedule.downPayment()).isEqualByComparingTo("20");
        assertThat(schedule.regular()).hasSize(6);
        assertThat(schedule.totalInterest()).isEqualByComparingTo("15");
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void settingOnGivesAllConfiguredRepayments() {
        Schedule schedule = generate(WEEKS, 1, 8, BigDecimal.valueOf(20), 1, true, false);

        assertThat(schedule.downPayment()).isEqualByComparingTo("20");
        assertThat(schedule.regular()).hasSize(8);
        assertThat(schedule.regularPrincipal()).isEqualByComparingTo("80");
        assertThat(schedule.totalInterest()).isEqualByComparingTo("20");
        // every repayment is a whole amount (installments in multiples of 1), only the last one takes the remainder
        for (LoanScheduleModelPeriod period : schedule.regular().subList(0, 7)) {
            assertThat(period.principalDue().add(period.interestDue()).stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
        }
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void withoutRoundingThePrincipalIsSharedEqually() {
        Schedule schedule = generate(WEEKS, 1, 8, BigDecimal.valueOf(20), null, true, false);

        assertThat(schedule.regular()).hasSize(8);
        for (LoanScheduleModelPeriod period : schedule.regular()) {
            assertThat(period.principalDue()).isEqualByComparingTo("10");
            assertThat(period.interestDue()).isEqualByComparingTo("2.5");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 4, 6, 8, 12, 16 })
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void everyNiaRepaymentCountIsKept(int numberOfRepayments) {
        Schedule schedule = generate(WEEKS, 1, numberOfRepayments, BigDecimal.valueOf(20), 1, true, false);

        assertThat(schedule.regular()).hasSize(numberOfRepayments);
        assertThat(schedule.regularPrincipal()).isEqualByComparingTo("80");
        assertThat(schedule.totalInterest()).isEqualByComparingTo("20");
    }

    @ParameterizedTest
    @CsvSource({ "37, 4", "37, 16", "55, 12", "100, 12", "130, 6", "150, 16", "250, 12", "333, 8", "500, 16", "1000, 12", "1234, 6",
            "2500, 16" })
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void anyLoanAmountKeepsTheRepaymentCount(BigDecimal principal, int numberOfRepayments) {
        Schedule schedule = generate(principal, WEEKS, 1, numberOfRepayments, BigDecimal.valueOf(20), 1, true, false);

        assertThat(schedule.regular()).hasSize(numberOfRepayments);
        assertThat(schedule.regularPrincipal().add(schedule.downPayment())).isEqualByComparingTo(principal);
        // 20% flat for the whole term on the full amount, up to the cent rounding of each installment's interest
        BigDecimal expectedInterest = principal.multiply(BigDecimal.valueOf(0.2));
        assertThat(schedule.totalInterest().subtract(expectedInterest).abs())
                .isLessThanOrEqualTo(BigDecimal.valueOf(0.01).multiply(BigDecimal.valueOf(numberOfRepayments)));
        // all repayments but the last are whole amounts
        for (LoanScheduleModelPeriod period : schedule.regular().subList(0, numberOfRepayments - 1)) {
            assertThat(period.principalDue().add(period.interestDue()).stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
        }
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void twiceWeeklyDownPaymentProductKeepsItsRepaymentsAndPairs() {
        // 2HB4 with both Nia settings: every 3 working days, 8 repayments, 20% down payment
        Schedule schedule = generate(DAYS, 3, 8, BigDecimal.valueOf(20), 1, true, true);

        assertThat(schedule.regular()).hasSize(8);
        assertThat(schedule.regularPrincipal()).isEqualByComparingTo("80");
        assertThat(schedule.totalInterest()).isEqualByComparingTo("20");
        // Monday disbursement: Thu, Mon, Thu, Mon...
        for (int i = 0; i < 8; i++) {
            assertThat(schedule.regular().get(i).periodDueDate().getDayOfWeek().getValue()).isEqualTo(i % 2 == 0 ? 4 : 1);
        }
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void productsWithoutDownPaymentAreUnchanged() {
        Schedule off = generate(WEEKS, 1, 6, null, 1, false, false);
        Schedule on = generate(WEEKS, 1, 6, null, 1, true, false);

        assertThat(on.downPayment()).isEqualByComparingTo("0");
        assertThat(on.regular()).hasSize(6);
        assertThat(on.totalInterest()).isEqualByComparingTo("20");
        for (int i = 0; i < 6; i++) {
            assertThat(on.regular().get(i).principalDue()).isEqualByComparingTo(off.regular().get(i).principalDue());
            assertThat(on.regular().get(i).interestDue()).isEqualByComparingTo(off.regular().get(i).interestDue());
            assertThat(on.regular().get(i).periodDueDate()).isEqualTo(off.regular().get(i).periodDueDate());
        }
    }

    private record Schedule(List<LoanScheduleModelPeriod> regular, BigDecimal downPayment, BigDecimal totalInterest) {

        BigDecimal regularPrincipal() {
            return regular.stream().map(LoanScheduleModelPeriod::principalDue).reduce(ZERO, BigDecimal::add);
        }
    }

    private Schedule generate(PeriodFrequencyType frequency, int repaymentEvery, int numberOfRepayments, BigDecimal downPaymentPercentage,
            Integer inMultiplesOf, boolean spreadRemainingPrincipal, boolean stepsInWorkingDays) {
        return generate(BigDecimal.valueOf(100L), frequency, repaymentEvery, numberOfRepayments, downPaymentPercentage, inMultiplesOf,
                spreadRemainingPrincipal, stepsInWorkingDays);
    }

    private Schedule generate(BigDecimal principal, PeriodFrequencyType frequency, int repaymentEvery, int numberOfRepayments,
            BigDecimal downPaymentPercentage, Integer inMultiplesOf, boolean spreadRemainingPrincipal, boolean stepsInWorkingDays) {
        WorkingDays workingDays = new WorkingDays(MONDAY_TO_SATURDAY, MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false);
        HolidayDetailDTO holidayDetailDTO = new HolidayDetailDTO(false, EMPTY_LIST, workingDays, false, false, stepsInWorkingDays,
                spreadRemainingPrincipal);
        LoanApplicationTerms terms = terms(principal, frequency, repaymentEvery, numberOfRepayments, downPaymentPercentage, inMultiplesOf,
                holidayDetailDTO);
        MathContext mc = MoneyHelper.getMathContext();
        LoanScheduleModel model = generator.generate(mc, terms, new HashSet<>(), holidayDetailDTO);

        List<LoanScheduleModelPeriod> regular = model.getPeriods().stream().filter(p -> p.isRepaymentPeriod() && !p.isDownPaymentPeriod())
                .toList();
        BigDecimal downPayment = model.getPeriods().stream().filter(LoanScheduleModelPeriod::isDownPaymentPeriod)
                .map(LoanScheduleModelPeriod::principalDue).reduce(ZERO, BigDecimal::add);
        BigDecimal totalInterest = regular.stream().map(LoanScheduleModelPeriod::interestDue).reduce(ZERO, BigDecimal::add);
        return new Schedule(regular, downPayment, totalInterest);
    }

    private LoanApplicationTerms terms(BigDecimal principal, PeriodFrequencyType frequency, int repaymentEvery, int numberOfRepayments,
            BigDecimal downPaymentPercentage, Integer inMultiplesOf, HolidayDetailDTO holidayDetailDTO) {
        ApplicationCurrency dollarCurrency = new ApplicationCurrency("USD", "US Dollar", 2, 0, "currency.USD", "$");
        Money principalAmount = Money.of(fromApplicationCurrency(dollarCurrency), principal);
        BigDecimal wholeTermRate = BigDecimal.valueOf(20);
        BigDecimal annualNominalInterestRate = new AprCalculator().calculateFrom(WHOLE_TERM, wholeTermRate, numberOfRepayments,
                repaymentEvery, frequency);
        boolean downPayment = downPaymentPercentage != null;
        return LoanApplicationTerms.assembleFrom(dollarCurrency, repaymentEvery * numberOfRepayments, frequency, numberOfRepayments,
                repaymentEvery, frequency, null, INVALID, EQUAL_INSTALLMENTS, FLAT, wholeTermRate, WHOLE_TERM, annualNominalInterestRate,
                SAME_AS_REPAYMENT_PERIOD, false, principalAmount, MONDAY, null, null, null, null, null, null, null,
                Money.of(fromApplicationCurrency(dollarCurrency), ZERO), false, null, EMPTY_LIST, BigDecimal.valueOf(36_000L), null,
                DaysInMonthType.ACTUAL, DaysInYearType.ACTUAL, false, null, null, null, null, null, ZERO, inMultiplesOf, NONE, null, ZERO,
                EMPTY_LIST, true, 0, false, holidayDetailDTO, false, false, false, null, false, downPayment, downPaymentPercentage,
                downPayment, DISBURSEMENT_DATE, MONDAY, false, CUMULATIVE, LoanScheduleProcessingType.HORIZONTAL);
    }
}
