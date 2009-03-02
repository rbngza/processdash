// Copyright (C) 2009 Tuma Solutions, LLC
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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, see <http://www.gnu.org/licenses/>.
//
// The author(s) may be contacted at:
//     processdash@tuma-solutions.com
//     processdash-devel@lists.sourceforge.net

package net.sourceforge.processdash.tool.prefs;

import java.awt.BorderLayout;
import java.io.IOException;
import java.net.URLConnection;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JPanel;

import net.sourceforge.processdash.templates.TemplateLoader;
import net.sourceforge.processdash.tool.prefs.editor.PreferencesCheckBox;
import net.sourceforge.processdash.tool.prefs.editor.PreferencesFileList;
import net.sourceforge.processdash.tool.prefs.editor.PreferencesRadioButtons;
import net.sourceforge.processdash.tool.prefs.editor.PreferencesTextField;
import net.sourceforge.processdash.ui.lib.binding.BoundForm;
import net.sourceforge.processdash.util.XMLUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * This form is used to modify the user preferences under a specific category.
 */
public class PreferencesForm extends BoundForm {

    /** Various xml tags used in spec files */
    private static final String ID_TAG = "id";
    private static final String REQUIRES_TAG = "requires";
    public static final String SETTING_TAG = "setting";

    /** The tags for which special Preferences editors are used */
    private static final String CHECKBOX_TAG = "checkbox";
    private static final String TEXTFIELD_TAG = "textfield";
    private static final String RADIOBUTTONS_TAG = "radio";
    private static final String FILELIST_TAG = "file-list";

    /** The JPanel containing the GUI */
    private JPanel panel = new JPanel();

    private static final Logger logger = Logger.getLogger(PreferencesForm.class.getName());

    public PreferencesForm(PreferencesCategory category) {
        addElementType(CHECKBOX_TAG, PreferencesCheckBox.class);
        addElementType(TEXTFIELD_TAG, PreferencesTextField.class);
        addElementType(RADIOBUTTONS_TAG, PreferencesRadioButtons.class);
        addElementType(FILELIST_TAG, PreferencesFileList.class);

        selectCategory(category);
        panel.setLayout(new BorderLayout());
        panel.add(BorderLayout.CENTER, getContainer());
    }

    /**
     * Selects the category shown by the PreferencesForm
     */
    private void selectCategory(PreferencesCategory category) {
        if (category != null) {
            SortedSet<PreferencesPane> panes = category.getPanes();

            for (PreferencesPane pane : panes) {
                setResources(pane.getResources());
                loadSpecFileContents(pane.getSpecFile());
            }
        }
    }

    private void loadSpecFileContents(String specFileLocation) {
        try {
            Document spec = getSpecDocument(specFileLocation);
            addFormElements(spec.getDocumentElement());
        } catch (IllegalArgumentException e) {
            logger.log(Level.SEVERE, e.getMessage());
        }
    }

    /**
     * An element should be ignored if the requirements in its "requires" attribute
     *  are not met.
     */
    @Override
    protected boolean shouldIgnoreElement(Element xml) {
        boolean elementIsValid = false;

        if (!super.shouldIgnoreElement(xml)) {
            String requirements = xml.getAttribute(REQUIRES_TAG);

            elementIsValid =
                TemplateLoader.meetsPackageRequirement(requirements);

            if (!elementIsValid) {
                logger.log(Level.INFO, "Could not load preferences widget \"" +
                                        xml.getAttribute(ID_TAG) + "\". " +
                                        "Requirements \"" + requirements + "\" " +
                                        "not met.");
            }
        }

        return !elementIsValid;
    }

    private Document getSpecDocument(String specFileLocation) {
        Document document = null;
        URLConnection conn = TemplateLoader.resolveURLConnection(specFileLocation);

        if (conn != null) {
            try {
                document = XMLUtils.parse(conn.getInputStream());
            }
            catch (SAXException e) { document = null; }
            catch (IOException e) { document = null; }
        }

        if (document == null) {
            throw new IllegalArgumentException("Could not open specFile \"" +
                                               specFileLocation + "\"");
        }

        return document;
    }

    public JPanel getPanel() {
        return panel;
    }

}
