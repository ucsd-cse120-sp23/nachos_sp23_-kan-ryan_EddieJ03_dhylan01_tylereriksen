package nachos.threads;

import nachos.machine.*;

import java.util.*;

/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 * 
 * <p>
 * You must implement this.
 * 
 * @see nachos.threads.Condition
 */
public class Condition2 {
	/**
	 * Allocate a new condition variable.
	 * 
	 * @param conditionLock the lock associated with this condition variable.
	 * The current thread must hold this lock whenever it uses <tt>sleep()</tt>,
	 * <tt>wake()</tt>, or <tt>wakeAll()</tt>.
	 */
	public Condition2(Lock conditionLock) {
		this.conditionLock = conditionLock;
        this.conditionalQueue = new LinkedList<>();
	}

	/**
	 * Atomically release the associated lock and go to sleep on this condition
	 * variable until another thread wakes it using <tt>wake()</tt>. The current
	 * thread must hold the associated lock. The thread will automatically
	 * reacquire the lock before <tt>sleep()</tt> returns.
	 */
	public void sleep() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // we need to disable interrupts to make this an atomic operation
        // kinda hacky but it is what it is
		boolean intStatus = Machine.interrupt().disable();

        KThread currThread = KThread.currentThread();

		// add this thread into waiting queue of this conditional variable
		conditionalQueue.add(currThread);

        // release the lock for somebody else ( don't do this AFTER sleep :( )
		conditionLock.release();

		// sleep the current thread AFTER release
		KThread.sleep();

        // reacquire lock before leaving sleep
		conditionLock.acquire();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up at most one thread sleeping on this condition variable. The
	 * current thread must hold the associated lock.
	 */
	public void wake() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		boolean intStatus = Machine.interrupt().disable();

		if (this.conditionalQueue.size() > 0) {
            KThread thread = conditionalQueue.poll();

            // cancel any existing timer - if no timer wake the thread
            if (!ThreadedKernel.alarm.cancel(thread))
                thread.ready();
		}

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up all threads sleeping on this condition variable. The current
	 * thread must hold the associated lock.
	 */
	public void wakeAll() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        int len = this.conditionalQueue.size();

