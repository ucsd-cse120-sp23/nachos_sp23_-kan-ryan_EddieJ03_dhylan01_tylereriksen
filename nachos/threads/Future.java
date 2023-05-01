package nachos.threads;

import java.util.*;
import java.util.function.IntSupplier;
import nachos.machine.*;

/**
 * A <i>Future</i> is a convenient mechanism for using asynchonous
 * operations.
 */
public class Future {
    private KThread thread;

    private Lock lock;

    private Condition conditionalVariable;

    Integer result;

    /**
     * Instantiate a new <i>Future</i>.  The <i>Future</i> will invoke
     * the supplied <i>function</i> asynchronously in a KThread.  In
     * particular, the constructor should not block as a consequence
     * of invoking <i>function</i>.
     */
    public Future (IntSupplier function) {
        this.lock = new Lock();
        this.conditionalVariable = new Condition(this.lock);

        this.thread = new KThread(new Runnable() {
            public void run() {
				wrapperFunction(function);
			}
        });

        // start running the thread immediately
        this.thread.fork();
    }

    /**
     * Return the result of invoking the <i>function</i> passed in to
     * the <i>Future</i> when it was created.  If the function has not
     * completed when <i>get</i> is invoked, then the caller is
     * blocked.  If the function has completed, then <i>get</i>
     * returns the result of the function.  Note that <i>get</i> may
     * be called any number of times (potentially by multiple
     * threads), and it should always return the same value.
     */
    public int get () {
        this.lock.acquire();

	    while (this.result == null) 
            this.conditionalVariable.sleep();

        this.lock.release();

        return (int)this.result;
    }

    private void wrapperFunction(IntSupplier function) {
        this.result = function.getAsInt();

        this.lock.acquire();
        this.conditionalVariable.wakeAll();
        this.lock.release();
    }

    // one thread waits, other one runs instantly after first thread finishes
    private static void selfTest1() {
        IntSupplier intSupplier = () -> {
            ThreadedKernel.alarm.waitUntil(3000);
            return 1000;  
        };
        
        Future future = new Future(intSupplier);

        KThread t1 = new KThread( new Runnable () {
            public void run() {
                long t0 = Machine.timer().getTime();
                int res = future.get();
                Lib.assertTrue(1000 == res, "Expected 1000");
                
                System.out.println("t1 waited for " + (Machine.timer().getTime() - t0) + " ticks"); 
            }
        }).setName("t1");

        KThread t2 = new KThread( new Runnable () {
            public void run() {
                long t0 = Machine.timer().getTime();
                int res = future.get();
                Lib.assertTrue(1000 == res, "Expected 1000");
                
                System.out.println("t2 waited for " + (Machine.timer().getTime() - t0) + " ticks"); 
            }
        }).setName("t2");

        t1.fork();
        t1.join(); 
        t2.fork();
        t2.join();
    }

    // hella threads waiting, two diff futures, all run before main thread
    private static void selfTest2() {
        IntSupplier intSupplier1 = () -> {
            ThreadedKernel.alarm.waitUntil(500);
            return 1000;  
        };

        IntSupplier intSupplier2 = () -> {
            ThreadedKernel.alarm.waitUntil(1000);
            return 50;  
        };

        Future future1 = new Future(intSupplier1);
        Future future2 = new Future(intSupplier2);

        List<KThread> threads = new LinkedList<>();
        
        for(int i = 0; i < 50; i++) {
            KThread t;

            if(i % 2 == 0) {
                t = new KThread( new Runnable () {
                    public void run() {
                        long t0 = Machine.timer().getTime();
                        int res = future1.get();
                        Lib.assertTrue(1000 == res, "Expected 1000");
                        
                        System.out.println("\nfuture1 waited for " + (Machine.timer().getTime() - t0) + " ticks\n"); 
                    }
                }).setName("t"+i);
            } else {
                t = new KThread( new Runnable () {
                    public void run() {
                        long t0 = Machine.timer().getTime();
                        int res = future2.get();
                        Lib.assertTrue(50 == res, "Expected 50");
                        
                        System.out.println("\nfuture2 waited for " + (Machine.timer().getTime() - t0) + " ticks\n"); 
                    }
                }).setName("t"+i);
            }

            threads.add(t);

            t.fork();
        }
                    
        // simulate main thread busy doing other stuff
        // other thread waits should be interleaved among the "doing stuff"
        for(int i = 0; i < 1000; i++) {
            System.out.print(" doing stuff... ");
            KThread.currentThread().yield();
        }
    }

    public static void selfTest() {
		Lib.debug(dbgThread, "Enter Future.selfTest");

        selfTest1();
        selfTest2();
	}

    private static final char dbgThread = 't';
}
