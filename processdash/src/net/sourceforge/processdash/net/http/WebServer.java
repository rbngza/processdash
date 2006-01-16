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

package net.sourceforge.processdash.net.http;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.net.*;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sourceforge.processdash.DashboardContext;
import net.sourceforge.processdash.InternalSettings;
import net.sourceforge.processdash.Settings;
import net.sourceforge.processdash.data.DoubleData;
import net.sourceforge.processdash.data.ImmutableDoubleData;
import net.sourceforge.processdash.data.SimpleData;
import net.sourceforge.processdash.data.StringData;
import net.sourceforge.processdash.data.repository.DataRepository;
import net.sourceforge.processdash.hier.DashHierarchy;
import net.sourceforge.processdash.i18n.Translator;
import net.sourceforge.processdash.net.cache.ObjectCache;
import net.sourceforge.processdash.security.DashboardPermission;
import net.sourceforge.processdash.templates.DashPackage;
import net.sourceforge.processdash.templates.TemplateLoader;
import net.sourceforge.processdash.ui.Browser;
import net.sourceforge.processdash.ui.web.TinyCGIBase;
import net.sourceforge.processdash.util.Base64;
import net.sourceforge.processdash.util.FileUtils;
import net.sourceforge.processdash.util.HTMLUtils;
import net.sourceforge.processdash.util.HTTPUtils;
import net.sourceforge.processdash.util.MD5;
import net.sourceforge.processdash.util.ResourcePool;
import net.sourceforge.processdash.util.StringUtils;

public class WebServer {

    private Vector serverSocketListeners = new Vector();

    Vector serverThreads = new Vector();
    URL [] roots = null;
    DataRepository data = null;

    Hashtable cgiLoaderMap = new Hashtable();
    Hashtable addOnLoaderMap = new Hashtable();
    Hashtable cgiCache = new Hashtable();
    MD5 md5 = new MD5();
    private static final int ALLOW_REMOTE_NEVER = 0;
    private static final int ALLOW_REMOTE_MAYBE = 1;
    private static final int ALLOW_REMOTE_ALWAYS = 2;
    private int allowingRemoteConnections = ALLOW_REMOTE_NEVER;
    private static int ALLOW_REMOTE_CONNECTIONS_SETTING = ALLOW_REMOTE_NEVER;
    private int port;
    private String startupTimestamp, startupTimestampHeader;
    private InheritableThreadLocal effectiveClientSocket =
        new InheritableThreadLocal();

    public static final String PROTOCOL = "HTTP/1.0";
    public static final String DEFAULT_TEXT_MIME_TYPE =
        "text/plain; charset=iso-8859-1";
    public static final String DEFAULT_BINARY_MIME_TYPE =
        "application/octet-stream";
    public static final String SERVER_PARSED_MIME_TYPE =
        "text/x-server-parsed-html";
    public static final String CGI_MIME_TYPE = "application/x-httpd-cgi";
    public static final String TIMESTAMP_HEADER = "Dash-Startup-Timestamp";
    public static final String PACKAGE_ENV_PREFIX = "Dash_Package_";
    public static final String LINK_SUFFIX = ".link";
    public static final String LINK_MIME_TYPE = "text/x-server-shortcut";
    public static final String CGI_LINK_PREFIX = "class:";
    public static final String DASHBOARD_PROTOCOL = "processdash";

    public static final DashboardPermission SET_PASSWORD_PERMISSION =
        new DashboardPermission("webServer.setPassword");
    public static final DashboardPermission GET_SOCKET_PERMISSION =
        new DashboardPermission("webServer.getSocket");
    public static final DashboardPermission GET_CGI_POOL_PERMISSION =
        new DashboardPermission("webServer.getCGIPool");
    public static final DashboardPermission CREATE_PERMISSION =
        new DashboardPermission("webServer.create");
    public static final DashboardPermission SET_ROOTS_PERMISSION =
        new DashboardPermission("webServer.setRoots");
    public static final DashboardPermission SET_HIERARCHY_PERMISSION =
        new DashboardPermission("webServer.setHierarchy");
    public static final DashboardPermission SET_DATA_PERMISSION =
        new DashboardPermission("webServer.setDataRepository");
    public static final DashboardPermission SET_CACHE_PERMISSION =
        new DashboardPermission("webServer.setCache");
    public static final DashboardPermission SET_DASHBOARD_CONTEXT =
        new DashboardPermission("webServer.setDashboardContext");
    public static final DashboardPermission SET_ALLOW_REMOTE_PERMISSION =
        new DashboardPermission("webServer.setAllowRemoteConnections");
    public static final DashboardPermission ADD_PORT_PERMISSION =
        new DashboardPermission("webServer.addPort");
    public static final DashboardPermission QUIT_PERMISSION =
        new DashboardPermission("webServer.quit");

    public static final String HTTP_ALLOWREMOTE_SETTING = "http.allowRemote";
    private static final DateFormat dateFormat =
                           // Tue, 05 Dec 2000 17:28:07 GMT
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    private static InetAddress LOCAL_HOST_ADDR, LOOPBACK_ADDR;
    private static final Properties mimeTypes = new Properties();
    private static final Hashtable DEFAULT_ENV = new Hashtable();
    private static final String CRLF = "\r\n";
    private static final int SCAN_BUF_SIZE = 4096;
    private static final String DASH_CHARSET = HTTPUtils.DEFAULT_CHARSET;
    static final String HEADER_CHARSET = DASH_CHARSET;
    private static String OUTPUT_CHARSET = DASH_CHARSET;

    private static final Logger logger =
        Logger.getLogger(WebServer.class.getName());

    static {
        try {
            DEFAULT_ENV.put("SERVER_SOFTWARE", "PROCESS_DASHBOARD");
            DEFAULT_ENV.put("SERVER_NAME", "localhost");
            DEFAULT_ENV.put("GATEWAY_INTERFACE", "CGI/1.1");
            DEFAULT_ENV.put("SERVER_ADDR", "127.0.0.1");
            DEFAULT_ENV.put("PATH_INFO", "");
            DEFAULT_ENV.put("PATH_TRANSLATED", "");
            DEFAULT_ENV.put("REMOTE_HOST", "localhost");
            DEFAULT_ENV.put("REMOTE_ADDR", "127.0.0.1");

            dateFormat.setTimeZone(new SimpleTimeZone(0, "GMT"));
            mimeTypes.load(WebServer.class
                           .getResourceAsStream("mime_types"));
        } catch (Exception e) { e.printStackTrace(); }
        try {
            LOCAL_HOST_ADDR = InetAddress.getLocalHost();
            LOOPBACK_ADDR   = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException uhe) {}
    }

    private ClassLoader getParentClassLoader(String url) {
        if (!url.startsWith("jar:")) return null;
        int pos = url.lastIndexOf("!/Templates");
        if (pos == -1) return null;
        url = url.substring(4, pos);
        synchronized (addOnLoaderMap) {
            ClassLoader result = (ClassLoader) addOnLoaderMap.get(url);
            if (result == null) try {
                result = new URLClassLoader(new URL[] { new URL(url) });
                addOnLoaderMap.put(url, result);
            } catch (Exception e) {}
            return result;
        }
    }

    public ResourcePool getCGIPool(String path) {
        // REFACTOR: This method should be removed and some other
        // mechanism should be created instead.
        GET_CGI_POOL_PERMISSION.checkPermission();
        return (ResourcePool) cgiCache.get(path);
    }

    private class TinyWebThread extends Thread implements HTTPHeaderWriter {

