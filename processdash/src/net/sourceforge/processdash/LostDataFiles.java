// Copyright (C) 2000-2003 Tuma Solutions, LLC
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

package net.sourceforge.processdash;


import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Array;

import javax.swing.JOptionPane;

import net.sourceforge.processdash.i18n.Resources;


// class LostDataFiles contains information and methods that are used to
// deal with lost or damaged data files.

public class LostDataFiles implements FilenameFilter {

    private static String acceptFilter = "tttt"; // used to determine if a file
                                                 // is lost
    private static String rejectFilter[] = {"tasks", "time.log"};
                                     // an exception to acceptFilter

    private String lostFiles[]; // contains the list of lost data files

    // the constructor
    public LostDataFiles() {
        lostFiles = null;
    }

    // implements the accept method for the FilenameFilter used by
    // File.list().  Currently a file is accepted if it is not a directory
    // starts with the letters in acceptFilter, and is not one of the
    // strings in rejectFilter.

    public boolean accept(File location, String filename) {

        if (filename.startsWith(acceptFilter)) {
            for(int j = 0; j < rejectFilter.length; j++) {
                if (filename.equals(rejectFilter[j])) {
                    return false;
                }  // end if
            } // end for

            // Zero length files are harmless - just delete them, rather
            // than panicking the user.
            File f = new File(location, filename);
            if (f.length() == 0 && f.delete())
                return false;
        } // end if
        else {
            return false;
        }
    return true;
    }

    // findLostFiles gets a list of files in the searchDir that match the
    // acceptFilter, removes any files that match the rejectFilter, and
    // places the result in lostFiles.

    public void findLostFiles (String searchDir) {
        File searchFile = new File(searchDir);

        // First make sure we have a directory, then get a directory list
        // that matches the accept method
        if (searchFile.isDirectory()) {
            lostFiles = searchFile.list(this);
        }
    }

    // For now, resolve just pops up an information dialog to indicate to
    // the user that lost data must be resolved manually.  A future change
    // may actually allow the user to resolve the problem on the fly.
    // true is returned if the repair was "successful".
    public boolean repair(ProcessDashboard dash) {
        int response;

        ProcessDashboard parent = dash;

        int lostCount = 0;
            if (lostFiles != null) {
                    lostCount = Array.getLength(lostFiles);
            }

        // If there are lost files, resolve them
        if (lostCount > 0) {
            ProcessDashboard.dropSplashScreen();

            // Create an instance of the InfoDialog
            Resources r = Resources.getDashBundle("ProcessDashboard.Errors");
            response = JOptionPane.showConfirmDialog
                (parent,
                 this.printOut() + "\n" + r.getString("Lost_Data_Message"),
                 r.getString("Lost_Data_Title"),
                 JOptionPane.YES_NO_OPTION,
                 JOptionPane.ERROR_MESSAGE);


            if ((response == JOptionPane.NO_OPTION)||
                (response == JOptionPane.CLOSED_OPTION)) {
                return (false);
            }
        }
        return (true);
    }

    // printOut converts the data in lostFiles into a single printable string
    public String printOut() {
        String result = "";

        int lostCount = Array.getLength(lostFiles);

        // If there are lost files, add them to the result
        for (int i = 0; i < lostCount; i++) {
            result = result + lostFiles[i] + "\n";
        }
    return (result);
    }
}