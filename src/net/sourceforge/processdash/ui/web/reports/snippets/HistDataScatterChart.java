// Copyright (C) 2006 Tuma Solutions, LLC
// Process Dashboard - Data Automation Tool for high-maturity processes
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 3
// of the License, or (at your option) any later version.
//
// Additional permissions also apply; see the README-license.txt
// file in the project root directory for more information.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, see <http://www.gnu.org/licenses/>.
//
// The author(s) may be contacted at:
//     processdash@tuma-solutions.com
//     processdash-devel@lists.sourceforge.net

package net.sourceforge.processdash.ui.web.reports.snippets;

import java.io.IOException;

public class HistDataScatterChart extends AbstractChartSnippet {

    protected void writeContents() throws IOException {
        StringBuffer args = getQueryParams();
        appendParam(args, "for", "[Rollup_List]");
        copyParam(args, "ShowTrend", "trend");

        String query = args.toString();
        if (query.indexOf("&d2") == -1) {
            out.write("<!-- at least two metrics must be specified -->\n\n");
        } else {
            writeSmallChart("xy", query);
        }
    }
}