        Socket clientSocket = null;
        InputStream inputStream = null;
        BufferedReader in = null;
        OutputStream outputStream = null;
        Writer headerOut = null;
        boolean isRunning = false;
        Exception exceptionEncountered = null;
        boolean headerRead = false;
        Map env = null;
        String outputCharset = OUTPUT_CHARSET;

        String uri, method, protocol, id, path, query;

        private class TinyWebThreadException extends Exception {};

        public TinyWebThread(Socket clientSocket) {
            super("TinyWebThread-"+nextThreadNum());
            try {
                effectiveClientSocket.set(clientSocket);
                this.clientSocket = clientSocket;
                this.inputStream = new BufferedInputStream
                    (clientSocket.getInputStream());
                this.in = new BufferedReader
                    (new InputStreamReader(inputStream));
                this.outputStream = new BufferedOutputStream
                    (clientSocket.getOutputStream());
                this.headerOut = new BufferedWriter
                    (new OutputStreamWriter(outputStream, HEADER_CHARSET));
            } catch (IOException ioe) {
                this.inputStream = null;
            } finally {
                effectiveClientSocket.set(null);
            }
        }

        public TinyWebThread(String uri) {
            super("TinyWebThread-"+nextThreadNum());
            this.clientSocket = null;
            String request = "GET " + uri + " HTTP/1.0\r\n\r\n";
            this.inputStream = new ByteArrayInputStream(request.getBytes());
            this.in = new BufferedReader(new InputStreamReader(inputStream));
            this.outputStream = new ByteArrayOutputStream(1024);
            this.outputCharset = "UTF-8";
            try {
                this.headerOut = new BufferedWriter
                    (new OutputStreamWriter(outputStream, HEADER_CHARSET));
            } catch (UnsupportedEncodingException e) {
                // shouldn't happen.
            }
        }

        public byte[] getOutput() throws IOException {
            if (outputStream instanceof ByteArrayOutputStream) {
                run();
                if (exceptionEncountered instanceof IOException)
                    throw (IOException) exceptionEncountered;
                if (exceptionEncountered != null) {
                    IOException ioe = new IOException();
                    ioe.initCause(exceptionEncountered);
                    throw ioe;
                }

                return ((ByteArrayOutputStream) outputStream).toByteArray();

            } else
                return null;
        }

        public void dispose() {
            close();
            inputStream = null;
            outputStream = null;
            exceptionEncountered = null;
        }

        public synchronized void close() {
            if (isRunning)
                this.interrupt();
            serverThreads.remove(this);

            try {
                if (headerOut != null) { headerOut.flush(); headerOut.close(); }
                if (in  != null) in.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException ioe) {}

            headerOut = null;
            in = null;
            clientSocket = null;
            env = null;
        }

        public void run() {
            if (inputStream != null) {
                isRunning = true;
                try {
                    handleRequest();
                } catch (TinyWebThreadException twte) {
                    if (exceptionEncountered == null)
                        exceptionEncountered = twte;
                }
                isRunning = false;
            }

            close();
        }

        private void handleRequest() throws TinyWebThreadException {
            String line = null;
            try {
                // read and process the header line
                line = readLine(inputStream);
                logger.log(Level.FINE, "WebServer received request: {0}", line);
                StringTokenizer tok = new StringTokenizer(line, " ");
                method   = tok.nextToken();
                uri      = tok.nextToken();
                protocol = tok.nextToken();

                // Check for a valid method
                if (!"GET".equals(method) && !"POST".equals(method))
                    sendError(501, "Not Implemented",
                              "Unsupported Request Method" );

                // break the uri into hierarchy path, file path, and
                // query string; place the results in the appropriate
                // object-global variables.
                parseURI(uri);

                // only accept localhost requests.
                checkIP();

                // serve up the request.
                serveRequest();

            } catch (NoSuchElementException nsee) {
                sendError( 400, "Bad Request", "No request found." );
            } catch (TinyWebThreadException twte) {
                throw twte;
            } catch (Throwable t) {
                sendError( 500, "Internal Error", null, null, t);
            }
        }

        private void serveRequest() throws TinyWebThreadException, IOException
        {
            // open the requested file
            URLConnection conn = resolveURL(path);

            // decide what to do with the file based on its mime-type.
            String initial_mime_type =
                getMimeTypeFromName(conn.getURL().getFile());
            if (!Translator.isTranslating() &&
                SERVER_PARSED_MIME_TYPE.equals(initial_mime_type))
                servePreprocessedFile(conn, "text/html");
            else if (CGI_MIME_TYPE.equals(initial_mime_type))
                serveCGI(conn);
            else if (LINK_MIME_TYPE.equals(initial_mime_type))
                serveLink(conn);
            else
                servePlain(conn, initial_mime_type);
        }

        /** Break the URI into hierarchy path, file path, and query string.
         *
         * The results are placed into the object-global variables
         * "id", "path", and "query".
         *
         * URIs of the following forms are recognized (all may have
         * query strings appended): <PRE>
         *     /#####/regular/path
         *     /regular/path
         *     //regular/path
         *     /hierarchy/path//regular/path
         * </PRE> */
        private void parseURI(String uri) throws TinyWebThreadException {

            // extract the query string from the end.
            int pos = uri.indexOf('?');
            if (pos != -1) {
                query = uri.substring(pos + 1);
                uri = uri.substring(0, pos);
            }

            // remove "." and ".." directories from the uri.
            uri = canonicalizePath(uri);

            // ensure uri starts with a slash.
            if ( uri == null || !uri.startsWith("/") )
                sendError( 400, "Bad Request", "Bad filename." );

            // find the double slash that separates the id from the path.
            pos = uri.indexOf("//");
            if (pos >= 0) {
                id = uri.substring(0, pos);
                path = uri.substring(pos+2);
            } else try {
                 pos = uri.indexOf('/', 1);
                 id = uri.substring(1, pos);
                 Integer.parseInt(id);
                 path = uri.substring(pos + 1);
             } catch (Exception e) {
                 /* This block will be reached if the uri did not contain a
                  * second '/' character, or if the text between the initial
                  * and the second slash was not a number.  In these cases,
                  * we treat the uri as a simple file path, with no hierarchy
                  * path information.
                  */
                 id = "";
                 path = uri.substring(1);
             }
         }

         /** Resolve an absolute URL */
         private URLConnection resolveURL(String url)
             throws TinyWebThreadException
         {
             URLConnection result = WebServer.this.resolveURL(url);
             if (result == null)
                 sendError(404, "Not Found", "File '" + url + "' not found.");
             return result;
         }

         /* Currently unused.
            * Resolve a relative URL
         private URLConnection resolveURL(String url, URL base)
             throws TinyWebThreadException
         {
             if (url.charAt(0) == '/')
                 return resolveURL(url.substring(1));

             try {
                 URL u = checkSafeURL(new URL(base, url));
                 if (u != null) {
                     URLConnection result = u.openConnection();
                     result.connect();
                     return result;
                 }
             } catch (IOException a) { }

             sendError(404, "Not Found", "File '" + url + "' not found.");
             return null;        // this line will never be reached.
         }
         */

         private void parseHTTPHeaders() throws IOException {
             buildEnvironment();

             if (headerRead) return;

             // Parse the headers on the original http request and add to
             // the cgi script environment.
             String line, header;
             StringBuffer text = new StringBuffer();
             int pos;
             while (null != (line = readLine(inputStream))) {
                 if (line.length() == 0) break;
                 header = parseHeader(line,text).toUpperCase().replace('-','_');

                 if (header.equals("CONTENT_TYPE") ||
                     header.equals("CONTENT_LENGTH"))
                     env.put(header, text.toString());
                 else
                     env.put("HTTP_" + header, text.toString());
             }
             headerRead = true;
         }


