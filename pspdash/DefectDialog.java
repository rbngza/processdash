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

import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.Dimension;
import java.awt.BorderLayout;
import java.awt.event.*;
import java.text.NumberFormat;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import java.util.*;

public class DefectDialog extends JDialog
    implements ActionListener, DocumentListener, WindowListener
{
    PSPDashboard parent;
    String defectFilename;
    PropertyKey defectPath;
    DefectLog defectLog = null;
    Timer stopwatch = new Timer(false);
    JButton defectTimerButton, OKButton, CancelButton, fixDefectButton;
    Date date = null;
    String defectNumber = null;
    JLabel number;
    JTextField fix_defect;
    DecimalField fix_time;
    JTextArea description;
    JComboBox defect_type, phase_injected, phase_removed;
    boolean isDirty = false, autoCreated = false;

    /** A stack of the defect dialogs that have been interrupted. */
    private static Stack interruptedDialogs = new Stack();
    /** The defect dialog which was timing most recently. */
    private static DefectDialog activeDialog = null;
    /** A timer object for refreshing the fix time field. */
    private javax.swing.Timer activeRefreshTimer = null;

    DefectDialog(PSPDashboard dash, String defectFilename,
                 PropertyKey defectPath) {
        super(dash, "Defect Dialog");

        parent = dash;
        this.defectFilename = defectFilename;
        this.defectPath = defectPath;
        defectLog = new DefectLog(defectFilename, defectPath.path(),
                                  dash.data, dash);
        date = new Date();

        JPanel panel = new JPanel();
        GridBagLayout layout = new GridBagLayout();
        GridBagConstraints g = new GridBagConstraints();
        JComponent c;
        Insets bottom_margin = new Insets(1, 1, 8, 1);
        Insets small_margin = new Insets(1, 1, 1, 1);
        panel.setBorder(new EmptyBorder(5,5,5,5));
        panel.setLayout(layout);

                                // first row
        g.gridy = 0;   g.insets = small_margin; g.anchor = g.WEST;

        number = new JLabel();
        g.gridx = 0;   layout.setConstraints(number, g);
        panel.add(number);

        c = new JLabel("Fix Defect");
        g.gridx = 1;   g.gridwidth = 2; layout.setConstraints(c, g);
        panel.add(c);

                                // second row
        g.gridy = 1;   g.insets = bottom_margin;   g.anchor = g.NORTHWEST;
        g.gridwidth = 1;
        defect_type = DefectTypeStandard.get
            (defectPath.path(), dash.data).getAsComboBox();
        defect_type.insertItemAt("Choose a type", 0);
        defect_type.setSelectedIndex(0);
        defect_type.addActionListener(this);

        g.gridx = 0;   layout.setConstraints(defect_type, g);
        panel.add(defect_type);

        fix_defect = new JTextField(5);
        fix_defect.getDocument().addDocumentListener(this);
        fix_defect.setMinimumSize(fix_defect.getPreferredSize());
        g.gridx = 1;   layout.setConstraints(fix_defect, g);
        panel.add(fix_defect);

        fixDefectButton = new JButton
            (new ImageIcon(getClass().getResource("defect.gif")));
        fixDefectButton.addActionListener(this);
        g.gridx = 2;   layout.setConstraints(fixDefectButton, g);
        panel.add(fixDefectButton);

                                // third row
        g.gridy = 2;   g.insets = small_margin;   g.anchor = g.WEST;

        c = new JLabel("Injected");
        g.gridx = 0;   layout.setConstraints(c, g);
        panel.add(c);

        c = new JLabel("Removed");
        g.gridx = 1;   g.gridwidth = 2;   layout.setConstraints(c, g);
        panel.add(c);

                                // fourth row
        g.gridy = 3;   g.insets = bottom_margin;   g.anchor = g.NORTHWEST;
        g.gridwidth = 1;


        int prefixLength = defectPath.path().length() + 1;
        String defaultRemovalPhase =
            parent.currentPhase.path().substring(prefixLength);
        phase_removed = phaseComboBox(defectPath, defaultRemovalPhase);

        String defaultInjectionPhase =
            guessInjectionPhase(phase_removed, defaultRemovalPhase);
        phase_injected = phaseComboBox(defectPath, defaultInjectionPhase);

        phase_injected.insertItemAt("Before Development", 0);
        phase_injected.addActionListener(this);
        g.gridx = 0;   layout.setConstraints(phase_injected, g);
        panel.add(phase_injected);

        phase_removed.addItem("After Development");
        phase_removed.addActionListener(this);
        g.gridx = 1; g.gridwidth =2; layout.setConstraints(phase_removed, g);
        panel.add(phase_removed);

                                // fifth row
        g.gridy = 4;   g.insets = small_margin;   g.anchor = g.WEST;
        g.gridwidth = 1;

        c = new JLabel("Fix Time");
        g.gridx = 0;   layout.setConstraints(c, g);
        panel.add(c);

                                // sixth row
        g.gridy = 5;   g.insets = bottom_margin;

        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(1);
        fix_time = new DecimalField(0.0, 10, nf);
        fix_time.setMinimumSize(fix_time.getPreferredSize());
        g.anchor = g.NORTHWEST;   layout.setConstraints(fix_time, g);
        panel.add(fix_time);
        fix_time.getDocument().addDocumentListener(this);

        defectTimerButton = new JButton("Start Fixing");
        defectTimerButton.addActionListener(this);
        g.gridx = 1;   g.gridwidth = 2;   g.anchor = g.NORTH;
        layout.setConstraints(defectTimerButton, g);
        panel.add(defectTimerButton);

                                // seventh row
        g.gridy = 6;   g.insets = small_margin;   g.anchor = g.WEST;
        g.gridwidth = 1;
        c = new JLabel("Description");
        g.gridx = 0;   layout.setConstraints(c, g);
        panel.add(c);

                                // eighth row
        g.gridy = 7;   g.insets = bottom_margin;  g.fill = g.BOTH;
        description = new JTextArea();
        description.getDocument().addDocumentListener(this);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);

        JScrollPane scroller = new
            JScrollPane(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
                public Dimension getPreferredSize() {
                    return new Dimension(100, 100);
                }
            };
        scroller.getViewport().add(description);

        JPanel textWrapper = new JPanel(new BorderLayout());
//      textWrapper.setAlignmentX(LEFT_ALIGNMENT);
        textWrapper.setBorder(new BevelBorder(BevelBorder.LOWERED));
        textWrapper.add("Center", scroller);

        g.gridwidth = 3;   layout.setConstraints(textWrapper, g);
        panel.add(textWrapper);
        g.gridwidth = 1;

                                // ninth row
        g.gridy = 8;  g.insets = small_margin;
        g.anchor = g.CENTER;   g.fill = g.NONE;

        OKButton = new JButton("OK");
        OKButton.addActionListener(this);
        g.gridx = 0;   layout.setConstraints(OKButton, g);
        panel.add(OKButton);

        CancelButton = new JButton("Cancel");
        CancelButton.addActionListener(this);
        g.gridx = 1; g.gridwidth = 2; layout.setConstraints(CancelButton, g);
        panel.add(CancelButton);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(this);

        getContentPane().add(panel);
        pack();
        panel.setMinimumSize(panel.getPreferredSize());
        show();

        if ("true".equalsIgnoreCase(Settings.getVal("defectDialog.autostart")))
            startTimingDefect();
        setDirty(false);
    }

    DefectDialog(PSPDashboard dash, String defectFilename,
                 PropertyKey defectPath, Defect defect) {
        this(dash, defectFilename, defectPath);
        stopTimingDefect();
        setValues(defect);
        setDirty(false);
    }

    public void setDirty(boolean dirty) {
        isDirty = dirty;
        //OKButton.setEnabled(dirty);
    }

    public void save() {
        refreshFixTimeFromStopwatch();

        Defect d = new Defect();
        d.date = date;
        d.number = defectNumber;
        d.defect_type = (String)defect_type.getSelectedItem();
        d.phase_injected = (String)phase_injected.getSelectedItem();
        d.phase_removed = (String)phase_removed.getSelectedItem();
        d.fix_time = fix_time.getText();
        d.fix_defect = fix_defect.getText();
        d.description = description.getText();

        defectLog.writeDefect(d);

        defectNumber = d.number;
        number.setText("Defect #" + d.number);
        setDirty(false);
    }

    public void startTimingDefect() {
        stopwatch.start();
        defectTimerButton.setText("Stop Fixing");
        if (activeRefreshTimer == null)
            activeRefreshTimer = new javax.swing.Timer(6000, this);
        activeRefreshTimer.start();

        if (activeDialog != this) synchronized (interruptedDialogs) {
            if (activeDialog != null && activeDialog.stopwatch.isRunning()) {
                interruptedDialogs.push(activeDialog);
                activeDialog.stopTimingDefect();
            }

            interruptedDialogs.remove(this); // it might not be there, that's OK
            activeDialog = this;
        }
    }

    public void stopTimingDefect() {
        stopwatch.stop();
        defectTimerButton.setText("Start Fixing");
        if (activeRefreshTimer != null)
            activeRefreshTimer.stop();

        refreshFixTimeFromStopwatch();
    }

    private void toggleDefect() {
        boolean is_now_running = stopwatch.toggle();

        if (is_now_running)
            startTimingDefect();
        else
            stopTimingDefect();

        setDirty(true);
    }

    private void maybePopDialog() {
        if (activeDialog == this)
            if (interruptedDialogs.empty())
                activeDialog = null;
            else {
                activeDialog = (DefectDialog) interruptedDialogs.pop();
                activeDialog.toFront();

                if (stopwatch.isRunning())
                    activeDialog.startTimingDefect();
            }
    }

    private void maybeDeleteAutocreatedDefect() {
        if (autoCreated)
            defectLog.deleteDefect(defectNumber);
    }

    private void comboSelect(JComboBox cb, String item) {
        int i = cb.getItemCount();
        while (i != 0)
            if (item.equals(cb.getItemAt(--i))) {
                cb.setSelectedIndex(i);
                break;
            }
    }

    private JComboBox phaseComboBox(PropertyKey defectPath,
                                    String selectedChild) {
        JComboBox result = new JComboBox();

        int prefixLength = defectPath.path().length() + 1;
        String item = null;
        Enumeration leafNames = parent.getProperties().getLeafNames(defectPath);

        while (leafNames.hasMoreElements()) {
            item = ((String)leafNames.nextElement()).substring(prefixLength);

            // This is NOT the right way to do this. A better way would be to
            // look at the defect flag of each leaf.  Leaves that wanted to
            // forbid defects could set their flag to false. But this will work...
            if (item.endsWith("Postmortem") || item.endsWith("Reassessment"))
                continue;           // don't add to the list.
            result.addItem(item);
            if (item.equals(selectedChild))
                result.setSelectedItem(item);
        }

        return result;
    }

    /** Make an educated guess about which injection phase might correspond
     *  to the given removal phase.
     *
     * Currently, this just guesses the phase immediately preceeding the
     * removal phase.  In the future, it might read a user setting
     */
    private String guessInjectionPhase(JComboBox phases, String removalPhase)
    {
        String result, mappedGuess, onePhase;

        int pos = removalPhase.lastIndexOf('/');
        if (pos == -1)
            mappedGuess = (String) INJ_REM_PAIRS.get(removalPhase);
        else
            mappedGuess =
                (String) INJ_REM_PAIRS.get(removalPhase.substring(pos+1));

        int i = phases.getItemCount();
        while (i-- > 0)
            if (removalPhase.equals(phases.getItemAt(i))) break;

        result = null;
        while (i-- > 0) {
            onePhase = (String) phases.getItemAt(i);
            if (phaseMatches(onePhase, mappedGuess))
                return onePhase;
            if (result == null &&
                !onePhase.endsWith(" Review") &&
                !onePhase.endsWith(" Inspection") &&
                !onePhase.endsWith("Compile") &&
                !onePhase.endsWith("Test"))
                // remember the first non-quality, non-failure phase
                // we find before the removalPhase.
                result = onePhase;
        }
        if (result == null)
            result = removalPhase;

        return result;
    }

    private boolean phaseMatches(String fullName, String phaseName) {
        if (fullName == null || phaseName == null) return false;

        int pos = fullName.lastIndexOf('/');
        if (pos != -1)
            fullName = fullName.substring(pos+1);

        return fullName.equalsIgnoreCase(phaseName);
    }

    private static Map INJ_REM_PAIRS;
    static {
        HashMap phaseMap = new HashMap();
        String userSetting = Settings.getVal("defectDialog.phaseMap");
        if (userSetting != null) {
            StringTokenizer tok = new StringTokenizer(userSetting, "|");
            String phasePair, rem, inj;
            int pos;
            while (tok.hasMoreTokens()) {
                phasePair = tok.nextToken();
                pos = phasePair.indexOf("=>");
                if (pos != -1) {
                    inj = phasePair.substring(0, pos).trim();
                    rem = phasePair.substring(pos+2).trim();
                    phaseMap.put(rem, inj);
                }
            }
        }
        INJ_REM_PAIRS = Collections.unmodifiableMap(phaseMap);
    }

    private void hide_popups() {
        defect_type.hidePopup();
        phase_injected.hidePopup();
        phase_removed.hidePopup();
    }

    private volatile boolean programmaticallyChangingFixTime = false;

    private void fixTimeChanged() {
        if (programmaticallyChangingFixTime) return;
        setDirty(true);
        stopwatch.setElapsed((long) (fix_time.getValue() * 60.0));
    }

    private void refreshFixTimeFromStopwatch() {
        programmaticallyChangingFixTime = true;
        fix_time.setValue(stopwatch.minutesElapsedDouble());
        programmaticallyChangingFixTime = false;
    }

    private void openFixDefectDialog() {
        if (defectNumber == null) {
            save();
            setDirty(true);
            autoCreated = true;
        }
        DefectDialog d = new DefectDialog(parent, defectFilename, defectPath);
        d.fix_defect.setText(defectNumber);
        d.setDirty(false);
    }

    public void setValues(Defect d) {
        date = d.date;
        defectNumber = d.number;
        number.setText("Defect #" + d.number);
        comboSelect(defect_type, d.defect_type);
        comboSelect(phase_injected, d.phase_injected);
        comboSelect(phase_removed, d.phase_removed);
        fix_time.setText(d.fix_time); // will trigger fixTimeChanged
        fix_defect.setText(d.fix_defect);
        description.setText(d.description);
    }

    public void dispose() {
        hide_popups();
        if (activeRefreshTimer != null) {
            activeRefreshTimer.stop();
            activeRefreshTimer.removeActionListener(this);
            activeRefreshTimer = null;
        }
        interruptedDialogs.remove(this); // it might not be there, that's OK
        super.dispose();
    }


    /** Check to see if the removal phase is before the injection phase.
     *
     * If they are out of order, display an error message to the user and
     * return false; otherwise return true.
     */
    private boolean checkSequence() {
        if ("false".equalsIgnoreCase
            (Settings.getVal("defectDialog.restrictSequence")))
            return true;

        // Ensure that the user isn't removing a defect before it is
        // injected.
        String injected = (String)phase_injected.getSelectedItem();
        String removed  = (String)phase_removed.getSelectedItem();

        int numOptions = phase_injected.getItemCount();
        String option;
        for (int i = 0;  i < numOptions;  i++) {
            option = (String) phase_injected.getItemAt(i);
            if (option.equalsIgnoreCase(injected))
                return true;
            if (option.equalsIgnoreCase(removed)) {
                JOptionPane.showMessageDialog(this, SEQUENCE_ERROR_MSG,
                                              "Sequence Error",
                                              JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }
        // We shouldn't get here...
        return true;
    }
    private static final String [] SEQUENCE_ERROR_MSG = {
        "It is impossible to remove a defect before it has",
        "been injected!  Please double-check the injection",
        "and removal phases you have selected, and change",
        "them so the defect is not removed in an earlier",
        "phase than when it was injected." };


    /** Check to ensure that the user has selected a defect type.
     *
     * If they have not, display an error message to the user and
     * return false; otherwise return true.
     */
    private boolean checkValidType() {
        if (defect_type.getSelectedIndex() > 0)
            return true;

        JOptionPane.showMessageDialog
            (this, "Please choose a type for the defect.",
             "Choose defect type", JOptionPane.ERROR_MESSAGE);

        return false;
    }


    /** Check to see if the defect has been modified, prior to a "Cancel"
     * operation.
     *
     * If the defect has not been modified, return true.  If the
     * defect HAS been modified, ask the user if they really want to
     * discard their changes.  If they do, return true; otherwise
     * returns false.
     */
    private boolean checkDirty() {
        return (!isDirty ||
                JOptionPane.showConfirmDialog
                (this, "Discard changes?", "Confirm Cancel",
                 JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
    }

    /** Logic supporting a click of the OK button */
    private void okButtonAction() {
        if (checkSequence() && checkValidType()) {
            maybePopDialog();
            save();
            dispose();
        }
    }

    /** Logic supporting a click of the Cancel button */
    private void cancelButtonAction() {
        if (checkDirty()) {
            maybeDeleteAutocreatedDefect();
            maybePopDialog();
            dispose();
        }
    }

    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == activeRefreshTimer)
            refreshFixTimeFromStopwatch();
        else if (e.getSource() == OKButton)
            okButtonAction();
        else if (e.getSource() == CancelButton)
            cancelButtonAction();
        else if (e.getSource() == defectTimerButton)
            toggleDefect();
        else if (e.getSource() == fixDefectButton)
            openFixDefectDialog();
        else
            // this event must be a notification of a change to one of the
            // JComboBoxes on the form.
            setDirty(true);
    }

    // Implementation of the DocumentListener interface

    private void handleDocumentEvent(DocumentEvent e) {
        if (e.getDocument() == fix_time.getDocument())
            // If the user edited the "Fix Time" field, perform the
            // necessary recalculations.
            fixTimeChanged();

        else
            // The user changed one of the other text fields on the form
            // (for example, the Fix Defect or the Description).
            setDirty(true);
    }

    public void changedUpdate(DocumentEvent e) {}
    public void insertUpdate(DocumentEvent e)  { handleDocumentEvent(e); }
    public void removeUpdate(DocumentEvent e)  { handleDocumentEvent(e); }

    // Implementation of the WindowListener interface

    public void windowOpened(WindowEvent e) {}
    public void windowClosing(WindowEvent e) { cancelButtonAction(); }
    public void windowClosed(WindowEvent e) {}
    public void windowIconified(WindowEvent e) {}
    public void windowDeiconified(WindowEvent e) {}
    public void windowActivated(WindowEvent e) {}
    public void windowDeactivated(WindowEvent e) {}
}
