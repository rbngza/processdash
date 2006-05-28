// Copyright (C) 1998-2006 Tuma Solutions, LLC
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
// Foundation, Inc., 59 Temple Place -Suite 330, Boston, MA  02111-1307, USA.
//
// The author(s) may be contacted at:
// Process Dashboard Group
// c/o Ken Raisor
// 6137 Wardleigh Road
// Hill AFB, UT 84056-5843
//
// E-Mail POC:  processdash-devel@lists.sourceforge.net

package net.sourceforge.processdash.data.repository;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.sourceforge.processdash.data.DataContext;
import net.sourceforge.processdash.data.DateData;
import net.sourceforge.processdash.data.DoubleData;
import net.sourceforge.processdash.data.FrozenData;
import net.sourceforge.processdash.data.MalformedData;
import net.sourceforge.processdash.data.MalformedValueException;
import net.sourceforge.processdash.data.SaveableData;
import net.sourceforge.processdash.data.SimpleData;
import net.sourceforge.processdash.data.StringData;
import net.sourceforge.processdash.data.TagData;
import net.sourceforge.processdash.data.ValueFactory;
import net.sourceforge.processdash.data.compiler.CompilationException;
import net.sourceforge.processdash.data.compiler.CompiledScript;
import net.sourceforge.processdash.data.compiler.Compiler;
import net.sourceforge.processdash.data.compiler.ExecutionException;
import net.sourceforge.processdash.data.compiler.ExpressionContext;
import net.sourceforge.processdash.data.compiler.ListStack;
import net.sourceforge.processdash.data.compiler.analysis.DepthFirstAdapter;
import net.sourceforge.processdash.data.compiler.lexer.Lexer;
import net.sourceforge.processdash.data.compiler.lexer.LexerException;
import net.sourceforge.processdash.data.compiler.node.AIncludeDeclaration;
import net.sourceforge.processdash.data.compiler.node.ANewStyleDeclaration;
import net.sourceforge.processdash.data.compiler.node.AOldStyleDeclaration;
import net.sourceforge.processdash.data.compiler.node.AReadOnlyAssignop;
import net.sourceforge.processdash.data.compiler.node.ASearchDeclaration;
import net.sourceforge.processdash.data.compiler.node.ASimpleSearchDeclaration;
import net.sourceforge.processdash.data.compiler.node.AUndefineDeclaration;
import net.sourceforge.processdash.data.compiler.node.Start;
import net.sourceforge.processdash.data.compiler.node.TIdentifier;
import net.sourceforge.processdash.data.compiler.node.TStringLiteral;
import net.sourceforge.processdash.data.compiler.parser.Parser;
import net.sourceforge.processdash.data.compiler.parser.ParserException;
import net.sourceforge.processdash.hier.Filter;
import net.sourceforge.processdash.templates.TemplateLoader;
import net.sourceforge.processdash.util.CppFilterReader;
import net.sourceforge.processdash.util.EscapeString;
import net.sourceforge.processdash.util.RobustFileWriter;

public class DataRepository implements Repository, DataContext {

    public static final String anonymousPrefix = "///Anonymous";

        /** a mapping of data names (Strings) to data values (DataElements) */
        Hashtable data = new Hashtable(8000);
//    HashTree data = new HashTree(8000);

        /** a backwards mapping of the above hashtable for data values that happen
         *  to be DataListeners.  key is a DataListener, value is a String. */
        Map activeData = Collections.synchronizedMap(new WeakHashMap(2000));

        Set dataListenersForDeferredRemoval =
            Collections.synchronizedSet(new HashSet());
        volatile boolean deferDeletions = false;

        PrefixHierarchy repositoryListenerList = new PrefixHierarchy();

        Vector datafiles = new Vector();

        RepositoryServer dataServer = null;
        RepositoryServer secondaryDataServer = null;

        Hashtable PathIDMap = new Hashtable(20);
        Hashtable IDPathMap = new Hashtable(20);

        HashSet dataElementNameSet = new HashSet();
        Set dataElementNameSet_ext =
            Collections.unmodifiableSet(dataElementNameSet);

        private static Logger logger = Logger.getLogger(DataRepository.class
                .getName());


        private class DataSaver extends Thread implements DataConsistencyObserver {
            public DataSaver() {
                super("DataSaver");
                setDaemon(true);
                start();
            }
            public void run() {
                while (true) try {
                    sleep(120000);         // save dirty datafiles every 2 minutes
                    addDataConsistencyObserver(this);
                } catch (InterruptedException ie) {}
            }
            public void dataIsConsistent() {
                saveAllDatafiles();
                System.gc();
            }
        }

        DataSaver dataSaver = new DataSaver();


        private class DataFile implements Comparable {
            String prefix;
            String inheritsFrom;
            Map inheritedDefinitions;
            File file;
            volatile boolean isRemoved = false;
            boolean canWrite;
            boolean isImported = false;
            volatile int dirtyCount;

            public DataFile(String prefix, File file) {
                this.prefix = prefix;
                this.file = file;
                this.canWrite = (file == null ? false : file.canWrite());
                this.dirtyCount = 0;
            }

            public void invalidate() {
                file = null;
                canWrite = false;
            }

            public int compareTo(Object o) {
                DataFile that = (DataFile) o;
                return that.prefix.length() - this.prefix.length();
            }
        }


        /** The DataElement class tracks the state of a single piece of data. */
        private class DataElement {

            /** The name of this element */
            private String name;

            /** True if the name of this element matches the name of a default
             * value inherited by our {@link DataFile}.
             */
            private boolean isDefaultName;

            /** The value of this element.
             * 
             * When data elements are created but not initialized, their value is
             * set to null.  Elements with null values generally are not saved out
             * to any datafile.
             */
            private SaveableData value = null;

            /** True if the value of this data element came directly from the
             * default values inherited by our {@link DataFile}.
             */
            private boolean isDefaultValue;

            /** The datafile to which this element should be saved.
             * 
             * If this value is null, the element will not be saved out to any
             * datafile.
             */
            DataFile datafile = null;

            /** A value indicating the last time this element's value was read.
             */
            volatile byte timestamp;

            /** A count of the number of clients who are currently using this
             * element, and want to prevent it from being disposed of prematurely.
             */
            volatile byte disposalLockCount;

            /** A collection of objects that are interested in changes to the value
             * of this element.
             * 
             * SPECIAL MEANINGS:<ol>
             *  <li> a null value indicates that no objects have <b>ever</b>
             *       expressed an interest in this data element.</li>
             *  <li> a Vector with objects in it is a list of objects that should be
             *       notified if the value of this data element changes.</li>
             *  <li> an empty Vector indicates that, although some object(s) once
             *       expressed interest in this data element, no objects are
             *       interested any longer.</li>
             *  </ol>
             */
            Vector dataListeners = null;


            /** Create a new data element */
            public DataElement(DataFile datafile, String name, boolean isDefaultName) {
                this.datafile = datafile;
                this.name = name;
                this.isDefaultName = isDefaultName;
                this.timestamp = currentGeneration;
                this.disposalLockCount = 0;
            }

            public boolean isDefaultName() {
                return isDefaultName;
            }

            public synchronized SaveableData getValue() {
                return value;
            }

            public synchronized boolean isDefaultValue() {
                return isDefaultValue;
            }

            public SimpleData getSimpleValue() {
                try {
                    synchronized (this) {
                        lockFromDisposal();
                        if (value == null)
                            return null;
                    }
                    return value.getSimpleValue();
                } finally {
                    unlockForDisposal();
                }
            }

            public synchronized void setValue(SaveableData d, boolean isDefault) {
                this.value = d;
                this.isDefaultValue = isDefault;
            }

            public void disposeValue() {
                SaveableData value;
                synchronized (this) {
                    if (disposalLockCount > 0)
                        // if some other thread is currently using this element,
                        // give them a moment or two (but no more) to finish.
                        // Don't wait forever, in case someone just forgot to
                        // clean up their lock.
                        try { wait(500); } catch (InterruptedException e) {}
                    value = this.value;
                    this.value = null;
                }

                if (value != null) {
                    try {
                        value.dispose();
                    } catch (Exception e) {}
                    activeData.remove(value);
                }
            }

            public DataEvent getDataChangedEvent(String name) {
                return new DataEvent(DataRepository.this, name,
                        DataEvent.VALUE_CHANGED, getSimpleValue());
            }

            public synchronized void addDataListener(DataListener dl) {
                if (dataListeners == null)
                    dataListeners = new Vector();
                if (!dataListeners.contains(dl))
                    dataListeners.add(dl);
            }

            public void removeDataListener(DataListener dl) {
                synchronized (this) {
                    if (dataListeners == null || dataListeners.remove(dl) == false)
                        return;
                }
                maybeDelete(name, this, true);
            }

            public void removeDataListeners(Set listenersToRemove) {
                synchronized (this) {
                    if (dataListeners == null
                            || dataListeners.removeAll(listenersToRemove) == false)
                        return;
                }
                maybeDelete(name, this, true);
            }

            public boolean hasListeners() {
                return (dataListeners != null && !dataListeners.isEmpty());
            }

            public synchronized void lockFromDisposal() {
                disposalLockCount++;
            }

            public synchronized void unlockForDisposal() {
                if (--disposalLockCount == 0)
                    notifyAll();
            }
        }

        private class DataNotifier extends Thread {

            /** A list of the notifications we need to perform.
             *
             * the <B>keys</B> in the hashtable are DataListeners that need to be
             * notified of changes in data.
             *
             * the <b>values</b> are separate hashtables.  The keys of these
             * subhashtables name data elements that have changed, which the
             * listener is interested in.  The values in these subhashtables
             * are the named DataElements.
             */
            Hashtable notifications = null;

            /** A list of active listeners.  (An active listener is one that is going
             * to perform a recalculation as soon as it is notified of a data change.
             * That recalculation will probably trigger other data notifications.)
             *
             * The <b>keys</b> in the hashtable are the names of the data elements
             * which will be recalculated when we notify the DataListener which
             * is stored as the <b>value</b> in the hashtable.
             *
             * This data structure is basically a backward mapping of the
             * DataRepository's <code>activeData</code> structure, for only those
             * DataListeners which appear in the <code>notifications</code> list
             * above.
             */
            Hashtable activeListeners = null;

            /** a list of misbehaved data which appears to be circularly defined. */
            Set circularData;

            private volatile boolean suspended = false;

            public DataNotifier() {
                super("DataNotifier");
                notifications = new Hashtable();
                activeListeners = new Hashtable();
                circularData = Collections.synchronizedSet(new HashSet());
                setPriority(MIN_PRIORITY);
                setDaemon(true);
                start();
            }

            public void highPriority() {
                setPriority(NORM_PRIORITY);
            }
            public void lowPriority()  {
                setPriority((MIN_PRIORITY + NORM_PRIORITY)/2);
            }

            /** Determine all the notifications that will need to be made as
             * a result of a change to given <code>DataElement</code> with
             * the given <code>name</code>, and add those notifications to
             * our internal data structures.
             */
            public void dataChanged(String name, DataElement d) {
                if (name == null || circularData.contains(name)) return;
                if (d == null) d = (DataElement) data.get(name);
                if (d == null) return;

                List dataListenerList = d.dataListeners;
                if (dataListenerList == null || dataListenerList.isEmpty())
                    return;

                for (int i = dataListenerList.size();  i-- > 0; ) try {
                    DataListener dl = (DataListener) dataListenerList.get(i);
                    if (dataListenersForDeferredRemoval.contains(dl))
                        continue;

                    String listenerName = (String) activeData.get(dl);
                    boolean notifyActiveListener = (listenerName != null
                            && activeListeners.put(listenerName, dl) == null);

                    getElementsForDataListener(dl).put(name, d);
                    if (notifyActiveListener)
                        dataChanged(listenerName, null);
                } catch (IndexOutOfBoundsException ie) {
                    // Someone has been messing with dataListenerList while we're
                    // iterating through it. No matter...the worst that can happen
                    // is that we will notify someone who doesn't care anymore, and
                    // that is harmless.
                }

                if (suspended) synchronized (this) { notify(); }
            }

            private Hashtable getElementsForDataListener(DataListener dl) {
                Hashtable elements = null;
                synchronized (notifications) {
                    elements = ((Hashtable) notifications.get(dl));
                    if (elements == null) {
                        notifications.put(dl, elements = new Hashtable(2));
                    }
                }
                return elements;
            }

            public void addEvent(String name, DataElement d, DataListener dl) {
                if (name == null || dl == null) return;

                String listenerName = (String) activeData.get(dl);
                if (listenerName != null)
                    activeListeners.put(listenerName, dl);

                getElementsForDataListener(dl).put(name, d);

                fireEvent(dl);
            }

            public void removeDataListener(String name, DataListener dl) {
                Hashtable h = (Hashtable) notifications.get(dl);
                if (h != null)
                    h.remove(name);
            }

            public void deleteDataListener(DataListener dl) {
                notifications.remove(dl);
                String listenerName = (String) activeData.get(dl);
                if (listenerName != null)
                    activeListeners.remove(listenerName);
            }

