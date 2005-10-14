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


package net.sourceforge.processdash.log.ui;

import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.StringTokenizer;

import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import net.sourceforge.processdash.InternalSettings;
import net.sourceforge.processdash.ProcessDashboard;
import net.sourceforge.processdash.Settings;
import net.sourceforge.processdash.data.DateData;
import net.sourceforge.processdash.data.DoubleData;
import net.sourceforge.processdash.data.repository.DataRepository;
import net.sourceforge.processdash.hier.PropertyKey;
import net.sourceforge.processdash.i18n.Resources;
import net.sourceforge.processdash.log.ChangeFlagged;
import net.sourceforge.processdash.log.time.DashboardTimeLog;
import net.sourceforge.processdash.log.time.TimeLoggingModel;
import net.sourceforge.processdash.log.time.WorkingTimeLog;
import net.sourceforge.processdash.log.time.IONoSuchElementException;
import net.sourceforge.processdash.log.time.ModifiableTimeLog;
import net.sourceforge.processdash.log.time.MutableTimeLogEntry;
import net.sourceforge.processdash.log.time.MutableTimeLogEntryVO;
import net.sourceforge.processdash.log.time.TimeLogEntry;
import net.sourceforge.processdash.log.time.TimeLogEntryVO;
import net.sourceforge.processdash.ui.DashboardIconFactory;
import net.sourceforge.processdash.ui.SoundClip;
import net.sourceforge.processdash.ui.help.PCSH;
import net.sourceforge.processdash.ui.lib.DropDownButton;
import net.sourceforge.processdash.util.Stopwatch;


public class PauseButton extends DropDownButton implements ActionListener, PropertyChangeListener {

    private Icon pauseIcon;
    private Icon continueIcon;
    private String pauseTip;
    private String continueTip;
    private SoundClip timingSound = null;

    private boolean showCurrent = false;
    private boolean quiet;

    private TimeLoggingModel loggingModel;

    public PauseButton(TimeLoggingModel loggingModel) {
        super();
        this.loggingModel = loggingModel;
        loggingModel.addPropertyChangeListener(this);

        PCSH.enableHelp(this, "PlayPause");
        PCSH.enableHelpKey(getMenu(), "PlayPause");

        Resources res = Resources.getDashBundle("ProcessDashboard.Pause");
        pauseTip = res.getString("Pause_Tip");
        continueTip = res.getString("Continue_Tip");

        pauseIcon = DashboardIconFactory.getPauseIcon();
        continueIcon = DashboardIconFactory.getContinueIcon();
        getButton().setDisabledIcon(DashboardIconFactory.getDisabledContinueIcon());
        getButton().setMargin(new Insets(1,2,1,2));
        getButton().setFocusPainted(false);
        getButton().addActionListener(this);
        setRunFirstMenuOption(false);

        loadUserSettings();
        updateAppearance();

        if (quiet) {
            timingSound = new SoundClip(null);
        } else {
            timingSound = new SoundClip(getClass().getResource("timing.wav"));
        }
    }

    private void updateAppearance() {
        boolean paused = loggingModel.isPaused();
        getButton().setIcon(showCurrent == paused ? pauseIcon : continueIcon);
        getButton().setToolTipText(paused ? continueTip : pauseTip);
        setEnabled(loggingModel.isLoggingAllowed());
    }


    public void actionPerformed(ActionEvent e) {
        if (e.getSource() instanceof JMenuItem) {
            JMenuItem item = (JMenuItem) e.getSource();
            if (setPath(item.getText()))
                loggingModel.setPaused(false);
            else
                getMenu().remove(item);
        } else {
            loggingModel.setPaused(!loggingModel.isPaused());
        }
    }

    public void propertyChange(PropertyChangeEvent evt) {
        String propertyName = evt.getPropertyName();
        if (TimeLoggingModel.PAUSED_PROPERTY.equals(propertyName)
                || TimeLoggingModel.ACTIVE_TASK_PROPERTY.equals(propertyName)) {
            if (loggingModel.isPaused() == false)
                timingSound.play();
            updateAppearance();
        } else if (TimeLoggingModel.RECENT_PATHS_PROPERTY.equals(propertyName)) {
            reloadPaths();
        }
    }



    public boolean setPath(String path) {
        if (loggingModel.getActiveTaskModel() != null &&
                loggingModel.getActiveTaskModel().setPath(path))
            return true;
        else {
            // They've gone and edited their hierarchy, and the
            // requested node no longer exists! Beep to let them
            // know there was a problem, then remove this item
            // from the history list so they can't select it again
            // in the future.
            loggingModel.stopTiming();
            Toolkit.getDefaultToolkit().beep();
            return false;
        }
    }

    public boolean setPhase(String phase) {
        if (loggingModel.getActiveTaskModel() != null &&
                loggingModel.getActiveTaskModel().setPhase(phase))
            return true;
        else {
            // They have navigated to a new portion of the hierarchy,
            // where the current phase is not present.  Beep to let them
            // know there was a problem.
            loggingModel.stopTiming();
            Toolkit.getDefaultToolkit().beep();
            return false;
        }
    }

    private void reloadPaths() {
        JMenu menu = getMenu();
        menu.removeAll();

        Iterator recentItems = loggingModel.getRecentPaths().iterator();
        while (recentItems.hasNext()) {
            String path = (String) recentItems.next();
            JMenuItem itemToAdd = new JMenuItem(path);
            itemToAdd.addActionListener(this);
            menu.add(itemToAdd);
        }
    }

    public void saveData() {
        saveUserSettings();
    }


    private void loadUserSettings() {
        // Load the user setting for button appearance
        showCurrent = Settings.getBool("pauseButton.showCurrent", false);

        // Load the user setting for audible feedback
        quiet = Settings.getBool("pauseButton.quiet", false);

        // Load time multiplier setting
        String mult = Settings.getVal("timer.multiplier");
        if (mult != null) try {
            loggingModel.setMultiplier(Double.parseDouble(mult));
        } catch (NumberFormatException nfe) {}

        // Load the saved history list, if it is available.
        String history = Settings.getVal("pauseButton.historyList");
        if (history != null) {
            List paths = Arrays.asList(history.split("\t"));
            Collections.reverse(paths);
            loggingModel.setRecentPaths(paths);
        }

        // Load the user setting for history size
        loggingModel.setMaxRecentPathsRetained(Settings.getInt(
                "pauseButton.historySize", 10));
    }

    private void saveUserSettings() {
        // the only item that could have changed is the history list.
        List recentPaths = loggingModel.getRecentPaths();
        String settingResult = null;

        if (!recentPaths.isEmpty()) {
            StringBuffer setting = new StringBuffer();
            for (int i = recentPaths.size(); i-- > 0; )
                setting.append(recentPaths.get(i)).append('\t');
            settingResult = setting.substring(0, setting.length()-1);
        }

        // save the setting.
        InternalSettings.set("pauseButton.historyList", settingResult);
    }


}