        /** parse name=value pairs in the body of a server shortcut,
         * and add them to the query string */
        private void parseLinkParameters(BufferedReader linkContents)
            throws IOException
        {
            StringBuffer queryString = new StringBuffer();
            String param, name, val;
            int equalsPos;

            // read each line of the file.
            while ((param = linkContents.readLine()) != null) {
                equalsPos = param.indexOf('=');
                // ignore empty lines and lines starting with '='
                if (equalsPos == 0 || param.length() == 0)
                    continue;
                else if (equalsPos == -1)
                    // if there is no value, append the name only.
                    queryString.append("&")
                        .append(HTMLUtils.urlEncode(param.trim()));
                else {
                    // extract and append name and value.
                    name = param.substring(0, equalsPos);
                    val = param.substring(equalsPos+1);
                    if (val.startsWith("=")) val = val.substring(1);
                    queryString.append("&").append(HTMLUtils.urlEncode(name))
                        .append("=").append(HTMLUtils.urlEncode(val));
                }
            }

            if (queryString.length() != 0) {
                // merge the newly constructed query parameters with
                // the existing query string.
                String existingQuery = (String) env.get("QUERY_STRING");
                if (existingQuery != null)
                    queryString.append("&").append(existingQuery);

                // save the resulting query string into the environment
                // for this thread.
                query = queryString.toString().substring(1);
                env.put("QUERY_STRING", query);
            }
        }


        /** Handle a server shortcut. */
        private void serveLink(URLConnection conn)
            throws IOException, TinyWebThreadException
        {
            BufferedReader linkContents = new BufferedReader
                (new InputStreamReader(conn.getInputStream()));
            String redirectLocation = linkContents.readLine();
            if (redirectLocation.indexOf('?') != -1 ||
                redirectLocation.indexOf("//") != -1)
                sendError(500, "Internal Error",
                          "Malformed server shortcut.");

            // Parse the headers and build the environment.
            parseHTTPHeaders();
            parseLinkParameters(linkContents);

            linkContents.close();

            if (redirectLocation.startsWith(CGI_LINK_PREFIX)) {
                String className = redirectLocation
                    .substring(CGI_LINK_PREFIX.length()).trim();
                serveCGI(getScript(conn, className));
            } else {
                try {
                    // resolve the shortcut target as a URL relative
                    // to the path of the server shortcut.  Although
                    // this isn't strictly necessary if the target is
                    // absolute, performing these steps anyway helps
                    // to ensure that the URL is well-formed.
                    URL contextURL = new URL("http://unimportant/" + path);
                    URL uriURL = new URL(contextURL, redirectLocation);
                    path = uriURL.getFile().substring(1);
                } catch (IOException ioe) {
                    sendError(500, "Internal Error",
                              "Malformed server shortcut.");
                }
                serveRequest();
            }
        }

        /** Handle a cgi-like http request. */
        private void serveCGI(URLConnection conn)
            throws IOException, TinyWebThreadException
        {
            serveCGI(getScript(conn));
        }


        /** Handle a cgi-like http request. */
        private void serveCGI(TinyCGI script)
            throws IOException, TinyWebThreadException
        {
            // Parse the headers and build the environment.
            parseHTTPHeaders();

            // Run the cgi script, and capture the results.
            CGIOutputStream cgiOut = null;
            int mode = CGIOutputStream.NORMAL;
            try {
                if (script == null)
                    sendError(500, "Internal Error", "Couldn't load script." );

                if (script instanceof TinyCGIHighVolume)
                    mode = CGIOutputStream.LARGE;
                else if (script instanceof TinyCGIStreaming)
                    mode = CGIOutputStream.STREAMING;

                cgiOut = new CGIOutputStream
                    (this, outputStream, HEADER_CHARSET, mode);

                script.service(inputStream, cgiOut, env);
                cgiOut.finish();
            } catch (Exception cgie) {
                this.exceptionEncountered = cgie;
                if (cgiOut != null) cgiOut.cleanup();
                if (clientSocket == null) {
                    return;
                } else if (cgie instanceof TinyCGIException) {
                    TinyCGIException tce = (TinyCGIException) cgie;
                    sendError(tce.getStatus(), tce.getTitle(), tce.getText(),
                              tce.getOtherHeaders());
                } else {
                    sendError(500, "CGI Error", null, null, cgie);
                }
            } finally {
                if (script != null) doneWithScript(script);
            }
        }


        /** Create an environment for use by a CGI script or a server
         *  preprocessed file */
        private void buildEnvironment() {
            if (env != null) return;

            // Create the environment for the cgi script.
            env = new HashMap(DEFAULT_ENV);
            env.put("SERVER_PROTOCOL", protocol);
            env.put("REQUEST_METHOD", method);
            env.put("PATH_INFO", id);
            if (id != null && id.startsWith("/")) {
                env.put("PATH_TRANSLATED", HTMLUtils.urlDecode(id));
                env.put("SCRIPT_PATH", id + "//" + path);
            } else {
                if (data != null) env.put("PATH_TRANSLATED", data.getPath(id));
                env.put("SCRIPT_PATH", "/" + id + "/" + path);
            }
            env.put("SCRIPT_NAME", "/" + path);
            env.put("REQUEST_URI", uri);
            env.put("QUERY_STRING", query);
            Socket effectiveClientSocket = getEffectiveClientSocket();
            if (effectiveClientSocket != null) {
                env.put("REMOTE_PORT",
                        Integer.toString(effectiveClientSocket.getPort()));
                InetAddress addr = effectiveClientSocket.getInetAddress();
                env.put("REMOTE_HOST", addr.getHostName());
                env.put("REMOTE_ADDR", addr.getHostAddress());
                addr = effectiveClientSocket.getLocalAddress();
                env.put("SERVER_NAME", addr.getHostName());
                env.put("SERVER_ADDR", addr.getHostAddress());
            }
            env.put(TinyCGI.TINY_WEB_SERVER, WebServer.this);
        }

        private Socket getEffectiveClientSocket() {
            if (clientSocket != null)
                return clientSocket;
            else
                return (Socket) effectiveClientSocket.get();
        }

        private class CGIPool extends ResourcePool {
            Class cgiClass;
            CGIPool(String name, Class c) throws IllegalArgumentException {
                super(name);
                if (!TinyCGI.class.isAssignableFrom(c))
                    throw new IllegalArgumentException
                        (c.getName() + " does not implement "+
                         "net.sourceforge.processdash.net.http.TinyCGI");
                cgiClass = c;
            }
            protected Object createNewResource() {
                try {
                    return cgiClass.newInstance();
                } catch (Throwable t) {
                    return null;
                }
            }
        }

        /** Get an appropriate CGILoader for loading a class from the given
         * connection.
         */
        private ClassLoader getLoader(URLConnection conn) throws MalformedURLException {
            // All the cgi classes in a given directory are loaded by
            // a common classloader.  To find the classloader for this
            // class, we first extract the "directory" portion of the url
            // for this connection.
            String path = conn.getURL().toExternalForm();
            int end = path.lastIndexOf('/');
            path = path.substring(0, end+1);
            URL[] pathURL = new URL[] { new URL(path) };

            synchronized (cgiLoaderMap) {
                ClassLoader result = (ClassLoader) cgiLoaderMap.get(path);
                if (result == null) {
                    ClassLoader parent = getParentClassLoader(path);
                    if (parent == null)
                        result = new URLClassLoader(pathURL);
                    else
                        result = new URLClassLoader(pathURL, parent);
                    cgiLoaderMap.put(path, result);
                }
                return result;
            }
        }


