// PSP Dashboard - Data Automation Tool for PSP-like processes
// Copyright (C) 1999  United States Air Force
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
// OO-ALC/TISHD
// Attn: PSP Dashboard Group
// 6137 Wardleigh Road
// Hill AFB, UT 84056-5843
//
// E-Mail POC:  ken.raisor@hill.af.mil

import java.awt.Font;
import com.jrefinery.chart.*;
import com.jrefinery.chart.RadarPlot;


public class radar extends pspdash.CGIChartBase {

    /** Create a  line chart. */
    public JFreeChart createChart() {
        if (data.numCols() == 1) data = data.transpose();
        Plot plot = null;
        try {
            plot = new RadarPlot(null);
        } catch (AxisNotCompatibleException ance) { return null; }

        JFreeChart chart =
            new JFreeChart("Radar Chart", new Font("Arial", Font.BOLD, 24),
                           data.catDataSource(), plot);
        chart.setLegend(null);

        /*
        if (parameters.get("skipWedgeLabels") != null)
            plot.setDrawWedgeLabels(false);
        if (parameters.get("ellipse") != null)
            plot.setDrawCircle(false);
        String interiorSpacing = getParameter("interiorSpacing");
        if (interiorSpacing != null) try {
            plot.setInteriorSpacing(Integer.parseInt(interiorSpacing));
        } catch (NumberFormatException e) {}

        */

        return chart;
    }

}
