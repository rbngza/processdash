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
// Foundation, Inc., 59 Temple Place -Suite 330, Boston, MA  02111-1307, USA.
//
// The author(s) may be contacted at:
// OO-ALC/TISHD
// Attn: PSP Dashboard Group
// 6137 Wardleigh Road
// Hill AFB, UT 84056-5843
//
// E-Mail POC:  ken.raisor@hill.af.mil

package pspdash.data;

import pspdash.StringUtils;
import pspdash.PerlPool;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.NoSuchElementException;
import com.oroinc.text.perl.Perl5Util;
import com.oroinc.text.MalformedCachePatternException;

/** Perform cpp-like macro expansion on a stream of text.
 *
 * Currently expands <code>#define</code> macros. Macros with and without
 * arguments are supported. Expansion is done in traditional style, as
 * per "cpp -E -x -P -traditional". Also supports <code>#undef</code>.
 *
 * Also expands <b>limited</b> #ifdef and #ifndef macros.
 * <code>#ifdef identifier</code> and <code>#ifndef identifier</code> are
 * supported, as are the matching <code>#endif</code> and the optional
 * <code>#else</code>.  The more sophisticated <code>#if</code> and
 * <code>#elif</code> are NOT supported.
 *
 * Does NOT process #include directives, and does NOT filter out
 * comments.
 */
public class CppFilter {

    BufferedReader in;
    Perl5Util perl;
    HashMap substitutions;
    Stack ifStack;
    boolean ifSuppressionOn = false, disposeDisabled = false;
    int borrowedNewlineCount = 0;

    public CppFilter(BufferedReader in) {
        this.in = in;
        this.perl = PerlPool.get();
        this.substitutions = new HashMap();
        this.ifStack = null;
    }

    /** Construct a cpp filter on the input stream in.
     *  @param in the stream to filter.
     *  @param preDefinitions a set of #define instructions which should
     *      be processed before filtering the stream.
     */
    public CppFilter(BufferedReader in, String preDefinitions) {
        this(null);

        if (preDefinitions != null && preDefinitions.length() > 0) {
            this.in = new BufferedReader(new StringReader(preDefinitions));
            disposeDisabled = true;
            try {
                while (this.readLine() != null)
                    ;               // do nothing
            } catch (IOException ioe) {}
            disposeDisabled = false;
            ifStack = null;
            ifSuppressionOn = false;
            borrowedNewlineCount = 0;
        }

        this.in = in;
    }

    public void dispose() {
        if (disposeDisabled) return;

        PerlPool.release(perl);
        perl = null;
        in = null;
        substitutions = null;
        ifStack = null;
    }

    public String readLine() throws IOException {
        if (borrowedNewlineCount > 0) {
            borrowedNewlineCount--;
            return "";
        }
        String line = in.readLine();
        if (line == null) {
            dispose();
            return null;
        }
        if (line.startsWith(IF_DIRECTIVE)) {
            processIfDirective(line);           return "";
        } else if (line.startsWith(ENDIF_DIRECTIVE)) {
            processEndifDirective(line);        return "";
        } else if (line.startsWith(ELSE_DIRECTIVE)) {
            processElseDirective(line);         return "";
        } else if (ifSuppressionOn) {
            return "";
        } else if (line.startsWith(UNDEF_DIRECTIVE)) {
            processUndefDirective(line);        return "";
        } else if (line.startsWith(DEFINE_DIRECTIVE)) {
            while (line.endsWith("\\")) {
                // FIXME: rather than checking to see if the line ends
                // with a \, we instead need to check for an odd number of
                // slashes.  That is, "#define foo bar\\" should be treated
                // as a one-line macro translating "foo" to "bar\".
                line = line.substring(0, line.length()-1);
                String extra = in.readLine();
                if (extra == null) {
                    dispose();
                    break;
                }
                line = line + extra;
                borrowedNewlineCount++;
            }

            processDefineDirective(line);
            return "";
        } else
            return expandMacros(line);
    }

    private static final String IF_DIRECTIVE     = "#if";
    private static final String IFDEF_DIRECTIVE  = "#ifdef";
    private static final String IFNDEF_DIRECTIVE = "#ifndef";
    private static final String ELSE_DIRECTIVE   = "#else";
    private static final String ENDIF_DIRECTIVE  = "#endif";
    private static final String DEFINE_DIRECTIVE = "#define";
    private static final String UNDEF_DIRECTIVE  = "#undef";
    private static char DOLLAR_SIGN = '$';
    private static char DOLLAR_REPLACEMENT = '\u9999';

    public void processIfDirective(String line) {
        String identifier = null;
        boolean newSuppressionState = ifSuppressionOn;

        if (! ifSuppressionOn) {
            // Only enter this block if suppression is currently off. This is
            // because if an enclosing #if has suppressed output, there is
            // nothing this #if statement can do to turn it back on.
            boolean reverse = false;

            if (line.startsWith(IFDEF_DIRECTIVE)) {
                identifier =
                    getIdentifier(line.substring(IFDEF_DIRECTIVE.length()));
            } else if (line.startsWith(IFNDEF_DIRECTIVE)) {
                identifier =
                    getIdentifier(line.substring(IFNDEF_DIRECTIVE.length()));
                reverse = true;
            } else
                System.err.println
                    ("Ignoring malformed #if directive: " + line);

            if (identifier != null) {
                newSuppressionState = !substitutions.containsKey(identifier);
                if (reverse) newSuppressionState = !newSuppressionState;
            }
        }

        if (ifStack == null) ifStack = new Stack();
        ifStack.push(ifSuppressionOn ? Boolean.TRUE : Boolean.FALSE);
        ifSuppressionOn = newSuppressionState;
    }

