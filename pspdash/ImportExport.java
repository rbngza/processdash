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

import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.FileWriter;
import java.io.PrintWriter;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import java.util.*;

public class ImportExport extends JDialog implements ActionListener {

    static final int X_DATA = 0;
    static final int X_LIST = 1;

    static String FILE_SEP = null;

    PSPDashboard  parent;
    PSPProperties props;
    JCheckBox     incNonTemplate;
    JTree         tree;
    int           operation = X_DATA;

    PropSelectTreeModel treeModel;


    public ImportExport (PSPDashboard dash) {
        super (dash, "Export");

        parent = dash;
        props = parent.props;

        /* Create the JTreeModel. */
        treeModel = new PropSelectTreeModel
            (new DefaultMutableTreeNode (new JCheckBox("root")),
             props,
             PropSelectTreeModel.NO_LEAVES);

        /* Create the tree. */
        tree = new SelectableTree (treeModel, new SelectableTreeCellRenderer());

        /* Put the Tree in a scroller. */
        JScrollPane sp = new JScrollPane
            (ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
             ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        sp.getViewport().add(tree);

        getContentPane().add(sp, "Center");

        Box mainBox = new Box(BoxLayout.Y_AXIS);
        Box aBox = new Box(BoxLayout.X_AXIS);
        aBox.add (Box.createHorizontalStrut(2));
        incNonTemplate = new JCheckBox ("Show leaf nodes");
        incNonTemplate.setActionCommand("leaves");
        incNonTemplate.addActionListener(this);
        aBox.add (incNonTemplate);
        aBox.add (Box.createGlue());
        mainBox.add (aBox);
        mainBox.add (Box.createVerticalStrut(2));

        ButtonGroup bg = new ButtonGroup();

        Box buttonBox = new Box(BoxLayout.Y_AXIS);
        JRadioButton button;
        button = new JRadioButton ("Export Data");
        button.setActionCommand("XData");
        button.addActionListener(this);
        button.setSelected(true);
        bg.add (button);
        buttonBox.add (button);

        button = new JRadioButton ("Export Hierarchy List");
        button.setActionCommand("XList");
        button.addActionListener(this);
        bg.add (button);
        buttonBox.add (button);

        buttonBox.add (Box.createVerticalStrut(4));
        buttonBox.add (Box.createVerticalGlue());

        Box btnBox = new Box(BoxLayout.X_AXIS);
        btnBox.add(Box.createHorizontalGlue());
        JButton btn = new JButton ("Apply");
        btn.setActionCommand("Apply");
        btn.addActionListener(this);
        btnBox.add(btn);
        btnBox.add(Box.createHorizontalGlue());
        buttonBox.add (btnBox);
        buttonBox.add (Box.createVerticalStrut(2));

        getContentPane().add(mainBox, "South");
        getContentPane().add(buttonBox, "East");
        updateTree(incNonTemplate.isSelected());
        pack();
        show();

                                    // get needed system properties
        Properties prop = System.getProperties ();
        FILE_SEP = prop.getProperty ("file.separator");
    }

    protected void updateTree(boolean isSelected) {
        if (isSelected) {
            treeModel.setFilterCriteria(treeModel.NO_FILTER);
        } else {
            treeModel.setFilterCriteria(treeModel.NO_LEAVES);
        }

        treeModel.nodeStructureChanged((TreeNode)treeModel.getRoot());
        tree.repaint(tree.getVisibleRect());
    }



    protected void recursivelyAddSelectedToVector (DefaultMutableTreeNode dmn,
                                                   Vector v) {
        JCheckBox jcb = (JCheckBox)(dmn.getUserObject());
        if (jcb.isSelected())
            v.addElement (treeModel.getPropKey (props, dmn.getPath()).path());
        else
            for (int ii = 0; ii < treeModel.getChildCount(dmn); ii++) {
                recursivelyAddSelectedToVector
                    ((DefaultMutableTreeNode)treeModel.getChild (dmn, ii), v);
            }
    }

    public void actionPerformed(ActionEvent e) {
        String cmd = e.getActionCommand();
        FileDialog fd;
        String lastFile;
        boolean fail = false;

        if (cmd.equals("leaves")) {
            updateTree(incNonTemplate.isSelected());
        } else if (cmd.equals("XData")) {
            operation = X_DATA;
        } else if (cmd.equals("XList")) {
            operation = X_LIST;
        } else if (cmd.equals("Apply")) {
            DefaultMutableTreeNode dmn;
            Vector v = new Vector();
            dmn = (DefaultMutableTreeNode)treeModel.getRoot();
            for (int ii = 0; ii < treeModel.getChildCount(dmn); ii++) {
                recursivelyAddSelectedToVector
                    ((DefaultMutableTreeNode)treeModel.getChild (dmn, ii), v);
            }
            switch (operation) {
            case X_DATA:
                // Perform operation (filter TBD)
                //export the data
                // use file dialog to get file name/loc?
                //  (extend file dialog class to add more functionality/options?)
                fd = new FileDialog (parent,
                                     "Export Data To",
                                     FileDialog.SAVE);
                //fd.setDirectory ("");
                fd.setFile ("dash.txt");
                fd.show();
                lastFile = fd.getFile ();
                if (lastFile != null) {
                    JDialog working;
                    working = new JDialog (parent, "Exporting...");
                    working.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                    JLabel lab = new JLabel ("Export in Progress.  Please Wait.");
                    working.getContentPane().add(lab, "Center");
                    working.pack();
                    working.show();
                    Thread.yield();

                    String lastDir  = fd.getDirectory ();
                    try {
                        PrintWriter out =
                            new PrintWriter (new BufferedWriter
                                             (new FileWriter(lastDir + FILE_SEP + lastFile)));
                        parent.data.dumpRepository(out, v);

                        TimeLog tl = new TimeLog();
                        try {
                            TimeLogEntry tle;
                            tl.read (parent.getTimeLog());
                            Enumeration keys = tl.filter(PropertyKey.ROOT, null, null);
                            while (keys.hasMoreElements()) {
                                tle = (TimeLogEntry)keys.nextElement();
                                if (Filter.matchesFilter (v, tle.key.path()))
                                    out.println(tle.toAbbrevString());
                            }
                        } catch (IOException ioe) {}

                        out.close();
                    } catch (IOException ioe) {
                        fail = true; System.out.println("IOException: " + e);
                    };
                    lab.setText ("Export Complete.");
                    working.invalidate();
                }
                break;
            case X_LIST:
                // Perform operation (filter TBD)
                //export the hierarchy
                // use file dialog to get file name/loc?
                //  (extend file dialog class to add more functionality/options?)
                fd = new FileDialog (parent,
                                     "Export Hierarchy To",
                                     FileDialog.SAVE);
                //fd.setDirectory ("");
                fd.setFile ("hierarch.txt");
                fd.show();
                lastFile = fd.getFile ();
                if (lastFile != null) {
                    JDialog working;
                    working = new JDialog (parent, "Exporting...");
                    working.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                    JLabel lab = new JLabel ("Export in Progress.  Please Wait.");
                    working.getContentPane().add(lab, "Center");
                    working.pack();
                    working.show();
                    Thread.yield();

                    String lastDir  = fd.getDirectory ();
                    try {
                        PrintWriter out =
                            new PrintWriter (new BufferedWriter
                                             (new FileWriter(lastDir + FILE_SEP + lastFile)));
                        parent.props.orderedDump(out, v);
                        out.close();
                    } catch (IOException ioe) {
                        fail = true; System.out.println("IOException: " + e);
                    };
                    lab.setText ("Export Complete.");
                    working.invalidate();
                }
                break;
            }
        }
    }

}
