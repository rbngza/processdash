// Copyright (C) 1998-2016 Tuma Solutions, LLC
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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, see <http://www.gnu.org/licenses/>.
//
// The author(s) may be contacted at:
//     processdash@tuma-solutions.com
//     processdash-devel@lists.sourceforge.net


package net.sourceforge.processdash.util;

import java.util.Date;


public class Stopwatch {

    private Date createTime = null;
    private Date startTime = null;
    private Date stopTime = null;
    private long elapsedTime = 0;        // represented in seconds
    private long interruptTime = 0;      // represented in seconds
    private double multiplier = 1.0;

    public Stopwatch(boolean running) {
        createTime = new Date();
        if (running) { startTime = createTime; }
    }

    public Stopwatch() {
        this(true);
    }

    public Date getCreateTime() {
        return createTime;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public void setMultiplier(String mult) {
        multiplier = 1.0;
        if (mult != null) try {
            multiplier = Double.parseDouble(mult);
        } catch (NumberFormatException nfe) {}
    }

    public void start() {
        if (startTime == null) {
            startTime = new Date();
            if (stopTime != null) {
                interruptTime = interruptTime +
                    ((startTime.getTime() - stopTime.getTime()) / 1000);
            }
        }
    }

    public void stop() {
        stopAsOf(new Date());
    }

    private void stopAsOf(Date when) {
        if (startTime != null) {
            stopTime = when;
            elapsedTime = elapsedTime +
                ((stopTime.getTime() - startTime.getTime()) / 1000);
            startTime = null;
        }
    }

    public boolean toggle() {
        if (startTime == null)
            start();
        else
            stop();

        return (startTime != null);
    }

    public boolean isRunning() {
        return (startTime != null);
    }

    public void reset() {
        startTime = null;
        stopTime = null;
        elapsedTime = 0;
        interruptTime = 0;
    }

    public void setElapsed(long seconds) {
        if (isRunning()) {
            stop();
            elapsedTime = (long) (seconds / multiplier);
            start();
        } else
            elapsedTime = (long) (seconds / multiplier);
    }

    public double minutesElapsedDouble() {
        if (startTime == null) {
            return (double)elapsedTime * multiplier / 60.0;
        } else {
            Date now = new Date();
            return (double)(((now.getTime() - startTime.getTime()) / 1000) +
                           elapsedTime) * multiplier / 60.0;
        }
    }

    public long minutesElapsed() {
        return (long)minutesElapsedDouble();
    }

    public void setInterrupt(long seconds) {
        interruptTime = (long) (seconds / multiplier);;
    }

    public double runningMinutesInterrupt() {
        if (startTime != null) {
            return (double)interruptTime * multiplier / 60.0;
        } else {
            Date now = new Date();
            return (double)(((now.getTime() - stopTime.getTime()) / 1000) +
                            interruptTime) * multiplier / 60.0;
        }
    }

    public double minutesInterruptDouble() {
        return (double)interruptTime * multiplier / 60.0;
    }

    public long minutesInterrupt() {
        return (long)minutesInterruptDouble();
    }

    public void cancelTimingAsOf(Date cancellationTime) {
        // sanity check the parameter
        Date now = new Date();
        if (cancellationTime.after(now))
            cancellationTime = now;

        if (startTime != null) {
            // the timer is currently running.

            if (startTime.before(cancellationTime)) {
                // the timer was started sometime before cancellation time. All
                // we need to do is stop the timer retroactively.
                stopAsOf(cancellationTime);
                return;

            } else {
                // the timer should not have been started at all, because the
                // required cancellation time precedes it.

                // see if we have a period of interrupt time that precedes the
                // bad start time. If so, discard that period of interrupt time.
                if (stopTime != null) {
                    long interruptToDiscard = (startTime.getTime()
                            - stopTime.getTime()) / 1000;
                    interruptTime -= interruptToDiscard;
                    if (interruptTime < 0)
                        interruptTime = 0;
                }

                // Finally, cancel the current timing period without accruing
                // any logged time.
                startTime = null;
            }
        }

        if (stopTime != null && cancellationTime.before(stopTime)) {
            // The timer was stopped sometime in the past, but not soon enough.
            // Move the stop time backward, deleting the overlap from the
            // accrued logged time.
            long overlapTime = (stopTime.getTime()
                    - cancellationTime.getTime()) / 1000;
            if (overlapTime > elapsedTime) {
                reset();
            } else {
                elapsedTime -= overlapTime;
                stopTime = cancellationTime;
            }
        }
    }

}
