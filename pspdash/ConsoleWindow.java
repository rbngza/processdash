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

import java.awt.Dimension;
import java.io.*;
import javax.swing.*;


/** This simple class can capture the debugging output that was previously
 * sent to <code>System.out</code> and <code>System.err</code>, and display
 * it in a small dialog box instead.
 */
public class ConsoleWindow extends JFrame {

    JTextArea textArea;
    ConsoleOutputStream outputStream = null;
    PrintStream printStream = null;

    public ConsoleWindow() { this(true); }

    public ConsoleWindow(boolean install) {
        super("Console Output");
        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setCaretColor(textArea.getBackground());
        getContentPane().add(new JScrollPane(textArea));
        setSize(new Dimension(200, 200));
        if (install) install();
    }

    public OutputStream getOutputStream() {
        if (outputStream == null)
            outputStream = new ConsoleOutputStream();
        return outputStream;
    }
    public PrintStream getPrintStream() {
        if (printStream == null)
            printStream = new PrintStream(getOutputStream(), true);
        return printStream;
    }
    public void install() {
        System.setOut(getPrintStream());
        System.setErr(getPrintStream());
    }

    // WARNING - doesn't correctly translate bytes to chars.
    private class ConsoleOutputStream extends OutputStream {
        public void write(int b) {
            byte[] buf = new byte[1];
            buf[0] = (byte) b;
            textArea.append(new String(buf));
        }
        public void write(byte[] b, int off, int len) {
            textArea.append(new String(b, off, len));
        }
    }
}
