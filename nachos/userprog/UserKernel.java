package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.util.*;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
	/**
	 * Allocate a new user kernel.
	 */
	public UserKernel() {
		super();
	}

	/**
	 * Initialize this kernel. Creates a synchronized console and sets the
	 * processor's exception handler.
	 */
	public void initialize(String[] args) {
		super.initialize(args);

		freePPNLock = new Lock();

		processLock = new Lock();

		pidLock = new Lock();

		freePPN = new LinkedList<>();

		console = new SynchConsole(Machine.console());

		Machine.processor().setExceptionHandler(new Runnable() {
			public void run() {
				exceptionHandler();
			}
		});

		int physicalPageCount = Machine.processor().getNumPhysPages();

		for (int i = 0; i < physicalPageCount; i++) {
			freePPN.add(i);
		}

		System.out.println("TOTAL NUMBER OF PAGES: " + physicalPageCount);
	}

	/**
	 * Test the console device.
	 */
	public void selfTest() {
		super.selfTest();

		// System.out.println("Testing the console device. Typed characters");
		// System.out.println("will be echoed until q is typed.");

		// char c;

		// do {
		// 	c = (char) console.readByte(true);
		// 	console.writeByte(c);
		// } while (c != 'q');

		// System.out.println("");
	}

	/**
	 * Returns the current process.
	 * 
	 * @return the current process, or <tt>null</tt> if no process is current.
	 */
	public static UserProcess currentProcess() {
		if (!(KThread.currentThread() instanceof UThread))
			return null;

		return ((UThread) KThread.currentThread()).process;
	}

	/**
	 * The exception handler. This handler is called by the processor whenever a
	 * user instruction causes a processor exception.
	 * 
	 * <p>
	 * When the exception handler is invoked, interrupts are enabled, and the
	 * processor's cause register contains an integer identifying the cause of
	 * the exception (see the <tt>exceptionZZZ</tt> constants in the
	 * <tt>Processor</tt> class). If the exception involves a bad virtual
	 * address (e.g. page fault, TLB miss, read-only, bus error, or address
	 * error), the processor's BadVAddr register identifies the virtual address
	 * that caused the exception.
	 */
	public void exceptionHandler() {
		Lib.assertTrue(KThread.currentThread() instanceof UThread);

		UserProcess process = ((UThread) KThread.currentThread()).process;
		int cause = Machine.processor().readRegister(Processor.regCause);
		process.handleException(cause);
	}

	/**
	 * Start running user programs, by creating a process and running a shell
	 * program in it. The name of the shell program it must run is returned by
	 * <tt>Machine.getShellProgramName()</tt>.
	 * 
	 * @see nachos.machine.Machine#getShellProgramName
	 */
	public void run() {
		super.run();

		UserProcess process = UserProcess.newUserProcess();

		String shellProgram = Machine.getShellProgramName();
		if (!process.execute(shellProgram, new String[] {})) {
		    System.out.println ("Could not find executable '" +
					shellProgram + "', trying '" +
					shellProgram + ".coff' instead.");
		    shellProgram += ".coff";
		    if (!process.execute(shellProgram, new String[] {})) {
			System.out.println ("Also could not find '" +
					    shellProgram + "', aborting.");
			Lib.assertTrue(false);
		    }

		}

		KThread.currentThread().finish();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	public static int acquirePPN() {
		return freePPN.pollLast();
	}

	public static void releasePPN(int physicalPageNumber) {
		if (!freePPN.contains(physicalPageNumber))
			freePPN.addFirst(physicalPageNumber);
	}

	public static void incrementProcessCount() {
		processLock.acquire();		
		processCount++;
		processLock.release();
	}

	public static void decrementProcessCount() {
		processLock.acquire();
		processCount--;
		processLock.release();
	}

	public static int nextPID() {
		boolean status = Machine.interrupt().disable();
		int pid = processNumber++;
		Machine.interrupt().restore(status);
		return pid;
	}

	/** Globally accessible reference to the synchronized console. */
	public static SynchConsole console;

	// dummy variables to make javac smarter
	private static Coff dummy1 = null;

	// free pages
	public static LinkedList<Integer> freePPN;

	public static Lock freePPNLock;

	private static Lock processLock;

	private static Lock pidLock;

	static int processNumber = 0;

	static int processCount = 0;
}
