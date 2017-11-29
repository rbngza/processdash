// Copyright (C) 2017 Tuma Solutions, LLC
// REST API Add-on for the Process Dashboard
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

package net.sourceforge.processdash.rest.service;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import net.sourceforge.processdash.DashboardContext;
import net.sourceforge.processdash.net.http.PDashServletUtils;
import net.sourceforge.processdash.net.http.TinyCGI;

public class RestDashContext {

    private static DashboardContext ctx;

    public static DashboardContext get() {
        return ctx;
    }

    public static void init(HttpServletRequest request) {
        if (ctx == null) {
            Map env = PDashServletUtils.buildEnvironment(request);
            ctx = (DashboardContext) env.get(TinyCGI.DASHBOARD_CONTEXT);
        }
    }

}