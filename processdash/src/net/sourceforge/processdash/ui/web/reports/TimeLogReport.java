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


package net.sourceforge.processdash.ui.web.reports;


import java.io.IOException;
import java.util.Enumeration;

import net.sourceforge.processdash.hier.*;
import net.sourceforge.processdash.i18n.Resources;
import net.sourceforge.processdash.log.*;
import net.sourceforge.processdash.ui.web.*;
import net.sourceforge.processdash.util.FormatUtil;
import net.sourceforge.processdash.util.StringUtils;


public class TimeLogReport extends TinyCGIBase {

    private static final Resources resources =
        Resources.getDashBundle("Time.Report");

    private static final String START_TEXT =
        "<HTML><HEAD><TITLE>${Title}%for owner%%for path%</TITLE>%css%\n" +
        "<STYLE>\n" +
        "    TABLE { empty-cells: show }\n" +
        "    .header { font-weight: bold }\n" +
        "    TD { vertical-align: baseline }\n" +
        "</STYLE></HEAD>\n" +
        "<BODY><H1>${Title}%for path%</H1>\n" +
        "<TABLE BORDER><TR class=header>\n" +
        "<TD>${Project__Task}</TD>\n" +
        "<TD>${Phase}</TD>\n" +
        "<TD>${Start_Time}</TD>\n" +
        "<TD>${Elapsed}</TD>\n" +
        "<TD>${Interrupt}</TD>\n" +
        "<TD>${Comment}</TD></TR>\n";

    private static final String DISCLAIMER =
        "<P class=doNotPrint><A HREF=\"excel.iqy\"><I>" +
        "${Export_to_Excel}</I></A></P>"+
        "<P class=doNotPrint><I>${Caveat}</I></P>";


    /** Generate CGI script output. */
    protected void writeContents() throws IOException {

        String path = getPrefix();
        String title = For(path);
        String owner = For(getOwner());

        String header = START_TEXT;
        header = resources.interpolate(header, true);
        header = StringUtils.findAndReplace(header, "%for owner%", owner);
        header = StringUtils.findAndReplace(header, "%for path%", title);
        header = StringUtils.findAndReplace(header, "%css%", cssLinkHTML());
        out.print(header);

        TimeLog tl = new TimeLog();
        tl.readDefault();

        DashHierarchy props = getPSPProperties();
        Enumeration rows = tl.filter(props.findExistingKey(path), null, null);
        TimeLogEntry tle;
        String entryPath, phase;
        int slashPos;
        while (rows.hasMoreElements()) {
            tle = (TimeLogEntry) rows.nextElement();
            entryPath = tle.getPath();
            slashPos = entryPath.lastIndexOf("/");
            phase = entryPath.substring(slashPos+1);
            entryPath = entryPath.substring(0, slashPos);

            out.println("<TR>");
            out.println("<TD NOWRAP>" + entryPath + "</TD>");
            out.println("<TD>" + phase + "</TD>");
            out.println("<TD>" +
                        FormatUtil.formatDateTime(tle.getStartTime()) +
                        "</TD>");
            out.println("<TD>" + tle.getElapsedTime() + "</TD>");
            out.println("<TD>" + tle.getInterruptTime() + "</TD>");
            String comment = tle.getComment();
            out.println("<TD>" + (comment == null ? "" : comment) + "</TD>");
            out.println("</TR>");
        }
        out.println("</TABLE>");

        if (parameters.get("skipFooter") == null)
            out.print(resources.interpolate(DISCLAIMER, true));

        out.println("</BODY></HTML>");
    }

    private String For(String phrase) {
        if (phrase != null && phrase.length() > 1)
            return resources.format("For_FMT", phrase);
        else
            return "";
    }
}