    public void processEndifDirective(String line) {
        if (ifStack == null || ifStack.isEmpty())
            ifSuppressionOn = false;
        else {
            Boolean newSuppressionState = (Boolean) ifStack.pop();
            ifSuppressionOn = newSuppressionState.booleanValue();
        }
    }

    public void processElseDirective(String line) {
        if (ifStack == null || ifStack.isEmpty() ||
            ifStack.peek() == Boolean.FALSE)
            ifSuppressionOn = !ifSuppressionOn;
    }


    public void processUndefDirective(String line) {
        String identifier =
            getIdentifier(line.substring(UNDEF_DIRECTIVE.length()));
        substitutions.remove(identifier);
        macros = null;
    }


    private static final String getIdentifier(String lineRem) {
        try {
            StringTokenizer tok = new StringTokenizer(lineRem, " \t");
            return tok.nextToken();
        } catch (Exception e) {
            return "";
        }
    }

    public void processDefineDirective(String line) {
        line = line.substring(DEFINE_DIRECTIVE.length());
        StringTokenizer tok = new StringTokenizer(line, " \t");
        StringBuffer buffer = new StringBuffer();
        while (true) try {
            buffer.append(tok.nextToken());

            // If there are no '(' characters in the macro name, we're done.
            int leftParenPos = buffer.toString().indexOf('(');
            if (leftParenPos == -1) break;

            // Since there was a '(', we aren't done until we see ')'
            int rightParenPos = buffer.toString().indexOf(')');
            if (rightParenPos != -1) break;
        } catch (NoSuchElementException nsee) {
            System.err.println("Malformed directive: " +DEFINE_DIRECTIVE+line);
            return;
        }

        String macro = buffer.toString();
        // Remove spaces and tabs from the macro name - they shouldn't be there
        macro = StringUtils.findAndReplace(macro, " ", "");
        macro = StringUtils.findAndReplace(macro, "\t", "");

        String definition = "1";
        if (tok.hasMoreTokens()) {
            // Get the remainder of the line as the macro definition
            buffer.setLength(0);
            // skip over spaces and tabs to get the first part of the
            // definition.
            buffer.append(tok.nextToken());
            // Now change the delimiter to newline and get a token.
            // This will get everything left on the line, and will NOT
            // discard spaces/tabs.
            if (tok.hasMoreTokens()) buffer.append(tok.nextToken("\n"));

            definition = buffer.toString();
            definition = definition.replace(DOLLAR_SIGN, DOLLAR_REPLACEMENT);
        }

        StringBuffer regexp = new StringBuffer();

        int openParenPos = macro.indexOf('(');
        if (openParenPos == -1) {
            regexp.append("s\n\\b").append(macro).append("\\b\n")
                .append(definition).append("\ng");

        } else {
            tok = new StringTokenizer(macro, "(,)");
            String argName, separator;

            macro = tok.nextToken();
            regexp.append("s\n\\b").append(macro);
            separator = "\\(";
            int argPos = 0;
            while (tok.hasMoreTokens()) {
                argName = tok.nextToken();
                argPos++;
                regexp.append(separator).append("([^(,)]*)");
                separator = ",";
                definition = perl.substitute
                    ("s\n\\b" + argName + "\\b\n$" + argPos + "\ng",
                     definition);
            }
            regexp.append("\\)\n").append(definition).append("\ng");
        }
        substitutions.put(macro, regexp.toString());
        macros = null;
    }

    private ArrayList macros = null;

    public String expandMacros(String line) {
        if (macros == null)
            macros = new ArrayList(substitutions.values());
        boolean keepGoing = true, performedExpansion = false;
        Iterator i;
        String cmp;
        while (keepGoing && !macros.isEmpty()) {
            keepGoing = false;
            i = macros.iterator();
            while (i.hasNext()) try {
                line = perl.substitute((String) i.next(), cmp = line);
                if (!line.equals(cmp)) {
                    keepGoing = true;
                    performedExpansion = true;
                    i.remove();
                }
            } catch (MalformedCachePatternException mppe) {
                System.err.println(mppe);
            }
        }
        if (performedExpansion)
            macros = null;
        return line.replace(DOLLAR_REPLACEMENT, DOLLAR_SIGN);
    }

    private String escapeDollars(String str) {
        return pspdash.StringUtils.findAndReplace(str, "$", "\\$");
    }

    public static void main(String [] argv) {
        try {
            CppFilter f = new CppFilter
                (new BufferedReader(new FileReader(argv[0])));
            String line;
            while ((line = f.readLine()) != null)
                System.out.println(line);
        } catch (IOException ioe) {}
    }

}