        /** Get a TinyCGI script for a given uri path.
         * @param conn the URLConnection to the ".class" file for the script.
         *   TinyCGI scripts must be java classes in the root package (like
         *   the servlets API).
         * @return an instantiated TinyCGI script, or null on error.
         */
        private TinyCGI getScript(URLConnection conn) {
            return getScript(conn, null);
        }
        private TinyCGI getScript(URLConnection conn, String className) {
            CGIPool pool = null;
            synchronized (cgiCache) {
                pool = (CGIPool) cgiCache.get(path);
                if (pool == null) try {
                    ClassLoader cgiLoader = getLoader(conn);
                    Class clz = null;
                    if (className == null) {
                        className = conn.getURL().getFile();
                        int beg = className.lastIndexOf('/');
                        int end = className.indexOf('.', beg);
                        className = className.substring(beg + 1, end);
                    }
                    clz = cgiLoader.loadClass(className);
                    pool = new CGIPool(path, clz);
                    cgiCache.put(path, pool);
                } catch (Throwable t) {
                    // TODO: temporary fix to allow the old PSP for Engineers
                    // add-on to work with v1.7
                    String file = conn.getURL().getFile();
                    if (file != null && file.endsWith("/sizeest.class")) {
                        pool = new CGIPool(path, net.sourceforge.processdash.ui.web.psp.SizeEstimatingTemplate.class);
                        cgiCache.put(path, pool);
                    } else {
                        return null;
                    }
                }
            }
            return (TinyCGI) pool.get();
        }
        private void doneWithScript(Object script) {
            CGIPool pool = (CGIPool) cgiCache.get(path);
            if (pool != null)
                pool.release(script);
        }


        /** Parse an HTTP header (of the form "Header: value").
         *
         *  @param line The HTTP header line.
         *  @param value The value of the header found will be placed in
         *               this StringBuffer.
         *  @return The name of the header found.
         */
        private String parseHeader(String line, StringBuffer value) {
            int len = line.length();
            int pos = 0;
            while (pos < len  &&  ": \t".indexOf(line.charAt(pos)) == -1)
                pos++;
            String result = line.substring(0, pos);
            while (pos < len  &&  ": \t".indexOf(line.charAt(pos)) != -1)
                pos++;
            value.setLength(0);
            int end = line.indexOf('\r', pos);
            if (end == -1) end = line.indexOf('\n', pos);
            if (end == -1) end = line.length();
            value.append(line.substring(pos, end));
            return result;
        }


        /** Serve a plain HTTP request */
        private void servePlain(URLConnection conn, String mime_type)
            throws TinyWebThreadException, IOException
        {
            byte[] buffer = new byte[SCAN_BUF_SIZE];
            InputStream content = conn.getInputStream();
            int numBytes = -1;
            if (content == null)
                sendError( 500, "Internal Error", "Couldn't read file." );

            boolean translate =
                Translator.isTranslating() && !nonTranslatedPath(path);

            boolean preprocess = false;
            if (SERVER_PARSED_MIME_TYPE.equals(mime_type)) {
                preprocess = true;
                mime_type = "text/html";
            }

            if (mime_type == null ||
                (preprocess && translate) ||
                mime_type.startsWith("text/")) {

                PushbackInputStream pb = new PushbackInputStream
                    (content, SCAN_BUF_SIZE + 1);
                numBytes = pb.read(buffer);
                if (numBytes < 1)
                    sendError( 500, "Internal Error", "Couldn't read file." );
                pb.unread(buffer, 0, numBytes);
                content = pb;

                if (mime_type == null)
                    mime_type = getDefaultMimeType(buffer, numBytes);

                if ((preprocess && translate) ||
                     mime_type.startsWith("text/")) {
                    String scanBuf = new String
                        (buffer, 0, numBytes, DASH_CHARSET);
                    translate =
                        translate && mime_type.startsWith("text/html") &&
                        !containsNoTranslateTag(scanBuf);
                    preprocess =
                        preprocess || containsServerParseOverride(scanBuf);
                }
            }

            if (preprocess)
                servePreprocessedFile(content, translate, mime_type);

            else if (translate && mime_type.startsWith("text/html"))
                serveTranslatedFile(content, mime_type);

            else {
                discardHeader();
                sendHeaders(200, "OK", mime_type, conn.getContentLength(),
                            conn.getLastModified(), null);

                while (-1 != (numBytes = content.read(buffer))) {
                    outputStream.write(buffer, 0, numBytes);
                }
                outputStream.flush();
                content.close();
            }
        }

        private boolean containsServerParseOverride(String scanBuf) {
            return (scanBuf.indexOf(SERVER_PARSE_OVERRIDE) != -1);
        }
        private static final String SERVER_PARSE_OVERRIDE =
            "<!--#server-parsed";

        private boolean containsNoTranslateTag(String scanBuf) {
            return (scanBuf.indexOf(NO_TRANSLATE_TAG) != -1);
        }
        private static final String NO_TRANSLATE_TAG =
            "<!--#do-not-translate";

        private boolean nonTranslatedPath(String path) {
            return path.startsWith("help/");
        }


        /** Serve up a server-parsed html file. */
        private void servePreprocessedFile(URLConnection conn, String mimeType)
            throws TinyWebThreadException, IOException
        {
            String content = preprocessTextFile(conn.getInputStream(), false);
            byte[] bytes = content.getBytes(outputCharset);
            String contentType = HTTPUtils.setCharset(mimeType, outputCharset);
            sendHeaders(200, "OK", contentType, bytes.length, -1, null);
            outputStream.write(bytes);
        }


        /** Serve up a server-parsed html file. */
        private void servePreprocessedFile(InputStream in, boolean translate,
                                           String mimeType)
            throws TinyWebThreadException, IOException
        {
            String content = preprocessTextFile(in, translate);
            byte[] bytes = content.getBytes(outputCharset);
            String contentType = HTTPUtils.setCharset(mimeType, outputCharset);
            sendHeaders(200, "OK", contentType, bytes.length, -1, null);
            outputStream.write(bytes);
        }

        private String preprocessTextFile(InputStream in, boolean translate)
            throws TinyWebThreadException, IOException
        {
            String content = null;
            if (translate) {
                Reader r = Translator.translate
                    (new InputStreamReader(in, DASH_CHARSET));
                StringBuffer buf = new StringBuffer();
                int c;
                while ((c = r.read()) > 0)
                    buf.append((char) c);
                content = buf.toString();

            } else {
                byte[] rawContent = FileUtils.slurpContents(in, true);
                content = new String(rawContent, DASH_CHARSET);
            }

            parseHTTPHeaders();

            HTMLPreprocessor p =
                new HTMLPreprocessor(WebServer.this, data, env);
            return p.preprocess(content);
        }

        private void serveTranslatedFile(InputStream content, String mime_type)
            throws IOException
        {
            discardHeader();
            Reader fileReader = new InputStreamReader(content, DASH_CHARSET);
            Reader translatedReader = Translator.translate(fileReader);
            Writer output = new OutputStreamWriter(outputStream, outputCharset);
            mime_type = HTTPUtils.setCharset(mime_type, outputCharset);

            sendHeaders(200, "OK", mime_type, -1, -1, null);

            char[] buf = new char[4096];
            int numChars;
            while (-1 != (numChars = translatedReader.read(buf))) {
                output.write(buf, 0, numChars);
            }
            output.flush();
            content.close();
        }