        // wake up all threads
        while(len-- > 0) {
            wake();
        }
	}

    /**
	 * Atomically release the associated lock and go to sleep on
	 * this condition variable until either (1) another thread
	 * wakes it using <tt>wake()</tt>, or (2) the specified
	 * <i>timeout</i> elapses.  The current thread must hold the
	 * associated lock.  The thread will automatically reacquire
	 * the lock before <tt>sleep()</tt> returns.
	 */
    public void sleepFor(long timeout) {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // we need to disable interrupts to make this an atomic operation
        // kinda hacky but it is what it is
		boolean intStatus = Machine.interrupt().disable();

        KThread currThread = KThread.currentThread();

        // add homie to the queue :)
		this.conditionalQueue.add(currThread);

        // release the lock
		this.conditionLock.release();

        // wait in here until woken or waitUntil finishes
		ThreadedKernel.alarm.waitUntil(timeout);

        // once we wake up remove this bad boy from the queue
		this.conditionalQueue.remove(currThread);

        // restore the interrupts! 
		Machine.interrupt().restore(intStatus);

        // reacquire lock before returning
		this.conditionLock.acquire();
	}

    private Lock conditionLock;

    private LinkedList<KThread> conditionalQueue;
	
    private KThread threadTobeWake;

	// Place Condition2 testing code in the Condition2 class.

    // Example of the "interlock" pattern where two threads strictly
    // alternate their execution with each other using a condition
    // variable.  (Also see the slide showing this pattern at the end
    // of Lecture 6.)

    private static class InterlockTest {
        private static Lock lock;
        private static Condition2 cv;

        private static class Interlocker implements Runnable {
            public void run () {
                lock.acquire();
                for (int i = 0; i < 10; i++) {
                    System.out.println(KThread.currentThread().getName());
                    cv.wake();   // signal
                    cv.sleep();  // wait
                }
                lock.release();
            }
        }

        public InterlockTest () {
            lock = new Lock();
            cv = new Condition2(lock);

            KThread ping = new KThread(new Interlocker());
            ping.setName("ping");
            KThread pong = new KThread(new Interlocker());
            pong.setName("pong");

            ping.fork();
            pong.fork();

            // We need to wait for ping to finish, and the proper way
            // to do so is to join on ping.  (Note that, when ping is
            // done, pong is sleeping on the condition variable; if we
            // were also to join on pong, we would block forever.)
            // For this to work, join must be implemented.  If you
            // have not implemented join yet, then comment out the
            // call to join and instead uncomment the loop with
            // yields; the loop has the same effect, but is a kludgy
            // way to do it.
            ping.join();
            // for (int i = 0; i < 50; i++) { KThread.currentThread().yield(); }
        }
    }

	// Place sleepFor test code inside of the Condition2 class.
	private static void sleepForTest1 () {
		Lock lock = new Lock();
		Condition2 cv = new Condition2(lock);

		lock.acquire();
		long t0 = Machine.timer().getTime();
		System.out.println (KThread.currentThread().getName() + " sleeping");
		// no other thread will wake us up, so we should time out
		cv.sleepFor(2000);
		long t1 = Machine.timer().getTime();
		System.out.println (KThread.currentThread().getName() +
					" woke up, slept for " + (t1 - t0) + " ticks");
		lock.release();
	}

    // write test to sleepFor then get woken b4 

    // write a test to wake on thread then two more
    private static void cvTest3() {
        System.out.println("\nStarting cvtest3\n");
        final Lock lock = new Lock();
        final Condition2 cv = new Condition2(lock);

        KThread t1 = new KThread(
            new Runnable() {
                public void run() {
                    lock.acquire();
                    cv.sleep();
                    System.out.println("thread1");
                    lock.release();
                }
            }
        );

        KThread t2 = new KThread(
            new Runnable() {
                public void run() {
                    lock.acquire();
                    cv.sleep();
                    System.out.println("thread2");
                    lock.release();
                }
            }
        );

        KThread t3 = new KThread(
            new Runnable() {
                public void run() {
                    lock.acquire();
                    cv.sleep();
                    System.out.println("thread3");
                    lock.release();
                }
            }
        );

        t1.fork();
        t2.fork();
        t3.fork();

        for(int i = 0; i < 3; i++) {
            System.out.println("YIELD MAIN");
            KThread.currentThread().yield();
        }

        lock.acquire();
        cv.wake();
        System.out.println("ONLY ONE THREAD RUNNING");
        lock.release();

        KThread.currentThread().yield();

        lock.acquire();
        cv.wakeAll();
        lock.release();
        
        System.out.println("REMAINING THREADS RUNNING");

        t1.join();
        t2.join();
        t3.join();

        System.out.println("Finish cvtest3\n");
    }

    // wake and wakeAll on no blocked threads does nothing
    private static void cvTest4() {
        System.out.println("starting cvtest4");
        final Lock lock = new Lock();
        final Condition2 cv = new Condition2(lock);
        lock.acquire();
        cv.wake();
        cv.wakeAll();
        lock.release();
        System.out.println("Finishing cvtest4");
    }

	// Place Condition2 test code inside of the Condition2 class.

    // Test programs should have exactly the same behavior with the
    // Condition and Condition2 classes.  You can first try a test with
    // Condition, which is already provided for you, and then try it
    // with Condition2, which you are implementing, and compare their
    // behavior.

    // Do not use this test program as your first Condition2 test.
    // First test it with more basic test programs to verify specific
    // functionality.

    public static void cvTest5() {
        final Lock lock = new Lock();
        // final Condition empty = new Condition(lock);
        final Condition2 empty = new Condition2(lock);
        final LinkedList<Integer> list = new LinkedList<>();

        KThread consumer = new KThread( new Runnable () {
                public void run() {
                    lock.acquire();
                    while(list.isEmpty()){
                        empty.sleep();
                    }
                    Lib.assertTrue(list.size() == 5, "List should have 5 values.");
                    while(!list.isEmpty()) {
                        // context swith for the fun of it
                        KThread.currentThread().yield();
                        System.out.println("Removed " + list.removeFirst());
                    }
                    lock.release();
                }
            });

        KThread producer = new KThread( new Runnable () {
                public void run() {
                    lock.acquire();
                    for (int i = 0; i < 5; i++) {
                        list.add(i);
                        System.out.println("Added " + i);
                        // context swith for the fun of it
                        KThread.currentThread().yield();
                    }
                    empty.wake();
                    lock.release();
                }
            });

        consumer.setName("Consumer");
        producer.setName("Producer");
        consumer.fork();
        producer.fork();

        // We need to wait for the consumer and producer to finish,
        // and the proper way to do so is to join on them.  For this
        // to work, join must be implemented.  If you have not
        // implemented join yet, then comment out the calls to join
        // and instead uncomment the loop with yield; the loop has the
        // same effect, but is a kludgy way to do it.
        consumer.join();
        producer.join();
        //for (int i = 0; i < 50; i++) { KThread.currentThread().yield(); }
    }

    // Invoke Condition2.selfTest() from ThreadedKernel.selfTest()
    public static void selfTest() {
        new InterlockTest();
		sleepForTest1();
        cvTest3();
        cvTest4();
		cvTest5();
    }
}
