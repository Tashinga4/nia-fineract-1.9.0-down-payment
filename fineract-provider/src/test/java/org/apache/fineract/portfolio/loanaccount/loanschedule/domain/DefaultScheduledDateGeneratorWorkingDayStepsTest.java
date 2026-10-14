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
import static org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.MONTHS;
import static org.apache.fineract.portfolio.common.domain.PeriodFrequencyType.WEEKS;
import static org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType.CUMULATIVE;
import static org.apache.fineract.portfolio.loanproduct.domain.AmortizationMethod.EQUAL_PRINCIPAL;
import static org.apache.fineract.portfolio.loanproduct.domain.InterestCalculationPeriodMethod.SAME_AS_REPAYMENT_PERIOD;
import static org.apache.fineract.portfolio.loanproduct.domain.InterestMethod.FLAT;
import static org.apache.fineract.portfolio.loanproduct.domain.LoanPreClosureInterestCalculationStrategy.NONE;
import static org.apache.fineract.portfolio.loanproduct.domain.RepaymentStartDateType.DISBURSEMENT_DATE;
import static org.apache.fineract.util.TimeZoneConstants.EUROPE_BERLIN_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.junit.context.WithTenantContext;
import org.apache.fineract.junit.context.WithTenantContextExtension;
import org.apache.fineract.junit.system.WithSystemProperty;
import org.apache.fineract.junit.system.WithSystemPropertyExtension;
import org.apache.fineract.junit.timezone.WithSystemTimeZone;
import org.apache.fineract.junit.timezone.WithSystemTimeZoneExtension;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.RescheduleType;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.workingdays.data.AdjustedDateDetailsDTO;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.service.WorkingDaysUtil;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Nia twice-weekly repayments: products repaying every 3 days, counted in working days (Monday to Saturday), must
 * alternate between fixed pairs of weekdays: Mon/Thu, Tue/Fri, Wed/Sat.
 */
@ExtendWith({ WithSystemTimeZoneExtension.class, WithTenantContextExtension.class, WithSystemPropertyExtension.class })
public class DefaultScheduledDateGeneratorWorkingDayStepsTest {