        /** read and discard the rest of the request header from inputStream */
        private void discardHeader() throws IOException {
            if (headerRead) return;

            String line;
            while (null != (line = readLine(inputStream)))
                if (line.length() == 0)
                    break;

            headerRead = true;
        }


        /** ensure that requests are originating from the local machine. */
        private void checkIP() throws TinyWebThreadException, IOException {
            Socket effectiveClientSocket = getEffectiveClientSocket();

            // unconditionally allow internal requests.
            if (effectiveClientSocket == null) return;

            // unconditionally serve up items in the root directory
            // (This includes "style.css", "DataApplet.*", "data.js")
            // and the Images/ directory.
            if (path.indexOf('/') == -1 || path.startsWith("Images/")) return;

            // unconditionally serve requests that originate from the
            // local host.
            InetAddress remoteIP = effectiveClientSocket.getInetAddress();
            if (remoteIP.equals(LOOPBACK_ADDR) ||
                remoteIP.equals(LOCAL_HOST_ADDR)) return;

            parseHTTPHeaders();
            String path = (String) env.get("PATH_TRANSLATED");
            if (path == null) path = "";
            do {
                if (checkPassword(path)) return;
                path = chopPath(path);
            } while (path != null);

            if (allowingRemoteConnections != ALLOW_REMOTE_ALWAYS)
                sendErrorOrAuth(403, "Forbidden", "Not accepting " +
                                "requests from remote IP addresses ." );
        }
        private String chopPath(String path) {
            if (path == null) return null;
            int slashPos = path.lastIndexOf('/');
            if (slashPos == -1)
                return null;
            else
                return path.substring(0, slashPos);
        }
        private boolean checkPassword(String path)
            throws TinyWebThreadException
        {
            if (data == null)
                return false;
            String dataName = path + "/_Password_";
            Object value = data.getValue(dataName);
            if (value == null)
                return false;

            if (value instanceof DoubleData) {
                if (0 == ((DoubleData) value).getInteger())
                    sendErrorOrAuth(403, "Forbidden", "Not accepting " +
                                    "requests from remote IP addresses ." );
                else
                    return true;
            }

            if (value instanceof StringData) {
                String val = ((StringData) value).getString();
                sawPassword = true;

                if (getUserCredential() != null &&
                    val.indexOf(getUserCredential()) != -1) {
                    env.put("AUTH_USER", getAuthUser());
                    return true;
                }
                if (getGuestCredential() != null &&
                    val.indexOf(getGuestCredential()) != -1) {
                    env.put("AUTH_USER", "anonymous");
                    return true;
                }
            }

            return false;
        }
        private boolean sawPassword = false;
        private void sendErrorOrAuth(int status, String title, String text)
            throws TinyWebThreadException {
            if (sawPassword)
                sendError(401, "Unauthorized", "Authorization required.",
                          "WWW-Authenticate: Basic realm=\"Process " +
                          "Dashboard\""+CRLF);
            else
                sendError(status, title, text, null);
        }
        private String userCredential = null;
        private String guestCredential = null;
        private String wwwUser = null;
        private String getAuthUser() {
            authenticate(); return wwwUser; }
        private String getUserCredential() {
            authenticate(); return userCredential; }
        private String getGuestCredential() {
            authenticate(); return guestCredential; }
        private void authenticate() {
            if (wwwUser != null) return; // already authenticated
            String credentials = (String) env.get("HTTP_AUTHORIZATION");
            if (credentials == null) return; // no password given by client
            StringTokenizer tok = new StringTokenizer(credentials);
            try {
                tok.nextToken(); // "Basic"
                credentials = Base64.decode(tok.nextToken());
            } catch (Exception e) { return; }
            int colonPos = credentials.indexOf(':');
            if (colonPos == -1) return;
            wwwUser = credentials.substring(0, colonPos);
            String wwwPassword = credentials.substring(colonPos+1);

            String md5hash;
            synchronized (md5) {
                md5.Init();
                md5.Update(wwwPassword);
                md5hash = md5.asHex();
            }
            userCredential = wwwUser + ":" + md5hash;
            guestCredential = "*:" + md5hash;
        }


        /** Send an HTTP error page.
         *
         * @throws TinyWebThreadException automatically and unequivocally
         *    after printing the error page.  (This greatly simplifies
         *    TinyWebThread control logic.  Anytime an exception or
         *    other error is found, just call this method;  an error page
         *    will be generated, and then an exception will be thrown.
         *    TinyWebThreadExceptions are caught in only one place, at
         *    the top level run() method.)
         */
        private void sendError(int status, String title, String text )
            throws TinyWebThreadException {
            sendError(status, title, text, null, null);
        }
        private void sendError(int status, String title, String text,
                               String otherHeaders )
            throws TinyWebThreadException {
            sendError(status, title, text, otherHeaders, null);
        }
        private void sendError(int status, String title, String text,
                               String otherHeaders, Throwable cause )
            throws TinyWebThreadException
        {
            TinyWebThreadException result = new TinyWebThreadException();
            if (cause != null)
                result.initCause(cause);
            try {
                if (exceptionEncountered == null)
                    exceptionEncountered = result;
                discardHeader();
                sendHeaders( status, title, "text/html", -1, -1, otherHeaders);
                if (cause != null) {
                    StringWriter w = new StringWriter();
                    cause.printStackTrace(new PrintWriter(w));
                    if (text == null)
                        text = "Error encountered:<PRE>" + w + "</PRE>";
                    else
                        text = text + "<!--\n" + w + "\n-->";
                }
                headerOut.write("<HTML><HEAD><TITLE>" + status + " " + title +
                                "</TITLE></HEAD>\n<BODY BGCOLOR=\"#cc9999\"><H4>" +
                                status + " " + title + "</H4>\n" +
                                text + "\n" + "</BODY></HTML>\n");
                headerOut.flush();
            } catch (IOException ioe) {
            }
            throw result;
        }

        private boolean headersSent = false;
        public void sendHeaders(int status, String title, String mimeType,
                                long length, long mod, String otherHeaders )
            throws IOException
        {
            if (headersSent) return;

            headersSent = true;

            Date now = new Date();

            headerOut.write(PROTOCOL + " " + status + " " + title + CRLF);
            headerOut.write("Server: localhost" + CRLF);
            headerOut.write("Date: " + dateFormat.format(now) + CRLF);
            if (mimeType != null)
                headerOut.write("Content-Type: " + mimeType + CRLF);
            // headerOut.write("Accept-Ranges: bytes" + CRLF);
            if (mod > 0)
                headerOut.write("Last-Modified: " +
                                dateFormat.format(new Date(mod)) + CRLF);
            if (length >= 0)
                headerOut.write("Content-Length: " + length + CRLF);
            headerOut.write(startupTimestampHeader + CRLF);
            if (otherHeaders != null)
                headerOut.write(otherHeaders);
            headerOut.write("Connection: close" + CRLF + CRLF);
            headerOut.flush();
        }

        private String getMimeTypeFromName(String name) {
            // locate file extension and lookup associated mime type.
            int pos = name.lastIndexOf('.');
            if (pos >= 0) {
                String suffix = name.substring(pos).toLowerCase();
                if (suffix.equals(".class") &&
                    name.indexOf("/IE/") == -1 &&
                    name.indexOf("/NS/") == -1)
                    // Eventually, we may want a better method of deciding
                    // between cgi scripts and other class files.  Perhaps
                    // a special ID (like 0) can flag an uninterpreted .class
                    // file?
                    return CGI_MIME_TYPE;
                else
                    return (String) mimeTypes.get(suffix);
            } else
                return null;
        }

