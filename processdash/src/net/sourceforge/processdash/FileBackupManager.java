// Copyright (C) 2003-2007 Tuma Solutions, LLC
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


package net.sourceforge.processdash;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.sourceforge.processdash.log.time.WorkingTimeLog;
import net.sourceforge.processdash.tool.export.mgr.ExternalResourceManager;
import net.sourceforge.processdash.ui.ConsoleWindow;
import net.sourceforge.processdash.util.FileUtils;
import net.sourceforge.processdash.util.ThreadThrottler;


/** Backup data and other files automatically.
 *
 * We want to back up data files (*.dat), defect logs (*.def), the time log
 * (time.log), the state file (state), user settings (pspdash.ini), and
 * the error log (log.txt).
 *
 * Do this each time the dashboard starts or shuts down.
 * Also do it at periodically, as configured by the user.
 */
public class FileBackupManager {

    public static final int STARTUP = 0;
    public static final int RUNNING = 1;
    public static final int SHUTDOWN = 2;

    public static final String BACKUP_TIMES_SETTING = "backup.timesOfDay";

    private static OutputStream logFile = null;
    private static final String LOG_FILE_NAME = "log.txt";
    private static final String HIST_LOG_FILE_NAME = "histLog.txt";
    private static final String OLD_BACKUP_TEMP_FILENAME = "temp_old.zip";
    private static final String NEW_BACKUP_TEMP_FILENAME = "temp_new.zip";
    private static final SimpleDateFormat DATE_FMT =
        new SimpleDateFormat("yyyyMMddHHmmss");
    private static final long DAY_MILLIS = 24L /*hours*/ * 60 /*minutes*/
        * 60 /*seconds*/ * 1000 /*millis*/;
    private static final Logger logger = Logger
            .getLogger(FileBackupManager.class.getName());


    public static void maybeRun(String dataDirName, int when, String who) {
        if (Settings.isReadOnly())
            return;

        if (Settings.getBool("backup.enabled", true)) {
            try {
                run(dataDirName, when, who);
            } catch (Throwable t) {}
        } else if (when == STARTUP &&
                   Settings.getBool("logging.enabled", false)) {
            startLogging(new File(dataDirName));
        }
    }


    public synchronized static File run(String dataDirName, int when, String who) {
        File dataDir = new File(dataDirName);
        File backupDir = new File(dataDir, "backup");
        if (!backupDir.exists()) backupDir.mkdir();
        if (!backupDir.exists()) return null;
        boolean loggingEnabled = Settings.getBool("logging.enabled", false);

        if (loggingEnabled)
            stopLogging();

        File result = null;
        try {
            result = backupFiles(dataDir, backupDir, when, who);
        } catch (Exception e) {
            printError(e);
        }

        if (loggingEnabled && when != SHUTDOWN)
            startLogging(dataDir);

        return result;
    }


    private static boolean oldBackupIsEmpty;
    private static boolean oldBackupContainsTimeLogFile;
    private static boolean oldBackupContainsTimeLogModFile;


