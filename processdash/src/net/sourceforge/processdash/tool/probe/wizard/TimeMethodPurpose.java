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

import net.sourceforge.processdash.Settings;
import net.sourceforge.processdash.process.ProcessUtil;


public class TimeMethodPurpose extends MethodPurpose {

    static final String PURPOSE_KEY = "Time";

    private ProcessUtil processUtil;
    private ProbeData histData;

    TimeMethodPurpose(ProbeData data) {
        this.processUtil = data.getProcessUtil();
        this.histData = data;
    }

    public String getKey() {
        return PURPOSE_KEY;
    }

    public String getUnits() {
        return Wizard.resources.getHTML("Hours");
    }

    public String formatBeta1(double beta1) {
        return processUtil.formatProductivity(1.0 / beta1);
    }

    public double getExpectedBeta1() {
        return 1.0 / histData.getProductivity();
    }

    public int getYColumn() {
        return ProbeData.ACT_TIME;
    }

    public String getTargetDataElement() {
        return "Estimated Time";
    }

    public int getTargetColumn() {
        return ProbeData.EST_TIME;
    }

    public String getTargetName() {
        return resources.getString("Method.Time.Label");
    }

    public int mapInputColumn(int xColumn) {
        boolean strictMethods = true;
        if ("false".equalsIgnoreCase
            (Settings.getVal("probeWizard.strictTimeMethods")))
            strictMethods = false;

        if (strictMethods)
            return ProbeData.EST_OBJ_LOC;
        else if (xColumn != ProbeData.EST_OBJ_LOC &&
                 xColumn != ProbeData.EST_NC_LOC)
            return ProbeData.EST_NC_LOC;
        else
            return xColumn;
    }

    public double getMult() {
        return 60;
    }

    public String getTargetDataElementMin() {
        return "Estimated Min Time";
    }

    public String getTargetDataElementMax() {
        return "Estimated Max Time";
    }

}