            /** Send one data listener a data changed event, indicating all of
             * the items they are listening to that have changed.
             * 
             * @param dl the listener to notify.
             */
            private void fireEvent(DataListener dl) {
                if (dl == null) return;

                Hashtable elements = ((Hashtable) notifications.get(dl));
                if (elements == null) return;

                String listenerName = (String) activeData.get(dl);
                if (listenerName != null && circularData.contains(listenerName)) {
                    notifications.remove(dl);
                    return;
                }

                synchronized (elements) {
                    if (notifications.get(dl) == null) return;

                    Thread t = (Thread) elements.get(CIRCULARITY_TOKEN);

                    if (t != null) {
                        if (t != Thread.currentThread()) {
                            //System.out.println("waiting for other thread...");
                            try { elements.wait(1000); } catch (InterruptedException ie) {}
                            //System.out.println("waiting done.");
                            return;
                        } else {
                            // circular dependencies between active data listeners
                            // exist. Break the circular dependency and abort.
                            if (listenerName != null) {
                                logger.log(Level.WARNING, "Infinite recursion "
                                          + "encountered while recalculating {0} "
                                          + "- ABORTING", listenerName);
                                circularData.add(listenerName);
                            }
                            return;
                        }
                    }

                    // record the fact that we are the thread currently handling this
                    // element notification list
                    elements.put(CIRCULARITY_TOKEN, Thread.currentThread());
                }

                try {                 // run through the elements to see if any are
                                      // also expected to change, and do those first.
                    for (Iterator i = elements.keySet().iterator(); i.hasNext();) {
                        String name = (String) i.next();
                        if (name == CIRCULARITY_TOKEN) continue;
                        DataListener activeListener =
                            (DataListener) activeListeners.get(name);
                        if (activeListener != null)
                            fireEvent(activeListener);
                    }
                } catch (ConcurrentModificationException cme) {
                    // The loop above is designed to optimize the event delivery
                    // process. It attempts impose minimal overhead by iterating
                    // over the list of elements directly instead of making a copy.
                    // That opens the door to the slight possibility of a
                    // ConcurrentModificationException.  If one occurs, just proceed
                    // without optimizing the delivery of this particular event.
                    logger.log(Level.FINEST, "Caught cme while optimizing event "
                              + "delivery for {0}", listenerName);
                } finally {
                    Hashtable currentElements;
                    synchronized (notifications) {
                        currentElements = (Hashtable) notifications.remove(dl);
                        if (currentElements != elements && currentElements != null) {
                            // Eeek!  While we've been doing the work above, someone
                            // changed the notifications map behind our back, and
                            // replaced the element list with a different one. (I'm
                            // not sure if this can ever happen, but it doesn't hurt
                            // to be paranoid.)  Undo the damage.
                            notifications.put(dl, currentElements);
                        }
                    }
                    elements.remove(CIRCULARITY_TOKEN);
                    if (listenerName != null)
                        activeListeners.remove(listenerName);
                    if (currentElements == null) {
                        // this is an indication that the data listener in question
                        // has apparently been deleted in the time since we started
                        // this method.  We can skip the task of delivering events.
                        return;
                    }
                }

                try {
                    // Now, send the data changed event to this data listener.
                    if (elements.size() == 1) {
                        // if there is only one changed element, we can use the
                        // singular form of the data notification method, and avoid
                        // creating a Vector object.
                        Map.Entry e = (Map.Entry) elements.entrySet().iterator().next();
                        String name = (String) e.getKey();
                        DataElement d = (DataElement) e.getValue();
                        dl.dataValueChanged(d.getDataChangedEvent(name));
                    } else if (elements.size() > 1) {
                        // Build a list of data events to send
                        Vector dataEvents = new Vector();
                        for (Iterator i = elements.entrySet().iterator(); i.hasNext();) {
                            Map.Entry e = (Map.Entry) i.next();
                            String name = (String) e.getKey();
                            DataElement d = (DataElement) e.getValue();
                            dataEvents.addElement(d.getDataChangedEvent(name));
                            dl.dataValuesChanged(dataEvents);
                        }
                    }
                } catch (RemoteException rem) {
                    logger.log(Level.WARNING,
                            "Error when trying to notify a datalistener", rem);
                } catch (Exception e) {
                    // Various exceptions, most notably NullPointerException, can
                    // occur if we erroneously notify a DataListener of changes *after*
                    // it has unregistered for those changes.  Such mistakes can happen
                    // due to multithreading, but no harm is done as long as the
                    // exception is caught here.
                } finally {
                    // notify other threads that might have seen our CIRCULARITY
                    // TOKEN and are waiting for us to finish.
                    synchronized (elements) { elements.notifyAll(); }
                }
            }

            private boolean fireEvent() {
                if (!notifications.isEmpty()) {
                    try {
                        fireEvent((DataListener) notifications.keys().nextElement());
                        return true;
                    } catch (java.util.NoSuchElementException e) {
                    }
                }
                return false;
            }

            public void run() {
                while (true) try {
                    if (fireEvent())
                        yield();
                    else
                        doWait();
                } catch (Exception e) {}
            }

            private synchronized void doWait() {
                suspended = true;
                try { wait(); } catch (InterruptedException i) {}
                suspended = false;
            }

            public boolean flush() {
                boolean result = false;
                while (fireEvent()) { result = true; }
                return result;
            }
        }
        private static final String CIRCULARITY_TOKEN = "CIRCULARITY_TOKEN";

        DataNotifier dataNotifier;


        private class DataFreezer extends Thread implements RepositoryListener,
                DataConsistencyObserver, DataNameFilter.PrefixLocal
        {

            /** Keys in this hashtable are the String names of freeze tag
             * data elements.  Values are the FrozenDataSets to which they
             * refer. */
            private Hashtable frozenDataSets;

            /** A list of names of data elements which need to be frozen. */
            private Set itemsToFreeze;

            /** A list of names of data elements which need to be thawed. */
            private Set itemsToThaw;

            /** Flag indicating that we've received a request to terminate. */
            private volatile boolean terminate = false;

            private Object runLock = new Object();
            private Object waitLock = new Object();

            private Logger logger = Logger.getLogger(DataFreezer.class.getName());

            public DataFreezer() {
                super("DataFreezer");
                setDaemon(true);
                frozenDataSets = new Hashtable();
                itemsToFreeze = Collections.synchronizedSet(new LinkedHashSet());
                itemsToThaw = Collections.synchronizedSet(new LinkedHashSet());
                addRepositoryListener(this, "");
                start();
            }

            public void run() {
                // run this thread until ordered to terminate
                while (!terminate) {
                    // Wait until the data is consistent - don't freeze or thaw anything
                    // while files are being opened and closed.
                    addDataConsistencyObserver(this);

                    // Sleep until we're needed again.
                    waitForWork();
                }

                // On termination, make one last sweep for data to freeze.
                dataIsConsistent();
            }

            public void dataIsConsistent() {
                synchronized (runLock) {
                    try {
                        // Perform all requested work.
                        MAX_DIRTY = Integer.MAX_VALUE;
                        deferDeletions = true;
                        while (!itemsToFreeze.isEmpty() || !itemsToThaw.isEmpty()) {
                            freezeAll();
                            thawAll();
                        }
                        deferDeletions = false;
                        processedDeferredDataListenerDeletions();
                        MAX_DIRTY = 10;
                        saveAllDatafiles();
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "DataFreezer got exception", t);
                    } finally {
                        runLock.notifyAll();
                    }
                }
            }

            private void waitForWork() {
                if (!terminate)
                    synchronized (waitLock) {
                        while (!terminate && itemsToFreeze.isEmpty() && itemsToThaw.isEmpty()) {
                            try {
                                waitLock.wait();
                            } catch (InterruptedException e) {
                            }
                        }
                    }
            }

