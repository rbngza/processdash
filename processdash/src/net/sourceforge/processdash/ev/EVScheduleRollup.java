// Process Dashboard - Data Automation Tool for high-maturity processes
// Copyright (C) 2003 Software Process Dashboard Initiative
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
// The author(s) may be contacted at:
// Process Dashboard Group
// c/o Ken Raisor
// 6137 Wardleigh Road
// Hill AFB, UT 84056-5843
//
// E-Mail POC:  processdash-devel@lists.sourceforge.net

package net.sourceforge.processdash.ev;

import java.util.*;

import org.jfree.data.XYDataset;

/** This class represents a schedule that has been rolled up from
 *  several subschedules. (Note: at this time it only supports the
 *  rollup of plain schedules - rollup of rollup schedules is
 * forbidden.)
 */
public class EVScheduleRollup extends EVSchedule {

    Vector subSchedules = new Vector();
    double totalPlanTime;
    boolean calcHypotheticalDates = true;

    /** Create an EVScheduleRollup.
     *
     * @param evtasklists a collection of tasklists whose schedules
     *     should be included in this EVScheduleRollup.
     */
    public EVScheduleRollup(Vector evtasklists) {
        super();
        metrics = new EVMetricsRollup();

        Iterator i = evtasklists.iterator();
        while (i.hasNext())
            addSchedule((EVTaskList) i.next());
    }


    /** Clone an existing EVScheduleRollup.
     */
    public EVScheduleRollup(EVScheduleRollup r, boolean supportFormatting) {
        super();
        metrics = new EVMetricsRollup(supportFormatting);

        Iterator i = r.subSchedules.iterator();
        while (i.hasNext())
            subSchedules.add(((EVSchedule) i.next()).copy());
    }


    /** Create a rollup of several random schedules.
     */
    public EVScheduleRollup(EVScheduleRandom[] list) {
        super();
        metrics = new EVMetricsRollup();

        for (int i = 0;   i < list.length;   i++)
            addSchedule(list[i]);
    }


    /** Add an additional subschedule to this EVScheduleRollup.
     */
    public synchronized void addSchedule(EVTaskList taskList) {
        EVSchedule schedule = taskList.getSchedule();
        addSchedule(schedule);
        String errorQualifier = "[" + taskList.getRootName() + "] ";
        schedule.getMetrics().setErrorQualifier(errorQualifier);
    }
    private synchronized void addSchedule(EVSchedule schedule) {
        subSchedules.add(schedule);
    }


    /** Remove a subschedule from this EVScheduleRollup.
     */
    public synchronized void removeSchedule(EVSchedule schedule) {
        subSchedules.remove(schedule);
    }

    /** Replace a child schedule of this EVScheduleRollup.
     * WARNING: no checks are performed on the parameters. This method
     * is <b>only</b> meant to be called when recalculations on a
     * child caused a replacement object to be created (rather than
     * just mutations within the existing object).  This method should
     * <b>NOT</b> be used to replace one schedule with an entirely
     * different schedule - use removeSchedule() and addSchedule() for that.
     */
    public synchronized void replaceSchedule(int pos, EVTaskList taskList) {
        EVSchedule schedule = taskList.getSchedule();
        if (schedule == subSchedules.get(pos)) return;

        subSchedules.set(pos, schedule);
        String errorQualifier = "[" + taskList.getRootName() + "] ";
        schedule.getMetrics().setErrorQualifier(errorQualifier);
    }


    /** Recalculate this EVScheduleRollup from its subschedules.
     *
     * <b>Important:</b> all the subschedules should already be
     *      recalculated.
     */
    public synchronized void recalc() {
        cleanUp();
        recalcTotalPlanTime();
        recreatePeriods();
        ((EVMetricsRollup) metrics).reset(getEffectiveDate());

        // add data from all the subschedules to *this* schedule.
        Iterator i = subSchedules.iterator();
        while (i.hasNext())
            addScheduleData((EVSchedule) i.next());

        // recalculate cumulative values for *this* schedule.
        calculateCumValues();

        // fire events to indicate that our contents have changed.
        getMetrics().recalcComplete(this);
        firePreparedEvents();
    }


    /** recalculate total plan time.
     *
     * total plan time for this schedule is equal to the sum of the plan
     * times for all the subschedules.
     */
    protected synchronized void recalcTotalPlanTime() {
        totalPlanTime = 0;
        Iterator i = subSchedules.iterator();
        while (i.hasNext())
            totalPlanTime += ((EVSchedule) i.next()).getMetrics().totalPlan();
    }


