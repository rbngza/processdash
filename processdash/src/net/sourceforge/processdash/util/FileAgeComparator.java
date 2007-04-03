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

package net.sourceforge.processdash.util;

import java.io.File;
import java.util.Comparator;

/**
 * Compartor for sorting files by age.
 */
public class FileAgeComparator implements Comparator {

    /** comparator which sorts files by age, from oldest to newest. */
    public static final FileAgeComparator OLDEST_FIRST =
        new FileAgeComparator(true);

    /** comparator which sorts files by age, from newest to oldest. */
    public static final FileAgeComparator NEWEST_FIRST =
        new FileAgeComparator(false);


    private boolean oldestFirst;

    private FileAgeComparator(boolean oldestFirst) {
        this.oldestFirst = oldestFirst;
    }

    public int compare(Object o1, Object o2) {
        File f1 = (File) o1;
        File f2 = (File) o2;
        if (f1.lastModified() > f2.lastModified())
            return oldestFirst ? +1 : -1;
        else if (f1.lastModified() < f2.lastModified())
            return oldestFirst ? -1 : +1;
        else
            return 0;
    }

}