        /** Check to see if the data is text or binary, and return the
         *  appropriate default mime type. */
        private String getDefaultMimeType(byte [] buffer, int numBytes)
        {
            while (numBytes-- > 0)
                if (Character.isISOControl((char) buffer[numBytes]) &&
                    "\t\r\n\f".indexOf((char) buffer[numBytes]) == -1)
                    return DEFAULT_BINARY_MIME_TYPE;

            return DEFAULT_TEXT_MIME_TYPE;
        }
    }

    private boolean isDirectory(URL u) {
        String urlString = u.toString();
        if (!urlString.startsWith("file:/")) return false;
        String filename = urlString.substring(5);
        filename = HTMLUtils.urlDecode(filename);
        File file = new File(filename);
        return file.isDirectory();
    }

    /** Canonicalize a path through the removal of directory changes
     * made by occurences of &quot;..&quot; and &quot;.&quot;.
     *
     * @return a canonical path, or null on error.
     */
    private static String canonicalizePath(String path) {
        if (path == null) return null;
        path = path.trim();

        int pos, beg;
        while (true) {

            if (path.startsWith("../") || path.startsWith("/../"))
                return null;

            else if (path.startsWith("./"))
                path = path.substring(2);

            else if ((pos = path.indexOf("/./")) != -1)
                path = path.substring(0, pos) + path.substring(pos+2);

            else if (path.endsWith("/."))
                path = path.substring(0, path.length()-2);

            else if ((pos = path.indexOf("/../", 1)) != -1) {
                beg = path.lastIndexOf('/', pos-1);
                if (beg == -1)
                    path = path.substring(pos+4);
                else
                    path = path.substring(0, beg) + path.substring(pos+3);

            } else if (path.endsWith("/..")) {
                beg = path.lastIndexOf('/', path.length()-4);
                if (beg == -1)
                    return null;
                else
                    path = path.substring(0, beg+1);

            } else
                return path;
        }
    }

    private URLConnection resolveURL(String url) {
        url = canonicalizePath(url);
        if (url == null) return null;

        List variations = getVariations(url);
        URL u;
        URLConnection result;
        for (Iterator v = variations.iterator(); v.hasNext();) {
            url = (String) v.next();
            for (int i = 0;  i < roots.length;  i++) try {
                u = new URL(roots[i], url);

                // don't accept resolved URLs that point to a directory
                if (isDirectory(u)) continue;

                // System.out.println("trying url: " + u);
                result = u.openConnection();
                // System.out.println("connection opened.");
                result.connect();
                // System.out.println("connection connected.");
                // System.out.println("Using URL: " + u);
                return result;
            } catch (IOException ioe) { }
        }

        return null;
    }

    private List getVariations(String url) {
        List result = new LinkedList();
        String localeVariant = getLocaleVariant(url);
        if (localeVariant != null) {
            result.add(localeVariant);
            result.add(localeVariant + LINK_SUFFIX);
        }
        result.add(url);
        result.add(url + LINK_SUFFIX);
        return result;
    }

    private String getLocaleVariant(String url) {
        if (!Translator.isTranslating())
            return null;

        String lang = Locale.getDefault().getLanguage();
        int lastSlashPos = url.lastIndexOf('/');
        int dotPos = url.indexOf('.', lastSlashPos+1);
        if (dotPos == -1)
            return url + "_" + lang;
        else
            return url.substring(0, dotPos) + "_" + lang +
                url.substring(dotPos);
    }

    /** Calculate the user credential that would work for an http
     * Authorization field.
     */
    public static String calcCredential(String user, String password) {
        return "Basic " + Base64.encode(user + ":" + password);
    }


    /** Save a password setting in the data repository.
     *
     * Adjusts the password settings for the given prefix.
     * Normally, adds the username/password pair to the password table,
     *     or changes the existing password for that username.
     * If user is null and password is non-null, discards all password
     *     information and marks this node as "forbidden".
     * If user is null and password is null, discards all password
     *     information and marks this node as "unprotected".
     */
    public static void setPassword(DataRepository data, String prefix,
                                   String user, String password) {
        SET_PASSWORD_PERMISSION.checkPermission();

        String dataName = DataRepository.createDataName(prefix,  "_Password_");

        if (user == null) {
            DoubleData val;
            if (password == null)
                val = ImmutableDoubleData.TRUE;
            else
                val = ImmutableDoubleData.FALSE;
            data.putValue(dataName, val);
            return;
        }


        Object val = data.getSimpleValue(dataName);
        HashMap passwords = new HashMap();
        if (val instanceof StringData) try {
            StringTokenizer tok = new StringTokenizer
                (((StringData) val).format(), ";");
            while (tok.hasMoreTokens()) {
                String credential = tok.nextToken();
                int colonPos = credential.indexOf(':');
                String credUser = credential.substring(0, colonPos);
                String passHash = credential.substring(colonPos+1);
                passwords.put(credUser, passHash);
            }
        } catch (Exception e) { e.printStackTrace(); }

        MD5 md5 = new MD5();
        md5.Init();
        md5.Update(password);
        passwords.put(user, md5.asHex());

        StringBuffer passwordList = new StringBuffer();
        Iterator i = passwords.entrySet().iterator();
        Map.Entry e;
        while (i.hasNext()) {
            e = (Map.Entry) i.next();
            passwordList.append(e.getKey()).append(":")
                .append(e.getValue()).append(";");
        }
        data.putValue(dataName, StringData.create(passwordList.toString()));
    }

    public static boolean arePasswordsPresent(DataRepository data) {
        for (Iterator i = data.getKeys(); i.hasNext();) {
            String dataName = (String) i.next();
            if (dataName.endsWith("/_Password_")) {
                SimpleData val = data.getSimpleValue(dataName);
                if (val != null && val.test()) {
                    return true;
                }
            }
        }
        return false;
    }


    /** Encode HTML entities in the given string, and return the result. */
    public static String encodeHtmlEntities(String str) {
        str = StringUtils.findAndReplace(str, "&",  "&amp;");
        str = StringUtils.findAndReplace(str, "<",  "&lt;");
        str = StringUtils.findAndReplace(str, ">",  "&gt;");
        str = StringUtils.findAndReplace(str, "\"", "&quot;");
        return str;
    }

    public static String urlEncodePath(String path) {
        path = HTMLUtils.urlEncode(path);
        path = StringUtils.findAndReplace(path, "%2F", "/");
        path = StringUtils.findAndReplace(path, "%2f", "/");
        return path;
    }

    public static String getHostName() {
        String result = Settings.getVal("http.hostname");
        if (result != null && result.length() > 0)
            return result;

        if (ALLOW_REMOTE_CONNECTIONS_SETTING != ALLOW_REMOTE_NEVER) try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException uhe) {}