            private void stopWaitingForWork() {
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }
            }

            public boolean flush() {
                if (terminate)
                    return false;

                boolean result = false;
                synchronized (runLock) {
                    while (!itemsToFreeze.isEmpty() || !itemsToThaw.isEmpty()) {
                        try {
                            result = true;
                            runLock.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                }
                return result;
            }

            /** Freeze all waiting items. */
            private void freezeAll() {
                String item;
                while ((item = pop(itemsToFreeze)) != null)
                    performFreeze(item);
            }

            /** Thaw all waiting items. */
            private void thawAll() {
                String item;
                while ((item = pop(itemsToThaw)) != null)
                    performThaw(item);
            }

            /** Pop the first item off a set, in a thread-safe fashion.
             * @return a item which has been removed from the set, or null if
             *  the set is empty.
             */
            private String pop(Set set) {
                synchronized (set) {
                    if (set.isEmpty())
                        return null;
                    else {
                        String result = (String) set.iterator().next();
                        set.remove(result);
                        return result;
                    }
                }
            }

            public void terminate() {
                // Stop listening for events.
                removeRepositoryListener(this);

                // stop this thread (if the thread is currently awake, this will
                // not have an immediate effect.)
                terminate = true;
                stopWaitingForWork();
            }

            public boolean acceptPrefixLocalName(String prefix, String localName) {
                return localName.indexOf(FREEZE_FLAG_TAG2) != -1;
            }

            public void dataAdded(String dataName) {
                if (isFreezeFlagElement(dataName))
                    synchronized (frozenDataSets) {
                        if (!frozenDataSets.containsKey(dataName))
                              try {
                                  FrozenDataSet s;
                                  if (USE_OCD_FREEZING_LOGIC)
                                      s = new OCDFrozenDataSet(dataName);
                                  else
                                      s = new SimpleFrozenDataSet(dataName);
                                  frozenDataSets.put(dataName, s);
                              } catch (PatternSyntaxException pse) {
                                  logger.log(Level.WARNING, "The regular expression "
                                          + "for {0} is malformed.", dataName);
                              }
                    }
            }

            public void dataRemoved(String dataName) {
                if (!isFreezeFlagElement(dataName)) return;
                FrozenDataSet set = (FrozenDataSet) frozenDataSets.remove(dataName);
                if (set != null)
                    set.dispose();
            }

            private boolean isFreezeFlagElement(String dataName) {
                return (dataName.indexOf(FREEZE_FLAG_TAG) != -1);
            }

            /** Perform the work required to freeze a data value. */
            private void performFreeze(String dataName) {
                DataElement element = getOrCreateDefaultDataElement(dataName);
                if (element == null) return;

                try {
                    element.lockFromDisposal();
                    performFreeze(dataName, element);
                } finally {
                    element.unlockForDisposal();
                }
            }

            /** Perform the work required to freeze a data element.
             * 
             * The caller should lock the element from disposal before calling this
             * method, and unlock it afterward.
             */
            private void performFreeze(String dataName, DataElement element) {
                // Make certain no data values are currently in a state of flux
                dataNotifier.flush();

                // Retrieve the value of the element
                SaveableData value = element.getValue();

                // For now, lets add this in - don't doubly freeze data.  Supporting
                // double freezing of data might make it easier for the people who
                // write freeze flag expressions, but it makes things more confusing
                // for end users:
                //  * data items that are frozen by multiple freeze flags perplex
                //    the user: they toggle some boolean value and can't figure out
                //    why the data isn't thawing
                //  * sometimes it is possible for data accidentally to become doubly
                //    frozen by the SAME freeze flag.  Then users toggle the flag and
                //    their data toggles between frozen and doubly frozen.
                if (value instanceof FrozenData)
                    return;

                // Determine the prefix of the data element.
                String prefix = "";
                if (element.datafile != null) {
                    // don't freeze data elements in imported files.
                    if (element.datafile.isImported) return;
                    prefix = element.datafile.prefix;
                }

                // Don't freeze null data elements when there is no default value.
                if (value == null && !element.isDefaultName()) return;

                // Create the frozen version of the value.
                SaveableData frozenValue = new FrozenData(dataName, value,
                            DataRepository.this, prefix, element.isDefaultValue());

                // Make one last check to ensure that some other thread hasn't
                // disposed of or altered the element we were freezing.
                if (element.getValue() == value && data.get(dataName) == element) {
                    // Save the frozen value to the repository.
                    logger.log(Level.FINE, "Freezing data element {0}", dataName);
                    putValue(dataName, frozenValue, IS_NOT_DEFAULT_VAL);

                } else {
                    // If another thread disposed of the element or altered its value
                    // while this method was running, start over from the beginning.
                    performFreeze(dataName);
                }
            }

            /** Perform the work required to thaw a data value. */
            private void performThaw(String dataName) {
                DataElement element = (DataElement) data.get(dataName);
                if (element == null) return;
                if (element.datafile != null && element.datafile.isImported) return;
                logger.log(Level.FINE, "Thawing data element {0}", dataName);

                SaveableData value = element.getValue(), thawedValue;
                if (value instanceof FrozenData) {
                    // Thaw the value.
                    FrozenData fd = (FrozenData) value;
                    thawedValue = fd.thaw();
                    boolean isDefaultVal = (thawedValue == FrozenData.DEFAULT);
                    if (isDefaultVal) {
                        boolean fileIsReadOnly = (element.datafile == null ? false
                                : !element.datafile.canWrite);
                        thawedValue = instantiateValue(dataName, fd.getPrefix(),
                                lookupDefaultValueObject(dataName, element),
                                fileIsReadOnly);
                    }

                    // Save the thawed value to the repository.
                    putValue(dataName, thawedValue, isDefaultVal);
                }
            }

            /** Register the named data elements for freezing.
             *
             * The elements are not frozen immediately, but rather added to a
             * queue for freezing sometime in the future.
             */
            public void freeze(Collection dataNames) {
                synchronized (itemsToThaw) {
                    synchronized (itemsToFreeze) {
                        for (Iterator iter = dataNames.iterator(); iter.hasNext();) {
                            String dataName = (String) iter.next();
                            if (itemsToThaw.remove(dataName) == false)
                                itemsToFreeze.add(dataName);
                        }
                    }
                }
                stopWaitingForWork();
            }

            /** Register the named data elements for thawing.
             *
             * The elements are not thawed immediately, but rather added to a
             * queue for thawing sometime in the future.
             */
            public void thaw(Collection dataNames) {
                synchronized (itemsToThaw) {
                    synchronized (itemsToFreeze) {
                        for (Iterator iter = dataNames.iterator(); iter.hasNext();) {
                            String dataName = (String) iter.next();
                            if (itemsToFreeze.remove(dataName) == false)
                                itemsToThaw.add(dataName);
                        }
                    }
                }
                stopWaitingForWork();
            }

            private abstract class FrozenDataSet {
                public abstract void dispose();
            }

            private class OCDFrozenDataSet extends FrozenDataSet implements
                      DataListener, RepositoryListener, DataConsistencyObserver {

                String freezeFlagName;
                Pattern freezeRegexp;
                Set dataItems;
                int currentState = FDS_GRANDFATHERED;
                boolean observedFlagValue;
                volatile boolean initializing;
                Set tentativeFreezables;
                char[] buffer = null;

                public OCDFrozenDataSet(String freezeFlagName)
                            throws PatternSyntaxException {
                    this.freezeFlagName = freezeFlagName;

                    logger.log(Level.FINE, "Creating FrozenDataSet for {0}",
                            freezeFlagName);

                    // Fetch the prefix and the regular expression.
                    int pos = freezeFlagName.indexOf(FREEZE_FLAG_TAG);
                    if (pos == -1) return; // shouldn't happen!

                    String prefix = freezeFlagName.substring(0, pos+1);
                    this.freezeRegexp = Pattern.compile(ValueFactory.regexpQuote(prefix)
                            + freezeFlagName.substring(pos + FREEZE_FLAG_TAG.length()));

                    this.initializing = true;
                    this.tentativeFreezables = new HashSet();
                    this.dataItems = Collections.synchronizedSet(new HashSet());

                    addDataListener(freezeFlagName, this);

                    addRepositoryListener(this, prefix);
                }

                public synchronized void dispose() {
                    removeRepositoryListener(this);
                    dataItems.clear();
                    deleteDataListener(this);
                }

                private void freeze(Collection itemNames) {
                    if (initializing) tentativeFreezables.addAll(itemNames);
                    else DataFreezer.this.freeze(itemNames);
                }
                private void freeze(String itemName) {
                    if (initializing) tentativeFreezables.add(itemName);
                    else DataFreezer.this.freeze(Collections.singleton(itemName));
                }

                private void freezeAll(Set dataItems) {
                    logger.log(Level.FINE, "Need to freeze data elements for flag {0}",
                            freezeFlagName);
                    synchronized (dataItems) {
                        freeze(dataItems);
                    }
                }

                private void thawAll(Set dataItems) {
                    logger.log(Level.FINE, "Need to thaw data elements for flag {0}",
                            freezeFlagName);
                    synchronized (dataItems) {
                        thaw(dataItems);
                    }
                }

                // The next two methods implement the DataListener interface.

                public void dataValueChanged(DataEvent e) {
                    if (! freezeFlagName.equals(e.getName())) return;
                    observedFlagValue = (e.getValue() != null && e.getValue().test());
                    addDataConsistencyObserver(this);
                }

                public void dataValuesChanged(Vector v) {
                    if (v == null || v.size() == 0) return;
                    for (int i = v.size();  i > 0; )
                        dataValueChanged((DataEvent) v.elementAt(--i));
                }

                /** Respond to a change in the value of the freeze flag.
                 *  The state transition diagram is: <PRE>
                 *
                 *     current
                 *     state     freeze flag = TRUE         freeze flag = FALSE
                 *     -------   ------------------         -----------------------
                 *     FROZEN    no change                  set to thawed; thaw all
                 *     GRAND     no change                  set to thawed
                 *     THAWED    set to frozen; freeze all  no change
                 *
                 * </PRE>
                 */
                public void dataIsConsistent() {
                    //System.out.println(freezeFlagName + " = "+ observedFlagValue);
                    synchronized (this) {
                        if (observedFlagValue == true) {
                            // data should be frozen or grandfathered.
                            if (currentState == FDS_THAWED) {
                                currentState = FDS_FROZEN;
                                freezeAll(dataItems);
                            }

                        } else {            // data should be thawed.
                            if (currentState == FDS_FROZEN && !initializing)
                                thawAll(dataItems);
                            currentState = FDS_THAWED;
                        }

                        if (initializing) {
                            initializing = false;
                            if (currentState == FDS_FROZEN)
                                freezeAll(tentativeFreezables);
                            tentativeFreezables = null;
                        }
                    }
                }

                /** Respond to a notification about a data element that has been
                 *  added to the repository.
                 *
                 *  (Note that this happens during initial opening of
                 *  datafiles as well as on an ongoing basis as new elements
                 *  are created.) The state transition diagram is: <PRE>
                 *
                 *     current
                 *     state     item = THAWED        item = FROZEN
                 *     -------   -------------------  -------------
                 *     FROZEN    freeze the item (1)  no action
                 *     GRAND     no action            set to frozen; freeze all
                 *     THAWED    no action            no action (2)
                 *
                 * </PRE>
                 * Notes:<P>
                 * (1) This situation would most likely occur as the result of
                 *     freezing a project, then installing a new definition for its
                 *     process. If the new process definition defines a new data
                 *     element, then this situation would be triggered; the best
                 *     course of action is to freeze it along with its colleagues.<P>
                 *
                 * (2) A single data item might belong to two distinct FreezeSets.
                 *     If both sets were frozen, it would be <b>doubly</b> frozen.
                 *     On the other hand, it might be frozen by one but not the
                 *     other, triggering this scenario.
                 */
                public void dataAdded(String dataName) {
                    if (isFreezeFlagElement(dataName))
                        return;           // don't freeze freeze flags!
                    if (!freezeRegexp.matcher(dataName).matches())
                        return;           // only freeze data which matches the regexp.

                    DataElement e = (DataElement) data.get(dataName);
                    SaveableData value = (e == null ? null : e.getValue());
                    boolean valueIsFrozen = (value instanceof FrozenData);

                    synchronized (this) {
                        if (currentState == FDS_GRANDFATHERED && valueIsFrozen) {
                            freezeAll(dataItems);
                            currentState = FDS_FROZEN;
                        } else if (currentState == FDS_FROZEN && !valueIsFrozen) {
                            freeze(dataName);
                        }

                        dataItems.add(dataName);
                    }
                }

                public void dataRemoved(String dataName) {
                    dataItems.remove(dataName);
                }
            }

            private class SimpleFrozenDataSet extends FrozenDataSet implements
                      DataListener, DataConsistencyObserver {

                private String freezeFlagName;
                private int currentState;
                private volatile boolean initializing;

                public SimpleFrozenDataSet(String freezeFlagName) {
                    logger.log(Level.FINE, "Creating FrozenDataSet for {0}",
                            freezeFlagName);

                    this.freezeFlagName = freezeFlagName;
                    this.currentState = FDS_GRANDFATHERED;
                    this.initializing = true;
                    addDataListener(freezeFlagName, this, DO_NOT_NOTIFY);
                    addDataConsistencyObserver(this);
                }

                public synchronized void dispose() {
                    removeDataListener(freezeFlagName, this);
                    frozenDataSets.remove(freezeFlagName);
                }

                // The next two methods implement the DataListener interface.

                public void dataValueChanged(DataEvent e) {
                    if (freezeFlagName.equals(e.getName()))
                        addDataConsistencyObserver(this);
                }

                public void dataValuesChanged(Vector v) {
                    if (v == null || v.size() == 0) return;
                    for (int i = v.size();  i > 0; )
                        dataValueChanged((DataEvent) v.elementAt(--i));
                }

                /** Respond to a change in the value of the freeze flag.
                 */
                public synchronized void dataIsConsistent() {
                    //System.out.println(freezeFlagName + " = "+ observedFlagValue);
                    SimpleData flagValue = getSimpleValue(freezeFlagName);
                    boolean flagState = (flagValue != null && flagValue.test());
                    int newState = (flagState ? FDS_FROZEN : FDS_THAWED);

                    if (initializing) {
                        initializing = false;
                        currentState = newState;

                    } else if (currentState != newState) {
                        currentState = newState;
                        Collection items = getItemsInFrozenDataSet();
                        if (items == null)
                            dispose();
                        else if (newState == FDS_FROZEN) {
                            logger.log(Level.FINE,
                                      "Freezing data elements for flag {0}",
                                      freezeFlagName);
                            freeze(items);
                        } else if (newState == FDS_THAWED) {
                            logger.log(Level.FINE,
                                    "Thawing data elements for flag {0}",
                                    freezeFlagName);
                            thaw(items);
                        }
                    }
                }

                private Collection getItemsInFrozenDataSet() {
                      // Fetch the prefix and the regular expression.
                      int prefixLen = freezeFlagName.indexOf(FREEZE_FLAG_TAG);
                      if (prefixLen == -1)
                          return null; // shouldn't happen!

                      String prefix = freezeFlagName.substring(0, prefixLen);
                      Pattern freezeRegexp;
                      try {
                          String re = freezeFlagName.substring(prefixLen
                                  + FREEZE_FLAG_TAG.length());
                          freezeRegexp = Pattern.compile(re);
                      } catch (PatternSyntaxException pse) {
                          logger.log(Level.WARNING, "The regular expression "
                                  + "for {0} is malformed.", freezeFlagName);
                          return null;
                      }

                      logger.log(Level.FINER,
                              "Searching for data items matching flag {0}",
                              freezeFlagName);
                      Collection result = new ArrayList();
                      for (Iterator i = getKeys(prefix, null); i.hasNext();) {
                          String dataName = (String) i.next();
                          if (dataName.length() <= prefixLen
                                  || !Filter.pathMatches(dataName, prefix)
                                  || isFreezeFlagElement(dataName)
                                  || PercentageFunction.isPercentageDataName(dataName))
                              continue;
                          String dataNameTail = dataName.substring(prefixLen + 1);
                          if (freezeRegexp.matcher(dataNameTail).matches()) {
                              result.add(dataName);
                              logger.log(Level.FINER, "Found data item: {0}",
                                      dataName);
                          }
                      }

                      return result;
                    }
            }
        }
        private static final boolean USE_OCD_FREEZING_LOGIC = false;
        private static final String FREEZE_FLAG_TAG = "/FreezeFlag/";
        private static final String FREEZE_FLAG_TAG2 = FREEZE_FLAG_TAG.substring(1);
        private static final int FDS_FROZEN = 0;
        private static final int FDS_GRANDFATHERED = 1;
        private static final int FDS_THAWED = 2;

        DataFreezer dataFreezer;

        public void disableFreezing() {
            if (dataFreezer != null) {
                dataFreezer.terminate();
                dataFreezer = null;
            }
        }

        private static final int JANITOR_DATA_AGE_SECONDS = Integer.getInteger(
                DataRepository.class.getName() + ".dataAge", 10).intValue();
        private static final int JANITOR_DATA_ELEM_COUNT =  Integer.getInteger(
                DataRepository.class.getName() + ".dataCount", 30000).intValue();
        private static final int JANITOR_DATA_LIFESPAN =  Integer.getInteger(
                DataRepository.class.getName() + ".dataGenerations", 3).intValue();

        private static final int MAX_NEW_ITEMS_PER_GENERATION =
            JANITOR_DATA_ELEM_COUNT / JANITOR_DATA_LIFESPAN;
        private static final int JANITOR_GENERATION_TIME =
            (JANITOR_DATA_AGE_SECONDS * 1000) / JANITOR_DATA_LIFESPAN;

        volatile byte currentGeneration = 0;

        private class DataJanitor extends Thread {

            volatile long newItemsInCurrentGeneration = 0;

            volatile boolean workToDo = false;

            private Logger logger = Logger.getLogger(DataRepository.class.getName()
                    + ".Janitor");

            public DataJanitor() {
                super("DataJanitor");
                setPriority((NORM_PRIORITY + MAX_PRIORITY) / 2);
                setDaemon(true);
                start();
            }

            public void run() {
                while (true) {
                    if (!workToDo)
                        waitForWork(JANITOR_GENERATION_TIME);

                    currentGeneration++;

                    runCleanup();
                }
            }

            /** Perform an explicitly requested, complete cleaning of the elements
             * starting with any of the given prefixes.
             * 
             * @param prefixes  a list of prefixes to match, or null to match any
             *    prefix.
             */
            public void cleanup(Collection prefixes) {
                int sizeBefore = data.size();
                long timeBefore = System.currentTimeMillis();
                checkMemory();

                for (Iterator i = getInternalKeys(); i.hasNext();) {
                    String dataName = (String) i.next();
                    DataElement d = (DataElement) data.get(dataName);
                    if (isDefaultElement(d) && !d.hasListeners()
                            && d.disposalLockCount == 0
                            && Filter.matchesFilter(prefixes, dataName)) {
                        cleanup(d);
                    }
                }

                debugCleanupResults("cleanup", sizeBefore, timeBefore);
            }

            private void debugCleanupResults(String when, int sizeBefore,
                    long timeBefore) {
                int sizeAfter = data.size();
                long timeAfter = System.currentTimeMillis();
                if (logger.isLoggable(Level.FINER)) {
                    if (sizeBefore != sizeAfter)
                        logger.finer("janitor." + when + ": sizeBefore="
                                + sizeBefore + ", sizeAfter=" + sizeAfter
                                + ", timeToGC=" + (timeAfter - timeBefore) + "ms");
                    else
                        logger.finer("janitor." + when + ": no change; size="
                                + sizeBefore + ", timeToGC="
                                + (timeAfter - timeBefore) + "ms");
                }
            }

            private synchronized void newWork() {
                workToDo = true;
                notifyAll();
            }

            private synchronized void waitForWork(int delay) {
                if (workToDo == false)
                    try {
                        wait(delay);
                    } catch (InterruptedException e) {
                    }
            }

            private DataElement touch(DataElement e) {
                if (e != null)
                    e.timestamp = currentGeneration;
                return e;
            }

            private DataElement itemWasCreated(DataElement e) {
                if (newItemsInCurrentGeneration++ > MAX_NEW_ITEMS_PER_GENERATION)
                    newWork();
                return e;
            }

            private void runCleanup() {
                workToDo = false;
                newItemsInCurrentGeneration = 0;

                int cleanedCount = 0;
                int potentiallyCleanableCount = 0;
                int cleanableCount = 0;

                int sizeBefore = data.size();
                long timeBefore = System.currentTimeMillis();
                checkMemory();

                for (Iterator i = getInternalKeys(); i.hasNext();) {
                    String dataName = (String) i.next();
                    DataElement d = (DataElement) data.get(dataName);
                    if (isDefaultElement(d)) {
                        potentiallyCleanableCount++;
                        if (!d.hasListeners()) {
                            cleanableCount++;
                            int age = currentGeneration - d.timestamp;
                            if (age > JANITOR_DATA_LIFESPAN
                                    && d.disposalLockCount == 0) {
                                cleanup(d);
                                cleanedCount++;
                            }
                        }
                    }
                }

                debugCleanupResults("runCleanup", sizeBefore, timeBefore);

                if (cleanableCount == 0 && workToDo == false
                        && newItemsInCurrentGeneration == 0) {
                    // if we didn't find anything to clean, and no new items have
                    // arrived since we started this method, go into a deep sleep.
                    logger.finest("Nothing to do: deep sleeping...");
                    waitForWork(JANITOR_GENERATION_TIME * 50);
                    logger.finest("Waking up from deep sleep. dataSize="
                            + data.size());

                } else if ((potentiallyCleanableCount - cleanedCount
                                > JANITOR_DATA_ELEM_COUNT)
                        && cleanableCount > 10) {
                    // if there is LOTS of stuff to clean up, but we didn't
                    // accomplish much on this iteration, start a new generation
                    // right away.
                    logger.finest("Lots of work! Restarting quickly.");
                    waitForWork(200);
                    workToDo = true;
                }
            }

            private final boolean isDefaultElement(DataElement e) {
                return (e != null
                        && e.isDefaultValue()
                        && !shouldCreateEagerly(e.getValue()));
            }

            private void cleanup(DataElement e) {
                synchronized (e) {
                    if (e.disposalLockCount == 0) {
                        data.remove(e.name);
                        e.disposeValue();
                    }
                }
            }
        }

        private DataJanitor janitor;



        URL [] templateURLs = null;



        public DataRepository() {
            INTERN_MAP = data;
            includedFileCache.put("<dataFile.txt>", globalDataDefinitions);
            dataNotifier = new DataNotifier();
            dataFreezer  = new DataFreezer();
            janitor = new DataJanitor();
        }

        public void startServer(ServerSocket socket) {
            if (dataServer == null) {
                dataServer = new RepositoryServer(this, socket);
                dataServer.start();
            }
        }

        public void startSecondServer(ServerSocket socket) {
            if (secondaryDataServer == null) {
                secondaryDataServer = new RepositoryServer(this, socket);
                secondaryDataServer.start();
            }
        }

        public boolean areDatafilesDirty() {
            if (!saveDisabled) {
                synchronized (datafiles) {
                    for (Iterator i = datafiles.iterator(); i.hasNext();) {
                        DataFile datafile = (DataFile) i.next();
                        if (datafile.file != null && datafile.dirtyCount > 0)
                            return true;
                    }
                }
            }
            return false;
        }

        public void saveAllDatafiles() {
            List files;
            synchronized (datafiles) {
                files = new ArrayList(datafiles);
            }

            for (Iterator i = files.iterator(); i.hasNext();) {
                DataFile datafile = (DataFile) i.next();
                try {
                    if (datafile.dirtyCount > 0)
                        saveDatafile(datafile);
                } catch (Exception e) {
                    logger.log(Level.SEVERE,
                            "Encountered error when saving datafiles", e);
                }
            }
        }

        public void finalize() {
            logger.fine("Finalizing DataRepository");
            waitForCalculations();
            // Command the data freezer to terminate.
            if (dataFreezer != null) dataFreezer.terminate();
            try {
                // wait up to 6 seconds total for the DataFreezer thread to die.
                logger.finer("Waiting for DataFreezer");
                dataFreezer.join(6000);
            } catch (InterruptedException e) {}

            saveAllDatafiles();
            if (dataServer != null)
                dataServer.quit();
            if (secondaryDataServer != null)
                secondaryDataServer.quit();
        }

        /** Set this flag to true when performing memory measurement testing.
         */
        private static final boolean ENABLE_MEMORY_CHECKS = false;
        private static URL MEM_CHECK_URL;
        static {
            if (ENABLE_MEMORY_CHECKS) {
                String propName = DataRepository.class.getName() + ".memCheckURL";
                String memCheckUrl = System.getProperty(propName);
                if (memCheckUrl != null)
                    try {
                        MEM_CHECK_URL = new URL(memCheckUrl);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
            }
        }
        private void checkMemory() {
            if (ENABLE_MEMORY_CHECKS && MEM_CHECK_URL != null) {
                try {
                    MEM_CHECK_URL.openConnection().connect();
                } catch (Exception e) {};
            }
        }

        public void gc(Collection prefixes) {
            janitor.cleanup(prefixes);
        }

        public void setDatafileSearchURLs(URL[] templateURLs) {
            this.templateURLs = templateURLs;
        }


        public synchronized void renameData (String oldPrefix, String newPrefix) {
            DataFile datafile = getDataFileForPrefix(oldPrefix, true);

            if (datafile != null) {
                String datafileName = datafile.file.getPath();
                remapIDs(oldPrefix, newPrefix);

                closeDatafile(oldPrefix);     // close the datafile, then
                try {                         // open it again with the new prefix.
                    openDatafile(newPrefix, datafileName);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Problem reopening datafile", e);
                }

            } else {
                datafile = guessDataFile(oldPrefix+"/foo", REQUIRE_WRITABLE);
                if (datafile != null && datafile.prefix.length() == 0)
                    remapDataNames(oldPrefix, newPrefix);
            }
        }

        /** this renames data values in the global datafile. */
        private void remapDataNames(String oldPrefix, String newPrefix) {

            String name, newName;
            DataElement element;
            SaveableData value;

            oldPrefix = oldPrefix + "/";
            newPrefix = newPrefix + "/";
            int oldPrefixLen = oldPrefix.length();
            Iterator k = getInternalKeys();
            while (k.hasNext()) {
                name = (String) k.next();
                if (!name.startsWith(oldPrefix))
                    continue;

                element = (DataElement) data.get(name);
                if (element == null ||
                    element.isDefaultName() ||
                    element.datafile == null ||
                    element.datafile.prefix == null ||
                    element.datafile.prefix.length() > 0)
                    // only remap data which lives in the global datafile.
                    continue;

                value = element.getValue();

                // At this point, we will not rename data elements unless they
                // are SimpleData.  Non-simple data (e.g., functions, etc) needs
                // to know its name and prefix, so it would be more complicated to
                // move - but none of that stuff should be moving.
                if (value instanceof SimpleData) {
                    newName = newPrefix + name.substring(oldPrefixLen);
                    newName = intern(newName, false);
                    //System.out.println("renaming " + name + " to " + newName);
                    putValue(newName, value.getSimpleValue(), IS_NOT_DEFAULT_VAL);
                    putValue(name, null, IS_NOT_DEFAULT_VAL);
                }
            }
        }


        private static final boolean disableSerialization = true;
        private boolean definitionsDirty = true;
        public void maybeSaveDefinitions(File out) throws IOException {
            if (definitionsDirty)
                saveDefinitions(new FileOutputStream(out));
        }
        public void saveDefinitions(OutputStream out) throws IOException {
            if (disableSerialization) return;
            ObjectOutputStream o = new ObjectOutputStream(out);
            o.writeObject(includedFileCache);
            o.writeObject(defineDeclarations);
            o.writeObject(defaultDefinitions);
            o.writeObject(globalDataDefinitions);
            o.writeObject(mountedPhantomData);
            o.close();
            definitionsDirty = false;
        }
        public void loadDefinitions(InputStream in) {
            if (disableSerialization) return;
            try {
                ObjectInputStream i = new ObjectInputStream(in);
                Hashtable a, b, c, d, e;
                a = (Hashtable) i.readObject();
                b = (Hashtable) i.readObject();
                c = (Hashtable) i.readObject();
                d = (Hashtable) i.readObject();
                e = (Hashtable) i.readObject();
                remountPhantomData(e);
                includedFileCache.putAll(a);
                defineDeclarations.putAll(b);
                defaultDefinitions.putAll(c);
                globalDataDefinitions.putAll(d);
                System.out.println("loaded serialized definitions.");
                in.close();
                definitionsDirty = false;
            } catch (Throwable t) {}
        }


        public void dumpRepository(PrintWriter out, Vector filt) {
            dumpRepository(out, (Collection) filt, false);
        }

        public void dumpRepository(PrintWriter out, Collection filt) {
            dumpRepository(out, filt, false);
        }

        public void dumpRepository(PrintWriter out, Vector filt,
                boolean dataStyle) {
            dumpRepository(out, (Collection) filt, dataStyle);
        }

        public void dumpRepository(PrintWriter out, Collection filt,
                boolean dataStyle) {
            gc(null);

            Iterator k = getKeys();
            String name, value;
            DataElement  de;
            SimpleData sd;

                                      // print out all element values.
            while (k.hasNext()) {
                name = (String) k.next();
                if (net.sourceforge.processdash.hier.Filter.matchesFilter(filt, name)) {
                    try {
                        de = getOrCreateDefaultDataElement(name);
                        if (de != null && de.datafile != null) {
                            value = null;
                            sd = de.getSimpleValue();
                            if (sd == null) continue;

                            if (dataStyle) {
                                value = sd.saveString();
                            } else if (sd instanceof DateData) {
                                value = ((DateData)sd).formatDate();
                            } else if (sd instanceof StringData) {
                                value = StringData.escapeString(((StringData)sd).getString());
                                // } else if (sd instanceof DoubleData) {
                                // value = ((DoubleData)sd).formatNumber(3);
                            } else
                                value = sd.toString();

                            if (dataStyle) {
                                out.println(name.substring(1) + "==" + value);
                            } else {
                                if (name.indexOf(',') != -1)
                                    name = EscapeString.escape(name, '\\', ",", "c");
                                out.println(name + "," + value);
                            }
                        }
                    } catch (Exception e) {
//          System.err.println("Data error:"+e.toString()+" for:"+name);
                    }
                }

                Thread.yield();
            }
        }


        public void dumpRepositoryListeners(Writer out) throws IOException {

            // print out all element values.
            for (Iterator i = getInternalKeys(); i.hasNext();) {
                String name = (String) i.next();
                DataElement element = (DataElement) data.get(name);
                if (element == null)
                    continue;

                out.write(name);
                out.write("=" + element.getValue());
                if (element.dataListeners != null)
                    out.write(", listeners=" + element.dataListeners);
                out.write("\n");
            }
        }

        public synchronized void closeDatafile(String prefix) {
            logger.log(Level.FINE, "Closing datafile for prefix {0}", prefix);

            gc(Collections.singleton(prefix));

            startInconsistency();

            try {
                // find the datafile associated with 'prefix'
                DataFile datafile = getDataFileForPrefix(prefix, false);

                if (datafile != null) {

                    remapIDs(prefix, "///deleted//" + prefix);

                                          // save previous changes to the datafile.
                                          // FIXME: if this fails due to file I/O, the
                                          // unsaved changes will be lost.
                    if (datafile.dirtyCount > 0)
                        saveDatafile(datafile);

                                            // remove 'datafile' from the list of
                                            // datafiles in this repository.
                    datafile.isRemoved = true;
                    datafiles.removeElement(datafile);

                    Iterator k = getInternalKeys();
                    String name;
                    DataElement element;
                    Vector elementsToRemove = new Vector();

                    Set inheritedDataNames = new HashSet();
                    if (datafile.inheritedDefinitions != null)
                        inheritedDataNames.addAll(datafile.inheritedDefinitions.keySet());

                                          // build a list of all the data elements of
                                          // this datafile.
                    while (k.hasNext()) {
                        name = (String) k.next();
                        element = (DataElement)data.get(name);
                        if (element != null && element.datafile == datafile) {
                            elementsToRemove.addElement(name);
                            elementsToRemove.addElement(element);

                            String localName = name.substring(prefix.length() + 1);
                            inheritedDataNames.remove(localName);
                        }
                    }

                                          // call the dispose() method on all the data
                                          // elements' values.
                    for (int i = elementsToRemove.size();  i > 0; ) {
                        element = (DataElement) elementsToRemove.elementAt(--i);
                        name    = (String) elementsToRemove.elementAt(--i);
                        element.disposeValue();
                        element.datafile = null;
                    }
                                          // remove the data elements.
                    for (int i = elementsToRemove.size();  i > 0; ) {
                        element = (DataElement) elementsToRemove.elementAt(--i);
                        name    = (String) elementsToRemove.elementAt(--i);
                        removeValue(name);
                    }

                                          // fire removal events for all of the inherited
                                          // data elements that are no longer in the
                                          // repository.
                    for (Iterator i = inheritedDataNames.iterator(); i.hasNext();) {
                        String localName = (String) i.next();
                        name = prefix + "/" + localName;
                        repositoryListenerList.dispatchRemoved(name);
                    }
                }

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Problem closing datafile", e);
            } finally {
                finishInconsistency();
            }
        }

        private class DataElementAlreadyExistsException extends Exception {
            public DataElement elem;
            public DataElementAlreadyExistsException(DataElement elem) {
                System.out.println("DataElementAlreadyExistsException"+elem.name);
                this.elem = elem;
            }

        }

        /** Add a DataElement to the repository.
         * 
         * @param name the name of the data element
         * @param isDefaultName true if the name represents a data element that
         *    could possibly be auto-created from the definitions inherited by
         *    the element's DataFile.
         * @param value the value for the data element
         * @param isDefaultValue true if the value was auto-created from the
         *    definitions inherited by the element's DataFile.
         * @param datafile the DataFile for the element
         * @param notify true if a dataAdded event should be sent to registered
         *    RepositoryListeners
         * @return the DataElement that was added to the repository.
         * @throws DataElementAlreadyExistsException if a data element is already
         *    present in the repository with that name.
         */
        private DataElement add(String name, boolean isDefaultName,
                SaveableData value, boolean isDefaultValue, DataFile datafile,
                boolean notify) throws DataElementAlreadyExistsException {

                                    // Add the element to the table
            DataElement d = new DataElement(datafile, name, isDefaultName);
            d.setValue(value, isDefaultValue);
            synchronized (data) {
                DataElement displaced = (DataElement) data.put(name, d);
                if (displaced != null) {
                    // restore the displaced data element. Then throw an exception
                    // to our caller indicating that they cannot add the element,
                    // because it already exists.
                    data.put(name, displaced);
                    throw new DataElementAlreadyExistsException(displaced);
                }
            }

            if (notify && !isDefaultName && !name.startsWith(anonymousPrefix))
                repositoryListenerList.dispatchAdded(name);

            return d;
        }



        /** remove the named data element.
         * @param name             the name of the element to remove.
         */
        public synchronized void removeValue(String name) {

            DataElement removedElement = (DataElement)data.get(name);

            // if the named object existed in the repository,
            if (removedElement != null) {

                removedElement.disposeValue();

                                        // notify any data listeners
                removedElement.setValue(null, false);
                dataNotifier.dataChanged(name, removedElement);

                                        // notify any repository listeners
                if (!name.startsWith(anonymousPrefix))
                    repositoryListenerList.dispatchRemoved(name);

                          // flag the element's datafile as having been modified
                if (removedElement.datafile != null)
                    datafileModified(removedElement.datafile);

                                          // disown the element from its datafile,
                removedElement.datafile = null;
                removedElement.disposeValue();
                removedElement.setValue(null, false);  // erase its previous value,
                maybeDelete(name, removedElement, true); // and discard if appropriate.
            }
        }


        /** Determine which data file an element with the given name would be
         * placed in.
         */
        private DataFile guessDataFile(String name, boolean requireWritable) {

            int pos = name.indexOf("//");
            if (pos == -1 || isLegacyDoubleSlashDataName(name, pos))
                synchronized (datafiles) {
                    for (Iterator i = datafiles.iterator(); i.hasNext();) {
                        DataFile datafile = (DataFile) i.next();
                        if (requireWritable) {
                            if (datafile.file == null)
                                continue;
                            if (!datafile.canWrite)
                                continue;
                        }
                        if (name.length() > datafile.prefix.length()
                                && Filter.pathMatches(name, datafile.prefix, true))
                            return datafile;
                    }
                }

            return null;
        }

        private boolean isLegacyDoubleSlashDataName(String name, int pos) {
            for (int i = 0; i < LEGACY_DOUBLE_SLASH_PREFIXES.length; i++) {
                String prefix = LEGACY_DOUBLE_SLASH_PREFIXES[i];
                if (name.regionMatches(pos - prefix.length(), prefix, 0,
                        prefix.length()))
                    return true;
            }

            for (int i = 0; i < LEGACY_DOUBLE_SLASH_SUFFIXES.length; i++) {
                String suffix = LEGACY_DOUBLE_SLASH_SUFFIXES[i];
                if (name.regionMatches(pos+2, suffix, 0, suffix.length()))
                    return true;
            }

            return false;
        }
        private static final String[] LEGACY_DOUBLE_SLASH_PREFIXES = {
            "Local_Sized_Object_List"
        };
        private static final String[] LEGACY_DOUBLE_SLASH_SUFFIXES = {
            "Export_Manager"
        };

        /** Return the datafile whose prefix exactly matches the given prefix.
         * 
         * @param prefix the data file prefix to look for.
         * @param requireNonNullFile if true, only items with a non-null file
         *          will be considered a match.
         * @return the matching datafile
         */
        private DataFile getDataFileForPrefix(String prefix,
                boolean requireNonNullFile) {
            synchronized (datafiles) {
                for (Iterator i = datafiles.iterator(); i.hasNext();) {
                    DataFile f = (DataFile) i.next();
                    if (f.prefix.equals(prefix))
                        if (requireNonNullFile == false || f.file != null)
                            return f;
                }
            }
            return null;
        }



        public void maybeCreateValue(String name, String value, String prefix) {

            DataElement d = (DataElement)data.get(name);

            // if this name represents a default value that has lazily not yet been
            // constructed, do not create the default value.
            if (d == null && lookupDefaultValueObject(name, null) != null)
                return;

            if (d == null || d.getValue() == null) {
                SaveableData v;
                try {
                    v = ValueFactory.create(name, value, this, prefix);
                } catch (MalformedValueException e) {
                    v = new MalformedData(value);
                }
                if (d == null) {
                    DataFile f = guessDataFile(name, REQUIRE_WRITABLE);
                    try {
                        add(name, IS_NOT_DEFAULT_NAME, v, IS_DEFAULT_VAL, f,
                                DO_NOTIFY);
                    } catch (DataElementAlreadyExistsException e) {
                        d = e.elem;
                    }
                }
                if (d != null && d.getValue() == null)
                    putValue(name, v, IS_DEFAULT_VAL);
            }
        }


        public SaveableData getValue(String name) {
            DataElement d = getOrCreateDefaultDataElement(name);
            if (d == null)
                return null;
            else
                return d.getValue();
        }

        public final SimpleData getSimpleValue(String name) {
            DataElement d = getOrCreateDefaultDataElement(name);
            if (d == null)
                return null;
            else
                return d.getSimpleValue();
        }

        private DataElement getOrCreateDefaultDataElement(String dataName) {
            DataElement d = (DataElement)data.get(dataName);
            if (d == null) {
                try {
                    d = maybeCreateDefaultData(dataName);
                } catch (DataElementAlreadyExistsException e) {
                    d = e.elem;
                }
            }
            if (d == null) {
                try {
                    d = maybeCreatePercentage(dataName);
                } catch (DataElementAlreadyExistsException e) {
                    d = e.elem;
                }
            }
            return janitor.touch(d);
        }

        /** If the name designates a lazy default value, automatically creates that
         * value and returns it.  Otherwise, returns null.
         * 
         * @throws DataElementAlreadyExistsException if a data element with
         *   that name already exists
         */
        private DataElement maybeCreateDefaultData(String name)
                throws DataElementAlreadyExistsException {
            if (name == null)
                return null;

            DataFile f = guessDataFile(name, DO_NOT_REQUIRE_WRITABLE);
            if (f == null)
                return null;
            else if (f.isImported)
                return getImportedFileNullElement(f);
            else if (f.inheritedDefinitions == null)
                return null;

            String localName = name.substring(f.prefix.length() + 1);
            Object defaultValueObject = f.inheritedDefinitions.get(localName);
            if (defaultValueObject == null)
                return null;

            SaveableData value = instantiateValue(name, f.prefix,
                    defaultValueObject, !f.canWrite);
            return janitor.itemWasCreated(add(name, IS_DEFAULT_NAME, value,
                    IS_DEFAULT_VAL, f, DO_NOT_NOTIFY));
        }

        /** Return a DataElement which can act as a placeholder for null values
         * in an imported data file.
         *
         * Each imported file has a corresponding null placeholder.  If the
         * placeholder has already been created for the given file, it will be
         * returned.  If not, it will be created.
         * 
         * @param dataFile an imported data file
         * @return the null placeholder DataElement
         */
        private DataElement getImportedFileNullElement(DataFile dataFile) {
            if (dataFile == null || !dataFile.isImported)
                return null;
            String dataName = createDataName(dataFile.prefix,
                    IMPORTED_NULL_ELEMENT_NAME);
            DataElement result = (DataElement) data.get(dataName);
            if (result == null) {
                try {
                    result = add(dataName, IS_DEFAULT_NAME, null, IS_DEFAULT_VAL,
                            dataFile, false);
                } catch (DataElementAlreadyExistsException e) {
                    result = e.elem;
                }
            }
            return result;
        }
        private static final String IMPORTED_NULL_ELEMENT_NAME =
            "Imported_File_Null_Token";

        /** If the item looks like a percentage, automatically creates the
         * percentage on the fly and returns it.
         * 
         * @throws DataElementAlreadyExistsException if a data element with
         *   that name already exists
         */
        private DataElement maybeCreatePercentage(String name)
                throws DataElementAlreadyExistsException {

            if (!PercentageFunction.isPercentageDataName(name))
                return null;

            try {
                // Note: this is creating the new percentage object in the null
                // datafile.  This helps prevent it from being saved, but if the
                // user edits it, it will never be saved.  Is this correct?
                SaveableData result = new PercentageFunction(name, this);
                return add(name, IS_DEFAULT_NAME, result, IS_DEFAULT_VAL, null,
                        DO_NOT_NOTIFY);
            } catch (MalformedValueException mve) {
                return null;
            }
        }



        public SaveableData getInheritableValue(String prefix, String name) {
            return getInheritableValue(new StringBuffer(prefix), name);
        }

        public SaveableData getInheritableValue(StringBuffer prefix_, String name)
        {
            String prefix = prefix_.toString();
            String dataName = prefix + "/" + name;
            SaveableData result = getValue(dataName);
            int pos;
            while (result == null && prefix.length() > 0) {
                pos = prefix.lastIndexOf('/');
                if (pos == -1)
                    prefix = "";
                else
                    prefix = prefix.substring(0, pos);
                dataName = prefix + "/" + name;
                result = getValue(dataName);
            }
            if (result != null) prefix_.setLength(prefix.length());
            return result;
        }



        private static final int MAX_RECURSION_DEPTH = 100;
        private volatile int recursion_depth = 0;

        public void putValue(String name, SaveableData value) {
            putValue(name, value, IS_NOT_DEFAULT_VAL, MAYBE_MODIFYING_DATAFILE);
        }

        protected void putValue(String name, SaveableData value,
                boolean isDefaultValue) {
            putValue(name, value, isDefaultValue, MAYBE_MODIFYING_DATAFILE);
        }
        protected void putValue(String name, SaveableData value,
                boolean isDefaultValue, boolean checkDatafileModification) {

            if (recursion_depth >= MAX_RECURSION_DEPTH) {
                System.err.println
                    ("DataRepository detected circular dependency in data,\n" +
                     "    bailed out after " + MAX_RECURSION_DEPTH + " iterations.");
                new Exception().printStackTrace(System.err);
                return;
            }

            try {
                recursion_depth++;
                DataElement d = (DataElement)data.get(name);

                if (d != null) {
                                        // change the value of the data element.
                    SaveableData oldValue = d.getValue();
                    d.setValue(value, isDefaultValue);

                                          // possibly mark the datafile as modified.
                    if (checkDatafileModification &&
                        d.datafile != null &&
                        value != oldValue &&
                        (oldValue == null || value == null ||
                         !value.saveString().equals(oldValue.saveString()))) {

                        // This data element has been changed and should be saved.

                        if (PHANTOM_DATAFILES.contains(d.datafile))
                            // move the item OUT of the phantom datafile so it will be saved.
                            d.datafile = guessDataFile(name, REQUIRE_WRITABLE);

                        datafileModified(d.datafile);
                    }

                                          // possibly throw away the old value.
                    if (oldValue != null && oldValue != value)
                        try {
                            oldValue.dispose();
                        } catch (Exception ex) {}

                                          // notify any listeners registed for the change
                    dataNotifier.dataChanged(name, d);

                                          // check if this element is no longer needed.
                    maybeDelete(name, d, false);

                } else {
                    //  if the value was not already in the repository, add it.

                    // if we're writing a default value, it must be for a default name.
                    boolean isDefaultName = isDefaultValue;

                    // determine the data file for this element.
                    DataFile f = guessDataFile(name, REQUIRE_WRITABLE);

                    // if we aren't writing a default value, check to see if one would
                    // have been provided by the datafile.  (That is, are we overwriting
                    // a default value that has not yet been lazily constructed?)
                    if (!isDefaultValue && f != null && f.inheritedDefinitions != null) {
                        String localName = name.substring(f.prefix.length() + 1);
                        isDefaultName = f.inheritedDefinitions.containsKey(localName);
                    }

                    try {
                        add(name, isDefaultName, value, isDefaultValue, f, DO_NOTIFY);
                        if (!isDefaultValue && checkDatafileModification)
                            datafileModified(f);
                    } catch (DataElementAlreadyExistsException e) {
                        // this rare occurrence means that some other thread created this
                        // DataElement in the time since we tried to retrieve it and found
                        // that it was missing.  The best course of action is to retry the
                        // operation.
                        putValue(name, value, isDefaultValue, checkDatafileModification);
                    }
                }
            } finally {
                recursion_depth--;
            }
        }

        public void valueRecalculated(String name, SaveableData value) {

            if (recursion_depth < MAX_RECURSION_DEPTH) {
                DataElement d = (DataElement)data.get(name);
                if (d == null || d.getValue() != value) return;

                try {
                    recursion_depth++;

                    // notify any listeners registed for the change
                    dataNotifier.dataChanged(name, d);

                } finally {
                    recursion_depth--;
                }

            } else {
                System.err.println
                    ("DataRepository detected circular dependency in data,\n" +
                     "    bailed out after " + MAX_RECURSION_DEPTH + " iterations.");
                new Exception().printStackTrace(System.err);
            }
        }

        public void userPutValue(String name, SaveableData value) {
            waitForCalculations();
            String aliasName = getAliasedName(name);
            putValue(aliasName, value, IS_NOT_DEFAULT_VAL);
        }

        private void waitForCalculations() {
            while (dataFreezer.flush() || dataNotifier.flush()) {
                // do nothing.
            }
        }

        public String getAliasedName(String name) {
            DataElement d = getOrCreateDefaultDataElement(name);
            String aliasName = null;
            if (d != null) {
                try {
                    d.lockFromDisposal();
                    if (d.getValue() instanceof AliasedData)
                        aliasName = ((AliasedData) d.getValue()).getAliasedDataName();
                } finally {
                    d.unlockForDisposal();
                }
            }

            if (aliasName != null)
                return getAliasedName(aliasName);
            else
                return name;
        }

        public void restoreDefaultValue(String name) {

            DataElement d = (DataElement) data.get(name);

            if (d == null)
                // either the item either doesn't exist, or it is already a
                // lazy default value.
                return;

            Object defaultValue = lookupDefaultValueObject(name, d);

            SaveableData value = null;
            if (defaultValue != null) {
                String prefix = "";
                boolean readOnly = false;
                if (d.datafile != null) {
                    prefix = d.datafile.prefix;
                    readOnly = !d.datafile.canWrite;
                }
                value = instantiateValue(name, prefix, defaultValue, readOnly);
            }
            putValue(name, value, defaultValue != null);
        }


        public SimpleData evaluate(String expression)
            throws CompilationException, ExecutionException {
            return evaluate(expression, "");
        }

        public SimpleData evaluate(String expression, String prefix)
            throws CompilationException, ExecutionException {
            return evaluate(Compiler.compile(expression), prefix);
        }

        public SimpleData evaluate(CompiledScript script, String prefix)
            throws ExecutionException
        {
            ListStack stack = new ListStack();
            ExpressionContext context = new SimpleExpressionContext(prefix);
            script.run(stack, context);
            SimpleData value = (SimpleData) stack.pop();
            if (value != null)
                value = (SimpleData) value.getEditable(false);
            return value;
        }

        private class SimpleExpressionContext implements ExpressionContext {
            private String prefix;
            public SimpleExpressionContext(String p) { prefix = p; }
            public SimpleData get(String dataName) {
                return getSimpleValue(createDataName(prefix, dataName)); }
            public String resolveName(String dataName) {
                return createDataName(prefix, dataName); }
        }


        public void putExpression(String name, String prefix, String expression)
            throws MalformedValueException
        {
            try {
                CompiledFunction f = new CompiledFunction
                    (name, Compiler.compile(expression), this, prefix);
                putValue(name, f, IS_NOT_DEFAULT_VAL);
            } catch (CompilationException e) {
                throw new MalformedValueException();
            }
        }


        private static final String includeTag = "#include ";
        private final Hashtable includedFileCache = new Hashtable();

        private Map getIncludedFileDefinitions(String datafile) {
            //debug("getIncludedFileDefinitions("+datafile+")");
            datafile = followDatafileRedirections(datafile);
            Object definitions = includedFileCache.get(datafile);
            if (definitions instanceof DefinitionFactory) {
                definitions = ((DefinitionFactory) definitions).getDefinitions(this);
                definitions = Collections.unmodifiableMap((Map) definitions);
                includedFileCache.put(datafile, definitions);
                definitionsDirty = true;
            }
            return (Map) definitions;
        }

        /** Check in the defaultDefinitions map for any requested redirections.
         */
        private String followDatafileRedirections(String datafile) {
            Object def = datafile;
            while (def instanceof String) {
                datafile = (String) def;
                def = defaultDefinitions.get(datafile);
            }
            return datafile;
        }

        /** Get the definitions for the given includable datafile, loading
         *  them if necessary.
         */
        public Map loadIncludedFileDefinitions(String datafile)
            throws FileNotFoundException, IOException, InvalidDatafileFormat
        {
            //debug("loadIncludedFileDefinitions("+datafile+")");
            datafile = bracket(datafile);

            // Check in the defaultDefinitions map for any requested redirections.
            datafile = followDatafileRedirections(datafile);

            Map result = getIncludedFileDefinitions(datafile);
            if (result == null) {
                result = new HashMap();

                // Lookup any applicable default data definitions.
                DefinitionFactory defaultDefns =
                    (DefinitionFactory) defaultDefinitions.get(datafile);
                if (defaultDefns != null)
                    result.putAll(defaultDefns.getDefinitions(DataRepository.this));

                if (!isImaginaryDatafileName(datafile))
                    loadDatafile(datafile, findDatafile(datafile), result,
                            DO_FOLLOW_INCLUDES, DO_CLOSE);

                // Although we aren't technically done creating this datafile,
                // we need to store it in the cache before calling
                // insertRollupDefinitions to avoid entering an infinite loop.
                includedFileCache.put(datafile, result);

                // check to see if the datafile requests a rollup
                Object rollupIDval = result.get("Use_Rollup");
                if (rollupIDval instanceof StringData) {
                    String rollupID = ((StringData) rollupIDval).getString();
                    insertRollupDefinitions(result, rollupID);
                }

                // prepare renaming operations for later use
                DataRenamingOperation.initRenamingOperations(result);

                result = Collections.unmodifiableMap(result);
                includedFileCache.put(datafile, result);
                definitionsDirty = true;
            }

            return result;
        }

        private void insertRollupDefinitions(Map definitions, String rollupID) {
            // It would be nice to accept a list of rollupIDs
            try {
                String aliasDatafile = getAliasDatafileName(rollupID);

                // Get the set of alias definitions
                Map aliasDefinitions = loadIncludedFileDefinitions(aliasDatafile);

                if (aliasDefinitions != null) {
                    Map result = new HashMap();
                    result.putAll(aliasDefinitions);
                    result.putAll(definitions);
                    definitions.putAll(result);
                }
            } catch (Exception e) {}
        }


        Object lookupDefaultValueObject(String dataName, DataElement element) {
            DataFile datafile = null;

            // if the data element is null, try looking it up.
            if (element == null) element = (DataElement)data.get(dataName);

            if (element == null)
                datafile = guessDataFile(dataName, DO_NOT_REQUIRE_WRITABLE);
            else
                datafile = element.datafile;

            if (datafile == null || datafile.inheritedDefinitions == null)
                // if the element has no datafile, or its datafile doesn't have any
                // inherited definitions, then the default value is null.
                return null;

            Map defaultValues = datafile.inheritedDefinitions;

            int prefixLength = datafile.prefix.length() + 1;
            String nameWithinDataFile = dataName.substring(prefixLength);
            Object defaultVal = defaultValues.get(nameWithinDataFile);
            return defaultVal;
        }



        private InputStream findDatafile(String path) throws
            FileNotFoundException {
                                      // find file in search path?
            if (path.startsWith("<")) {
                                              // strip <> chars
                path = path.substring(1, path.length()-1);

                URL u;
                URLConnection conn;
                                        // look in each template URL until we
                                        // find the named file
                for (int i = 0;  i < templateURLs.length;  i++) try {
                    u = new URL(templateURLs[i], path);
                    conn = u.openConnection();
                    conn.connect();
                    InputStream result = conn.getInputStream();
                    return result;
                } catch (IOException ioe) { }

                                        // couldn't find the file in any template
                                        // URL - give up.
                throw new FileNotFoundException("<" + path + ">");
            }

            throw new FileNotFoundException(path);    // fail.
        }


        private class LoadingException extends RuntimeException {
            Exception root;
            public LoadingException(Exception e) { root = e; }
            public Exception getRoot() { return root; }
        }
        private class FileLoader extends DepthFirstAdapter {
            private String inheritedDatafile = null;
            private boolean followIncludes;
            private Map dest;
            public FileLoader(Map dest, boolean followIncludes) {
                this.dest = dest;
                this.followIncludes = followIncludes;
            }
            public String getInheritedDatafile() { return inheritedDatafile; }

            private void putVal(String name, Object value) {
                if (name.startsWith("/"))
                    putGlobalValue(name, value);
                else if ((value == null || value.equals("null")
                        || value.equals("=null")) && followIncludes)
                    dest.remove(name);
                else
                    dest.put(name, value);
            }

            /** Process a new style declaration. */
            public void caseANewStyleDeclaration(ANewStyleDeclaration node) {
                String name = Compiler.trimDelim(node.getIdentifier());
                CompiledScript script = null;
                try {
                    script = Compiler.compile(node.getValue());
                } catch (CompilationException ce) {
                    throw new LoadingException
                        (new InvalidDatafileFormat(ce.getMessage()));
                }
                if (!script.isConstant())
                    putVal(name, script);
                else {
                    SimpleData constant = script.getConstant();
                    if (constant != null &&
                        node.getAssignop() instanceof AReadOnlyAssignop)
                        constant = (SimpleData) constant.getEditable(false);
                    putVal(name, constant);
                }
            }

            /** Process an old style declaration. */
            public void caseAOldStyleDeclaration(AOldStyleDeclaration node) {
                String line = node.getOldStyleDeclaration().getText(), name, value;
                int equalsPosition = line.indexOf('=');
                if (equalsPosition == -1)
                    throw new LoadingException
                        (new InvalidDatafileFormat
                            ("There is no '=' character on the line: '" + line + "'."));

                name = line.substring(0, equalsPosition);
                value = line.substring(equalsPosition+1);
                putVal(name, value);
            }

            public void caseASearchDeclaration(ASearchDeclaration node) {
                putVal(Compiler.trimDelim(node.getIdentifier()),
                       new SearchFactory(node));
            }

            public void caseASimpleSearchDeclaration(ASimpleSearchDeclaration node) {
                putVal(Compiler.trimDelim(node.getIdentifier()),
                       new SearchFactory(node));
            }

            /** Process an include directive. */
            public void caseAIncludeDeclaration(AIncludeDeclaration node) {
                if (followIncludes == false) {
                    if (inheritedDatafile != null)
                        throw new LoadingException(new InvalidDatafileFormat(
                                "Multiple includes found when followIncludes==false"));
                    if (node.getExcludeClause() != null)
                        throw new LoadingException(new InvalidDatafileFormat(
                                "Exclude clause found when followIncludes==false"));
                }

                String line = node.getIncludeDirective().getText();
                inheritedDatafile = line.substring(includeTag.length()).trim();

                // Add proper exception handling in case someone is somehow using
                // the deprecated include syntax.
                if (inheritedDatafile.startsWith("\"")) {
                    throw new LoadingException
                        (new InvalidDatafileFormat
                            ("datafile #include directives with relative" +
                             " paths are no longer supported."));
                }

                if (followIncludes == false)
                    return;

                try {
                    Map cachedIncludeFile =
                        loadIncludedFileDefinitions(inheritedDatafile);
                    Map filteredIncludeFile = cachedIncludeFile;

                    if (node.getExcludeClause() != null) {
                        IdentifierLister filter = new IdentifierLister();
                        node.getExcludeClause().apply(filter);
                        filteredIncludeFile = filterDefinitions
                            (cachedIncludeFile, filter.identifiers, filter.strings);
                    }

                    dest.putAll(filteredIncludeFile);
                } catch (Exception e) {
                    throw new LoadingException(e);
                }
            }

            public void caseAUndefineDeclaration(AUndefineDeclaration node) {
                IdentifierLister list = new IdentifierLister();
                node.getIdentifierList().apply(list);
                Iterator i = list.identifiers.iterator();
                while (i.hasNext())
                    dest.remove(i.next());
            }
        }

        private class IdentifierLister extends DepthFirstAdapter {
            public ArrayList identifiers = new ArrayList();
            public ArrayList strings = new ArrayList();
            public IdentifierLister() {}
            public void caseTIdentifier(TIdentifier node) {
                identifiers.add(Compiler.trimDelim(node)); }
            public void caseTStringLiteral(TStringLiteral node) {
                strings.add(Compiler.trimDelim(node)); }
        }

        // loadDatafile - opens the file passed to it and looks for "x = y" type
        // statements.  If one is found it associates x with y in the Hashtable
        // dest.  If an include statement is found on the first line, a recursive
        // call to loadDatafile is made, using the same Hashtable.  Return the
        // name of the include file, if one was found.

        private String loadDatafile(String file, InputStream datafile, Map dest,
                boolean followIncludes, boolean close)
            throws FileNotFoundException, IOException, InvalidDatafileFormat {
            return loadDatafile(file, new InputStreamReader(datafile), dest,
                    followIncludes, close);
        }
        private String loadDatafile(String filename, Reader datafile, Map dest,
                boolean followIncludes, boolean close)
            throws FileNotFoundException, IOException, InvalidDatafileFormat {

            //debug("loadDatafile("+filename+")");
            // Initialize data, file, and read buffer.
            BufferedReader in = new BufferedReader(datafile);
            FileLoader loader = new FileLoader(dest, followIncludes);
            String defineDecls = null;
            if (filename != null)
                defineDecls = (String) defineDeclarations.get(filename);

            try {
                CppFilterReader readIn = new CppFilterReader(in, defineDecls);
                Parser p = new Parser(new Lexer(new PushbackReader(readIn, 1024)));

                // Parse the file.
                Start tree = p.parse();

                // Apply the file loader.
                tree.apply(loader);

            } catch (ParserException pe) {
                String message = "Could not parse " +filename+ "; " + pe.getMessage();
                TemplateLoader.logTemplateError(message);
                throw new InvalidDatafileFormat(message);
            } catch (LexerException le) {
                String message = "Could not parse " +filename+ "; " + le.getMessage();
                TemplateLoader.logTemplateError(message);
                throw new InvalidDatafileFormat(message);
            } catch (LoadingException load) {
                Exception root = load.getRoot();
                if (root instanceof FileNotFoundException)
                    throw (FileNotFoundException) root;
                if (root instanceof IOException)
                    throw (IOException) root;
                if (root instanceof InvalidDatafileFormat)
                    throw (InvalidDatafileFormat) root;
                System.err.println("Unusual exception when loading file: " + root);
                root.printStackTrace();
                throw new IOException(root.getMessage());
            } finally {
                if (close) in.close();
            }

            return loader.getInheritedDatafile();
        }

        public void parseDatafile(String contents, Map dest)
            throws FileNotFoundException, IOException, InvalidDatafileFormat {
            loadDatafile(null, new StringReader(contents), dest, DO_FOLLOW_INCLUDES,
                    DO_CLOSE);
        }

        private final Hashtable defineDeclarations = new Hashtable();
        public void putDefineDeclarations(String datafile, String decls) {
            defineDeclarations.put(bracket(datafile), decls);
            definitionsDirty = true;
        }

        private final Hashtable defaultDefinitions = new Hashtable();

        public void registerDefaultData(DefinitionFactory d,
                                        String datafile,
                                        String imaginaryFilename) {
            if (datafile != null && datafile.length() > 0) {
                defaultDefinitions.put(bracket(datafile), d);
                if (imaginaryFilename != null)
                    defaultDefinitions.put(bracket(imaginaryFilename),
                                           bracket(datafile));
            } else
                includedFileCache.put(bracket(imaginaryFilename), d);
            definitionsDirty = true;
        }
        private String bracket(String filename) {
            if (filename == null || filename.startsWith("<")) return filename;
            return "<" + filename + ">";
        }

        public String getRollupDatafileName(String rollupID) {
            return "ROLLUP:" + rollupID;
        }
        public String isRollupDatafileName(String dataFile) {
            if (dataFile != null && dataFile.startsWith("ROLLUP:"))
                return dataFile.substring("ROLLUP:".length());
            else
                return null;
        }

        public String getAliasDatafileName(String rollupID) {
            return "ROLLUP-ALIAS:" + rollupID;
        }

        public String getImaginaryDatafileName(String templateID) {
            return templateID + "?dataFile.txt";
        }
        boolean isImaginaryDatafileName(String dataFile) {
            return (dataFile != null &&
                    (dataFile.endsWith("?dataFile.txt") ||
                     dataFile.endsWith("?dataFile.txt>")));
        }

        private Hashtable globalDataDefinitions = new Hashtable();
        private boolean globalDataIsMounted = false;

        public void addGlobalDefinitions(InputStream datafile, boolean close)
            throws FileNotFoundException, IOException, InvalidDatafileFormat {
            loadDatafile(null, datafile, globalDataDefinitions, DO_FOLLOW_INCLUDES,
                    close);
            DataRenamingOperation.initRenamingOperations(globalDataDefinitions);
        }

        private SaveableData instantiateValue(String name, String dataPrefix,
                                              Object valueObj, boolean readOnly) {

            SaveableData o = null;

            if (valueObj instanceof SimpleData) {
                o = (SimpleData) valueObj;
                if (readOnly) o = o.getEditable(false);

            } else if (valueObj instanceof CompiledScript) {
                o = new CompiledFunction(name, (CompiledScript) valueObj,
                                         this, dataPrefix);

            } else if (valueObj instanceof SearchFactory) {
                o = ((SearchFactory) valueObj).buildFor(name, this, dataPrefix);

            } else if (valueObj instanceof String) {
                String value = (String) valueObj;
                if (value.startsWith("=")) {
                    readOnly = true;
                    value = value.substring(1);
                }

                try {
                    o = ValueFactory.createQuickly(name, value, this, dataPrefix);
                } catch (MalformedValueException mfe) {
                    // temporary fix to allow old PSP for Engineers add-on to work in 1.7
                    if ("![(&&\tCompleted)]".equals(value)) {
                        valueObj = Compiler.compile("[Completed]");
                        o = new CompiledFunction(name, (CompiledScript) valueObj,
                                                 this, dataPrefix);
                    } else {
                        o = new MalformedData(value);
                    }
                }
                if (readOnly && o != null) o.setEditable(false);
            }

            return o;
        }

        private boolean instantiatedDataMatches(Object valueObj, SaveableData val) {
            if (valueObj == val) {
                // exact same object?  matches.
                return true;

            } else if (valueObj == null || val == null) {
                // one object is null but the other isn't
                return false;

            } else if (valueObj instanceof SimpleData) {
                // compare SimpleData for equality.
                return ((SimpleData) valueObj).equals(val);

            } else if (valueObj instanceof CompiledScript) {
                // compare CompiledScript for match.
                return ((CompiledScript) valueObj).matches(val);

            } else if (valueObj instanceof SearchFactory) {
                // compare SearchFactory for match.
                return ((SearchFactory) valueObj).matches(val);

            } else if (valueObj instanceof String) {
                // compare save strings of old-style data.
                String valueObjStr = (String) valueObj;
                if (valueObjStr.startsWith("="))
                    valueObjStr = valueObjStr.substring(1);
                String valStr = val.saveString();
                if (valStr != null && valStr.startsWith("="))
                    valStr = valStr.substring(1);
                return valueObjStr.equals(valStr);
            }

            return false;
        }

        private void putGlobalValue(String name, Object valueObj) {
            DataElement e = (DataElement) data.get(name);
            if (e != null && e.getValue() != null)
                return;                 // don't overwrite existing values?

            String localName = name.substring(1);
            valueObj = DataRenamingOperation.maybeInitRenamingOperation(
                    localName, valueObj);
            globalDataDefinitions.put(localName, valueObj);
            definitionsDirty = true;

            if (e != null) {
                SaveableData o = instantiateValue(name, "", valueObj, false);
                if (o != null)
                    putValue(name, o, IS_DEFAULT_VAL);
            } else if (!(valueObj instanceof DataRenamingOperation)) {
                if (globalDataIsMounted)
                    repositoryListenerList.dispatchAdded(name);
            }
        }


        private Map filterDefinitions(Map definitions,
                                       List identifiers,
                                       List regularExpressions) {
            Map result = new HashMap(definitions);

            // delete all the specified identifiers from the map.
            for (Iterator i = identifiers.iterator(); i.hasNext();) {
                String identifier = (String) i.next();
                result.remove(identifier);
            }

            // remove data elements which match any of the regular expressions.
            for (Iterator r = regularExpressions.iterator(); r.hasNext();) {
                String regExp = (String) r.next();
                Pattern p = Pattern.compile(regExp);
                for (Iterator i = result.keySet().iterator(); i.hasNext();) {
                    String identifier = (String) i.next();
                    if (p.matcher(identifier).matches())
                        i.remove();
                }
            }

            return result;
        }

        public void openDatafile(String dataPrefix, String datafilePath)
            throws FileNotFoundException, IOException, InvalidDatafileFormat {

            logger.log(Level.FINE, "Opening datafile {0}", datafilePath);

            Hashtable values = new Hashtable();

            DataFile dataFile = new DataFile(dataPrefix, new File(datafilePath));
            dataFile.inheritsFrom =
                loadDatafile(null, new FileInputStream(dataFile.file),
                             values, DO_NOT_FOLLOW_INCLUDES, DO_CLOSE);
            if (dataFile.inheritsFrom != null)
                dataFile.inheritedDefinitions =
                    loadIncludedFileDefinitions(dataFile.inheritsFrom);

            // perform any renaming operations that were requested in the datafile
            boolean dataModified = DataRenamingOperation.performRenames(values,
                    dataFile.inheritedDefinitions);

                                    // only add the datafile element if the
                                    // loadDatafile process was successful
            addDataFile(dataFile);

                                    // mount the data in the repository.
            mountData(dataFile, dataPrefix, values);

            logger.log(Level.FINE, "Done opening datafile {0}", datafilePath);

            if (dataModified)       // possibly mark the file as modified.
                datafileModified(dataFile);
        }

        void mountData(DataFile dataFile, String dataPrefix, Map values)
            throws InvalidDatafileFormat
        {
            try {
                startInconsistency();

                boolean registerDataNames = false;
                String datafilePath = "internal data";
                boolean fileEditable = true;

                // if this is a regular file,
                if (dataFile != null && dataFile.file != null) {
                    datafilePath = dataFile.file.getPath();
                    fileEditable = dataFile.canWrite;
                    // register the names of data elements in this file IF it is
                    // not global data.
                    registerDataNames = dataPrefix.length() > 0;
                }

                Map defaultData = dataFile.inheritedDefinitions;
                if (defaultData == null)
                    defaultData = Collections.EMPTY_MAP;

                boolean successful = false;
                boolean datafileModified = false;
                int retryCount = 10;
                while (!successful && retryCount-- > 0) try {

                    // First, create all the values that are listed explicitly in the
                    // values map.
                    for (Iterator i = values.entrySet().iterator(); i.hasNext();) {
                        Map.Entry defn = (Map.Entry) i.next();
                        String localName = (String) defn.getKey();
                        Object valueObj = defn.getValue();
                        String dataName = createDataName(dataPrefix, localName);
                        SaveableData o = instantiateValue(dataName, dataPrefix, valueObj,
                                !fileEditable);

                        if (o instanceof MalformedData)
                            logger.warning("Data value for '" + dataName + "' in file '"
                                    + datafilePath + "' is malformed.");

                        DataElement d = (DataElement)data.get(dataName);
                        if (d == null) {
                            boolean isDefaultName = defaultData.containsKey(localName);
                            if (o != null || isDefaultName) {
                                try {
                                    add(dataName, isDefaultName, o, IS_NOT_DEFAULT_VAL,
                                            dataFile, DO_NOTIFY);
                                } catch (DataElementAlreadyExistsException e) {
                                    d = e.elem;
                                }
                            }
                        }
                        if (d != null) {
                            putValue(dataName, o, IS_NOT_DEFAULT_VAL,
                                    NOT_MODIFYING_DATAFILE);
                            d = (DataElement)data.get(dataName);
                            if (d != null)
                                d.datafile = dataFile;
                        }

                        if (registerDataNames && (o instanceof DoubleData
                                || o instanceof CompiledFunction))
                            dataElementNameSet.add(localName);
                    }

                    // Next, handle the default values that this datafile inherits.
                    String dataPrefixSlash = dataPrefix + "/";
                    for (Iterator i = defaultData.entrySet().iterator(); i.hasNext();) {
                        Map.Entry defn = (Map.Entry) i.next();
                        String localName = (String) defn.getKey();
                        Object valueObj = defn.getValue();

                        // if we already processed an explicit value with this same
                        // name, do nothing.
                        if (values.containsKey(localName))
                            continue;

                        // Ignore renaming operations; they are not relevant.
                        if (valueObj instanceof DataRenamingOperation)
                            continue;

                        boolean shouldCreateEagerly = shouldCreateEagerly(valueObj);
                        if ("@now".equals(valueObj))
                            shouldCreateEagerly = datafileModified = true;

                        String dataName = dataPrefixSlash + localName;
                        DataElement d = (DataElement)data.get(dataName);
                        if (d == null) {
                            // this data element does not already exist in the repository.
                            // most such items do not need to be created; we can let them
                            // be lazily created later if needed.

                            if (shouldCreateEagerly) {
                                // the item doesn't exist, but we should create it anyway.
                                SaveableData o = instantiateValue(dataName,
                                        dataPrefix, valueObj, !fileEditable);
                                if (o != null) {
                                    try {
                                        add(dataName, IS_DEFAULT_NAME, o, IS_DEFAULT_VAL,
                                                dataFile, DO_NOT_NOTIFY);
                                    } catch (DataElementAlreadyExistsException e) {
                                        d = e.elem;
                                    }
                                }
                            }
                        }

                        if (d != null) {
                            // a matching data element exists.
                            d.datafile = dataFile;
                            dataName = d.name;

                            if (instantiatedDataMatches(valueObj, d.getValue())) {
                                // the data element already has the proper value. (This
                                // will be common, as clients registering data listeners
                                // will cause lazy data to spring to life even as this
                                // for loop is executing.) Nothing needs to be done.

                            } else if (!d.hasListeners()) {
                                // This element has the wrong value, but no one is
                                // listening to it.  Revert the element to a lazy
                                // default value.
                                janitor.cleanup(d);

                            } else {
                                // This element has the wrong value, and clients are
                                // listening to it.  Create and save the correct value.
                                SaveableData o = instantiateValue(dataName,
                                        dataPrefix, valueObj, !fileEditable);
                                putValue(dataName, o, IS_DEFAULT_VAL,
                                        NOT_MODIFYING_DATAFILE);
                                d.isDefaultName = true;
                            }
                        }

                        // send an event stating that the element is added. (Elsewhere in
                        // DataRepository, added events are never sent for data elements
                        // with default names (because it cannot tell whether the item is
                        // springing forth for the first time, or a subsequent time).
                        repositoryListenerList.dispatchAdded(dataName);

                        if (registerDataNames && (valueObj instanceof DoubleData
                                || valueObj instanceof CompiledScript))
                            dataElementNameSet.add(localName);
                    }

                    // make a call to getID.  We don't need the resulting value, but
                    // having made the call will cause an ID to be mapped for this
                    // prefix.  This is necessary to allow users to bring up HTML pages
                    // from their browser's history or bookmark list.
                    getID(dataPrefix);

                    if (defaultData == globalDataDefinitions)
                        globalDataIsMounted = true;
                    if (datafileModified)
                        datafileModified(dataFile);

                    successful = true;

                } catch (Throwable e) {
                    if (retryCount > 0) {
                        // Try again to open this datafile. Most errors are transient,
                        // caused by incredibly infrequent thread-related problems.
                        logger.log(Level.WARNING, "when opening "
                                + datafilePath + " caught error; retrying.", e);
                    } else {
                        // We've done our best, but after 10 tries, we still can't open
                        // this datafile.  Give up and throw an exception.
                        dataFile.invalidate();
                        closeDatafile(dataPrefix);
                        throw new InvalidDatafileFormat("Caught unexpected exception "+e);
                    }
                }

            } finally {
                finishInconsistency();
            }
        }

        protected boolean shouldCreateEagerly(Object defaultValueObject) {
            if (defaultValueObject instanceof TagData
                    || defaultValueObject instanceof FrozenData)
                return true;

            return false;
        }

        private Hashtable mountedPhantomData = new Hashtable();

        private void remountPhantomData(Hashtable h) throws InvalidDatafileFormat {
            Iterator i = h.entrySet().iterator();
            Map.Entry e;
            while (i.hasNext()) {
                e = (Map.Entry) i.next();
                mountPhantomData((String) e.getKey(), (Map) e.getValue());
            }
        }

        public void mountPhantomData(String dataPrefix, Map values)
            throws InvalidDatafileFormat
        {
            // It is important to mount the data with *some* datafile - if a
            // data element's datafile is null, it is considered transient and
            // can be deleted at any time if no one is listening to its value.
            DataFile f = getPhantomDataFile(dataPrefix);
            f.inheritedDefinitions = values;
            addDataFile(f);
            mountData(f, dataPrefix, Collections.EMPTY_MAP);

            if (mountedPhantomData != null) {
                mountedPhantomData.put(dataPrefix, values);
                definitionsDirty = true;
            }
        }

        private Object importedDataSynchLock = new Object();
        public void mountImportedData(String dataPrefix, Map values)
            throws InvalidDatafileFormat
        {
            synchronized (importedDataSynchLock) {
                mountImportedDataImpl(dataPrefix, values);
            }
        }
        private void mountImportedDataImpl(String dataPrefix, Map values)
                throws InvalidDatafileFormat {
            String prefixToDiscard = null;
            DataFile previousDataFile = getDataFileForPrefix(dataPrefix, false);
            if (previousDataFile != null) {
                prefixToDiscard = previousDataFile.prefix;
                prefixToDiscard = prefixToDiscard.replace('/', '\\');
                prefixToDiscard = '\u0001' + prefixToDiscard.substring(1);
                previousDataFile.prefix = prefixToDiscard;
            }

            DataFile dataFile = new DataFile(dataPrefix, null);
            dataFile.isImported = true;
            addDataFile(dataFile);
            mountData(dataFile, dataPrefix, values);

            if (prefixToDiscard != null) {
                // reassociate the null element with the new data file (instead
                // of the old one)
                DataElement e = getImportedFileNullElement(dataFile);
                e.datafile = dataFile;

                // discard elements that were present in the old data file, that
                // no longer exist
                closeDatafile(prefixToDiscard);

                // send a dataChanged event for the null element, indicating that
                // the imported file has changed.
                dataNotifier.dataChanged(e.name, e);
            }
        }

        private DataFile getPhantomDataFile(String prefix) {
            DataFile d = new DataFile(prefix, null);
            PHANTOM_DATAFILES.add(d);
            return d;
        }
        private Set PHANTOM_DATAFILES = Collections.synchronizedSet(new HashSet());

        private static volatile int MAX_DIRTY = 10;

        private void addDataFile(DataFile df) {
            synchronized (datafiles) {
                datafiles.add(df);
                Collections.sort(datafiles);
            }
        }

        private void datafileModified(DataFile datafile) {
            if (datafile != null && ++datafile.dirtyCount > MAX_DIRTY)
                saveDatafile(datafile);
        }

        private Iterator getInternalKeys() {
            List l = new ArrayList();
            synchronized (data) {
                l.addAll(data.keySet());
            }
            return l.iterator();
        }

        public Iterator getKeys() {
            return getKeys(null, null);
        }
        public Iterator getKeys(Object prefixes, Object hints) {
            return new AllDataNamesIterator(prefixes, hints);
        }

        private class AllDataNamesIterator implements Iterator {
            private Set explicitDataNames;
            private List files;
            private DataNameFilter.PrefixLocal prefixLocalFilter;

            private DataFile workingDatafile;
            private Iterator workingDefaultLocalNames;
            private Iterator workingExplicitNames;

            public AllDataNamesIterator(Object prefix, Object hints) {
                synchronized (data) {
                    explicitDataNames = new HashSet(data.keySet());
                }
                synchronized (datafiles) {
                    files = new LinkedList(datafiles);
                }

                for (Iterator i = files.iterator(); i.hasNext();) {
                    DataFile f = (DataFile) i.next();
                    if (f.inheritedDefinitions == null
                            || f.inheritedDefinitions.isEmpty())
                        i.remove();
                    else if (prefixesMightMatch(prefix, f.prefix) == false)
                        i.remove();
                }

                if (hints instanceof DataNameFilter.PrefixLocal)
                    prefixLocalFilter = (DataNameFilter.PrefixLocal) hints;

                loadNextWorkingFile();
            }
            private boolean prefixesMightMatch(Object prefixes, String prefixB) {
                if (prefixes instanceof String)
                    return prefixesMightMatch((String) prefixes, prefixB);

                if (prefixes instanceof Collection) {
                    for (Iterator i = ((Collection) prefixes).iterator(); i.hasNext();)
                        if (prefixesMightMatch(i.next(), prefixB))
                            return true;
                    return false;
                }

                return true;
            }
            private boolean prefixesMightMatch(String prefixA, String prefixB) {
                if (prefixA == null || prefixB == null)
                    return true;
                else
                    return prefixA.startsWith(prefixB)
                            || prefixB.startsWith(prefixA);
            }
            public boolean hasNext() {
                return files.isEmpty() == false
                        || explicitDataNames.isEmpty() == false
                        || hasNextWorkingDefaultLocalName()
                        || hasNextWorkingExplicitName();
            }
            private boolean hasNextWorkingDefaultLocalName() {
                return (workingDatafile != null
                        && workingDatafile.isRemoved == false
                        && workingDefaultLocalNames.hasNext());
            }
            private boolean hasNextWorkingExplicitName() {
                return (workingExplicitNames != null
                        && workingExplicitNames.hasNext());
            }
            public Object next() {
                // first, try to return one of the default names inherited by the
                // working datafile.
                while (hasNextWorkingDefaultLocalName()) {
                    String prefix = workingDatafile.prefix;
                    Map.Entry e = (Map.Entry) workingDefaultLocalNames.next();
                    if (e.getValue() instanceof DataRenamingOperation)
                        continue;
                    String localName = (String) e.getKey();
                    if (prefixLocalFilter != null
                            && !prefixLocalFilter.acceptPrefixLocalName(prefix,
                                    localName))
                        continue;
                    String result = createDataName(prefix, localName);
                    explicitDataNames.remove(result);
                    return result;
                }

                // If we just ran out of default names for the working datafile,
                // create the list of explicit names present in that datafile.
                if (workingExplicitNames == null)
                    loadWorkingExplicitNames();

                // return the next explicit name we know about, that is still
                // present in the data map.
                while (hasNextWorkingExplicitName()) {
                    String result = (String) workingExplicitNames.next();
                    if (workingDatafile == null
                            || Filter.pathMatches(result, workingDatafile.prefix)) {
                        workingExplicitNames.remove();
                        if (data.containsKey(result))
                            return result;
                    }
                }

                if (!files.isEmpty() || !explicitDataNames.isEmpty()) {
                    loadNextWorkingFile();
                    return next();
                } else {
                    // in certain very rare occasions, another thread might
                    // have closed a file or deleted a data element since our
                    // hasNext() method was called last, and we might no longer
                    // have any data to return.  We can't violate the contract
                    // of Iterator, so we'll just return an imaginary data name.
                    return anonymousPrefix + "/No_More_Data_Elements";
                }
            }
            private void loadNextWorkingFile() {
                if (files.isEmpty()) {
                    workingDatafile = null;
                    workingDefaultLocalNames = null;
                } else {
                    workingDatafile = (DataFile) files.remove(0);
                    workingDefaultLocalNames = workingDatafile
                            .inheritedDefinitions.entrySet().iterator();
                }
                workingExplicitNames = null;
            }
            private void loadWorkingExplicitNames() {
                if (workingDatafile != null) {
                    if (ORDER_NAMES_BY_PREFIX)
                        workingExplicitNames = explicitDataNames.iterator();
                    else
                        workingExplicitNames = null;

                } else {
                    // if there is no working datafile, list the explicit names
                    // that have not yet been returned.
                    workingExplicitNames = explicitDataNames.iterator();
                    // explicitDataNames = Collections.EMPTY_SET;
                }
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        }
        /** If true, {@link AllDataNamesIterator} will group explicit data names
         * next to implicit data names, according to datafile prefixes.  Testing
         * seems to suggest that this provides no memory advantage, but slows down
         * the operation of the iterator.
         */
        private static final boolean ORDER_NAMES_BY_PREFIX = false;


        protected boolean saveDisabled = false;

        /** Saves a set of data to the appropriate data file.
         * 
         * @param datafile the datafile to save
         */
        private void saveDatafile(DataFile datafile) {
            if (datafile == null || datafile.file == null || datafile.isRemoved
                      || saveDisabled)
                  return;

            // this flag should stay false until we are absolutely certain
            // that we have successfully saved the datafile.
            boolean saveSuccessful = false;

            // synchronize to prevent two different threads from trying to save
            // the same datafile concurrently.
            synchronized (datafile) { try {
                // debug("saveDatafile");

                Set valuesToSave = new TreeSet();

                // if the data file has an include statement, lookup the associated
                // default values defined by the included file.
                Map defaultValues = datafile.inheritedDefinitions;
                if (defaultValues == null)
                    defaultValues = Collections.EMPTY_MAP;

                // optimistically mark the datafile as "clean" at the beginning of
                // the save operation.  This way, if the datafile is modified
                // during the save operation, the dirty changes will take effect,
                // and the datafile will be saved again in the future.
                datafile.dirtyCount = 0;

                int prefixLength = datafile.prefix.length() + 1;

                for (Iterator i = getInternalKeys(); i.hasNext();) {
                    String name = (String) i.next();
                    DataElement element = (DataElement)data.get(name);

                    if (element == null
                            || element.datafile != datafile
                            || element.isDefaultValue())
                        // if there is no such element, if it doesn't belong to this
                        // DataFile, or if it has a default value, skip it.
                        continue;

                    SaveableData value = element.getValue();
                    String valStr = null;
                    boolean editable = true;

                    if (value != null) {
                        valStr = value.saveString();
                        editable = value.isEditable();
                    } else if (element.isDefaultName()) {
                        // store the fact that the default is overwritten with null
                        valStr = "null";
                    }

                    if (valStr == null || valStr.length() == 0)
                        continue;

                    name = name.substring(prefixLength);
                    valuesToSave.add(name + (editable ? "=" : "==") + valStr);
                }

                // Write the saved values
                BufferedWriter out;
                try {
                    out = new BufferedWriter(new RobustFileWriter(datafile.file));
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Encountered exception while opening "
                            + datafile.file.getPath() + "; save aborted", e);
                    return;
                }

                try {
                    // if the data file has an include statement, write it to the file
                    if (datafile.inheritsFrom != null) {
                        out.write(includeTag + datafile.inheritsFrom);
                        out.newLine();
                    }

                    // If the data file has a prefix, write it as a comment to the file
                    if (datafile.prefix != null && datafile.prefix.length() > 0) {
                        out.write("= Data for " + datafile.prefix);
                        out.newLine();
                    }

                    for (Iterator i = valuesToSave.iterator(); i.hasNext();) {
                        out.write((String) i.next());
                        out.newLine();
                    }

                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Encountered exception while writing to "
                            + datafile.file.getPath() + "; save aborted", e);
                    return;
                }

                try {
                    // Close output file
                    out.flush();
                    out.close();

                    saveSuccessful = true;
                    System.err.println("Saved " + datafile.file.getPath());
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Encountered exception while closing "
                            + datafile.file.getPath() + "; save aborted", e);
                }

            } finally {
                // if we couldn't successfully save the datafile, mark it as dirty.
                if (!saveSuccessful)
                    datafile.dirtyCount = 1000;
            } }
        }




        public void addDataListener(String name, DataListener dl) {
            addDataListener(name, dl, DO_NOTIFY);
        }

        /** Register an object to receive notifications about changes in a data
         * value.
         * 
         * Note: If the named data element does not exist, and it would have been
         * in an imported data file, this method will <b>not</b> register a
         * listener on the named data element.  Instead, it will register a
         * listener on a "proxy" element associated with the imported datafile.
         * The name of this proxy element will be returned.
         * 
         * @param name the name of the data value to watch
         * @param dl the listener to notify
         * @param notify if true, a notification will be sent immediately as part
         *    of the processing of this request
         * @return the name of the element to which the notification was added.
         *    (Will usually be the same as <code>name</code>, except as noted
         *    above.)
         */
        public String addDataListener(String name, DataListener dl, boolean notify) {
            DataElement d;

            // lookup the element.
            d = getOrCreateDefaultDataElement(name);

            // If the item doesn't exist, either as an explicit or default value,
            // create an entry for it with the value null.
            if (d == null) {
                try {
                    d = add(name, IS_NOT_DEFAULT_NAME, null, IS_NOT_DEFAULT_VAL,
                            guessDataFile(name, REQUIRE_WRITABLE), DO_NOTIFY);
                } catch (DataElementAlreadyExistsException e) {
                    d = e.elem;
                }
            }

            d.addDataListener(dl);

            if (notify)
                dataNotifier.addEvent(name, d, dl);

            return d.name;
        }

        public String addActiveDataListener
            (String name, DataListener dl, String dataListenerName) {
            return addActiveDataListener(name, dl, dataListenerName, true);
        }

        public String addActiveDataListener
            (String name, DataListener dl, String dataListenerName, boolean notify) {
            String result = addDataListener(name, dl, notify);
            activeData.put(dl, dataListenerName);
            return result;
        }


        private void maybeDelete(String name, DataElement d,
                boolean deleteDefaultValues) {

            // never discard elements that override a default value in a file.
            if (d.isDefaultName() && !d.isDefaultValue() && d.datafile != null)
                return;

            if (d.dataListeners == null) {    // if no one cares about this element
                if (d.getValue() == null)         // and it has no value,
                    data.remove(name);            // throw it away.

            } else if (d.dataListeners.isEmpty()) {
                               // if no one cares about this element any longer,
                if (d.getValue() == null                 // and it has no value,
                        || d.datafile == null            // or it has no datafile,
                        || (deleteDefaultValues &&       // or it is a discardable
                                janitor.isDefaultElement(d)))    // default value,
                    janitor.cleanup(d);          // ask the janitor to discard it.
            }
        }


        public void removeDataListener(String name, DataListener dl) {
            DataElement d = (DataElement)data.get(name);
            if (d != null) {
                d.removeDataListener(dl);
                dataNotifier.removeDataListener(name, dl);
            }
        }
        public void removeActiveDataListener(DataListener dl) {
            activeData.remove(dl);
        }


        public void deleteDataListener(DataListener dl) {
            // debug("deleteDataListener");

            if (deferDeletions) {
                dataListenersForDeferredRemoval.add(dl);
                return;
            }

            // walk the hashtable, removing this datalistener.
            for (Iterator i = getInternalKeys(); i.hasNext();) {
                String name = (String) i.next();
                DataElement element = (DataElement) data.get(name);
                if (element != null)
                    element.removeDataListener(dl);
            }
            dataNotifier.deleteDataListener(dl);
            activeData.remove(dl);
            // debug("deleteDataListener done");
        }

        void processedDeferredDataListenerDeletions() {
            Set listenersToRemove ;
            synchronized (dataListenersForDeferredRemoval) {
                if (dataListenersForDeferredRemoval.isEmpty())
                    return;
                listenersToRemove = new HashSet(dataListenersForDeferredRemoval);
            }

                      // walk the hashtable, removing this datalistener.
            for (Iterator i = getInternalKeys(); i.hasNext();) {
                String name = (String) i.next();
                DataElement element = (DataElement) data.get(name);
                if (element != null)
                    element.removeDataListeners(listenersToRemove);
            }
            for (Iterator i = listenersToRemove.iterator(); i.hasNext();) {
                DataListener dl = (DataListener) i.next();
                dataNotifier.deleteDataListener(dl);
                activeData.remove(dl);
                dataListenersForDeferredRemoval.remove(dl);
            }
            // debug("deleteDataListener done");
        }



        public void addRepositoryListener(RepositoryListener rl, String prefix) {
            //debug("addRepositoryListener:" + prefix);

                                    // add the listener to our repository list.
            repositoryListenerList.addListener(rl, prefix);

                                    // notify the listener of all the elements
                                    // already in the repository.
            Iterator k = getKeys(prefix, rl);
            String name;


            if (prefix != null && prefix.length() != 0)

                                    // if they have specified a prefix, notify them
                                    // of all the data beginning with that prefix.
                while (k.hasNext())
                    if ((name = (String) k.next()).startsWith(prefix))
                        rl.dataAdded(name);

            else                    // if they have specified no prefix, only
                                    // notify them of data that is NOT anonymous.
                while (k.hasNext())
                    if (!(name = (String) k.next()).startsWith(anonymousPrefix))
                        rl.dataAdded(name);

            // debug("addRepositoryListener done");
        }



        public void removeRepositoryListener(RepositoryListener rl) {
            // debug("removeRepositoryListener");
            repositoryListenerList.removeListener(rl);
            // debug("removeRepositoryListener done");
        }

        private volatile int inconsistencyDepth = 0;
        private Set consistencyListeners =
            Collections.synchronizedSet(new HashSet());

        public void addDataConsistencyObserver(DataConsistencyObserver o) {
            boolean callbackImmediately = false;
            synchronized (consistencyListeners) {
                if (inconsistencyDepth == 0)
                    callbackImmediately = true;
                else
                    consistencyListeners.add(o);
            }
            if (callbackImmediately) o.dataIsConsistent();
        }

        public void startInconsistency() {
            synchronized (consistencyListeners) { inconsistencyDepth++; }
        }

        public void finishInconsistency() {
            synchronized (consistencyListeners) {
                if (--inconsistencyDepth == 0 &&
                    !consistencyListeners.isEmpty()) {
                    ConsistencyNotifier notifier =
                        new ConsistencyNotifier(consistencyListeners);
                    consistencyListeners.clear();
                    notifier.start();
                }
            }
        }

        private class ConsistencyNotifier extends Thread {
            private Set listenersToNotify;

            public ConsistencyNotifier(Set listeners) {
                listenersToNotify = new HashSet(listeners);
            }

            public void run() {
                // give things a chance to settle down.
                //System.out.println("waiting for notifier at " +new java.util.Date());
                dataNotifier.flush();
                //System.out.println("notifier done at " + new java.util.Date());

                Iterator i = listenersToNotify.iterator();
                DataConsistencyObserver o;
                while (i.hasNext()) {
                    o = (DataConsistencyObserver) i.next();
                    o.dataIsConsistent();
                }
            }
        }

        public String getID(String prefix) {
            // if we already have a mapping for this prefix, return it.
            String ID = (String) PathIDMap.get(prefix);
            if (ID != null) return ID;

            // try to come up with a good ID Number for this prefix.  As a first
            // guess, use the hashCode of the path to the datafile for this prefix.
            // This way, with any luck, the same project will map to the same ID
            // Number each time the program runs (since the name of the datafile
            // will most likely never change after the project is created).

                                      // find the datafile associated with 'prefix'
            String datafileName = "null";
            DataFile datafile = getDataFileForPrefix(prefix, false);
            if (datafile != null) {
                if (datafile.file == null)
                    datafileName = "";
                else
                    datafileName = datafile.file.getPath();
            }

                                      // compute the hash of the datafileName.
            int IDNum = datafileName.hashCode();
            ID = Integer.toString(IDNum);

                      // if that ID Number is taken,  increment and try again.
            while (IDPathMap.containsKey(ID))
                ID = Integer.toString(++IDNum);

                        // store the ID-path pair in the hashtables.
            PathIDMap.put(prefix, ID);
            IDPathMap.put(ID, prefix);
            return ID;
        }

        public String getPath(String ID) {
            return (String) IDPathMap.get(ID);
        }

        private void remapIDs(String oldPrefix, String newPrefix) {
            String ID = (String) PathIDMap.remove(oldPrefix);

            if (ID != null) {
                PathIDMap.put(newPrefix, ID);
                IDPathMap.put(ID, newPrefix);
            }

            if (dataServer != null)
                dataServer.deletePrefix(oldPrefix);
        }

        public Set getDataElementNameSet() { return dataElementNameSet_ext; }

        public static final String PARENT_PREFIX = "../";

        public static String chopPath(String path) {
            if (path == null) return null;
            int slashPos = path.lastIndexOf('/');
            if (slashPos == path.length() - 1)
                slashPos = path.lastIndexOf('/', slashPos);
            if (slashPos == -1)
                return null;
            else
                return path.substring(0, slashPos);
        }

        public static String createDataName(String prefix, String name) {
            if (name == null) return null;
            if (name.startsWith("/")) return intern(name, true);
            while (name.startsWith(PARENT_PREFIX)) {
                prefix = chopPath(prefix);
                if (prefix == null) {
                    prefix = "";
                    break;
                }
                name = name.substring(PARENT_PREFIX.length());
            }
            StringBuffer buf = new StringBuffer(prefix.length() + name.length() + 1);
            buf.append(prefix);
            if (!prefix.endsWith("/")) buf.append("/");
            buf.append(name);
            return intern(buf.toString(), false);
        }

        private Comparator nodeComparator = null;
        public void setNodeComparator(Comparator c) { nodeComparator = c; }

        public int compareNames(String name1, String name2) {
            int result = 0;
            if (nodeComparator != null)
                result = nodeComparator.compare(name1, name2);

            if (result == 0)
                result = name1.compareTo(name2);

            return result;
        }

        private static String intern(String s, boolean recommendNew) {
            if (INTERN_MAP != null) {
                DataElement e = (DataElement) INTERN_MAP.get(s);
                if (e != null)
                    return e.name;
            }
            return s;
        }
        private static Map INTERN_MAP = null;

        // the following boolean constants are declared to provide readability
        // in the code above.
        private static final boolean IS_DEFAULT_NAME = true;
        private static final boolean IS_NOT_DEFAULT_NAME = false;
        private static final boolean IS_DEFAULT_VAL = true;
        private static final boolean IS_NOT_DEFAULT_VAL = false;
        private static final boolean DO_NOTIFY = true;
        private static final boolean DO_NOT_NOTIFY = false;
        private static final boolean DO_FOLLOW_INCLUDES = true;
        private static final boolean DO_NOT_FOLLOW_INCLUDES = false;
        private static final boolean DO_CLOSE = true;
        private static final boolean MAYBE_MODIFYING_DATAFILE = true;
        private static final boolean NOT_MODIFYING_DATAFILE = false;
        private static final boolean REQUIRE_WRITABLE = true;
        private static final boolean DO_NOT_REQUIRE_WRITABLE = false;
}