    /** Discard the current <code>periods</code> list and recreate it.
     *
     * A list of periods (with zeros for all period data) is created;
     * the period boundaries (start and end dates) for this new list
     * will be the "set union" of all the period boundaries of the
     * subschedules.
     */
    private synchronized void recreatePeriods() {
        // collect a sorted list of all the distinct period start/stop
        // times in all the schedules
        TreeSet periodBoundaries = new TreeSet();
        Iterator i = subSchedules.iterator();
        while (i.hasNext())
            addBoundaries(periodBoundaries, (EVSchedule) i.next());

        // create our list of periods based upon the distinct period
        // boundaries collected in the previous step
        periods.clear();
        if (periodBoundaries.isEmpty()) {
            long now = System.currentTimeMillis();
            periods.add(new Period(new Date(now), 0.0));
            periods.add(new Period(new Date(now + WEEK_MILLIS), 20.0));
            setEffectiveDate(new Date(now));
        } else {
            i = periodBoundaries.iterator();
            while (i.hasNext())
                add(new Period((Date) i.next(), 0.0));
            recalcEffectiveDate();
        }

    }
    private void addBoundaries(Set periodBoundaries, EVSchedule schedule) {
        Iterator i = schedule.periods.iterator();
        while (i.hasNext())
            periodBoundaries.add(((Period) i.next()).endDate);
    }


    /** recalculate the effective date of this schedule.
     *
     * The effective date of a rollup schedule is the chronologically latest
     * of the effective dates of the subschedules. (This is based upon the
     * assumption that the subschedules are being kept up to date, and if they
     * had any additional information they would have posted it..therefore they
     * must not have any additional info.)
     */
    protected synchronized void recalcEffectiveDate() {
        Date effectiveDate = null;

        Iterator i = subSchedules.iterator();
        while (i.hasNext())
            effectiveDate = maxDate(effectiveDate,
                                    ((EVSchedule)i.next()).getEffectiveDate());
        if (effectiveDate == null)
            effectiveDate = new Date();
        setEffectiveDate(effectiveDate);
    }


    /** Sum period data from the given schedule into this schedule.
     */
    protected void addScheduleData(EVSchedule schedule) {
        schedule.calcIndividualValues();

        Iterator i = schedule.periods.iterator();
        while (i.hasNext())
            addPeriodData((Period) i.next());

        // add data in the EVMetrics objects.
        ((EVMetricsRollup) metrics).addMetrics(schedule.getMetrics());
    }

    /** Sum data from the given period into this schedule.
     *
     * @param src the period whose data should be added
     */
    private void addPeriodData(Period src) {
        Period dest;
        long srcStart, srcEnd, srcLength;
        long overlapStart, overlapEnd, overlapLength;
        long destStart, destEnd;
        srcStart  = src.getBeginDate().getTime();
        srcEnd    = src.endDate.getTime();
        srcLength = srcEnd - srcStart;
        destEnd = A_LONG_TIME_AGO.getTime();
        Iterator i = periods.iterator();
        while (i.hasNext()) {
            dest = (Period) i.next();
            destStart = destEnd;
            destEnd = dest.endDate.getTime();

            if (destEnd < srcStart) continue;

            overlapStart  = Math.max(srcStart, destStart);
            overlapEnd    = Math.min(srcEnd,   destEnd);
            overlapLength = overlapEnd - overlapStart;

            if (overlapLength > 0)
                addPeriodData(src, dest,
                              (double) overlapLength / (double) srcLength);

            if (destEnd > srcEnd) break;
        }
    }

    private void addPeriodData(Period src, Period dest, double percent) {
        dest.planTotalTime      += percent * src.planTotalTime;
        dest.planDirectTime     += percent * src.planDirectTime;
        dest.planValue          += percent * src.planValue;
        dest.actualDirectTime   += percent * src.actualDirectTime;
        dest.actualIndirectTime += percent * src.actualIndirectTime;
        dest.earnedValue        += percent * src.earnedValue;
    }


    /** Recalculate the values of all the "cumulative" fields in all the
     * periods in this schedule, based upon the "per-period" fields.
     */
    private void calculateCumValues() {
        Iterator i = periods.iterator();
        double cumPlanTime = 0, cumPlanValue = 0,
            cumActualTime = 0, cumEarnedValue = 0;
        Period p;
        while (i.hasNext()) {
            p = (Period) i.next();
            p.cumPlanDirectTime   = (cumPlanTime    += p.planDirectTime);
            p.cumPlanValue        = (cumPlanValue   += p.planValue);
            p.cumActualDirectTime = (cumActualTime  += p.actualDirectTime);
            p.cumEarnedValue      = (cumEarnedValue += p.earnedValue);
        }
    }



