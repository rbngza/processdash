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

package net.sourceforge.processdash.ui.systray;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.EventHandler;
import java.beans.PropertyChangeListener;

import net.sourceforge.processdash.DashController;
import net.sourceforge.processdash.InternalSettings;
import net.sourceforge.processdash.ProcessDashboard;
import net.sourceforge.processdash.i18n.Resources;
import net.sourceforge.processdash.log.time.TimeLoggingModel;
import net.sourceforge.processdash.ui.lib.DuplicatedMenu;

/**
 * Creates and manages the popup menu for the dashboard tray icon.
 * 
 * This class creates the popup menu and initializes it with appropriate menu
 * items. Then, it registers as a listener for relevant changes in application
 * state, and updates the contents of the popup menu appropriately.
 * 
 * @author tuma
 */
public class MenuHandler {

    private PopupMenu popupMenu;

    private ActionListener showWindowAction;

    private ActionListener playPauseAction;

    private ActionListener changeTaskAction;


    private static final Resources res = Resources
            .getDashBundle("ProcessDashboard.SysTray.Menu");


    public MenuHandler(ProcessDashboard pdash, TrayIcon icon,
            Reminder reminder) {
        this.popupMenu = new PopupMenu();
        icon.setPopupMenu(popupMenu);

        createSharedActions(pdash);

        popupMenu.add(new DuplicatedMenu(pdash.getTitle(),
            pdash.getConfigurationMenu()));
        popupMenu.add(new ReminderMenu(reminder));
        popupMenu.add(new RemoveTrayIconAction(icon, reminder));
        ScriptMenuReplicator.replicate(pdash, popupMenu);
        popupMenu.add(makeChangeTaskMenuItem());
        popupMenu.add(new PlayPauseMenuItem(pdash.getTimeLoggingModel()));
        popupMenu.add(makeShowWindowMenuItem(pdash));

    }

    public ActionListener getShowWindowAction() {
        return showWindowAction;
    }

    public ActionListener getPlayPauseAction() {
        return playPauseAction;
    }

    public ActionListener getChangeTaskAction() {
        return changeTaskAction;
    }

    private void createSharedActions(ProcessDashboard pdash) {
        showWindowAction = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                DashController.raiseWindow();
            }
        };

        final TimeLoggingModel timeLoggingModel = pdash.getTimeLoggingModel();
        playPauseAction = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                timeLoggingModel.setPaused(!timeLoggingModel.isPaused());
            }
        };

        changeTaskAction = pdash.getChangeTaskAction();
    }

    private MenuItem makeShowWindowMenuItem(ProcessDashboard pdash) {
        String windowTitle = pdash.getTitle();
        String menuText = res.format("Show_Window_FMT", windowTitle);
        MenuItem showWindow = new MenuItem(menuText);
        showWindow.addActionListener(showWindowAction);
        return showWindow;
    }

    private MenuItem makeChangeTaskMenuItem() {
        MenuItem changeTaskItem = new MenuItem(res.getString("Change_Task"));
        changeTaskItem.addActionListener(changeTaskAction);
        return changeTaskItem;
    }

    public class RemoveTrayIconAction extends MenuItem {

        private TrayIcon icon;

        private Reminder reminder;

        public RemoveTrayIconAction(TrayIcon icon, Reminder reminder) {
            super(res.getString("Remove_Icon"));
            this.icon = icon;
            addActionListener(EventHandler.create(ActionListener.class, this,
                "hideIcon"));
        }

        public void hideIcon() {
            // if the main window is currently "minimized to the tray" when we
            // remove the tray icon, the user would have no way of getting it
            // back.  Raise the window to make certain that doesn't happen.
            DashController.raiseWindow();
            InternalSettings.set(SystemTrayManagement.DISABLED_SETTING, "true");
            reminder.setDisabled(true);
            SystemTray.getSystemTray().remove(icon);
        }
    }


    public class PlayPauseMenuItem extends MenuItem {
        TimeLoggingModel timeLoggingModel;

        public PlayPauseMenuItem(TimeLoggingModel timeLoggingModel) {
            this.timeLoggingModel = timeLoggingModel;

            PropertyChangeListener pcl = EventHandler.create(
                PropertyChangeListener.class, this, "update");
            timeLoggingModel.addPropertyChangeListener(pcl);

            addActionListener(playPauseAction);

            update();
        }

        public void update() {
            String display;
            if (!timeLoggingModel.isLoggingAllowed())
                display = res.getString("StartStop.Disabled");
            else if (timeLoggingModel.isPaused())
                display = res.getString("StartStop.Paused");
            else
                display = res.getString("StartStop.Timing");
            setLabel(display);
        }
    }

}
