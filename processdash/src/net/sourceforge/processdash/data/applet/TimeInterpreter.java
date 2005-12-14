// Process Dashboard - Data Automation Tool for high-maturity processes
// Copyright (C) 2005 Software Process Dashboard Initiative
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

package net.sourceforge.processdash.data.applet;

import java.text.NumberFormat;
import java.text.ParseException;

import net.sourceforge.processdash.data.DoubleData;
import net.sourceforge.processdash.data.MalformedValueException;
import net.sourceforge.processdash.data.repository.Repository;
import net.sourceforge.processdash.util.AdaptiveNumberFormat;
import net.sourceforge.processdash.util.FormatUtil;
import net.sourceforge.processdash.util.TimeNumberFormat;

class TimeInterpreter extends DoubleInterpreter {

    public static final NumberFormat FMT = new AdaptiveNumberFormat(
            new TimeNumberFormat(), 1);

    public TimeInterpreter(Repository r, String name, int numDigits_ignored,
            boolean readOnly) {
        super(r, name, 1, readOnly);
    }

    public void setString(String s) throws MalformedValueException {
        try {
            Number n = FMT.parse(s);
            value = new DoubleData(n.doubleValue());
        } catch (Exception pe) {
            throw new MalformedValueException();
        }
    }

    public String getString() {
        if (value instanceof DoubleData && value.isDefined()) {
            double time = ((DoubleData) value).getDouble();
            return FMT.format(time);
        } else
            return super.getString();
    }

}