    public Date getHypotheticalDate(double cumPlanTime, boolean useDTPI) {
        if (!calcHypotheticalDates) return null;

        // Clone ourself so we can perform a "what-if" calculation without
        // screwing up our data.
        EVScheduleRollup r = new EVScheduleRollup(this, false);
        r.calcHypotheticalDates = false;

        // grow all the subschedules so they are plenty long enough.
        Iterator i = r.subSchedules.iterator();
        EVSchedule s;
        while (i.hasNext()) {
            s = (EVSchedule) i.next();
            s.cleanUp();
            if (useDTPI)
                s.multiply(1 / s.getMetrics().directTimePerformanceIndex());
            s.getPlannedCompletionDate(cumPlanTime, cumPlanTime);
        }

        // recalculate the data in the cloned rollup schedule.
        r.recalc();
        return r.extrapolateWithinSchedule(cumPlanTime);
    }

    public static Period getSlice(EVSchedule src, Date start, Date end) {
        EVScheduleRollup r = null;
        if (src instanceof EVScheduleRollup)
            r = new EVScheduleRollup((EVScheduleRollup) src, false);
        else {
            r = new EVScheduleRollup(new Vector());
            r.addSchedule(src);
        }
        EVSchedule dud = new EVSchedule(start, end, 0);
        r.addSchedule(dud);
        r.calcHypotheticalDates = false;
        r.recalc();

        Period result = dud.get(1), p;
        for (int i = 0;   i < r.periods.size();   i++) {
            p = r.get(i);
            if (start.compareTo(p.endDate) >= 0) continue;
            if (end.compareTo(p.getBeginDate()) <= 0) break;
            r.addPeriodData(p, result, 1.0);
            result.cumPlanDirectTime   = p.cumPlanDirectTime;
            result.cumPlanValue        = p.cumPlanValue;
            result.cumActualDirectTime = p.cumActualDirectTime;
            result.cumEarnedValue      = p.cumEarnedValue;
        }
        return result;
    }


    public void saveActualTaskInfo(Date when, double planValue,
                                   double actualTime) { }
    protected synchronized boolean addHours(double requiredCumPlanTime) {
        return false; }
    protected synchronized boolean grow(boolean automatic) {
        return false; }
    public synchronized void deleteRow(int row) { }
    public synchronized void insertRow(int row) { }
    public synchronized void addRow() { }

    public synchronized void cleanUp() {
        prepForEvents();
        defaultPlanDirectTime = defaultPlanTotalTime = 0;
    }

    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) { }
    public boolean rowIsAutomatic(int row) { return false; }

    static Date maxDate(Date a, Date b) {
        if (a == null) return b;
        if (b == null) return a;
        return (a.before(b) ? b : a);
    }
    static Date minDate(Date a, Date b) {
        if (a == null) return b;
        if (b == null) return a;
        return (a.before(b) ? a : b);
    }

    private static final String OPTIMIZED_LABEL =
        resources.getString("Schedule.Optimized_Label");
    private class OptForecastChartSeries extends ForecastChartSeries {
        public String getSeriesName() { return OPTIMIZED_LABEL; }
        protected Date getForecastDate() {
            return ((EVMetricsRollup) metrics).optimizedForecastDate();
        }
        protected Date getForecastDateLPI() {
            return  ((EVMetricsRollup) metrics).optimizedForecastDateLPI();
        }
        protected Date getForecastDateUPI() {
            return  ((EVMetricsRollup) metrics).optimizedForecastDateUPI();
        }
    }

    private class RollupValueChartData extends ValueChartData {
        public RollupValueChartData() {
            super();
            ChartSeries[] s = new ChartSeries[4];
            s[0] = series[0];
            s[1] = series[1];
            s[2] = series[2];
            s[3] = optForecast = new OptForecastChartSeries();
            series = s;
        }
        OptForecastChartSeries optForecast;
        protected void recalcForecast(Double currentYVal) {
            super.recalcForecast(currentYVal);

            optForecast.currentYVal = currentYVal;
            optForecast.forecastYVal = ONE_HUNDRED;
            optForecast.recalc();
            if (numSeries == 3 && optForecast.getItemCount() != 0)
                numSeries = 4;
        }
    }
    public XYDataset getValueChartData() {
        ValueChartData result = new RollupValueChartData();
        result.recalc();
        return result;
    }


    public void recalcMetrics() {
        ((EVMetricsRollup) metrics).reset(getEffectiveDate());

        // add data in the EVMetrics objects.
        Iterator i = subSchedules.iterator();
        while (i.hasNext()) {
            EVSchedule s = (EVSchedule) i.next();
            ((EVMetricsRollup) metrics).addMetrics(s.getMetrics());
        }

        // fire events to indicate that our contents have changed.
        getMetrics().recalcComplete(this);
    }

}
