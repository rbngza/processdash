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


package pspdash;

import java.awt.event.*;
import java.net.*;
import java.util.*;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.TreePath;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import pspdash.data.DataRepository;
import pspdash.data.StringData;
import pspdash.data.SimpleData;

public class EVTaskListXMLAbstract extends EVTaskList {

    private String xmlSource = null;

    protected EVTaskListXMLAbstract(String taskListName,
                                    String displayName,
                                    boolean willNeedChangeNotification) {
        super(taskListName, displayName, willNeedChangeNotification);
    }

    protected boolean openXML(String xmlDoc, String displayName) {
        return openXML(xmlDoc, displayName, null);
    }

    protected boolean openXML(String xmlDoc, String displayName,
                              String errorMessage) {
        if (xmlDoc == null) {
            if (errorMessage == null) errorMessage = "Invalid schedule";
            createErrorRootNode(displayName, errorMessage);
            return false;
        } else if (xmlDoc.equals(xmlSource))
            return true;

        try {
            // parse the XML document.
            Document doc = XMLUtils.parse(xmlDoc);
            Element docRoot = doc.getDocumentElement();

            // extract the task list and the schedule.
            root = new EVTask((Element) docRoot.getFirstChild());
            schedule = new EVSchedule((Element) docRoot.getLastChild());

            // optionally set the display name.
            if (displayName != null)
                ((EVTask) root).name = displayName;

            // optionally set an error message.
            if (errorMessage != null) {
                ((EVTask) root).setTaskError(errorMessage);
                if (!errorMessage.startsWith(" "))
                    schedule.getMetrics().addError
                        (errorMessage, (EVTask) root);
            }

            // minimally recalculate the schedule.
            ((EVTask) root).simpleRecalc(schedule);
            totalPlanTime = schedule.getMetrics().totalPlan();

            // keep a record of the xml doc we parsed for future efficiency.
            xmlSource = xmlDoc;
            return true;
        } catch (Exception e) {
            System.err.println("Got exception: " +e);
            e.printStackTrace();
            if (errorMessage == null) errorMessage = "Invalid schedule";
            createErrorRootNode(displayName, errorMessage);
            return false;
        }
    }

}
