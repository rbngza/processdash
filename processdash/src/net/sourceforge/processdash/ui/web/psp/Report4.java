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

package net.sourceforge.processdash.ui.web.psp;


import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import net.sourceforge.processdash.i18n.Resources;
import net.sourceforge.processdash.i18n.Translator;
import net.sourceforge.processdash.log.Defect;
import net.sourceforge.processdash.log.DefectAnalyzer;
import net.sourceforge.processdash.process.DefectTypeStandard;
import net.sourceforge.processdash.ui.web.TinyCGIBase;
import net.sourceforge.processdash.util.FormatUtil;
import net.sourceforge.processdash.util.HTMLUtils;
import net.sourceforge.processdash.util.StringUtils;



public class Report4 extends TinyCGIBase implements DefectAnalyzer.Task {

    private static final Resources resources =
        Resources.getDashBundle("Defects.R4");
    private static final String TOTAL = resources.getString("Total");

    // In the following map, keys are defect types (e.g. "Syntax") and
    // values are arrays of integers.
    protected Map defectCounts;
    protected int[] totals;
    protected boolean strict = true;

    public static final int INJ_DESIGN  = 0;
    public static final int INJ_CODE    = 1;
    public static final int REM_COMPILE = 2;
    public static final int REM_TEST    = 3;
    public static final int PRESENT_AT_COMP_ENTRY = 4;
    public static final int FOUND_IN_COMPILE = 5;


    private static final String [] FILTERS = {
        "inj=Design", "inj=Code", "rem=Compile", "rem=Test" };

    private static final String HEADER_TEXT =
        "<HTML><HEAD><TITLE>${Title}</TITLE>%css%\n" +
        "<STYLE>\n" +
        "    TABLE { empty-cells: show }\n" +
        "    TD { text-align:center; vertical-align: baseline }\n" +
        "    .header { font-weight: bold; vertical-align:bottom }\n" +
        "    .footnote { font-size: small; font-style:italic }\n" +
        "    @media print { .doNotPrint { display: none } }\n" +
        "</STYLE></HEAD>\n" +
        "<BODY><H1>%path%</H1><H2>${Title}</H2>";

    private static final String D23_HEADER =
        "<H3>${D23.Title}</H3>\r\n" +
        "<TABLE NAME=D23 BORDER><TR class=header><TD></TD>\r\n" +
        "<TD colspan=2>${D23.Number_Injected}</TD>\r\n" +
        "<TD colspan=2>${D23.Percentage_Injected}</TD>\r\n" +
        "<TD colspan=2>${D23.Number_Removed}</TD>\r\n" +
        "<TD colspan=2>${D23.Percentage_Removed}</TD></TR>\r\n" +

        "<TR><TD>${D23.Type}</TD>\r\n" +
        "<TD>${Design}</TD><TD>${Code}</TD>\r\n" +
        "<TD>${Design}</TD><TD>${Code}</TD>\r\n" +
        "<TD>${Compile}</TD><TD>${Test}</TD>\r\n" +
        "<TD>${Compile}</TD><TD>${Test}</TD></TR>\r\n";

    private static final String D24_HEADER =
        "<H3>${D24.Title}</H3>\r\n" +
        "<TABLE NAME=D24 BORDER>\r\n" +
        "<TR class=header><TD>${D24.Defect_Type}</TD>\r\n" +
        "<TD VALIGN=bottom>${D24.Compile_Entry}</TD>" +
        "<TD VALIGN=bottom>${D24.Compile_Found}</TD>" +
        "<TD VALIGN=bottom>${D24.Compile_Percent}</TD></TR>\n";

    /** Generate CGI script output. */
    protected void writeContents() {

        String path = getPrefix();
        strict = (parameters.get("strict") != null);

        initValues();
        DefectAnalyzer.run(getPSPProperties(), getDataRepository(),
                           path, parameters, this);

        String header = resources.interpolate(HEADER_TEXT, true);
        header = StringUtils.findAndReplace(header, "%css%", cssLinkHTML());
        header = StringUtils.findAndReplace(header, "%path%", path);
        out.println(header);

        out.print
            (Translator.translate(resources.interpolate(D23_HEADER, true)));

        String defectLogParam = (String) env.get("QUERY_STRING");
        if (defectLogParam == null)
            defectLogParam = "";
        else
            defectLogParam = StringUtils.findAndReplace
                (defectLogParam, "qf=../", "qf=");

        Iterator defectTypes = defectCounts.keySet().iterator();
        String defectType;
        int [] row;
        while (defectTypes.hasNext()) {
            defectType = (String) defectTypes.next();
            row = (int[]) defectCounts.get(defectType);
            printD23(defectLogParam, defectType, row);
        }
        printD23(defectLogParam, TOTAL, totals);

        out.println("</TABLE>");


        out.print(resources.interpolate(D24_HEADER, true));

        defectTypes = defectCounts.keySet().iterator();
        while (defectTypes.hasNext()) {
            defectType = (String) defectTypes.next();
            row = (int[]) defectCounts.get(defectType);
            printD24(defectType, row);
        }
        printD24(TOTAL, totals);

        out.println("</TABLE>");
        out.println(resources.interpolate
                ("<P class='doNotPrint'><A HREF=\"../excel.iqy\"><I>" +
                 "${Export_to_Excel}</I></A></P>", true));
        if (strict) {
            String query = (String) env.get("QUERY_STRING");
            query = StringUtils.findAndReplace(query, "strict", "notstrict");
            String anchor = "<A HREF='r4.class?" + query + "'>";
            String footnote = resources.interpolate(FOOTNOTE, false);
            footnote = StringUtils.findAndReplace(footnote, "<A>", anchor);
            footnote = StringUtils.findAndReplace(footnote, "<a>", anchor);
            out.println("<P><HR>" + footnote);
        }
        out.println("</BODY></HTML>");
    }
    protected static final String FOOTNOTE =
        "<P class=footnote><span class=doNotPrint>" +
        "${Strict_Footnote_HTML}</span></P>";

