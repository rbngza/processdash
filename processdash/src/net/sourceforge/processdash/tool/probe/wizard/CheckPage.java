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

package net.sourceforge.processdash.tool.probe.wizard;

import net.sourceforge.processdash.process.ProcessUtil;
import net.sourceforge.processdash.util.FormatUtil;


public class CheckPage extends WizardPage {

    private static final int LOW_PROD = -1;
    private static final int GOOD_PROD = 0;
    private static final int HIGH_PROD = 1;
    private static final int NO_PROD = 2;


    private static final String[] CMP_RES_KEYS = {
        "Check.Productivity.Low_HTML_FMT",
        "Check.Productivity.Comparable_HTML_FMT",
        "Check.Productivity.High_HTML_FMT",
    };

    protected ProbeData histData;
    protected ProcessUtil processUtil;
    protected double size, time, estProductivity, histProductivity, histDev;
    protected int cmpFlag;

    public void writeHTMLContents() {
        calcData();
        String sizeLabel =
            histData.getResultSet().getColName(ProbeData.ACT_NC_LOC);

        writeStepTitle(resources.getString("Check.Title"));
        out.print("<p>");
        out.println(resources.getHTML("Check.Header"));
        out.print("<ul><li>");
        out.print(resources.format
            ("Check.Size_FMT", FormatUtil.formatNumber(size), sizeLabel));
        out.print("</li><li>");
        out.print(resources.format
            ("Check.Time_FMT", FormatUtil.formatNumber(time)));
        out.println("</li></ul><p>");

        out.print("<p>");
        writeProductivityStatement();
        out.println("</p>");

        String resKey = "Check.Productivity.Success_Instruction_HTML";
        out.print("<p>");
        if (cmpFlag == LOW_PROD || cmpFlag == HIGH_PROD) {
            resKey = "Check.Productivity.Reestimate_Instruction_HTML";
            setNextPage("Size");
        }
        out.print("<p>");
        out.print(resources.getString(resKey));
        out.println("</p>");
    }

    public boolean parseFormData() {
        return true;
    }

    public boolean writeReportSection() {
        calcData();
        writeSectionTitle(resources.getString("Check.Report_Title"));
        out.print("<p style='margin-left:1cm'>");
        writeProductivityStatement();
        out.println("</p>");
        return true;
    }

    protected void calcData() {
        histData = new ProbeData(data, prefix);
        size = histData.getCurrentValue(ProbeData.EST_NC_LOC);
        time = histData.getCurrentValue(ProbeData.EST_TIME);
        processUtil = histData.getProcessUtil();

        // check to see if their estimates are reasonable.
        estProductivity = size / time;
        histProductivity = histData.getProductivity();
        histDev = histData.getProdStddev();
        // handle the case where they have only one historical data
        // point, by assuming a 30% variation in productivity.
        if (histDev == 0 || Double.isInfinite(histDev)|| Double.isNaN(histDev))
            histDev = histProductivity * 0.30;
        double delta = estProductivity - histProductivity;

        if (Double.isNaN(histProductivity) ||
            Double.isInfinite(histProductivity))
            cmpFlag = NO_PROD;
        else if (estProductivity > histProductivity + histDev)
            cmpFlag = HIGH_PROD;
        else if (estProductivity < histProductivity - histDev)
            cmpFlag = LOW_PROD;
        else
            cmpFlag = GOOD_PROD;
    }

    private void writeProductivityStatement() {
        out.println(resources.format
                    ("Check.Productivity.Plain_HTML_FMT",
                     processUtil.formatProductivity(estProductivity)));
        if (cmpFlag != NO_PROD)
            out.println(resources.format(CMP_RES_KEYS[cmpFlag+1],
                        processUtil.formatProductivity(histProductivity),
                        FormatUtil.formatNumber(histDev)));
    }

}
