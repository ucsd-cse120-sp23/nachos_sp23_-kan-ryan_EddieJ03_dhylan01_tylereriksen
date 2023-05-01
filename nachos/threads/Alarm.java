package nachos.threads;

import java.util.*;

import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		long time = Machine.timer().getTime();

		while (waitingQueue.peek() != null && 
				waitingQueue.peek().wakeTime <= time) {
			boolean intStatus = Machine.interrupt().disable();
			waitingQueue.poll().thread.ready();
			Machine.interrupt().restore(intStatus);
		}

		KThread.yield();		
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		if(x <= 0) 
			return;

		WThread newWThread = new WThread(Machine.timer().getTime() + x, KThread.currentThread());
		waitingQueue.add(newWThread);

		boolean intStatus = Machine.interrupt().disable();
		KThread.sleep();											
		Machine.interrupt().restore(intStatus);
	}

    /**
	 * Cancel any timer set by <i>thread</i>, effectively waking
	 * up the thread immediately (placing it in the scheduler
	 * ready set) and returning true.  If <i>thread</i> has no
	 * timer set, return false.
	 * 
	 * <p>
	 * @param thread the thread whose timer should be cancelled.
	 */
    public boolean cancel(KThread thread) {
		boolean cancelled = false;

		for (WThread wThread : this.waitingQueue) {
			KThread currThread = wThread.thread;
			
			if (currThread == thread) {
				waitingQueue.remove(wThread);
				boolean disableInterruptResult = Machine.interrupt().disable();
				currThread.ready();
				Machine.interrupt().restore(disableInterruptResult);
				cancelled = true;
				break;
			}
		}

		return cancelled;
	}

	private PriorityQueue<WThread> waitingQueue = new PriorityQueue<>();

	private class WThread implements Comparable<WThread> {
		KThread thread;
		long wakeTime;

		public WThread(long wakeTime, KThread thread) {
			this.thread = thread;
			this.wakeTime = wakeTime;
		}

		@Override
        public int compareTo(WThread other){
			return Long.compare(this.wakeTime, other.wakeTime);
        }
	}

	// Add Alarm testing code to the Alarm class
    public static void alarmTest1() {
		int durations[] = {1000, 10*1000, 100*1000};
		long t0, t1;

		for (int d : durations) {
			t0 = Machine.timer().getTime();
			ThreadedKernel.alarm.waitUntil(d);
			t1 = Machine.timer().getTime();
			System.out.println("alarmTest1: waited for " + (t1 - t0) + " ticks");
		}
    }

    // Implement more test methods here ...

	// test waitUntil edge cases
	public static void alarmTest2() {
		int durations[] = {-1000, -10*1000, 0};
		long t0, t1;

		for (int d : durations) {
			t0 = Machine.timer().getTime();
			ThreadedKernel.alarm.waitUntil(d);
			t1 = Machine.timer().getTime();
			System.out.println("alarmTest1: waited for " + (t1 - t0) + " ticks");
		}
    }

    // Invoke Alarm.selfTest() from ThreadedKernel.selfTest()
    public static void selfTest() {
		alarmTest1();
		// Invoke your other test methods here ...
		alarmTest2();
    }
}
