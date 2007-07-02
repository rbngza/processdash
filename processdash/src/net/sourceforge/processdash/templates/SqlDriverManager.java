// Copyright (C) 2007 Tuma Solutions, LLC
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

package net.sourceforge.processdash.templates;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.sourceforge.processdash.util.StringUtils;

import org.w3c.dom.Element;

public class SqlDriverManager {

    private static final Set REGISTERED_DRIVERS = new HashSet();

    public synchronized static boolean registerDriver(String id) {
        if (id == null || id.length() == 0)
            return false;
        if (REGISTERED_DRIVERS.contains(id))
            return true;

        List drivers = ExtensionManager
                .getXmlConfigurationElements("jdbc-driver");
        for (Iterator i = drivers.iterator(); i.hasNext();) {
            Element configElement = (Element) i.next();
            String oneId = configElement.getAttribute("driverId");
            if (!StringUtils.hasValue(oneId))
                oneId = configElement.getAttribute("class");
            if (id.equals(oneId)) {
                try {
                    Object obj = ExtensionManager.getExecutableExtension(
                            configElement, "class", null);
                    DriverShim driverShim = new DriverShim(((Driver) obj));
                    DriverManager.registerDriver(driverShim);
                    REGISTERED_DRIVERS.add(id);
                    return true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }


    public static final class DriverShim implements Driver {
        private Driver delegate;

        public DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        public Connection connect(String url, Properties info)
                throws SQLException {
            return delegate.connect(url, info);
        }

        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
                throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

    }
}