        return "localhost";
    }

    public static Map getMimeTypeMap() {
        return Collections.unmodifiableMap(mimeTypes);
    }

    /** Utility routine: slurp an entire file from an InputStream.
     * 
     * @deprecated Use {@link FileUtils#slurpContents(InputStream, boolean)} instead.
     */
    public static byte[] slurpContents(InputStream in, boolean close)
        throws IOException
    {
        return FileUtils.slurpContents(in, close);
    }


    /** Utility routine: readLine from an InputStream.
     *
     * This is needed because the only readLine method in the Java library
     * is in the BufferedReader class.  A BufferedReader will likely grab
     * more bytes than we necessarily want it to.
     *
     * Although this method is not performing any character encoding,
     * Hopefully we're okay because we're just parsing plaintext HTTP headers.
     */
    static String readLine(InputStream in) throws IOException {
        return readLine(in, false);
    }
    static String readLine(InputStream in, boolean keepCRLF)
        throws IOException
    {
        StringBuffer result = new StringBuffer();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (keepCRLF) result.append((char) c);
                break;
            } else if (c == '\r') {
                if (keepCRLF) result.append((char) c);
            } else {
                result.append((char) c);
            }
        }

        return result.toString();
    }

    /** Utility routine: readLine from a byte array. The carraige return
     * and linefeed that terminate the line are returned as part of the
     * final result.
     *
     * Although this method is not performing any character encoding,
     * Hopefully we're okay because we're just parsing plaintext HTTP headers.
     */
    static String readLine(byte[] buf, int beg) throws IOException {
        int p = beg;
        // find an initial sequence of non-line-terminating charaters
        while (p < buf.length && buf[p] != '\r' && buf[p] != '\n') p++;
        // skip over up to two line termination characters.
        if (p < buf.length && buf[p] == '\r') p++;
        if (p < buf.length && buf[p] == '\n') p++;
        return new String(buf, beg, p-beg);
    }

    /** Perform an internal http request for the caller.
     *
     * @param uri the absolute uri of a resource on this server (e.g.
     *     <code>/0980/help/about.htm?foo=bar</code>)
     * @param skipHeaders if true, the generated response headers are discarded
     * @return the response generated by performing the http request.
     */
    public byte[] getRequest(final String uri, boolean skipHeaders)
        throws IOException
    {
        if (internalRequestNesting > 50)
            throw new IOException("Infinite recursion - aborting.");

        byte[] result = null;
        try {
            result = (byte[]) AccessController.doPrivileged
                (new PrivilegedExceptionAction() {
                    public Object run() throws Exception {
                        return getRequestProtectedImpl(uri);
                    }});
        } catch (PrivilegedActionException e) {
            if (e.getException() instanceof IOException)
                throw (IOException) e.getException();
            else if (e.getException() instanceof RuntimeException)
                throw (RuntimeException) e.getException();
            else {
                IOException ioe = new IOException(e.getMessage());
                ioe.initCause(e);
                throw ioe;
            }
        }

        if (!skipHeaders)
            return result;
        else {
            int headerLen = HTTPUtils.getHeaderLength(result);
            byte [] contents = new byte[result.length - headerLen];
            System.arraycopy(result, headerLen, contents, 0, contents.length);
            return contents;
        }
    }
    private byte[] getRequestProtectedImpl(String uri) throws IOException {
        synchronized(this) { internalRequestNesting++; }
        TinyWebThread t = new TinyWebThread(uri);
        byte [] result = null;
        try {
            result = t.getOutput();
        } finally {
            synchronized(this) { internalRequestNesting--; }
            if (t != null) t.dispose();
        }
        return result;
    }
    private volatile int internalRequestNesting = 0;

    /** Perform an internal http request for the caller.
     * @param context the uri of an original request within this web server
     * @param uri a uri to fetch the contents of.  If it does not begin with
     *     a slash, it will be interpreted relative to <code>context</code>.
     * @param skipHeaders if true, the generated response headers are discarded
     * @return the response generated by performing the http request.
     */
    public byte[] getRequest(String context, String uri, boolean skipHeaders)
        throws IOException
    {
        if (!uri.startsWith("/")) {
            URL contextURL = new URL("http://unimportant" + context);
            URL uriURL = new URL(contextURL, uri);
            uri = uriURL.getFile();
        }
        return getRequest(uri, skipHeaders);
    }

    public String getRequestAsString(String uri) throws IOException {
        byte[] response = getRequest(uri, false);
        int headerLen = HTTPUtils.getHeaderLength(response);
        String header = new String(response, 0, headerLen, HEADER_CHARSET);

        String charset = HTTPUtils.DEFAULT_CHARSET;
        String contentType = HTTPUtils.getContentType(header);
        if (contentType != null)
            charset = HTTPUtils.getCharset(contentType);

        return new String
            (response, headerLen, response.length - headerLen, charset);
    }

    /** Perform an internal http request and return raw results.
     *
     * Server-parsed HTML files are returned verbatim, and
     * cgi scripts are returned as binary streams.
     */
    public byte[] getRawRequest(final String uri)
        throws IOException
    {
        return (byte[]) AccessController.doPrivileged(new PrivilegedAction() {
            public Object run() {
                return getRawRequestImpl(uri);
            }
        });
    }

    private byte[] getRawRequestImpl(String uri) {
        try {
            if (uri.startsWith("/"))
                uri = uri.substring(1);
            URLConnection conn = resolveURL(uri);
            if (conn == null) return null;

            InputStream in = conn.getInputStream();
            byte[] result = FileUtils.slurpContents(in, true);
            return result;
        } catch (IOException ioe) {
            return null;
        }
    }

    /** Clear the classloader caches, so classes will be reloaded.
     */
    public void clearClassLoaderCaches() {
        addOnLoaderMap.clear();
        cgiLoaderMap.clear();
        cgiCache.clear();
    }

    private void writePackagesToDefaultEnv() {
        Iterator i = DEFAULT_ENV.keySet().iterator();
        while (i.hasNext())
            if (((String) i.next()).startsWith(PACKAGE_ENV_PREFIX))
                i.remove();
        i = TemplateLoader.getPackages().iterator();
        while (i.hasNext()) {
            DashPackage pkg = (DashPackage) i.next();
            DEFAULT_ENV.put(PACKAGE_ENV_PREFIX + pkg.id, pkg.version);
        }
    }

    /** Parse the HTTP headers in text, and put them into the dest map.
     *  Returns the number of bytes of header information found and parsed,
     *  so the body of the HTTP message will begin at that char in text.
     *
     * @param text an HTTP message
     * @param dest a Map where the parsed headers should be stored. The keys
     * in the map will be field names, converted to upper case.  The values
     * will be field values.  If a given header is repeated, the values will
     * be concatenated into a comma separated list, as suggested in RFC2616.
     * @return the number of bytes parsed out of text
     *
     * This isn't 100% compliant with RFC2616; it doesn't allow header values
     * to be split across multiple lines.
    public int getHeaders(byte [] text, Map dest) {
        String line, header, oldVal;
        StringBuffer value = new StringBuffer();
        int pos = 0;
        while (pos < text.length) {
            line = readLine(text, pos);
            pos += line.length();

            // if the header line begins with a line termination char,
            if (line.length() == 0 ||
                line.charAt(0) == '\r' || line.charAt(0) == '\n')
                break; // then we've encountered the end of the headers.

            header = parseHeader(line, value).toUpperCase();
            oldVal = (String) dest.get(header);
            if (oldVal == null)
                dest.put(header, value.toString());
            else
                dest.put(header, oldVal + "," + value);
        }
        return pos;
    }
     */

    /** Return the number of the port this server is listening on. */
    public int getPort()         { return port; }

    /** Return the startup timestamp for this server. */
    public String getTimestamp() { return startupTimestamp; }

    public static String getOutputCharset() {
        return OUTPUT_CHARSET;
    }

    private void init() throws IOException
    {
        CREATE_PERMISSION.checkPermission();
        startupTimestamp = Long.toString((new Date()).getTime());
        startupTimestampHeader = TIMESTAMP_HEADER + ": " + startupTimestamp;
        initAllowRemote();

        String charsetName = Settings.getVal("http.charset");
        if (charsetName != null && charsetName.length() > 0) try {
            if ("auto".equals(charsetName))
                charsetName =
                    (Translator.isTranslating() ? "UTF-8" : "ISO-8859-1");

            "test".getBytes(charsetName);
            OUTPUT_CHARSET = charsetName;
        } catch (UnsupportedEncodingException uee) {}

        try {
            DashboardURLStreamHandlerFactory.initialize(this);
        } catch (Exception e) {}
    }

    private void initAllowRemote() {
        String setting = Settings.getVal(HTTP_ALLOWREMOTE_SETTING);
        if ("true".equalsIgnoreCase(setting))
            allowingRemoteConnections = ALLOW_REMOTE_ALWAYS;
        else if ("false".equalsIgnoreCase(setting))
            allowingRemoteConnections = ALLOW_REMOTE_MAYBE;
        else
            allowingRemoteConnections = ALLOW_REMOTE_NEVER;

        ALLOW_REMOTE_CONNECTIONS_SETTING = allowingRemoteConnections;
    }

    private InetAddress getListenAddress() {
        InetAddress listenAddress = null;
        if (allowingRemoteConnections == ALLOW_REMOTE_NEVER)
            listenAddress = LOOPBACK_ADDR;
        return listenAddress;
    }

    /**
     * Run a tiny web server on the given port, serving up resources
     * out of the given package within the class path.
     *
     * Serving up resources out of the classpath seems like a nice
     * idea, since it allows html pages, etc to be JAR-ed up and
     * invisible to the user.
     */
    WebServer(int port, String path) throws IOException
    {
        if (path == null || path.length() == 0)
            throw new IOException("Path must be specified");

        if (path.startsWith("/")) path = path.substring(1);
        if (!path.endsWith("/"))  path = path + "/";
        Enumeration e = getClass().getClassLoader().getResources(path);
        Vector v = new Vector();
        while (e.hasMoreElements())
            v.addElement(e.nextElement());
        int i = v.size();
        URL [] roots = new URL[i];
        while (i-- > 0)
            roots[i] = (URL) v.elementAt(i);

        init();
        addPort(port);
        setRoots(roots);
    }

    /**
     * Run a tiny web server on the given port, serving up files out
     * of the given directory.
     */
    WebServer(String directoryToServe, int port)
        throws IOException
    {
        File rootDir = new File(directoryToServe);
        if (!rootDir.isDirectory())
            throw new IOException("Not a directory: " + directoryToServe);

        URL [] roots = new URL[1];
        roots[0] = rootDir.toURL();

        init();
        addPort(port);
        setRoots(roots);
    }

    /** Create a web server, not listening on any ports and not serving
     * any content.
     * 
     * @throws IOException
     */
    public WebServer() throws IOException {
        init();
    }

    /**
     * Run a tiny web server on the given port.
     */
    public WebServer(int port) throws IOException {
        init();
        addPort(port);
    }

    /**
     * Run a tiny web server on the given port, serving up resources
     * out of the given list of template search URLs.
     */
    public WebServer(int port, URL[] roots) throws IOException {
        init();
        addPort(port);
        setRoots(roots);
    }

    public void setRoots(URL [] roots) {
        SET_ROOTS_PERMISSION.checkPermission();
        this.roots = roots;
        clearClassLoaderCaches();
        writePackagesToDefaultEnv();
    }

    public void setProps(DashHierarchy props) {
        SET_HIERARCHY_PERMISSION.checkPermission();
        if (props == null)
            DEFAULT_ENV.remove(TinyCGI.PSP_PROPERTIES);
        else
            DEFAULT_ENV.put(TinyCGI.PSP_PROPERTIES, props);
    }
    public void setData(DataRepository data) {
        if (System.getSecurityManager() != null)
            SET_DATA_PERMISSION.checkPermission();
        this.data = data;
        if (data == null)
            DEFAULT_ENV.remove(TinyCGI.DATA_REPOSITORY);
        else
            DEFAULT_ENV.put(TinyCGI.DATA_REPOSITORY, data);
    }

    public void setCache(ObjectCache cache) {
        SET_CACHE_PERMISSION.checkPermission();
        if (cache == null)
            DEFAULT_ENV.remove(TinyCGI.OBJECT_CACHE);
        else
            DEFAULT_ENV.put(TinyCGI.OBJECT_CACHE, cache);
    }

    public void setDashboardContext(DashboardContext dashboardContext) {
        SET_DASHBOARD_CONTEXT.checkPermission();
        if (dashboardContext == null)
            DEFAULT_ENV.remove(TinyCGI.DASHBOARD_CONTEXT);
        else
            DEFAULT_ENV.put(TinyCGI.DASHBOARD_CONTEXT, dashboardContext);
    }


    private volatile boolean isRunning = true;



    private class ServerSocketListener extends Thread implements PropertyChangeListener {
        private int port;
        private ServerSocket serverSocket;

        public ServerSocketListener(int port) throws IOException {
            super("WebServerListener-"+nextThreadNum());
            // create a server socket that listens on the specified port.
            this.port = port;
            setDaemon(true);
            serverSocketListeners.add(this);
            InternalSettings.addPropertyChangeListener
                (HTTP_ALLOWREMOTE_SETTING, this);
            openSocket();
        }

        private void openSocket() {
            InetAddress listenAddress = getListenAddress();

            while (serverSocket == null) try {
                serverSocket = new ServerSocket(port, 50, listenAddress);
            } catch (IOException ioex) {
                closeSocket(serverSocket);
                serverSocket = null;
                port += 1;
            }
        }

        private void closeSocket() {
            ServerSocket oldServerSocket = serverSocket;
            serverSocket = null;
            // if this thread is still running, the next line will cause the
            // accept() call in the run() method to throw a SocketException.
            closeSocket(oldServerSocket);
        }

        private void closeSocket(ServerSocket s) {
            if (s != null) try {
                s.close();
            } catch (IOException e) { }
        }

        private int getPortNumber() {
            return port;
        }

        public void run() {
            while (isRunning) {
                if (serverSocket == null)
                    openSocket();

                try {
                    Socket clientSocket = serverSocket.accept();

                    TinyWebThread serverThread = new TinyWebThread(clientSocket);
                    serverThreads.addElement(serverThread);
                    serverThread.start();

                } catch (IOException e) { }
            }

            close();
        }

        public void close() {
            closeSocket();
        }

        public void propertyChange(PropertyChangeEvent evt) {
            if (HTTP_ALLOWREMOTE_SETTING.equals(evt.getPropertyName())) {
                initAllowRemote();
                closeSocket();
            }
        }
    }


    /** Start listening for connections on an additional port */
    public void addPort(int port) throws IOException {
        ADD_PORT_PERMISSION.checkPermission();

        for (Iterator i = serverSocketListeners.iterator(); i.hasNext();) {
            ServerSocketListener s = (ServerSocketListener) i.next();
            if (port == s.getPortNumber())
                return;
        }

        ServerSocketListener s = new ServerSocketListener(port);
        s.start();
        this.port = s.getPortNumber();
        DEFAULT_ENV.put("SERVER_PORT", Integer.toString(this.port));
    }



    /** Stop the web server. */
    public void quit() {
        QUIT_PERMISSION.checkPermission();
        isRunning = false;

        // stop all server listener threads.
        for (Iterator i = serverSocketListeners.iterator(); i.hasNext();) {
            ServerSocketListener listener = (ServerSocketListener) i.next();
            listener.close();
        }
        this.serverSocketListeners.clear();

        // stop any web requests that are still running
        while (serverThreads.size() > 0) {
            TinyWebThread serverThread = (TinyWebThread) serverThreads.remove(0);
            serverThread.close();
        }
    }

    private static int threadInitNumber;
    private static synchronized int nextThreadNum() {
        return threadInitNumber++;
    }

}