    protected void printD23(String param, String label, int [] row) {
        String dt = param;
        if (!label.startsWith(TOTAL)) {
            dt += ("&type=" + HTMLUtils.urlEncode(label));
            if (!strict && 0 == (row[INJ_DESIGN] + row[INJ_CODE] +
                                 row[REM_COMPILE] + row[REM_TEST]))
                return;
        }
        out.println("<TR><TD><A HREF=\"../defectlog.class?" + dt +"\">" +
                    HTMLUtils.escapeEntities(label) +
                    "</A></TD>");
        out.println("<TD>" + fc(dt, row, INJ_DESIGN) + "</TD>");
        out.println("<TD>" + fc(dt, row, INJ_CODE) + "</TD>");
        out.println("<TD>" + fp(row, INJ_DESIGN) + "</TD>");
        out.println("<TD>" + fp(row, INJ_CODE) + "</TD>");
        out.println("<TD>" + fc(dt, row, REM_COMPILE) + "</TD>");
        out.println("<TD>" + fc(dt, row, REM_TEST) + "</TD>");
        out.println("<TD>" + fp(row, REM_COMPILE) + "</TD>");
        out.println("<TD>" + fp(row, REM_TEST) + "</TD></TR>");
    }
    /** format a count, found in slot col of array row. */
    protected String fc(String dt, int [] row, int col) {
        if (row[col] == 0) return NA;
        if (dt == null) return Integer.toString(row[col]);
        return "<A HREF=\"../defectlog.class?" + FILTERS[col] + "&" + dt
            +"\">" + row[col] + "</A>";
    }
    /** format a percentage, calculated by dividing item n of row by item d */
    protected String fp(int num, int denom) {
        if (num == 0 || denom == 0) return NA;
        return FormatUtil.formatPercent(((double) num) / denom, 0);
    }
    protected String fp(int [] row, int n) { return fp(row[n], totals[n]); }
    protected String fp(int [] row, int n, int d) { return fp(row[n],row[d]); }
    private static final String NA = resources.getString("NA");


    protected void printD24(String label, int [] row) {
        if (!strict && 0 == (row[PRESENT_AT_COMP_ENTRY] +
                             row[FOUND_IN_COMPILE]))
            return;

        out.println("<TR><TD>" +
                    HTMLUtils.escapeEntities(label) +
                    "</TD>");
        out.println("<TD>" + fc(null, row, PRESENT_AT_COMP_ENTRY) + "</TD>");
        out.println("<TD>" + fc(null, row, FOUND_IN_COMPILE) + "</TD>");
        out.println("<TD>" + fp(row, FOUND_IN_COMPILE,
                                PRESENT_AT_COMP_ENTRY) + "</TD></TR>");
    }

    /** Generate an empty row of the appropriate size */
    private int[] emptyRow() {
        int [] result = new int[6];
        for (int i=0;  i<6;  i++) result[i] = 0;
        return result;
    }
    /** Initialize internal data structures to zero */
    private void initValues() {
        totals = emptyRow();
        defectCounts = new TreeMap();
        if (strict) {
            DefectTypeStandard dts =
                DefectTypeStandard.get(getPrefix(), getDataRepository());
            for (int i=dts.options.size();  i-->0; )
                getRow((String) dts.options.elementAt(i));
        }
    }

    /** Lookup the row for a defect type - create it if it doesn't exist. */
    private int[] getRow(String defectType) {
        int [] result = (int[]) defectCounts.get(defectType);
        if (result == null)
            defectCounts.put(defectType, result = emptyRow());
        return result;
    }
    /** Increment a defect count for a particular defect type */
    protected void increment(int [] row, int type) {
        totals[type]++;
        row[type]++;
    }

    protected boolean test(String defPhase, String cmpPhase) {
        return defPhase.equals(cmpPhase) || defPhase.endsWith("/"+cmpPhase);
    }

    public void analyze(String path, Defect d) {
        int [] row = getRow(d.defect_type);

        if (test(d.phase_injected, "Design")) increment(row, INJ_DESIGN);
        else if (test(d.phase_injected, "Code")) increment(row, INJ_CODE);

        if (test(d.phase_removed, "Compile")) increment(row, REM_COMPILE);
        else if (test(d.phase_removed, "Test")) increment(row, REM_TEST);

        if (!test(d.phase_injected, "Compile") &&
            !test(d.phase_injected, "Test") &&
            !test(d.phase_injected, "Reassessment") &&
            !test(d.phase_injected, "Postmortem"))
            if (d.phase_removed.endsWith("Compile")) {
                increment(row, PRESENT_AT_COMP_ENTRY);
                increment(row, FOUND_IN_COMPILE);
            } else if (d.phase_removed.endsWith("Test") ||
                       d.phase_removed.endsWith("Reassessment") ||
                       d.phase_removed.endsWith("Postmortem"))
                increment(row, PRESENT_AT_COMP_ENTRY);
    }
}
