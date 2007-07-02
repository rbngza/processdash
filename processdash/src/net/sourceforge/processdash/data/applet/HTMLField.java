// Copyright (C) 1999-2006 Tuma Solutions, LLC
// Process Dashboard - Data Automation Tool for high-maturity processes
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


import net.sourceforge.processdash.Settings;
import net.sourceforge.processdash.data.repository.DataListener;


public abstract class HTMLField {

    public static final String nullValue = new String("");
    static boolean READ_ONLY_MODE = Settings.isReadOnly();


    protected DataInterpreter i = null;
    protected Object variantValue = nullValue;


    public boolean isEditable() {
        return !READ_ONLY_MODE && (i == null || i.isEditable());
    }


    public void dispose(boolean repositoryExists) {
        if (i != null) i.dispose(repositoryExists);
        i = null;
        variantValue = null;
    }


    public void unlock() { if (i != null) i.unlock(); }


    abstract public void repositoryChangedValue();


    public void maybeAddActiveListener(DataListener l) {
        if (i != null && i.isActive())
            i.setChangeListener(l);
    }
}