    private static final String MONDAY_TO_SATURDAY = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA";
    private static final String ALL_WEEK = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR,SA,SU";
    // Monday 12 October 2026
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final Map<DayOfWeek, DayOfWeek> PARTNER = Map.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.TUESDAY,
            DayOfWeek.FRIDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY, DayOfWeek.THURSDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY,
            DayOfWeek.TUESDAY, DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY);

    private final DefaultScheduledDateGenerator underTest = new DefaultScheduledDateGenerator();

    @BeforeEach
    public void setUp() {
        ConfigurationDomainService cds = Mockito.mock(ConfigurationDomainService.class);
        given(cds.getRoundingMode()).willReturn(6); // default

        MoneyHelper moneyHelper = new MoneyHelper();
        ReflectionTestUtils.setField(moneyHelper, "configurationDomainService", cds);
        moneyHelper.initialize();
    }

    @ParameterizedTest
    @EnumSource(value = DayOfWeek.class, names = { "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY" })
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void everyDisbursementDayAlternatesBetweenItsPair(DayOfWeek disbursementDay) {
        LocalDate disbursement = MONDAY.plusDays(disbursementDay.getValue() - 1L);
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, true, List.of());

        List<LocalDate> dueDates = schedule(terms(DAYS, 3, 8, disbursement, null, holidayDetailDTO), holidayDetailDTO, disbursement, 8);

        assertThat(dueDates).hasSize(8);
        DayOfWeek expected = PARTNER.get(disbursementDay);
        LocalDate previous = disbursement;
        for (LocalDate dueDate : dueDates) {
            assertThat(dueDate.getDayOfWeek()).as("due date %s", dueDate).isEqualTo(expected);
            assertThat(ChronoUnit.DAYS.between(previous, dueDate)).as("gap before %s", dueDate).isIn(3L, 4L);
            expected = PARTNER.get(expected);
            previous = dueDate;
        }
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void thursdayDisbursementRepaysMondayThenThursday() {
        LocalDate thursday = LocalDate.of(2026, 10, 15);
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, true, List.of());

        List<LocalDate> dueDates = schedule(terms(DAYS, 3, 8, thursday, null, holidayDetailDTO), holidayDetailDTO, thursday, 8);

        assertThat(dueDates).containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 12));
    }

    @ParameterizedTest
    @EnumSource(value = DayOfWeek.class, names = { "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY" })
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void applicationScheduleEqualsScheduleRegeneratedAfterDisbursement(DayOfWeek disbursementDay) {
        // at application the first repayment date is fixed up front (calculatedRepaymentsStartingFromDate)
        LocalDate disbursement = MONDAY.plusDays(disbursementDay.getValue() - 1L);
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, true, List.of());
        LocalDate firstRepayment = WorkingDaysUtil.plusWorkingDays(disbursement, 3, holidayDetailDTO.getWorkingDays());

        List<LocalDate> atApplication = schedule(terms(DAYS, 3, 8, disbursement, firstRepayment, holidayDetailDTO), holidayDetailDTO,
                disbursement, 8);
        List<LocalDate> afterDisbursement = schedule(terms(DAYS, 3, 8, disbursement, null, holidayDetailDTO), holidayDetailDTO,
                disbursement, 8);

        assertThat(atApplication).isEqualTo(afterDisbursement);
        assertThat(atApplication).noneMatch(date -> date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void settingOffKeepsStockBehaviour() {
        // stock 1.10.1: only a date landing on Sunday moves, the next date is still counted from the Sunday
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, false, List.of());

        List<LocalDate> dueDates = schedule(terms(DAYS, 3, 8, MONDAY, null, holidayDetailDTO), holidayDetailDTO, MONDAY, 8);

        assertThat(dueDates).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 5));
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void sevenDayWorkingWeekKeepsPlainDays() {
        HolidayDetailDTO holidayDetailDTO = holidayDTO(ALL_WEEK, true, List.of());

        List<LocalDate> dueDates = schedule(terms(DAYS, 3, 4, MONDAY, null, holidayDetailDTO), holidayDetailDTO, MONDAY, 4);

        assertThat(dueDates).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 10, 24));
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void weeklyProductsAreNotAffected() {
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, true, List.of());

        List<LocalDate> dueDates = schedule(terms(WEEKS, 1, 4, MONDAY, null, holidayDetailDTO), holidayDetailDTO, MONDAY, 4);

        assertThat(dueDates).containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 9));
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void holidayRescheduledToNextRepaymentDateKeepsThePair() {
        Holiday thursdayHoliday = holiday(LocalDate.of(2026, 10, 22), RescheduleType.RESCHEDULETONEXTREPAYMENTDATE, null);
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, true, List.of(thursdayHoliday));

        List<LocalDate> dueDates = schedule(terms(DAYS, 3, 8, MONDAY, null, holidayDetailDTO), holidayDetailDTO, MONDAY, 8);

        // the Thursday 22 Oct payment moves to Monday 26 Oct and the Mon/Thu pattern continues
        assertThat(dueDates).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26),
                LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 12));
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void holidayRescheduledToSpecificDateMovesOnlyThatInstallment() {
        Holiday thursdayHoliday = holiday(LocalDate.of(2026, 10, 22), RescheduleType.RESCHEDULETOSPECIFICDATE, LocalDate.of(2026, 10, 23));
        HolidayDetailDTO holidayDetailDTO = holidayDTO(MONDAY_TO_SATURDAY, true, List.of(thursdayHoliday));

        List<LocalDate> dueDates = schedule(terms(DAYS, 3, 8, MONDAY, null, holidayDetailDTO), holidayDetailDTO, MONDAY, 8);

        assertThat(dueDates).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23),
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 9));
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void idealDisbursementDateCountsBackInWorkingDays() {
        LocalDate mondayRepayment = LocalDate.of(2026, 10, 19);
        HolidayDetailDTO on = holidayDTO(MONDAY_TO_SATURDAY, true, List.of());
        HolidayDetailDTO off = holidayDTO(MONDAY_TO_SATURDAY, false, List.of());

        LocalDate withSetting = underTest.idealDisbursementDateBasedOnFirstRepaymentDate(DAYS, 3, mondayRepayment, null, on,
                terms(DAYS, 3, 8, MONDAY, null, on));
        LocalDate withoutSetting = underTest.idealDisbursementDateBasedOnFirstRepaymentDate(DAYS, 3, mondayRepayment, null, off,
                terms(DAYS, 3, 8, MONDAY, null, off));

        assertThat(withSetting).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(withoutSetting).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    @WithSystemTimeZone(EUROPE_BERLIN_ID)
    @WithTenantContext(tenantTimeZoneId = EUROPE_BERLIN_ID)
    @WithSystemProperty(key = FLOATING_TIMEZONE_PROPERTY_KEY, value = "true")
    public void workingDayHelpersSkipSunday() {
        WorkingDays mondayToSaturday = new WorkingDays(MONDAY_TO_SATURDAY, MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false);
        LocalDate saturday = LocalDate.of(2026, 10, 17);
        LocalDate sunday = LocalDate.of(2026, 10, 18);

        assertThat(WorkingDaysUtil.plusWorkingDays(saturday, 1, mondayToSaturday)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(WorkingDaysUtil.plusWorkingDays(saturday, 3, mondayToSaturday)).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(WorkingDaysUtil.plusWorkingDays(sunday, 3, mondayToSaturday)).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(WorkingDaysUtil.minusWorkingDays(LocalDate.of(2026, 10, 20), 3, mondayToSaturday)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(WorkingDaysUtil.nextWorkingDayOnOrAfter(sunday, mondayToSaturday)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(WorkingDaysUtil.nextWorkingDayOnOrAfter(saturday, mondayToSaturday)).isEqualTo(saturday);
        assertThat(WorkingDaysUtil.plusWorkingDays(saturday, 0, mondayToSaturday)).isEqualTo(saturday);
    }

    /** Same loop as AbstractCumulativeLoanScheduleGenerator.generate: next date, adjust, seed the next period. */
    private List<LocalDate> schedule(LoanApplicationTerms terms, HolidayDetailDTO holidayDetailDTO, LocalDate disbursement,
            int numberOfRepayments) {
        List<LocalDate> dueDates = new ArrayList<>();
        LocalDate actualRepaymentDate = disbursement;
        boolean isFirstRepayment = true;
        for (int period = 0; period < numberOfRepayments; period++) {
            actualRepaymentDate = underTest.generateNextRepaymentDate(actualRepaymentDate, terms, isFirstRepayment);
            AdjustedDateDetailsDTO adjusted = underTest.adjustRepaymentDate(actualRepaymentDate, terms, holidayDetailDTO);
            actualRepaymentDate = adjusted.getChangedActualRepaymentDate();
            isFirstRepayment = false;
            dueDates.add(adjusted.getChangedScheduleDate());
        }
        return dueDates;
    }

    private HolidayDetailDTO holidayDTO(String workingDaysRecurrence, boolean repaymentStepsInWorkingDays, List<Holiday> holidays) {
        WorkingDays workingDays = new WorkingDays(workingDaysRecurrence, MOVE_TO_NEXT_WORKING_DAY.getValue(), false, false);
        return new HolidayDetailDTO(!holidays.isEmpty(), holidays, workingDays, false, false, repaymentStepsInWorkingDays);
    }

    private Holiday holiday(LocalDate date, RescheduleType rescheduleType, LocalDate rescheduledTo) {
        Holiday holiday = Mockito.mock(Holiday.class);
        given(holiday.getFromDate()).willReturn(date);
        given(holiday.getToDate()).willReturn(date);
        given(holiday.getReScheduleType()).willReturn(rescheduleType);
        given(holiday.getRepaymentsRescheduledTo()).willReturn(rescheduledTo);
        return holiday;
    }

    private LoanApplicationTerms terms(PeriodFrequencyType frequency, int repaymentEvery, int numberOfRepayments, LocalDate disbursement,
            LocalDate calculatedFirstRepayment, HolidayDetailDTO holidayDetailDTO) {
        ApplicationCurrency dollarCurrency = new ApplicationCurrency("USD", "US Dollar", 2, 0, "currency.USD", "$");
        Money principalAmount = Money.of(fromApplicationCurrency(dollarCurrency), BigDecimal.valueOf(100L));
        return LoanApplicationTerms.assembleFrom(dollarCurrency, repaymentEvery * numberOfRepayments, frequency, numberOfRepayments,
                repaymentEvery, frequency, null, INVALID, EQUAL_PRINCIPAL, FLAT, ZERO, MONTHS, ZERO, SAME_AS_REPAYMENT_PERIOD, false,
                principalAmount, disbursement, null, calculatedFirstRepayment, null, null, null, null, null,
                Money.of(fromApplicationCurrency(dollarCurrency), ZERO), false, null, EMPTY_LIST, BigDecimal.valueOf(36_000L), null,
                DaysInMonthType.ACTUAL, DaysInYearType.ACTUAL, false, null, null, null, null, null, ZERO, null, NONE, null, ZERO,
                EMPTY_LIST, true, 0, false, holidayDetailDTO, false, false, false, null, false, false, null, false, DISBURSEMENT_DATE,
                disbursement, false, CUMULATIVE, LoanScheduleProcessingType.HORIZONTAL);
    }
}