    // Find the most recent backup in the directory.  Open it for input.
    // Open two zip output streams: one for the new backup, and one for
    // the old backup.
    // Retrieve all the files in the data directory. sort. iterate:
    //   - write the contents to the new backup zipfile.
    //   - compare the contents to the old backup zipfile.
    //      - If the contents are identical, do nothing.
    //      - If the file differ (or aren't present in both places), copy
    //        contents from the old backup input to the old backup output.
    // Close all files.
    // Rename the output files appropriately.
    // Delete old/outdated backup files.
    private static File backupFiles(File dataDir, File backupDir, int when,
            String who) throws IOException
    {
        List dataFiles = getDataFiles(dataDir);
        if (dataFiles == null || dataFiles.size() == 0)
            return null;        // nothing to do

        ExternalResourceManager extResourceMgr = ExternalResourceManager
                .getInstance();

        File[] backupFiles = getBackupFiles(backupDir);
        File mostRecentBackupFile = findMostRecentBackupFile(backupFiles);
        File oldBackupTempFile = new File(backupDir, OLD_BACKUP_TEMP_FILENAME);
        File newBackupTempFile = new File(backupDir, NEW_BACKUP_TEMP_FILENAME);

        ZipOutputStream newBackupOut = new ZipOutputStream(
                new BufferedOutputStream(
                        new FileOutputStream(newBackupTempFile)));
        newBackupOut.setLevel(9);

        boolean wroteHistLog = false;

        if (mostRecentBackupFile != null) {
            ZipInputStream oldBackupIn = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(
                            mostRecentBackupFile)));
            ZipOutputStream oldBackupOut = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(
                            oldBackupTempFile)));
            oldBackupOut.setLevel(9);
            oldBackupIsEmpty = true;
            oldBackupContainsTimeLogFile = false;
            oldBackupContainsTimeLogModFile = false;

            // iterate over all the entries in the old backup
            ZipEntry oldEntry;
            while ((oldEntry = oldBackupIn.getNextEntry()) != null) {
                String filename = oldEntry.getName();
                ThreadThrottler.tick();

                if (extResourceMgr.isArchivedItem(filename))
                    continue;

                if (HIST_LOG_FILE_NAME.equals(filename)) {
                    if (when == STARTUP)
                        // at startup, just copy the file to the new backup.
                        copyZipEntry(oldBackupIn, newBackupOut, oldEntry, null);
                    else
                        // other times, append the old file and the new log.
                        writeHistLogFile(oldBackupIn, newBackupOut, dataDir);
                    wroteHistLog = true;
                    continue;
                }

                File file = new File(dataDir, filename);

                if (dataFiles.remove(filename)) {
                    // this file is in the old backup zipfile AND in the backup
                    // directory.  Compare the two versions and back up the
                    // file appropriately.
                    backupFile(oldEntry, oldBackupIn, oldBackupOut,
                               newBackupOut, file, filename);
                } else {
                    // this file is in the old backup, but is no longer present
                    // in the backup directory.  Copy it over to the new version
                    // of the old backup
                    copyZipEntry(oldBackupIn, oldBackupOut, oldEntry, null);
                    wroteEntryToOldBackup(filename);
                }
            }

            // The two files that make up the time log must always be backed
            // up and restored as an atomic pair - otherwise, Bad Things can
            // happen.  If one of these files (but not the other) was written
            // to the incremental old backup, add its partner (which presumably
            // must be identical to the file in the dataDir).
            if (oldBackupContainsTimeLogFile
                    && !oldBackupContainsTimeLogModFile) {
                String filename = WorkingTimeLog.TIME_LOG_MOD_FILENAME;
                File file = new File(dataDir, filename);
                backupFile(null, null, null, oldBackupOut, file, filename);
            } else if (oldBackupContainsTimeLogModFile
                    && !oldBackupContainsTimeLogFile) {
                String filename = WorkingTimeLog.TIME_LOG_FILENAME;
                File file = new File(dataDir, filename);
                backupFile(null, null, null, oldBackupOut, file, filename);
            }

            oldBackupIn.close();
            mostRecentBackupFile.delete();

            if (oldBackupIsEmpty) {
                // ZipOutputStream refuses to create an empty archive.
                // Thus, we have to create a dummy entry to allow the
                // subsequent close() call to succeed.
                oldBackupOut.putNextEntry(new ZipEntry("foo"));
                oldBackupOut.close();
                oldBackupTempFile.delete();
            } else {
                oldBackupOut.close();
                oldBackupTempFile.renameTo(mostRecentBackupFile);
            }
        }

        // backup all the files that are present in the backup directory that
        // weren't in the old backup zipfile.
        for (Iterator iter = dataFiles.iterator(); iter.hasNext();) {
            ThreadThrottler.tick();
            String filename = (String) iter.next();
            File file = new File(dataDir, filename);
            backupFile(null, null, null, newBackupOut, file, filename);
        }

        // if the old backup didn't contain a historical log file, initialize
        // it with the current log file
        if (wroteHistLog == false)
            writeHistLogFile(null, newBackupOut, dataDir);

        // Allow the external resource manager to save any items of interest.
        extResourceMgr.addExternalResourcesToBackup(newBackupOut);

        // finalize the new backup, and give it its final name.
        newBackupOut.close();
        String outputFilename = getOutputFilename(when, new Date());
        File newBackupFile = new File(backupDir, outputFilename);
        newBackupTempFile.renameTo(newBackupFile);

        makeExtraBackupCopies(newBackupFile, who);
        cleanupOldBackupFiles(backupFiles);
        return newBackupFile;
    }


    private static File[] getBackupFiles(File backupDir) {
        File[] backupFiles = backupDir.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return BACKUP_FILENAME_PATTERN.matcher(name).matches();
                }});
        Arrays.sort(backupFiles);
        return backupFiles;
    }
    private static final Pattern BACKUP_FILENAME_PATTERN =
        Pattern.compile("pdash-\\d+-(startup|checkpoint|shutdown)\\.zip",
                Pattern.CASE_INSENSITIVE);


    private static File findMostRecentBackupFile(File[] backupFiles) {
        if (backupFiles != null && backupFiles.length > 0)
            return backupFiles[backupFiles.length - 1];
        else
            return null;
    }


    private static List getDataFiles(File dataDir) {
        List result = new ArrayList();
        String[] files = dataDir.list(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return inBackupSet(dir, name);
            }});
        if (files != null)
            result.addAll(Arrays.asList(files));

        File cmsDir = new File(dataDir, "cms");
        if (cmsDir.isDirectory())
            getCmsFiles(result, cmsDir, "cms");

        Arrays.sort(files);
        return result;
    }

    private static void getCmsFiles(List dest, File dir, String prefix) {
        File[] files = dir.listFiles();
        for (int i = 0; i < files.length; i++) {
            String filename = prefix + "/" + files[i].getName();
            if (files[i].isDirectory())
                getCmsFiles(dest, files[i], filename);
            else if (filename.toLowerCase().endsWith(".xml"))
                dest.add(filename);
        }
    }


    private static void copyZipEntry(InputStream oldBackupIn,
                                     ZipOutputStream oldBackupOut,
                                     ZipEntry e,
                                     byte[] prepend)
        throws IOException
    {
        ZipEntry eOut = new ZipEntry(e.getName());
        eOut.setTime(e.getTime());
        oldBackupOut.putNextEntry(eOut);

        if (prepend != null)
            oldBackupOut.write(prepend);

        int bytesRead;
        while ((bytesRead = oldBackupIn.read(copyBuf)) != -1) {
            ThreadThrottler.tick();
            oldBackupOut.write(copyBuf, 0, bytesRead);
            ThreadThrottler.tick();
        }
        oldBackupOut.closeEntry();
    }
    private static byte[] copyBuf = new byte[1024];


    private static void backupFile(ZipEntry oldEntry,
                                   ZipInputStream oldBackupIn,
                                   ZipOutputStream oldBackupOut,
                                   ZipOutputStream newBackupOut,
                                   File file, String filename)
        throws IOException
    {
        ByteArrayOutputStream bytesSeen = null;
        InputStream oldIn = null;

        // if the old backup file contains an entry for this file,
        if (oldEntry != null && oldBackupIn != null && oldBackupOut != null) {
            // do the prep to start comparing it with the new file.
            bytesSeen = new ByteArrayOutputStream();
            oldIn = oldBackupIn;
        }

        // create an entry in the new backup archive for this file
        ZipEntry e = new ZipEntry(filename);
        e.setTime(file.lastModified());
        e.setSize(file.length());
        newBackupOut.putNextEntry(e);

        InputStream fileIn = new BufferedInputStream(new FileInputStream(file));
        OutputStream fileOut = newBackupOut;
        int c, d;
        while ((c = fileIn.read()) != -1) {
            fileOut.write(c);

            // if we are still comparing the two files for identity
            //  (they've matched so far)
            if (oldIn != null) {
                // read the next byte from the old backup.
                d = oldIn.read();
                if (d != -1)
                    bytesSeen.write(d);
                // if we've found a mismatch between the current file and its
                // old backup,
                if (c != d) {
                    // then eagerly copy the rest of the old backup.
                    copyZipEntry(oldIn, oldBackupOut, oldEntry,
                                 bytesSeen.toByteArray());
                    oldIn = null;
                    bytesSeen = null;
                    oldBackupIn = null;
                    oldBackupOut = null;
                    wroteEntryToOldBackup(filename);
                }
            }
            ThreadThrottler.tick();
        }
        fileIn.close();

        if (oldIn != null) {
            // read the next byte from the old backup.
            d = oldIn.read();
            if (d != -1) {
                // if the old backup is longer than the current file, write it
                // to the backup save archive.
                bytesSeen.write(d);
                copyZipEntry(oldIn, oldBackupOut, oldEntry, bytesSeen.
                             toByteArray());
                wroteEntryToOldBackup(filename);
            }
        }

        // finish writing the file to the new backup archive.
        fileOut.flush();
        newBackupOut.closeEntry();
    }

    private static void wroteEntryToOldBackup(String filename) {
        oldBackupIsEmpty = false;
        if (filename.equalsIgnoreCase(WorkingTimeLog.TIME_LOG_FILENAME))
            oldBackupContainsTimeLogFile = true;
        if (filename.equalsIgnoreCase(WorkingTimeLog.TIME_LOG_MOD_FILENAME))
            oldBackupContainsTimeLogModFile = true;
    }


    private static void writeHistLogFile(ZipInputStream oldBackupIn,
            ZipOutputStream newBackupOut, File dataDir)
            throws IOException {
        File currentLog = new File(dataDir, LOG_FILE_NAME);
        if (oldBackupIn == null && !currentLog.exists())
            // if we have neither a historical log, nor a current log, there
            // is nothing to do.
            return;

        // start an entry in the zip file for the historical log.
        ZipEntry e = new ZipEntry(HIST_LOG_FILE_NAME);
        e.setTime(System.currentTimeMillis());
        newBackupOut.putNextEntry(e);

        // read in the previous historical log, and copy appropriate portions
        // to the output ZIP.
        if (oldBackupIn != null) {
            byte[] histLog = FileUtils.slurpContents(oldBackupIn, false);

            long totalSize = histLog.length + currentLog.length();
            int skip = (int) Math.max(0, totalSize - MAX_HIST_LOG_SIZE);

            if (skip < histLog.length)
                newBackupOut.write(histLog, skip, histLog.length - skip);
        }

        if (currentLog.exists() && currentLog.length() > 0) {
            newBackupOut.write(HIST_SEPARATOR.getBytes());
            FileUtils.copyFile(currentLog, newBackupOut);
        }

        newBackupOut.closeEntry();
    }

    private static int MAX_HIST_LOG_SIZE = Settings.getInt(
            "logging.maxHistLogSize", 500000);
    private static final String HIST_SEPARATOR = "--------------------"
            + "--------------------------------------------------"
            + System.getProperty("line.separator");


    private static void makeExtraBackupCopies(File backupFile, String who)
            throws IOException {
        if (who == null || who.length() == 0)
            return;

        String extraBackupDirs = InternalSettings.getExtendableVal(
                "backup.extraDirectories", ";");
        if (extraBackupDirs == null)
            return;

        String[] dirNames = extraBackupDirs.replace('/', File.separatorChar)
                .split(";");
        String filename = "backup-" + FileUtils.makeSafe(who) + ".zip";
        for (int i = 0; i < dirNames.length; i++) {
            ThreadThrottler.tick();
            File copy = new File(dirNames[i], filename);
            try {
                FileUtils.copyFile(backupFile, copy);
            } catch (Exception e) {
                System.err.println("Warning: unable to make extra backup to '"
                        + copy + "'");
            }
        }
    }


    private static void cleanupOldBackupFiles(File[] backupFiles) {
        int maxBackupAge = Settings.getInt("backup.keepBackupsNumDays", -1);
        if (maxBackupAge > 0) {
            long delta = maxBackupAge * DAY_MILLIS;
            Date oldAge = new Date(System.currentTimeMillis() - delta);
            String filename = getOutputFilename(STARTUP, oldAge);
            for (int i = 0; i < backupFiles.length-10; i++) {
                File file = backupFiles[i];
                if (file.getName().compareTo(filename) < 0)
                    file.delete();
            }
        }
    }


    private static void stopLogging() {
        if (logFile != null) try {
            ConsoleWindow.getInstalledConsole().setCopyOutputStream(null);
            logFile.flush();
            logFile.close();
        } catch (IOException ioe) { printError(ioe); }
    }

    private static void startLogging(File dataDir) {
        try {
            File out = new File(dataDir, LOG_FILE_NAME);
            logFile = new FileOutputStream(out);
            ConsoleWindow.getInstalledConsole().setCopyOutputStream(logFile);
            System.out.println("Process Dashboard - logging started at " +
                               new Date());
            System.out.println(System.getProperty("java.vendor") +
                               " JRE " + System.getProperty("java.version") +
                               "; " + System.getProperty("os.name"));
        } catch (IOException ioe) { printError(ioe); }
    }


    private static String getOutputFilename(int when, Date date) {
        return "pdash-" + DATE_FMT.format(date) + WHEN_STR[when] + ".zip";
    }

    private static final String[] WHEN_STR = {
        "-startup", "-checkpoint", "-shutdown"
    };

    public static boolean inBackupSet(File dir, String name) {
        if (name.equalsIgnoreCase(LOG_FILE_NAME)
                && (new File(dir, name)).length() > 0)
            // backup the log file if it contains anything.
            return true;

        name = name.toLowerCase();
        if (name.endsWith(".dat") ||    // backup data files
            name.endsWith(".def") ||    // backup defect logs
            name.equals("time.log") ||  // backup the time log
            name.equalsIgnoreCase(WorkingTimeLog.TIME_LOG_FILENAME) ||
            name.equalsIgnoreCase(WorkingTimeLog.TIME_LOG_MOD_FILENAME) ||
            name.equals("state") ||     // backup the state file
            name.equals(".pspdash") ||  // backup the user settings
            name.equals("pspdash.ini"))
            return true;

        return false;
    }

    private static void printError(Throwable t) {
        printError("Unexpected error in FileBackupManager", t);
    }

    private static void printError(String msg, Throwable t) {
        System.err.println(msg);
        t.printStackTrace();
    }


    public static class BGTask implements Runnable {

        private DashboardContext context;

        public void setDashboardContext(DashboardContext context) {
            this.context = context;
        }

        public void run() {
            try {
                ProcessDashboard dash = (ProcessDashboard) context;
                String property_directory = dash.property_directory;
                String ownerName = ProcessDashboard.getOwnerName(context
                        .getData());
                FileBackupManager.maybeRun(property_directory,
                        FileBackupManager.RUNNING, ownerName);
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                        "Encountered exception when performing auto backup", e);
            }
        }

    }

}